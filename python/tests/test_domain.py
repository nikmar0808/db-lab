"""Unit tests of the billing rule and the billing period: plain Python, no database (Stage 3, lesson A8).

pytest runs every function whose name starts with test_ in every file whose name starts with test_.
A plain assert is the check: if its condition is false, the test fails, and pytest shows both sides.
"""

from datetime import UTC, datetime
from decimal import Decimal

from billing.domain import BillingPeriod, bill_amount


def test_october_bill_of_stage_one_premise_one() -> None:
    # Stage 1: premise 1 used 530 kWh in October on DOM-1 (6.50 per kWh, 2.00 per day): 3445.00 + 62.00.
    amount = bill_amount(Decimal("530.000"), Decimal("6.5000"), Decimal("2.00"), 31)
    assert amount == Decimal("3507.00")


def test_rounds_half_away_from_zero_like_the_database() -> None:
    # 0.001 kWh x 5.0000 = 0.005: exactly half a paisa, rounded up, as PostgreSQL's round() does.
    assert bill_amount(Decimal("0.001"), Decimal("5.0000"), Decimal("0"), 0) == Decimal("0.01")


def test_october_starts_and_ends_at_midnight_india_time() -> None:
    october = BillingPeriod(2026, 10)
    # Midnight on 1 October in India is 18:30 on 30 September in UTC.
    assert october.start == datetime(2026, 9, 30, 18, 30, tzinfo=UTC)
    assert october.end == datetime(2026, 10, 31, 18, 30, tzinfo=UTC)
    assert october.days == 31


def test_december_rolls_over_to_january() -> None:
    december = BillingPeriod(2026, 12)
    assert december.last_day.isoformat() == "2026-12-31"
    assert december.end == datetime(2026, 12, 31, 18, 30, tzinfo=UTC)