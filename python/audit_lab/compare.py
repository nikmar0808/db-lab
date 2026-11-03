"""Loads the same audit records into both databases and compares them (Stage 7). Local databases only.

Run from the python folder with the passwords from .env in the environment:
    python -m audit_lab.compare                 (200,000 records; about a minute)
Writes docs/results/audit-comparison.md, and leaves both stores empty.
"""

import argparse
import time
from collections.abc import Iterator
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

import psycopg
from psycopg.types.json import Jsonb
from psycopg_pool import ConnectionPool
from pymongo.database import Database

from audit_lab import mongo_audit
from audit_lab.mongo_audit import COLLECTION, PLAIN_INDEX, TTL_INDEX, MongoAudit
from audit_lab.postgres_audit import REPORT_SQL, PostgresAudit
from audit_lab.records import EXPIRE_DAYS, sample_records
from billing import settings as pg_settings
from mongo_lab import settings as mongo_settings
from mongo_lab.client import Doc, make_client

RESULT = Path(__file__).resolve().parents[2] / "docs" / "results" / "audit-comparison.md"
lines: list[str] = []


def say(text: str = "") -> None:
    print(text, flush=True)
    lines.append(text)


def mb(n: float) -> str:
    return f"{n / 1024 / 1024:.1f} MB"


def walk(node: Any) -> Iterator[dict[str, Any]]:
    """Every dictionary inside an explain result, however deeply nested."""
    if isinstance(node, dict):
        yield node
        for v in node.values():
            yield from walk(v)
    elif isinstance(node, list):
        for v in node:
            yield from walk(v)


def mongo_plan(explain: dict[str, Any]) -> str:
    stages: list[str] = []
    for d in walk(explain):
        if "winningPlan" in d:
            stages = [s["stage"] for s in walk(d["winningPlan"]) if "stage" in s]
            break
    stats: dict[str, Any] = next((d["executionStats"] for d in walk(explain) if "executionStats" in d), {})
    return (f"{' < '.join(stages)}; keys {stats.get('totalKeysExamined')}, docs {stats.get('totalDocsExamined')}, "
            f"{stats.get('executionTimeMillis')} ms")


def pg_plan(conn: psycopg.Connection[Any], sql: str, params: tuple[Any, ...]) -> None:
    say("```text")
    for row in conn.execute("EXPLAIN (ANALYZE, BUFFERS) " + sql, params).fetchall():
        say(row[0])
    say("```")


def mongo_find_plan(db: Database[Doc], query: dict[str, Any]) -> str:
    return mongo_plan(db.command("explain", {"find": COLLECTION, "filter": query}, verbosity="executionStats"))


def pg_sizes(conn: psycopg.Connection[Any]) -> str:
    row = conn.execute("""SELECT pg_table_size('audit_record'), pg_relation_size('audit_record_document_ix'),
                                 pg_relation_size('audit_record_received_ix'), pg_relation_size('audit_record_pk')""").fetchone()
    assert row is not None
    return f"table {mb(row[0])}; GIN {mb(row[1])}; time index {mb(row[2])}; primary key {mb(row[3])}"


def mongo_sizes(db: Database[Doc]) -> str:
    s = db.command("collStats", COLLECTION)
    indexes = "; ".join(f"{k} {mb(v)}" for k, v in s["indexSizes"].items())
    return f"documents {mb(s['storageSize'])} on disk ({mb(s['size'])} before compression); {indexes}"


