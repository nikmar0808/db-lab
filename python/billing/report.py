"""Reads the lab database both ways and prints what it finds; changes nothing (Stage 3, step C7).

Run from the python folder with the passwords from .env in the environment:  python -m billing.report
"-m billing.report" runs this module as a program; the code under 'if __name__ == "__main__":' runs only then.
"""

from decimal import Decimal

from psycopg_pool import ConnectionPool
from sqlalchemy import create_engine, func, select
from sqlalchemy.orm import Session

from billing import settings
from billing.domain import BillingPeriod, bill_amount
from billing.models import Meter, MeterReading, Premise
from billing.psycopg_billing import calculate_bill, one_row


def main() -> None:
    october = BillingPeriod(2026, 10)

    # Way 1: psycopg. open=True opens the pool's connections now; the with-block closes them at the end.
    with ConnectionPool(settings.conninfo(), min_size=1, max_size=2, open=True) as pool:
        with pool.connection() as conn:
            cur = conn.execute("SELECT current_user, current_setting('server_version'), current_setting('TimeZone')")
            user, version, zone = one_row(cur)
            print(f"psycopg:    connected as {user} to PostgreSQL {version}; session time zone {zone}")
        print(f"psycopg:    calculate_bill(1, October 2026) = {calculate_bill(pool, 1, october)}")

    # Way 2: SQLAlchemy. The engine holds the connection pool; a Session borrows from it.
    engine = create_engine(settings.sqlalchemy_url())
    with Session(engine) as session:
        premise = session.get(Premise, 1)
        if premise is None:  # also tells mypy that, after this line, premise is a Premise and not None
            raise SystemExit("premise 1 not found: load sql/02-sample-data.sql first")
        kwh: Decimal = session.execute(
            select(func.coalesce(func.sum(MeterReading.kwh), 0))
            .join(Meter, Meter.id == MeterReading.meter_id)
            .where(Meter.premise_id == 1, MeterReading.read_at >= october.start, MeterReading.read_at < october.end)
        ).scalar_one()
        amount = bill_amount(kwh, premise.tariff.rate_per_kwh, premise.tariff.standing_charge, october.days)
        print(f"SQLAlchemy: premise 1 ({premise.address}) used {kwh} kWh in October; Python's rule gives {amount}")
    engine.dispose()  # close the engine's pooled connections


if __name__ == "__main__":
    main()