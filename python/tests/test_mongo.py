"""Tests of the MongoDB code against a real, throwaway MongoDB 8.2 replica set (Stage 6, lesson A8).

The fixtures in conftest.py start the container once for the whole run and load Stage 5's sample data with
mongo/practice/51-load-sample.js, validators included, so these tests run against exactly the collections the
lab uses. Tests keep out of each other's way by using different meters or months; they still run in file order.
"""

from datetime import UTC, datetime, timedelta
from decimal import Decimal

import pytest
from bson.decimal128 import Decimal128
from pymongo import MongoClient, monitoring
from pymongo.database import Database
from pymongo.errors import AutoReconnect, DuplicateKeyError, WriteError

from billing.domain import BILLING_ZONE, BillingPeriod, ReadingInput
from conftest import LabMongo
from mongo_lab.bills import produce_bill, record_readings_and_bill
from mongo_lab.client import Doc, make_client
from mongo_lab.readings import bulk_load, record_reading
from mongo_lab.shapes import bill_lines, schema_version, upgrade_v1_bills
from mongo_lab.summary import account_summary


def india_midnight(year: int, month: int, day: int) -> datetime:
    """Midnight India time on a day, as an exact moment: how every reading is timed."""
    return datetime(year, month, day, tzinfo=BILLING_ZONE)


def reading(meter_id: int, year: int, month: int, day: int, kwh: str) -> ReadingInput:
    return ReadingInput(meter_id, india_midnight(year, month, day), Decimal(kwh))


# ---------------------------------------------------------------- recording readings (lesson A5)

def test_the_first_reading_creates_the_bucket_then_later_ones_are_added(db: Database[Doc]) -> None:
    # Meter 9 (MTR-000161, premise 8) has no readings in the sample data.
    assert record_reading(db, 8, reading(9, 2026, 11, 1, "6.500")) == "new bucket"
    assert record_reading(db, 8, reading(9, 2026, 11, 2, "7.000")) == "added"

    bucket = db.meter_readings.find_one({"meter_id": 9, "month": "2026-11"})
    assert bucket is not None
    assert bucket["reading_count"] == 2
    assert bucket["total_kwh"] == Decimal128("13.500")
    assert isinstance(bucket["readings"][0]["kwh"], Decimal128)        # stored exact, as the validator demands
    assert bucket["readings"][0]["read_at"] == datetime(2026, 10, 31, 18, 30, tzinfo=UTC)


def test_the_same_reading_sent_twice_is_stored_once(db: Database[Doc]) -> None:
    first = reading(9, 2026, 12, 1, "6.000")
    assert record_reading(db, 8, first) == "new bucket"
    assert record_reading(db, 8, first) == "duplicate"
    bucket = db.meter_readings.find_one({"meter_id": 9, "month": "2026-12"})
    assert bucket is not None and bucket["reading_count"] == 1


def test_a_reading_without_a_time_zone_is_refused_before_it_reaches_the_database(db: Database[Doc]) -> None:
    with pytest.raises(ValueError, match="time zone"):
        record_reading(db, 8, ReadingInput(9, datetime(2026, 12, 2), Decimal("6.000")))


def test_the_validator_refuses_a_float(db: Database[Doc]) -> None:
    # A Python float becomes a BSON double. The validator of Stage 5 asks for decimal, and refuses it.
    with pytest.raises(WriteError) as refused:
        db.meter_readings.insert_one({"meter_id": 9, "premise_id": 8, "month": "2027-06", "reading_count": 1,
                                      "total_kwh": 7.5, "readings": [{"read_at": india_midnight(2027, 6, 1), "kwh": 7.5}]})
    assert refused.value.code == 121                                      # DocumentValidationFailed
    assert refused.value.details is not None and "errInfo" in refused.value.details


def test_bulk_load_writes_one_upsert_per_bucket(db: Database[Doc]) -> None:
    # January 2027 for the two meters of premise 9: 62 readings, 2 buckets.
    start = india_midnight(2027, 1, 1)
    batch = [ReadingInput(m, start + timedelta(days=d), Decimal("25.000")) for m in (10, 11) for d in range(31)]
    assert bulk_load(db, {10: 9, 11: 9}, batch) == 2
    bucket = db.meter_readings.find_one({"meter_id": 10, "month": "2027-01"})
    assert bucket is not None
    assert bucket["reading_count"] == 31 and bucket["total_kwh"] == Decimal128("775.000")


# ---------------------------------------------------------------- bills and the transaction (lesson A5)

def test_the_october_bill_equals_postgresql(db: Database[Doc]) -> None:
    # PostgreSQL's calculate_bill(1, October) gives 3507.00: 530 kWh at 6.50 plus 31 days at 2.00.
    bill = produce_bill(db, 1, BillingPeriod(2026, 10))
    assert bill["amount"] == Decimal128("3507.00")
    assert [line["kind"] for line in bill["lines"]] == ["energy", "standing charge"]
    saved = db.bills.find_one({"premise_id": 1, "period_start": datetime(2026, 10, 1, tzinfo=UTC)})
    assert saved is not None and saved["schema_version"] == 2


