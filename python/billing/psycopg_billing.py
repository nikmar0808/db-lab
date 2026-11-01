"""Way 1: psycopg alone, the counterpart of plain JDBC (Stage 3, lesson A5).

psycopg is the PostgreSQL driver for Python. Here it is used directly, with SQL written by hand, through a
connection pool from the companion package psycopg_pool, the counterpart of HikariCP.
"""

from decimal import Decimal
from typing import Any

from psycopg import Cursor
from psycopg_pool import ConnectionPool

from billing.domain import BillingPeriod, BillResult, ReadingInput, bill_amount


def one_row(cur: Cursor[Any]) -> tuple[Any, ...]:
    """The next row of a statement that always returns exactly one, such as an aggregate or INSERT ... RETURNING.

    fetchone() is typed as "a row, or None when there are no more rows". The type checker (mypy) therefore
    refuses fetchone()[0], because indexing None would crash. This helper turns the impossible None into a
    clear error, and tells mypy that what comes back is a row.
    """
    row = cur.fetchone()
    if row is None:
        raise RuntimeError("expected one row, got none")
    return row


class PsycopgBilling:
    """Records readings and produces a bill, in one transaction, with hand-written SQL."""

    def __init__(self, pool: ConnectionPool) -> None:
        # The pool is passed in by whoever creates this object (a test, or the report script): the same idea
        # as constructor injection in Spring, done by hand.
        self._pool = pool

    def record_readings_and_bill(
        self, premise_id: int, readings: list[ReadingInput], period: BillingPeriod
    ) -> BillResult:
        # pool.connection() borrows a connection. As a with-block it also manages the transaction: when the
        # block ends normally it COMMITs, when an exception leaves the block it ROLLs BACK, and in both cases
        # it returns the connection to the pool. The 2013 code's setAutoCommit/commit/rollback/finally, in one line.
        with self._pool.connection() as conn:
            with conn.cursor() as cur:  # a cursor runs statements and holds their results (JDBC: Statement + ResultSet)
                # %s marks a parameter, whatever its type; values are always passed separately, never pasted
                # into the SQL text, which is what protects against SQL injection (JDBC: ? and setXxx).
                cur.executemany(
                    "INSERT INTO meter_reading (meter_id, read_at, kwh) VALUES (%s, %s, %s)",
                    [(r.meter_id, r.read_at, r.kwh) for r in readings],
                )

                cur.execute(
                    """
                    SELECT p.customer_id, t.rate_per_kwh, t.standing_charge
                    FROM premise p JOIN tariff t ON t.code = p.tariff_code
                    WHERE p.id = %s
                    """,
                    (premise_id,),  # parameters are a tuple; (x,) with the comma is a tuple of one
                )
                row = cur.fetchone()  # one row as a tuple, or None if there is none
                if row is None:
                    raise ValueError(f"premise {premise_id} does not exist")
                customer_id, rate, standing = row  # unpack the tuple into three names

                cur.execute(
                    """
                    SELECT coalesce(sum(r.kwh), 0)
                    FROM meter m JOIN meter_reading r ON r.meter_id = m.id
                    WHERE m.premise_id = %s AND r.read_at >= %s AND r.read_at < %s
                    """,
                    (premise_id, period.start, period.end),
                )
                kwh: Decimal = one_row(cur)[0]  # numeric arrives as Decimal

                amount = bill_amount(kwh, rate, standing, period.days)

                cur.execute(
                    """
                    INSERT INTO bill (customer_id, premise_id, period_start, period_end, amount)
                    VALUES (%s, %s, %s, %s, %s)
                    RETURNING id
                    """,
                    (customer_id, premise_id, period.first_day, period.last_day, amount),
                )
                bill_id: int = one_row(cur)[0]

        # Reached only if the with-block committed.
        return BillResult(bill_id, premise_id, period.first_day, period.last_day, amount)


def calculate_bill(pool: ConnectionPool, premise_id: int, period: BillingPeriod) -> Decimal:
    """Calls the database function. PostgreSQL functions are called with a plain SELECT; no special syntax."""
    with pool.connection() as conn:
        cur = conn.execute("SELECT calculate_bill(%s, %s, %s)", (premise_id, period.first_day, period.last_day))
        amount: Decimal = one_row(cur)[0]
        return amount