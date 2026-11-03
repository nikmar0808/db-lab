"""One audit record, and how it looks in each database (Stage 7)."""

import random
from collections.abc import Iterator
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from decimal import Decimal
from typing import Any

from bson.decimal128 import Decimal128

SOURCES = ["HES-NORTH", "HES-SOUTH", "MDM-BATCH", "FIELD-APP"]
EXPIRE_DAYS = 90


@dataclass(frozen=True)
class AuditRecord:
    """What happened to one batch of readings received."""

    batch_id: str
    received_at: datetime          # an exact moment: must carry a time zone
    source: str
    outcome: str                   # ACCEPTED, PARTIAL, REJECTED or FAILED
    premise_id: int
    reading_count: int
    total_kwh: Decimal
    raw: dict[str, Any] = field(default_factory=dict)            # the message as it arrived, kept as it was
    errors: list[dict[str, Any]] = field(default_factory=list)   # present only when something went wrong

    def _common(self) -> dict[str, Any]:
        doc: dict[str, Any] = {"batch_id": self.batch_id, "source": self.source, "outcome": self.outcome,
                               "premise_id": self.premise_id, "reading_count": self.reading_count, "raw": self.raw}
        if self.errors:
            doc["errors"] = self.errors
        return doc

    def jsonb_document(self) -> dict[str, Any]:
        """For the jsonb column. JSON has no exact decimal type, so kWh travels as text and SQL casts it."""
        return {**self._common(), "total_kwh": str(self.total_kwh)}

    def mongo_document(self) -> dict[str, Any]:
        """For MongoDB. BSON has both types the jsonb form lacks: a real date and an exact decimal."""
        return {**self._common(), "received_at": self.received_at, "total_kwh": Decimal128(self.total_kwh)}


def sample_records(count: int, now: datetime, days: int = 120, seed: int = 7) -> Iterator[AuditRecord]:
    """Made-up records, spread evenly over the last days; the same seed always gives the same records."""
    rng = random.Random(seed)
    for i in range(count):
        received = (now - timedelta(seconds=rng.randint(0, days * 86400))).replace(microsecond=0)
        source = rng.choices(SOURCES, weights=[50, 30, 15, 5])[0]
        n = rng.randint(1, 8)
        kwhs = [Decimal(rng.randint(1000, 30000)) / 1000 for _ in range(n)]
        draw = rng.random()
        outcome = "ACCEPTED" if draw < 0.90 else "PARTIAL" if draw < 0.97 else "REJECTED"
        premise = rng.randint(1, 5000)
        serial = f"MTR-{rng.randint(1, 20000):06d}"
        errors: list[dict[str, Any]] = []
        if outcome != "ACCEPTED":
            # E900 is rare on purpose: a selective search. The others are common.
            code = "E900" if rng.random() < 0.01 else rng.choice(["E101", "E102", "E103", "E104"])
            errors = [{"code": code, "meter": serial, "message": "reading refused"}]
        raw: dict[str, Any] = {"format": "v2" if rng.random() < 0.5 else "v1", "meter_serial": serial,
                               "readings": [{"at": (received - timedelta(hours=h)).isoformat(), "kwh": str(k)}
                                            for h, k in enumerate(kwhs)]}
        if source.startswith("HES"):                   # each source sends its own extra fields: the shape varies
            raw["signal_dbm"] = rng.randint(-110, -60)
        elif source == "FIELD-APP":
            raw["engineer"] = f"ENG-{rng.randint(1, 200):03d}"
        yield AuditRecord(f"B-{i:07d}", received, source, outcome, premise, n, sum(kwhs, Decimal(0)), raw, errors)