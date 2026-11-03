"""The audit record in MongoDB: a validator, a TTL index and indexes for each search (Stage 7)."""

from collections.abc import Iterable
from datetime import datetime
from itertools import islice
from typing import Any

from pymongo.database import Database

from audit_lab.records import EXPIRE_DAYS, AuditRecord
from audit_lab.report import ReportRow
from mongo_lab.client import Doc

COLLECTION = "audit_records"
TTL_INDEX = "audit_received_ttl"
PLAIN_INDEX = "audit_received"

VALIDATOR = {"$jsonSchema": {
    "bsonType": "object",
    "required": ["batch_id", "received_at", "source", "outcome", "reading_count", "total_kwh"],
    "properties": {
        "batch_id": {"bsonType": "string"},
        "received_at": {"bsonType": "date"},              # a text date would never expire
        "source": {"bsonType": "string"},
        "outcome": {"enum": ["ACCEPTED", "PARTIAL", "REJECTED", "FAILED"]},
        "reading_count": {"bsonType": "int"},
        "total_kwh": {"bsonType": "decimal"},
    }}}

REPORT_FIELDS = {"source": "$source", "outcome": "$outcome"}


def report_pipeline(since: datetime, until: datetime) -> list[dict[str, Any]]:
    return [
        {"$match": {"received_at": {"$gte": since, "$lt": until}}},      # WHERE
        {"$group": {"_id": REPORT_FIELDS, "batches": {"$sum": 1},         # GROUP BY, count(*)
                    "readings": {"$sum": "$reading_count"}, "kwh": {"$sum": "$total_kwh"}}},   # sum(...)
        {"$sort": {"_id.source": 1, "_id.outcome": 1}},                   # ORDER BY
    ]


def setup(db: Database[Doc], ttl: bool = True) -> None:
    """Creates the collection and its indexes. Safe to run again."""
    if COLLECTION not in db.list_collection_names():
        db.create_collection(COLLECTION, validator=VALIDATOR)
    coll = db[COLLECTION]
    coll.create_index("batch_id", name="audit_batch")
    coll.create_index([("errors.code", 1), ("received_at", 1)], name="audit_error_time")
    if ttl:
        coll.create_index("received_at", name=TTL_INDEX, expireAfterSeconds=EXPIRE_DAYS * 86400)
    else:
        coll.create_index("received_at", name=PLAIN_INDEX)


class MongoAudit:
    def __init__(self, db: Database[Doc]) -> None:
        self._coll = db[COLLECTION]

    def write(self, record: AuditRecord) -> None:
        self._coll.insert_one(record.mongo_document())

    def write_many(self, records: Iterable[AuditRecord], chunk: int = 5000) -> int:
        """Loads many records, unordered, in chunks."""
        written = 0
        docs = (r.mongo_document() for r in records)
        while batch := list(islice(docs, chunk)):
            written += len(self._coll.insert_many(batch, ordered=False).inserted_ids)
        return written

    def find_by_batch(self, batch_id: str) -> list[Doc]:
        return list(self._coll.find({"batch_id": batch_id}))

    def report(self, since: datetime, until: datetime) -> list[ReportRow]:
        return [ReportRow(r["_id"]["source"], r["_id"]["outcome"], r["batches"], r["readings"], r["kwh"].to_decimal())
                for r in self._coll.aggregate(report_pipeline(since, until))]