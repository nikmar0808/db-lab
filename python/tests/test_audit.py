"""The audit record both ways, and the rule that its failure must not fail the business write (Stage 7)."""

import time
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from pathlib import Path

import psycopg
import pytest
from bson.decimal128 import Decimal128
from psycopg.types.json import Jsonb
from psycopg_pool import ConnectionPool
from pymongo import MongoClient
from pymongo.database import Database
from pymongo.errors import WriteError

from audit_lab import mongo_audit
from audit_lab.audited import FallbackFile, record_and_audit, write_audit_safely
from audit_lab.mongo_audit import COLLECTION, MongoAudit
from audit_lab.postgres_audit import PostgresAudit
from audit_lab.records import AuditRecord, sample_records
from billing.domain import BILLING_ZONE, BillingPeriod, ReadingInput
from billing.psycopg_billing import PsycopgBilling
from conftest import Fixture, LabMongo, count
from mongo_lab.client import Doc, make_client

FUTURE = datetime(2030, 1, 1, tzinfo=UTC)      # far from anything else, and from the TTL index


def record(batch_id: str, at: datetime = FUTURE, **changes) -> AuditRecord:
    values = {"source": "HES-NORTH", "outcome": "ACCEPTED", "premise_id": 1, "reading_count": 1,
              "total_kwh": Decimal("7.500"), "raw": {"meter_serial": "MTR-000001"}, "errors": []}
    return AuditRecord(batch_id, at, **{**values, **changes})


@pytest.fixture
def audit_db(db: Database[Doc]) -> Database[Doc]:
    mongo_audit.setup(db)
    return db


def reading(fixture: Fixture, day: int) -> ReadingInput:
    return ReadingInput(fixture.meter_id, datetime(2026, 10, day, tzinfo=BILLING_ZONE), Decimal("10.000"))


# ---------------------------------------------------------------- the same record in both

def test_the_same_report_from_sql_and_from_an_aggregation(pool: ConnectionPool, audit_db: Database[Doc]) -> None:
    postgres, mongo = PostgresAudit(pool), MongoAudit(audit_db)
    records = list(sample_records(300, FUTURE))
    assert postgres.write_many(records) == 300
    assert mongo.write_many(records) == 300
    since, until = FUTURE - timedelta(days=30), FUTURE + timedelta(seconds=1)
    from_sql, from_pipeline = postgres.report(since, until), mongo.report(since, until)
    assert from_sql and from_sql == from_pipeline
    assert sum(r.batches for r in from_sql) == sum(1 for r in records if r.received_at >= since)


def test_kwh_stays_exact_in_both(pool: ConnectionPool, audit_db: Database[Doc]) -> None:
    PostgresAudit(pool).write(record("EXACT-1", total_kwh=Decimal("0.100")))
    MongoAudit(audit_db).write(record("EXACT-1", total_kwh=Decimal("0.100")))
    assert PostgresAudit(pool).find_by_batch("EXACT-1")[0]["total_kwh"] == "0.100"     # text in jsonb, cast by SQL
    assert MongoAudit(audit_db).find_by_batch("EXACT-1")[0]["total_kwh"] == Decimal128("0.100")


# ---------------------------------------------------------------- jsonb and its GIN index

def plan(pool: ConnectionPool, sql: str, value: object) -> str:
    """The plan with sequential scans made as unattractive as possible: can the index serve this at all?"""
    with pool.connection() as conn:
        conn.execute("SET LOCAL enable_seqscan = off")
        return "\n".join(r[0] for r in conn.execute("EXPLAIN " + sql, (value,)).fetchall())


def test_the_gin_index_serves_containment_but_not_the_arrow_operator(pool: ConnectionPool) -> None:
    contains = plan(pool, "SELECT 1 FROM audit_record WHERE document @> %s", Jsonb({"batch_id": "X"}))
    arrow = plan(pool, "SELECT 1 FROM audit_record WHERE document ->> 'batch_id' = %s", "X")
    assert "audit_record_document_ix" in contains
    assert "audit_record_document_ix" not in arrow


