"""The Alembic practice migrations, run forwards and backwards on their own throwaway database (Stage 3, lesson A7).

Alembic is driven here from Python (alembic.command) instead of from the command line, with the same
alembic.ini. The practice database alembic_lab is created inside the test container, so Flyway's tables in
utility_lab are never touched: one set of tables, one migration tool.
"""

from pathlib import Path

import psycopg
import pytest
from alembic import command
from alembic.config import Config
from alembic.script import ScriptDirectory
from sqlalchemy import create_engine, inspect

from conftest import LabDatabase

INI = Path(__file__).resolve().parents[1] / "alembic_practice" / "alembic.ini"


@pytest.fixture(scope="module")
def alembic_config(database: LabDatabase) -> Config:
    # CREATE DATABASE cannot run inside a transaction, so autocommit=True: each statement commits on its own.
    with psycopg.connect(database.conninfo("test", "test"), autocommit=True) as conn:
        conn.execute("CREATE DATABASE alembic_lab OWNER lab_owner")
    config = Config(str(INI))
    config.set_main_option("sqlalchemy.url", database.url("lab_owner", "alembic_lab"))
    return config


def columns(database: LabDatabase) -> list[str]:
    """The column names of practice_note, or an empty list if the table does not exist."""
    engine = create_engine(database.url("lab_owner", "alembic_lab"))
    try:
        inspector = inspect(engine)  # reads the database's own description of its tables
        if not inspector.has_table("practice_note"):
            return []
        return [c["name"] for c in inspector.get_columns("practice_note")]
    finally:
        engine.dispose()


def test_upgrade_downgrade_and_upgrade_again(alembic_config: Config, database: LabDatabase) -> None:
    head = ScriptDirectory.from_config(alembic_config).get_current_head()  # the newest migration's id

    command.upgrade(alembic_config, "head")
    # The first four columns come from 0001 and 0002; later migrations may add more after them.
    assert columns(database)[:4] == ["id", "body", "created_at", "author"]
    with psycopg.connect(database.conninfo("lab_owner", "alembic_lab")) as conn:
        assert conn.execute("SELECT version_num FROM alembic_version").fetchone()[0] == head

    command.downgrade(alembic_config, "base")  # every downgrade(), newest first
    assert columns(database) == []

    command.upgrade(alembic_config, "head")
    assert "author" in columns(database)


def test_the_models_match_the_migrations(alembic_config: Config) -> None:
    command.upgrade(alembic_config, "head")
    # "alembic check": raises an error if the models describe anything the migrations have not built.
    command.check(alembic_config)