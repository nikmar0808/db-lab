"""Producing a bill from the buckets, and the main task in one multi-document transaction (Stage 6, lesson A5).

A bill document of this stage carries schema_version 2 and its lines; Stage 5's report wrote version 1, with an
amount only. mongo_lab.shapes reads both.
"""

from collections.abc import Sequence
from datetime import UTC, datetime
from decimal import Decimal
from typing import Any

from bson.decimal128 import Decimal128
from pymongo import MongoClient
from pymongo.client_session import ClientSession
from pymongo.database import Database
from pymongo.read_concern import ReadConcern

from billing.domain import BillingPeriod, ReadingInput, bill_amount
from mongo_lab.client import Doc, majority
from mongo_lab.readings import record_reading


def _dec(value: Any) -> Decimal:
    """A Decimal from a stored Decimal128 (or 0 when the field is missing)."""
    return value.to_decimal() if isinstance(value, Decimal128) else Decimal(value or 0)


def premise_and_tariff(db: Database[Doc], premise_id: int,
                       session: ClientSession | None = None) -> tuple[int, Doc, Doc]:
    """The customer id, the premise (from inside its customer document) and its tariff document."""
    customer = db.customers.find_one({"premises.premise_id": premise_id},
                                     {"premises": {"$elemMatch": {"premise_id": premise_id}}}, session=session)
    if customer is None:
        raise LookupError(f"premise {premise_id} does not exist")
    premise = customer["premises"][0]
    tariff = db.tariffs.find_one({"_id": premise["tariff_code"]}, session=session)
    if tariff is None:   # nothing in MongoDB guarantees the reference (Stage 5, lesson A5)
        raise LookupError(f"tariff {premise['tariff_code']} of premise {premise_id} does not exist")
    return customer["_id"], premise, tariff


def produce_bill(db: Database[Doc], premise_id: int, period: BillingPeriod,
                 session: ClientSession | None = None) -> Doc:
    """Totals the premise's buckets for the month, applies the tariff, and saves the bill (schema version 2)."""
    customer_id, _, tariff = premise_and_tariff(db, premise_id, session)
    month = f"{period.year:04d}-{period.month:02d}"
    kwh = sum((_dec(b["total_kwh"]) for b in db.meter_readings.find(
        {"premise_id": premise_id, "month": month}, {"total_kwh": 1}, session=session)), Decimal(0))
    rate, standing = _dec(tariff["rate_per_kwh"]), _dec(tariff["standing_charge"])
    amount = bill_amount(kwh, rate, standing, period.days)          # the same rule as PostgreSQL's calculate_bill
    bill: Doc = {
        "schema_version": 2,
        "customer_id": customer_id, "premise_id": premise_id,
        "period_start": datetime(period.year, period.month, 1, tzinfo=UTC),
        "period_end": datetime(period.last_day.year, period.last_day.month, period.last_day.day, tzinfo=UTC),
        "kwh": Decimal128(kwh),
        "lines": [
            {"kind": "energy", "quantity": Decimal128(kwh), "rate": Decimal128(rate),
             "amount": Decimal128((kwh * rate).quantize(Decimal("0.01")))},
            {"kind": "standing charge", "quantity": period.days, "rate": Decimal128(standing),
             "amount": Decimal128(standing * period.days)},
        ],
        "amount": Decimal128(amount),
        "status": "DRAFT",
    }
    # The unique index on (premise_id, period_start) refuses a second bill for the same month (DuplicateKeyError).
    db.get_collection("bills", write_concern=majority()).insert_one(bill, session=session)
    return bill


def record_readings_and_bill(client: MongoClient[Doc], db: Database[Doc], premise_id: int,
                             readings: Sequence[ReadingInput], period: BillingPeriod) -> Doc:
    """The main task of POC 3 in one transaction: record a batch of readings, then produce the month's bill.

    Either every reading and the bill are saved, or none of it is: the counterpart of the @Transactional service
    method of Stage 2. with_transaction starts the transaction, runs the function, and commits; if the server
    reports a temporary conflict (TransientTransactionError), it runs the whole function again; any other error
    aborts the transaction and is raised to the caller. read concern "snapshot" makes every read inside see the
    data as it was when the transaction started.
    """
    def work(session: ClientSession) -> Doc:
        for r in readings:
            record_reading(db, premise_id, r, session)
        return produce_bill(db, premise_id, period, session)

    with client.start_session() as session:
        result: Doc = session.with_transaction(work, read_concern=ReadConcern("snapshot"), write_concern=majority())
        return result