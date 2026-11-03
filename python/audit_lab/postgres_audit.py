"""The audit record in PostgreSQL: a jsonb column, a GIN index and an ordinary index on time (Stage 7)."""

from collections.abc import Iterable
from datetime import datetime
from typing import Any

from psycopg.types.json import Jsonb
from psycopg_pool import ConnectionPool

from audit_lab.records import AuditRecord
from audit_lab.report import ReportRow

REPORT_SQL = """
    SELECT document ->> 'source' AS source,
           document ->> 'outcome' AS outcome,
           count(*) AS batches,
           sum((document ->> 'reading_count')::int) AS readings,
           sum((document ->> 'total_kwh')::numeric) AS kwh
    FROM audit_record
    WHERE received_at >= %s AND received_at < %s
    GROUP BY 1, 2
    ORDER BY 1, 2
"""


class PostgresAudit:
    def __init__(self, pool: ConnectionPool) -> None:
        self._pool = pool

    def write(self, record: AuditRecord) -> None:
        """Saves one record in a transaction of its own, on a connection of its own."""
        with self._pool.connection() as conn:
            conn.execute("INSERT INTO audit_record (received_at, document) VALUES (%s, %s)",
                         (record.received_at, Jsonb(record.jsonb_document())))

    def write_many(self, records: Iterable[AuditRecord]) -> int:
        """Loads many records with COPY (Stage 4's fastest way in)."""
        written = 0
        with self._pool.connection() as conn, conn.cursor() as cur:
            with cur.copy("COPY audit_record (received_at, document) FROM STDIN") as copy:
                for r in records:
                    copy.write_row((r.received_at, Jsonb(r.jsonb_document())))
                    written += 1
        return written

    def find_by_batch(self, batch_id: str) -> list[dict[str, Any]]:
        """Containment: documents that hold this key with this value. The GIN index serves @>."""
        with self._pool.connection() as conn:
            rows = conn.execute("SELECT document FROM audit_record WHERE document @> %s",
                                (Jsonb({"batch_id": batch_id}),)).fetchall()
        return [row[0] for row in rows]

    def report(self, since: datetime, until: datetime) -> list[ReportRow]:
        with self._pool.connection() as conn:
            rows = conn.execute(REPORT_SQL, (since, until)).fetchall()
        return [ReportRow(*row) for row in rows]

    def delete_expired(self, before: datetime) -> int:
        """PostgreSQL has no TTL: something must run this on a schedule."""
        with self._pool.connection() as conn:
            return conn.execute("DELETE FROM audit_record WHERE received_at < %s", (before,)).rowcount