def test_postgresql_refuses_a_document_without_its_required_fields(pool: ConnectionPool) -> None:
    with pytest.raises(psycopg.errors.CheckViolation), pool.connection() as conn:
        conn.execute("INSERT INTO audit_record (received_at, document) VALUES (now(), %s)", (Jsonb({"source": "X"}),))


def test_mongodb_refuses_a_document_without_its_required_fields(audit_db: Database[Doc]) -> None:
    with pytest.raises(WriteError) as refused:
        audit_db[COLLECTION].insert_one({"batch_id": "NO-DATE", "source": "X"})
    assert refused.value.code == 121


# ---------------------------------------------------------------- automatic expiry

def test_the_ttl_index_removes_old_documents_by_itself(audit_db: Database[Doc]) -> None:
    # The test container runs the TTL monitor every second instead of every 60.
    old, recent = datetime.now(UTC) - timedelta(days=100), datetime.now(UTC) - timedelta(days=10)
    mongo = MongoAudit(audit_db)
    mongo.write(record("TTL-OLD", at=old))
    mongo.write(record("TTL-RECENT", at=recent))
    deadline = time.monotonic() + 20
    while mongo.find_by_batch("TTL-OLD") and time.monotonic() < deadline:
        time.sleep(0.5)
    assert mongo.find_by_batch("TTL-OLD") == []
    assert len(mongo.find_by_batch("TTL-RECENT")) == 1


def test_a_date_stored_as_text_never_expires(db: Database[Doc]) -> None:
    coll = db["stage7_text_dates"]                   # no validator here: that is how such a document gets in
    coll.drop()
    coll.create_index("received_at", expireAfterSeconds=1)
    coll.insert_many([{"_id": "text", "received_at": "2020-01-01"}, {"_id": "date", "received_at": datetime(2020, 1, 1, tzinfo=UTC)}])
    deadline = time.monotonic() + 20
    while coll.find_one({"_id": "date"}) and time.monotonic() < deadline:
        time.sleep(0.5)
    assert [d["_id"] for d in coll.find()] == ["text"]


def test_postgresql_has_no_ttl_a_delete_must_be_run(pool: ConnectionPool) -> None:
    postgres = PostgresAudit(pool)
    old = datetime(2020, 1, 1, tzinfo=UTC)
    postgres.write(record("OLD-PG", at=old))
    assert len(postgres.find_by_batch("OLD-PG")) == 1           # nothing removes it
    assert postgres.delete_expired(old + timedelta(days=1)) == 1
    assert postgres.find_by_batch("OLD-PG") == []


# ---------------------------------------------------------------- the traps of a shared transaction

def bad_audit(conn: psycopg.Connection) -> None:
    conn.execute("INSERT INTO audit_record (received_at, document) VALUES (now(), %s)", (Jsonb({}),))


def insert_reading(conn: psycopg.Connection, r: ReadingInput) -> None:
    conn.execute("INSERT INTO meter_reading (meter_id, read_at, kwh) VALUES (%s, %s, %s)", (r.meter_id, r.read_at, r.kwh))


READINGS_OF = "SELECT count(*) FROM meter_reading WHERE meter_id = %s"


def test_after_a_failed_statement_postgresql_refuses_everything_else(pool: ConnectionPool, new_premise: Fixture) -> None:
    with pool.connection() as conn:
        insert_reading(conn, reading(new_premise, 1))
        with pytest.raises(psycopg.errors.CheckViolation):
            bad_audit(conn)                              # caught, as try/catch would in Oracle-era code
        with pytest.raises(psycopg.errors.InFailedSqlTransaction):
            conn.execute("SELECT 1")
        conn.rollback()


def test_catching_the_audit_error_inside_the_transaction_loses_the_business_write(
        pool: ConnectionPool, new_premise: Fixture) -> None:
    with pool.connection() as conn:                      # no error at all when this block commits...
        insert_reading(conn, reading(new_premise, 1))
        try:
            bad_audit(conn)
        except psycopg.errors.CheckViolation:
            pass
    assert count(pool, READINGS_OF, new_premise.meter_id) == 0   # ...and the reading is gone