def test_readings_and_bill_are_saved_together(client: MongoClient[Doc], db: Database[Doc]) -> None:
    readings = [reading(9, 2027, 2, d, "10.000") for d in (1, 2, 3)]
    bill = record_readings_and_bill(client, db, 8, readings, BillingPeriod(2027, 2))
    # 30 kWh at 6.50, plus 28 days at 2.00.
    assert bill["amount"] == Decimal128("251.00")
    bucket = db.meter_readings.find_one({"meter_id": 9, "month": "2027-02"})
    assert bucket is not None and bucket["reading_count"] == 3


def test_a_failed_bill_rolls_back_the_readings_too(client: MongoClient[Doc], db: Database[Doc]) -> None:
    march = BillingPeriod(2027, 3)
    produce_bill(db, 8, march)                                            # March is already billed
    with pytest.raises(DuplicateKeyError):
        record_readings_and_bill(client, db, 8, [reading(9, 2027, 3, 1, "9.000")], march)
    # The reading was written inside the transaction, before the bill failed: it was rolled back with it.
    assert db.meter_readings.find_one({"meter_id": 9, "month": "2027-03"}) is None


# ---------------------------------------------------------------- the account summary (lesson A6)

class CommandCounter(monitoring.CommandListener):
    """Records the name of every command the client sends: the counterpart of Hibernate's statistics."""

    def __init__(self) -> None:
        self.names: list[str] = []

    def started(self, event: monitoring.CommandStartedEvent) -> None:
        self.names.append(event.command_name)

    def succeeded(self, event: monitoring.CommandSucceededEvent) -> None:
        pass

    def failed(self, event: monitoring.CommandFailedEvent) -> None:
        pass


def test_the_account_summary_sends_two_commands(lab_mongo: LabMongo) -> None:
    counter = CommandCounter()
    with make_client(lab_mongo.uri, event_listeners=[counter]) as counted:
        summary = account_summary(counted.get_database("utility_lab"), 1)
    data_commands = [n for n in counter.names if n in ("find", "aggregate")]
    assert data_commands == ["find", "aggregate"]
    meters = [m for p in summary["premises"] for m in p["meters"]]
    assert [m["serial_number"] for m in meters] == ["MTR-000088", "MTR-000089", "MTR-000101"]
    assert meters[0]["latest_read_at"] == datetime(2026, 10, 30, 18, 30, tzinfo=UTC)   # 31 October, India time
    assert meters[0]["latest_kwh"] == Decimal128("6.500")


# ---------------------------------------------------------------- what the application sees in a failover (lesson A4)

def fail_next_insert(admin_client: MongoClient[Doc], app_name: str) -> None:
    """Makes the server refuse the next insert from one program as a primary that has just stepped down would.

    configureFailPoint is a test-only command (the test container starts mongod with enableTestCommands=1).
    Error 189 is PrimarySteppedDown, with the label RetryableWriteError, exactly what an application receives
    while a replica set elects a new primary. appName limits it to the client with that name.
    """
    admin_client.admin.command({
        "configureFailPoint": "failCommand", "mode": {"times": 1},
        "data": {"failCommands": ["insert"], "errorCode": 189, "errorLabels": ["RetryableWriteError"],
                 "appName": app_name}})


def test_with_retryable_writes_the_insert_survives(lab_mongo: LabMongo, client: MongoClient[Doc]) -> None:
    fail_next_insert(client, "retry-on")
    with make_client(lab_mongo.uri, appname="retry-on") as app:
        app.utility_lab.stage6_failover.insert_one({"case": "retry on"})   # refused once, retried, succeeded
    assert client.utility_lab.stage6_failover.count_documents({"case": "retry on"}) == 1


def test_without_retryable_writes_the_application_sees_the_error(lab_mongo: LabMongo, client: MongoClient[Doc]) -> None:
    fail_next_insert(client, "retry-off")
    with make_client(lab_mongo.uri, appname="retry-off", retryWrites=False) as app:
        with pytest.raises(AutoReconnect) as seen:
            app.utility_lab.stage6_failover.insert_one({"case": "retry off"})
    print(f"\nthe application saw: {type(seen.value).__name__}: {seen.value}")
    assert client.utility_lab.stage6_failover.count_documents({"case": "retry off"}) == 0


# ---------------------------------------------------------------- documents of two shapes (lesson A7)

def test_version_1_bills_are_read_and_then_upgraded(db: Database[Doc]) -> None:
    # A bill as Stage 5's report wrote it: no schema_version, no lines.
    db.bills.insert_one({"customer_id": 3, "premise_id": 5, "period_start": datetime(2026, 10, 1, tzinfo=UTC),
                         "period_end": datetime(2026, 10, 31, tzinfo=UTC), "kwh": Decimal128("261.000"),
                         "amount": Decimal128("1758.50"), "status": "DRAFT"})
    old = db.bills.find_one({"premise_id": 5})
    assert old is not None and schema_version(old) == 1
    lines = bill_lines(db, old)
    assert [line["amount"] for line in lines] == [Decimal("1696.50"), Decimal("62.00")]
    assert sum(line["amount"] for line in lines) == Decimal("1758.50")

    assert upgrade_v1_bills(db) == 1                                      # only the version 1 bill is changed
    assert upgrade_v1_bills(db) == 0                                      # and running it again changes nothing
    new = db.bills.find_one({"premise_id": 5})
    assert new is not None and schema_version(new) == 2 and len(new["lines"]) == 2