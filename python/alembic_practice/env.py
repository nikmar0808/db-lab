"""Alembic's start-up script: every alembic command runs this file first (Stage 3, lesson A7).

It decides which database to connect to and which models describe the tables, then hands control to Alembic.
"""

import os
import sys
from logging.config import fileConfig

from alembic import context
from sqlalchemy import create_engine

# Make practice_models.py, which sits next to this file, importable.
sys.path.insert(0, os.path.dirname(__file__))
from practice_models import Base  # noqa: E402  (an import after code, on purpose)

config = context.config  # the settings read from alembic.ini
if config.config_file_name is not None:
    fileConfig(config.config_file_name)  # apply the log settings of alembic.ini

# The tables as the models describe them. "alembic check" and "alembic revision --autogenerate" compare
# this description with the real database.
target_metadata = Base.metadata


def database_url() -> str:
    """alembic_lab, as lab_owner. A test sets sqlalchemy.url itself; otherwise the lab's address is used."""
    url = config.get_main_option("sqlalchemy.url")
    if url:
        return url
    password = os.environ.get("LAB_OWNER_PASSWORD")
    if not password:
        raise RuntimeError("environment variable LAB_OWNER_PASSWORD is not set: load .env first")
    host = os.environ.get("DB_HOST", "127.0.0.1")
    port = os.environ.get("DB_PORT", "5433")
    return f"postgresql+psycopg://lab_owner:{password}@{host}:{port}/alembic_lab"


def run_migrations() -> None:
    engine = create_engine(database_url())
    with engine.connect() as connection:
        context.configure(connection=connection, target_metadata=target_metadata)
        # Each migration runs inside a transaction: PostgreSQL can roll back CREATE and ALTER (Stage 1).
        with context.begin_transaction():
            context.run_migrations()
    engine.dispose()


run_migrations()