def test_a_savepoint_keeps_the_business_write(pool: ConnectionPool, new_premise: Fixture) -> None:
    with pool.connection() as conn:
        insert_reading(conn, reading(new_premise, 1))
        try:
            with conn.transaction():                     # inside a transaction, this is a SAVEPOINT
                bad_audit(conn)
        except psycopg.errors.CheckViolation:
            pass
    assert count(pool, READINGS_OF, new_premise.meter_id) == 1


# ---------------------------------------------------------------- the decision

class BrokenStore:
    def write(self, record: AuditRecord) -> None:
        raise RuntimeError("audit store is down")


def test_a_failed_audit_leaves_the_business_write_and_a_fallback_line(
        pool: ConnectionPool, new_premise: Fixture, tmp_path: Path) -> None:
    fallback = FallbackFile(tmp_path / "fallback.jsonl")
    result = record_and_audit(PsycopgBilling(pool), BrokenStore(), fallback, "BROKEN-1", "HES-NORTH",
                              new_premise.premise_id, [reading(new_premise, 1)], BillingPeriod(2026, 10))
    assert result.amount == Decimal("127.00")            # 10 kWh at 6.50, plus 31 days at 2.00
    assert count(pool, READINGS_OF, new_premise.meter_id) == 1
    assert fallback.failures == 1
    assert "BROKEN-1" in fallback.path.read_text(encoding="utf-8")


def test_an_accepted_batch_is_audited(pool: ConnectionPool, new_premise: Fixture, tmp_path: Path) -> None:
    postgres = PostgresAudit(pool)
    fallback = FallbackFile(tmp_path / "fallback.jsonl")
    record_and_audit(PsycopgBilling(pool), postgres, fallback, "GOOD-1", "HES-NORTH",
                     new_premise.premise_id, [reading(new_premise, 1)], BillingPeriod(2026, 10))
    assert postgres.find_by_batch("GOOD-1")[0]["outcome"] == "ACCEPTED"
    assert fallback.failures == 0


def test_a_failed_business_write_rolls_back_and_its_audit_record_stays(
        pool: ConnectionPool, new_premise: Fixture, tmp_path: Path) -> None:
    postgres, billing = PostgresAudit(pool), PsycopgBilling(pool)
    fallback = FallbackFile(tmp_path / "fallback.jsonl")
    period = BillingPeriod(2026, 10)
    billing.record_readings_and_bill(new_premise.premise_id, [reading(new_premise, 1)], period)
    with pytest.raises(psycopg.errors.UniqueViolation):                  # October is already billed
        record_and_audit(billing, postgres, fallback, "FAILED-1", "HES-NORTH",
                         new_premise.premise_id, [reading(new_premise, 2)], period)
    assert count(pool, READINGS_OF, new_premise.meter_id) == 1           # the second reading rolled back
    assert postgres.find_by_batch("FAILED-1")[0]["outcome"] == "FAILED"  # the record of the attempt stays


def test_an_unreachable_store_costs_a_bounded_wait(tmp_path: Path) -> None:
    with make_client("mongodb://127.0.0.1:1/?directConnection=true", timeoutMS=500) as gone:
        fallback = FallbackFile(tmp_path / "fallback.jsonl")
        start = time.perf_counter()
        assert not write_audit_safely(MongoAudit(gone.get_database("utility_lab")), record("GONE-1"), fallback)
        assert time.perf_counter() - start < 2
    assert fallback.failures == 1


def test_a_hanging_store_costs_a_bounded_wait(lab_mongo: LabMongo, client: MongoClient[Doc], tmp_path: Path) -> None:
    # The server holds this program's next insert for 3 seconds, as an overloaded store would.
    client.admin.command({"configureFailPoint": "failCommand", "mode": {"times": 1},
                          "data": {"failCommands": ["insert"], "blockConnection": True, "blockTimeMS": 3000,
                                   "appName": "audit-slow"}})
    fallback = FallbackFile(tmp_path / "fallback.jsonl")
    with make_client(lab_mongo.uri, appname="audit-slow", timeoutMS=300) as slow:
        db = slow.get_database("utility_lab")
        start = time.perf_counter()
        ok = write_audit_safely(MongoAudit(db), record("SLOW-1"), fallback)
        elapsed = time.perf_counter() - start
    print(f"\nthe audit write gave up after {elapsed * 1000:.0f} ms")
    assert not ok and elapsed < 1.5