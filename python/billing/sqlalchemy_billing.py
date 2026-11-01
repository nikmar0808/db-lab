"""Way 2: SQLAlchemy ORM, the counterpart of Spring Data JPA with Hibernate (Stage 3, lesson A6).

Objects in, objects out. SQLAlchemy writes the SQL for loading and saving; the one aggregate query is built
in Python with select(), SQLAlchemy's way of writing SQL as code.
"""

from decimal import Decimal

from sqlalchemy import func, select
from sqlalchemy.orm import Session, sessionmaker

from billing.domain import BillingPeriod, BillResult, ReadingInput, bill_amount
from billing.models import Bill, Meter, MeterReading, Premise


class SqlAlchemyBilling:
    """Records readings and produces a bill, in one transaction, with ORM objects."""

    def __init__(self, sessions: sessionmaker[Session]) -> None:
        # A sessionmaker makes Session objects; a Session is SQLAlchemy's unit of work, the counterpart of
        # Hibernate's persistence context (Stage 2, lesson A7).
        self._sessions = sessions

    def record_readings_and_bill(
        self, premise_id: int, readings: list[ReadingInput], period: BillingPeriod
    ) -> BillResult:
        # sessions.begin() as a with-block: start a session and a transaction; COMMIT when the block ends
        # normally, ROLLBACK if an exception leaves it, then close the session. The @Transactional of Python.
        with self._sessions.begin() as session:
            session.add_all(
                MeterReading(meter_id=r.meter_id, read_at=r.read_at, kwh=r.kwh) for r in readings
            )

            premise = session.get(Premise, premise_id)  # by primary key; None if there is no such row
            if premise is None:
                raise ValueError(f"premise {premise_id} does not exist")
            tariff = premise.tariff  # lazy: loaded now, inside the transaction

            # Before running this query, the session sends the pending inserts above ("autoflush"),
            # so the total includes them: the same flush-before-query as Hibernate.
            # execute(...).scalar_one(): exactly one row with one value, or an error. (session.scalar() would
            # also allow "no row" and return None, which mypy rightly refuses to treat as a Decimal.)
            kwh: Decimal = session.execute(
                select(func.coalesce(func.sum(MeterReading.kwh), 0))
                .join(Meter, Meter.id == MeterReading.meter_id)
                .where(
                    Meter.premise_id == premise_id,
                    MeterReading.read_at >= period.start,
                    MeterReading.read_at < period.end,
                )
            ).scalar_one()

            amount = bill_amount(kwh, tariff.rate_per_kwh, tariff.standing_charge, period.days)
            bill = Bill(
                customer_id=premise.customer_id,
                premise_id=premise_id,
                period_start=period.first_day,
                period_end=period.last_day,
                amount=amount,
            )
            session.add(bill)
            session.flush()  # send the INSERT now, so that the database hands out bill.id
            result = BillResult(bill.id, premise_id, period.first_day, period.last_day, amount)

        return result