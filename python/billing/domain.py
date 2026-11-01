"""Inputs, outputs and the billing rule, in plain Python with no database (Stage 3, lessons A2 and A4).

The same rule and the same India-day periods as the Java project's BillCalculator and BillingPeriod.
"""

from dataclasses import dataclass
from datetime import date, datetime, timedelta
from decimal import ROUND_HALF_UP, Decimal
from zoneinfo import ZoneInfo

# The zone that defines a billing day, as in calculate_bill and the Java project.
BILLING_ZONE = ZoneInfo("Asia/Kolkata")


@dataclass(frozen=True)
class ReadingInput:
    """One reading to be saved. The counterpart of the Java record ReadingInput.

    @dataclass writes __init__, __eq__ and __repr__ from the annotated fields below; frozen=True makes the
    object read-only after it is created, like a Java record.
    """

    meter_id: int
    read_at: datetime  # must carry a time zone, so that it is an exact moment (timestamptz)
    kwh: Decimal


@dataclass(frozen=True)
class BillResult:
    """A saved bill, as returned to the caller."""

    bill_id: int
    premise_id: int
    period_start: date
    period_end: date
    amount: Decimal


@dataclass(frozen=True)
class BillingPeriod:
    """One billing month, in India days: from midnight India time on the 1st to midnight after the last day."""

    year: int
    month: int

    @property
    def first_day(self) -> date:
        return date(self.year, self.month, 1)

    @property
    def last_day(self) -> date:
        next_month = date(self.year + self.month // 12, self.month % 12 + 1, 1)
        return next_month - timedelta(days=1)

    @property
    def start(self) -> datetime:
        """The moment the period starts: midnight India time on the first day."""
        return datetime(self.year, self.month, 1, tzinfo=BILLING_ZONE)

    @property
    def end(self) -> datetime:
        """The moment just after the period: midnight India time after the last day (not included)."""
        following = self.last_day + timedelta(days=1)
        return datetime(following.year, following.month, following.day, tzinfo=BILLING_ZONE)

    @property
    def days(self) -> int:
        return (self.last_day - self.first_day).days + 1


def bill_amount(kwh: Decimal, rate_per_kwh: Decimal, standing_charge: Decimal, days: int) -> Decimal:
    """kWh x rate + days x standing charge, rounded to 2 places, halves away from zero (as PostgreSQL's round).

    Decimal, never float: float(0.1) + float(0.2) is 0.30000000000000004, which is no way to handle money.
    """
    amount = kwh * rate_per_kwh + standing_charge * days
    return amount.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)