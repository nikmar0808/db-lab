"""An audit write that fails must not fail the business write (Stage 7).

The decision: the business transaction commits first and alone. The audit write comes after, in its own
transaction, under a short time limit, and can never raise. If it fails, the record goes to a fallback file.
A business failure is audited too: its transaction has rolled back, and the audit record stays.
"""

import json
import logging
from collections.abc import Sequence
from dataclasses import dataclass, field
from datetime import UTC, datetime
from decimal import Decimal
from pathlib import Path
from typing import Protocol

from audit_lab.records import AuditRecord
from billing.domain import BillingPeriod, BillResult, ReadingInput

log = logging.getLogger(__name__)


class AuditStore(Protocol):
    """Anything with this method can be the audit store: PostgresAudit, MongoAudit or a test's stand-in."""

    def write(self, record: AuditRecord) -> None: ...


class Billing(Protocol):
    def record_readings_and_bill(self, premise_id: int, readings: list[ReadingInput],
                                 period: BillingPeriod) -> BillResult: ...


@dataclass
class FallbackFile:
    """Where a record goes when the store refuses it: one JSON line per record, for a later reload."""

    path: Path
    failures: int = field(default=0)        # in production, a metric with an alert on it

    def save(self, record: AuditRecord, error: Exception) -> None:
        self.failures += 1
        try:
            line = json.dumps({"received_at": record.received_at.isoformat(), "document": record.jsonb_document(),
                               "error": type(error).__name__})
            with self.path.open("a", encoding="utf-8") as f:
                f.write(line + "\n")
        except Exception:                   # even this must not fail the business write
            log.exception("audit record %s lost", record.batch_id)


def write_audit_safely(store: AuditStore, record: AuditRecord, fallback: FallbackFile) -> bool:
    """Returns True if the store took the record. Never raises: the one place where catching everything is right."""
    try:
        store.write(record)
        return True
    except Exception as e:
        log.warning("audit write failed for %s: %s", record.batch_id, type(e).__name__)
        fallback.save(record, e)
        return False


def record_and_audit(billing: Billing, store: AuditStore, fallback: FallbackFile, batch_id: str, source: str,
                     premise_id: int, readings: Sequence[ReadingInput], period: BillingPeriod) -> BillResult:
    """The main task, audited. The business result decides what the caller sees; the audit never does."""
    received = datetime.now(UTC)
    total = sum((r.kwh for r in readings), Decimal(0))

    def audit(outcome: str, errors: list[dict[str, str]]) -> None:
        write_audit_safely(store, AuditRecord(batch_id, received, source, outcome, premise_id, len(readings), total,
                                              {"readings": len(readings)}, errors), fallback)

    try:
        result = billing.record_readings_and_bill(premise_id, list(readings), period)
    except Exception as e:
        audit("FAILED", [{"code": type(e).__name__, "message": "business write rolled back"}])
        raise
    audit("ACCEPTED", [])
    return result