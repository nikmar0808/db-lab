"""Where the lab database is, and who connects (Stage 3, lesson A3).

The counterpart of the Java project's application.properties. Every value comes from an environment variable,
with the lab's address as the default; passwords have no default, so a missing one fails at once.
"""

import os


def _require(name: str) -> str:
    """The value of an environment variable that must be set."""
    value = os.environ.get(name)
    if not value:
        raise RuntimeError(f"environment variable {name} is not set: load .env first")
    return value


def conninfo(user: str = "lab_app", password_variable: str = "LAB_APP_PASSWORD", dbname: str = "utility_lab") -> str:
    """A libpq connection string, the form psycopg uses: space-separated key=value pairs."""
    host = os.environ.get("DB_HOST", "127.0.0.1")
    port = os.environ.get("DB_PORT", "5433")
    password = _require(password_variable)
    return f"host={host} port={port} dbname={dbname} user={user} password={password}"


def sqlalchemy_url(user: str = "lab_app", password_variable: str = "LAB_APP_PASSWORD", dbname: str = "utility_lab") -> str:
    """A SQLAlchemy database URL. "postgresql+psycopg" means: PostgreSQL, through the psycopg (version 3) driver."""
    host = os.environ.get("DB_HOST", "127.0.0.1")
    port = os.environ.get("DB_PORT", "5433")
    password = _require(password_variable)
    return f"postgresql+psycopg://{user}:{password}@{host}:{port}/{dbname}"