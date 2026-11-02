"""Recording meter readings into the monthly buckets of Stage 5 (Stage 6, lessons A2, A5 and A6).

A bucket holds one meter's readings for one India calendar month, with reading_count and total_kwh kept beside
the list. Every function takes an optional session: given one, the work joins that session's transaction.
"""

from collections import defaultdict
from collections.abc import Iterable
from decimal import Decimal
from typing import Literal

from bson.decimal128 import Decimal128
from pymongo import UpdateOne
from pymongo.client_session import ClientSession
from pymongo.database import Database

from billing.domain import BILLING_ZONE, ReadingInput
from mongo_lab.client import Doc

Outcome = Literal["added", "duplicate", "new bucket"]


def month_of(reading: ReadingInput) -> str:
    """The India calendar month a reading belongs to, as the bucket's key: '2026-11'."""
    return reading.read_at.astimezone(BILLING_ZONE).strftime("%Y-%m")


def record_reading(db: Database[Doc], premise_id: int, reading: ReadingInput,
                   session: ClientSession | None = None) -> Outcome:
    """Adds one reading to its bucket, once only: sending the same reading twice stores it once.

    Step 1 adds the reading to an existing bucket, but only if the bucket does not already hold a reading at
    that moment ("readings.read_at": {"$ne": ...}). $push, and the two $inc, change the one document together.
    Step 2 runs only when step 1 changed nothing: either the reading is already there (a duplicate), or the
    month's bucket does not exist yet, and is created with this first reading.
    """
    if reading.read_at.tzinfo is None:
        raise ValueError("read_at must carry a time zone: a reading is an exact moment")
    kwh = Decimal128(reading.kwh)              # an exact decimal in the database, never a float (Stage 5)
    month = month_of(reading)
    added = db.meter_readings.update_one(
        {"meter_id": reading.meter_id, "month": month, "readings.read_at": {"$ne": reading.read_at}},
        {"$push": {"readings": {"read_at": reading.read_at, "kwh": kwh}},
         "$inc": {"reading_count": 1, "total_kwh": kwh}},
        session=session)
    if added.matched_count == 1:
        return "added"
    if db.meter_readings.find_one({"meter_id": reading.meter_id, "month": month}, {"_id": 1},
                                  session=session) is not None:
        return "duplicate"
    db.meter_readings.insert_one(
        {"meter_id": reading.meter_id, "premise_id": premise_id, "month": month,
         "readings": [{"read_at": reading.read_at, "kwh": kwh}],
         "reading_count": 1, "total_kwh": kwh},
        session=session)
    return "new bucket"


def bulk_load(db: Database[Doc], premise_of: dict[int, int], readings: Iterable[ReadingInput]) -> int:
    """Loads many new readings with one bulk_write call per 1,000 buckets; returns the buckets written.

    For a trusted batch, such as a first load or a month's file from the meter-data system: it does not check for
    readings already present, which record_reading does. The readings are grouped by bucket in Python first, so
    each bucket is written by one upsert carrying all its readings ($push with $each), instead of one update per
    reading. ordered=False lets the server apply them in any order and carry on past a failed one.
    """
    grouped: dict[tuple[int, str], list[ReadingInput]] = defaultdict(list)
    for r in readings:
        grouped[(r.meter_id, month_of(r))].append(r)
    operations = []
    for (meter_id, month), items in grouped.items():
        total = sum((r.kwh for r in items), Decimal(0))
        operations.append(UpdateOne(
            {"meter_id": meter_id, "month": month},
            {"$push": {"readings": {"$each": [{"read_at": r.read_at, "kwh": Decimal128(r.kwh)} for r in items]}},
             "$inc": {"reading_count": len(items), "total_kwh": Decimal128(total)},
             "$setOnInsert": {"premise_id": premise_of[meter_id]}},
            upsert=True))
    written = 0
    for start in range(0, len(operations), 1000):
        result = db.meter_readings.bulk_write(operations[start:start + 1000], ordered=False)
        written += result.upserted_count + result.modified_count
    return written