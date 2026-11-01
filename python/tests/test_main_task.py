"""The main task, built two ways, held to the same checks (Stage 3, lesson A8).

Both tests ask for the fixture "billing", which has two params, so pytest runs each test twice:
once as test_...[psycopg] and once as test_...[sqlalchemy].
"""

from datetime import datetime, timedelta
from decimal import Decimal

import pytest
from psycopg_pool import ConnectionPool

from billing.domain import BILLING_ZONE, BillingPeriod, ReadingInput
from billing.psycopg_billing import calculate_bill
from conftest import Fixture, count

OCTOBER = BillingPeriod(2026, 10)


def daily_readings(meter_id: int, kwh: str, days: int = 31) -> list[ReadingInput]:
    """One reading per day of October, at midnight India time, each of the given kWh."""
    first = datetime(2026, 10, 1, tzinfo=BILLING_ZONE)
    return [ReadingInput(meter_id, first + timedelta(days=d), Decimal(kwh)) for d in range(days)]


def test_records_october_readings_and_bills_them(billing, new_premise: Fixture, pool: ConnectionPool) -> None:
    bill = billing.record_readings_and_bill(new_premise.premise_id, daily_readings(new_premise.meter_id, "8.000"), OCTOBER)

    # 31 days x 8 kWh = 248 kWh; 248 x 6.50 = 1612.00; plus 31 days x 2.00 = 62.00.
    assert bill.amount == Decimal("1674.00")
    assert bill.period_start.isoformat() == "2026-10-01"
    assert bill.period_end.isoformat() == "2026-10-31"
    assert count(pool, "SELECT count(*) FROM meter_reading WHERE meter_id = %s", new_premise.meter_id) == 31
    # Python's rule and the database function agree.
    assert calculate_bill(pool, new_premise.premise_id, OCTOBER) == bill.amount


def test_a_failure_leaves_nothing_half_done(billing, new_premise: Fixture, pool: ConnectionPool) -> None:
    readings = daily_readings(new_premise.meter_id, "8.000", days=3)
    readings.append(readings[0])  # the first reading again: breaks the one-reading-per-moment rule

    # pytest.raises: the test passes only if the code inside the with-block raises this kind of exception.
    with pytest.raises(Exception):
        billing.record_readings_and_bill(new_premise.premise_id, readings, OCTOBER)

    assert count(pool, "SELECT count(*) FROM meter_reading WHERE meter_id = %s", new_premise.meter_id) == 0
    assert count(pool, "SELECT count(*) FROM bill WHERE premise_id = %s", new_premise.premise_id) == 0


def test_an_unknown_premise_is_refused(billing) -> None:
    with pytest.raises(ValueError, match="premise -1 does not exist"):
        billing.record_readings_and_bill(-1, [], OCTOBER)