def main() -> None:
    parser = argparse.ArgumentParser(description="Compare the audit record in PostgreSQL and MongoDB")
    parser.add_argument("--records", type=int, default=200_000)
    args = parser.parse_args()
    n: int = args.records
    now = datetime.now(UTC).replace(microsecond=0)
    until = now + timedelta(seconds=1)
    since = now - timedelta(days=30)
    expire_before = now - timedelta(days=EXPIRE_DAYS)

    owner_info = pg_settings.conninfo("lab_owner", "LAB_OWNER_PASSWORD")
    with (ConnectionPool(pg_settings.conninfo(), min_size=1, max_size=5, open=True) as pool,
          make_client(mongo_settings.mongo_uri("local")) as client,
          psycopg.connect(owner_info, autocommit=True) as owner):
        db = client.get_database("utility_lab")
        postgres, mongo = PostgresAudit(pool), MongoAudit(db)
        owner.execute("TRUNCATE audit_record")
        db.drop_collection(COLLECTION)
        mongo_audit.setup(db, ttl=False)       # the plain time index for now: nothing expires during the load

        say(f"# The audit record both ways: {n:,} records\n")
        say("## Loading\n")
        start = time.perf_counter()
        postgres.write_many(sample_records(n, now))
        say(f"- PostgreSQL, COPY, with the GIN index in place: {time.perf_counter() - start:.1f} s")
        start = time.perf_counter()
        mongo.write_many(sample_records(n, now))
        say(f"- MongoDB, insert_many in chunks of 5,000, majority: {time.perf_counter() - start:.1f} s")
        owner.execute("ANALYZE audit_record")   # up-to-date statistics; lab_app may not run this

        extra = list(sample_records(1000, now, seed=11))
        for label, store in (("PostgreSQL", postgres), ("MongoDB", mongo)):
            start = time.perf_counter()
            for r in extra:
                store.write(r)
            say(f"- {label}, 1,000 single writes, each its own commit: {time.perf_counter() - start:.2f} s")

        say("\n## Space\n")
        say(f"- PostgreSQL: {pg_sizes(owner)}")
        say(f"- MongoDB: {mongo_sizes(db)}")
        owner.execute("CREATE INDEX audit_try_default_ix ON audit_record USING GIN (document)")
        row = owner.execute("SELECT pg_relation_size('audit_try_default_ix')").fetchone()
        assert row is not None
        say(f"- The same GIN index with the default operator class, jsonb_ops: {mb(row[0])}")
        owner.execute("DROP INDEX audit_try_default_ix")

        say("\n## Search 1: one batch by its id\n")
        pg_plan(owner, "SELECT document FROM audit_record WHERE document @> %s", (Jsonb({"batch_id": "B-0123456"}),))
        say(f"\nMongoDB: {mongo_find_plan(db, {'batch_id': 'B-0123456'})}\n")

        say("## Search 2: the same batch written with ->>, which the GIN index cannot serve\n")
        pg_plan(owner, "SELECT document FROM audit_record WHERE document ->> 'batch_id' = %s", ("B-0123456",))

        say("\n## Search 3: a field inside the raw message that no one planned for\n")
        pg_plan(owner, "SELECT count(*) FROM audit_record WHERE document @> %s",
                (Jsonb({"raw": {"meter_serial": "MTR-000123"}}),))
        say(f"\nMongoDB, no index on this field: {mongo_find_plan(db, {'raw.meter_serial': 'MTR-000123'})}\n")

        say("## Search 4: a rare error code in the last 30 days\n")
        pg_plan(owner, "SELECT count(*) FROM audit_record WHERE document @> %s AND received_at >= %s",
                (Jsonb({"errors": [{"code": "E900"}]}), since))
        say(f"\nMongoDB: {mongo_find_plan(db, {'errors.code': 'E900', 'received_at': {'$gte': since}})}\n")

        say("## The report: batches, readings and kWh by source and outcome, last 30 days\n")
        pg_plan(owner, REPORT_SQL, (since, until))
        explain = db.command("explain", {"aggregate": COLLECTION, "pipeline": mongo_audit.report_pipeline(since, until),
                                         "cursor": {}}, verbosity="executionStats")
        say(f"\nMongoDB: {mongo_plan(explain)}\n")
        start = time.perf_counter()
        pg_report = postgres.report(since, until)
        pg_ms = (time.perf_counter() - start) * 1000
        start = time.perf_counter()
        mongo_report = mongo.report(since, until)
        mongo_ms = (time.perf_counter() - start) * 1000
        say(f"PostgreSQL {pg_ms:.0f} ms, MongoDB {mongo_ms:.0f} ms, from the program. "
            f"The two reports are equal: {pg_report == mongo_report}\n")
        for line in pg_report:
            say(f"- {line.source} {line.outcome}: {line.batches} batches, {line.readings} readings, {line.kwh} kWh")

        say("\n## Expiry\n")
        row = owner.execute("SELECT count(*) FROM audit_record WHERE received_at < %s", (expire_before,)).fetchone()
        assert row is not None
        say(f"Older than {EXPIRE_DAYS} days in PostgreSQL: {row[0]:,}. "
            f"In MongoDB: {db[COLLECTION].count_documents({'received_at': {'$lt': expire_before}}):,}\n")
        say(f"- PostgreSQL before the delete: {pg_sizes(owner)}")
        start = time.perf_counter()
        deleted = postgres.delete_expired(expire_before)
        say(f"- PostgreSQL DELETE of {deleted:,} rows: {time.perf_counter() - start:.2f} s")
        say(f"- PostgreSQL after the delete: {pg_sizes(owner)}")
        start = time.perf_counter()
        owner.execute("VACUUM audit_record")
        say(f"- PostgreSQL VACUUM: {time.perf_counter() - start:.2f} s")
        say(f"- PostgreSQL after VACUUM: {pg_sizes(owner)}")

        say(f"- MongoDB before the TTL index: {mongo_sizes(db)}")
        db[COLLECTION].drop_index(PLAIN_INDEX)
        start = time.perf_counter()
        mongo_audit.setup(db, ttl=True)
        say(f"- MongoDB builds the TTL index {TTL_INDEX}: {time.perf_counter() - start:.2f} s")
        start = time.perf_counter()
        while db[COLLECTION].count_documents({"received_at": {"$lt": expire_before}}) > 0:
            if time.perf_counter() - start > 300:
                say("- MongoDB: not finished after 300 s")
                break
            time.sleep(5)
        else:
            say(f"- MongoDB: every expired document gone {time.perf_counter() - start:.0f} s after the index was built, "
                "with nothing else running")
        say(f"- MongoDB after expiry: {mongo_sizes(db)}")

        owner.execute("TRUNCATE audit_record")
        db.drop_collection(COLLECTION)

    RESULT.parent.mkdir(parents=True, exist_ok=True)
    RESULT.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    print(f"\nwritten: {RESULT}")


if __name__ == "__main__":
    main()