"""The result of the report, the same type from both databases, so that the two can be compared with ==."""

from dataclasses import dataclass
from decimal import Decimal


@dataclass(frozen=True)
class ReportRow:
    source: str
    outcome: str
    batches: int
    readings: int
    kwh: Decimal