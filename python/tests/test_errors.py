"""Broken rules as Python exceptions: Stage 1's SQLSTATE codes, reached from Python (Stage 3, lesson A5).

psycopg turns every SQLSTATE into its own exception class in psycopg.errors, named after the SQLSTATE's
standard name: 23505 unique_violation becomes UniqueViolation. SQLAlchemy wraps them in its own
IntegrityError and keeps psycopg's exception as .orig, as Spring keeps the SQLException underneath.
"""

from datetime import datetime
from decimal import Decimal

import psycopg
import pytest
from psycopg import errors
from psycopg_pool import ConnectionPool
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session, sessionmaker

from billing.domain import BILLING_ZONE
from billing.models import MeterReading
from conftest import Fixture, LabDatabase

INSERT_READING = "INSERT INTO meter_reading (meter_id, read_at, kwh) VALUES (%s, %s, %s)"
MOMENT = datetime(2026, 10, 1, tzinfo=BILLING_ZONE)


def test_duplicate_reading_is_a_unique_violation(pool: ConnectionPool, new_premise: Fixture) -> None:
    with pool.connection() as conn:
        conn.execute(INSERT_READING, (new_premise.meter_id, MOMENT, Decimal("7.5")))

    with pytest.raises(errors.UniqueViolation) as caught:  # "as caught" keeps the exception for checking
        with pool.connection() as conn:
            conn.execute(INSERT_READING, (new_premise.meter_id, MOMENT, Decimal("9.0")))
    assert caught.value.sqlstate == "23505"  # unique_violation, ORA-00001
    assert caught.value.diag.constraint_name == "meter_reading_meter_time_uk"


def test_reading_for_a_missing_meter_is_a_foreign_key_violation(pool: ConnectionPool) -> None:
    with pytest.raises(errors.ForeignKeyViolation) as caught:
        with pool.connection() as conn:
            conn.execute(INSERT_READING, (-1, MOMENT, Decimal("7.5")))
    assert caught.value.sqlstate == "23503"  # foreign_key_violation, ORA-02291


def test_after_an_error_the_whole_transaction_is_refused(pool: ConnectionPool, new_premise: Fixture) -> None:
    with pool.connection() as conn:
        with pytest.raises(errors.CheckViolation):
            conn.execute(INSERT_READING, (new_premise.meter_id, MOMENT, Decimal("-1")))  # breaks the kWh rule
        with pytest.raises(errors.InFailedSqlTransaction) as caught:
            conn.execute(INSERT_READING, (new_premise.meter_id, MOMENT, Decimal("7.5")))  # a good row, refused
        assert caught.value.sqlstate == "25P02"
        conn.rollback()


def test_sqlalchemy_wraps_the_driver_error(sessions: sessionmaker[Session], new_premise: Fixture) -> None:
    reading = dict(meter_id=new_premise.meter_id, read_at=MOMENT, kwh=Decimal("7.5"))
    with pytest.raises(IntegrityError) as caught:
        with sessions.begin() as session:
            session.add(MeterReading(**reading))  # **reading: pass the dictionary's entries as named arguments
            session.flush()
            session.add(MeterReading(**reading))
    assert isinstance(caught.value.orig, errors.UniqueViolation)
    assert caught.value.orig.sqlstate == "23505"


def test_the_program_runs_as_lab_app_and_cannot_create_tables(pool: ConnectionPool) -> None:
    with pool.connection() as conn:
        assert conn.execute("SELECT current_user").fetchone()[0] == "lab_app"
        with pytest.raises(errors.InsufficientPrivilege) as caught:
            conn.execute("CREATE TABLE app_try (x int)")
        assert caught.value.sqlstate == "42501"
        conn.rollback()


def test_python_sends_no_time_zone(database: LabDatabase) -> None:
    # Unlike Java's JDBC driver, psycopg does not set the session's TimeZone, so the server's default stays:
    # the Asia/Calcutta problem of Stages 0 and 2 cannot happen here.
    with psycopg.connect(database.conninfo()) as conn:
        assert conn.execute("SHOW TimeZone").fetchone()[0] == "Etc/UTC"