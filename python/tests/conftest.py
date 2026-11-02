"""Shared set-up for every test: a throwaway PostgreSQL 18 with the lab's accounts and tables (Stage 3, lesson A8),
and, from Stage 6, a throwaway MongoDB 8.2 replica set with Stage 5's collections.

pytest reads this file before the tests and offers each function decorated with @pytest.fixture to any test
that names it as a parameter. A fixture prepares something, hands it to the test with "yield", and cleans
up after the yield once it is no longer needed. scope="session" makes one copy for the whole test run.
The counterpart of the Java project's PostgresTestBase and TestData.
"""

import itertools
import time
from collections.abc import Iterator
from dataclasses import dataclass
from datetime import date
from pathlib import Path

import psycopg
import pytest
from psycopg_pool import ConnectionPool
from pymongo import MongoClient
from pymongo.database import Database
from pymongo.errors import OperationFailure, PyMongoError
from sqlalchemy import create_engine
from sqlalchemy.orm import Session, sessionmaker
from testcontainers.community.postgres import PostgresContainer
from testcontainers.core.container import DockerContainer

from billing.psycopg_billing import PsycopgBilling
from billing.sqlalchemy_billing import SqlAlchemyBilling
from mongo_lab.client import Doc, make_client

# The repository's root folder: this file is python/tests/conftest.py, so two folders up.
REPO = Path(__file__).resolve().parents[2]
INIT_SCRIPT = REPO / "postgres" / "init" / "01-roles-and-database.sh"
MIGRATIONS = REPO / "java" / "src" / "main" / "resources" / "db" / "migration"


@dataclass(frozen=True)
class LabDatabase:
    """Where the throwaway database is, and how to reach it as each lab account."""

    host: str
    port: int
    passwords = {"lab_app": "app-test", "lab_owner": "owner-test", "test": "test"}  # test-only values

    def conninfo(self, user: str = "lab_app", dbname: str = "utility_lab") -> str:
        return f"host={self.host} port={self.port} dbname={dbname} user={user} password={self.passwords[user]}"

    def url(self, user: str = "lab_app", dbname: str = "utility_lab") -> str:
        return f"postgresql+psycopg://{user}:{self.passwords[user]}@{self.host}:{self.port}/{dbname}"


def _wait_until_ready(db: LabDatabase) -> None:
    """Keep trying to connect as lab_app over the network until it works, for up to 60 seconds.

    During first-start set-up the image runs a temporary server that accepts no network connections, so a
    successful network connection means the set-up script has finished.
    """
    deadline = time.monotonic() + 60
    while True:
        try:
            with psycopg.connect(db.conninfo(), connect_timeout=2):
                return
        except psycopg.OperationalError:
            if time.monotonic() > deadline:
                raise
            time.sleep(0.5)


def _apply_migrations(db: LabDatabase) -> None:
    """Runs Flyway's migration files, in version order, as lab_owner.

    Flyway owns these tables (Stage 2). The tests only replay its files on an empty, throwaway database, so
    that Python is tested against exactly the tables Java builds.

    autocommit=True: each file is sent as it is and commits on its own, with no transaction around the files.
    V3 (Stage 4) holds CREATE INDEX CONCURRENTLY, which PostgreSQL refuses inside a transaction block.
    """
    files = sorted(MIGRATIONS.glob("V*__*.sql"), key=lambda f: int(f.name[1:].split("__")[0]))
    with psycopg.connect(db.conninfo("lab_owner"), autocommit=True) as conn:
        for file in files:
            conn.execute(file.read_text(encoding="utf-8"))


@pytest.fixture(scope="session")
def database() -> Iterator[LabDatabase]:
    """Starts postgres:18 for the test run, with Stage 0's accounts and Flyway's tables; removed at the end."""
    container = (
        PostgresContainer("postgres:18", username="test", password="test", dbname="test", driver=None)
        # Stage 0's first-start script, shown inside the container where the image looks for such scripts.
        .with_volume_mapping(str(INIT_SCRIPT), "/docker-entrypoint-initdb.d/01-roles-and-database.sh", "ro")
        .with_env("LAB_OWNER_PASSWORD", LabDatabase.passwords["lab_owner"])
        .with_env("LAB_APP_PASSWORD", LabDatabase.passwords["lab_app"])
    )
    with container:  # started here; stopped and removed when the with-block ends, after the last test
        host = container.get_container_host_ip()
        # 127.0.0.1 rather than localhost, which caused long delays on this Windows machine in POC 2.
        db = LabDatabase("127.0.0.1" if host == "localhost" else host, int(container.get_exposed_port(5432)))
        _wait_until_ready(db)
        _apply_migrations(db)
        yield db


@pytest.fixture(scope="session")
def pool(database: LabDatabase) -> Iterator[ConnectionPool]:
    """A psycopg connection pool as lab_app: at most 5 connections, wait at most 5 seconds for one."""
    with ConnectionPool(database.conninfo(), min_size=1, max_size=5, timeout=5, open=True) as p:
        yield p


@pytest.fixture(scope="session")
def sessions(database: LabDatabase) -> Iterator[sessionmaker[Session]]:
    """A SQLAlchemy engine as lab_app (its own pool, of the same size), and a maker of Sessions on it."""
    engine = create_engine(database.url(), pool_size=5, max_overflow=0, pool_timeout=5)
    yield sessionmaker(engine)
    engine.dispose()


@pytest.fixture(params=["psycopg", "sqlalchemy"])
def billing(request: pytest.FixtureRequest, pool: ConnectionPool, sessions: sessionmaker[Session]):
    """Each way of doing the main task in turn: every test that uses this fixture runs once per way."""
    if request.param == "psycopg":
        return PsycopgBilling(pool)
    return SqlAlchemyBilling(sessions)


@dataclass(frozen=True)
class Fixture:
    """The ids of one customer with one premise and one meter."""

    customer_id: int
    premise_id: int
    meter_id: int


_numbers = itertools.count(1)  # 1, 2, 3, ... : a different number for every premise the tests create


@pytest.fixture
def new_premise(pool: ConnectionPool) -> Fixture:
    """Creates, as lab_app, a tariff, customer, premise and meter that no other test uses.

    Priced like DOM-1 in Stage 1. Tests never depend on each other's rows or on the order they run in.
    """
    n = next(_numbers)
    tariff = f"TPY-{n}"
    with pool.connection() as conn:
        conn.execute(
            "INSERT INTO tariff (code, description, rate_per_kwh, standing_charge) VALUES (%s, 'Test', 6.5000, 2.00)",
            (tariff,),
        )
        customer_id = conn.execute(
            "INSERT INTO customer (name, email) VALUES (%s, %s) RETURNING id",
            (f"Python customer {n}", f"py{n}@example.com"),
        ).fetchone()[0]
        premise_id = conn.execute(
            "INSERT INTO premise (customer_id, address, tariff_code) VALUES (%s, %s, %s) RETURNING id",
            (customer_id, f"{n} Python Street", tariff),
        ).fetchone()[0]
        meter_id = conn.execute(
            "INSERT INTO meter (premise_id, serial_number, installed_on) VALUES (%s, %s, %s) RETURNING id",
            (premise_id, f"MTR-{800000 + n:06d}", date(2026, 1, 1)),
        ).fetchone()[0]
    return Fixture(customer_id, premise_id, meter_id)


def count(pool: ConnectionPool, sql: str, value: int) -> int:
    """Runs a SELECT count(*) with one parameter and returns the number."""
    with pool.connection() as conn:
        return conn.execute(sql, (value,)).fetchone()[0]


# ---------------------------------------------------------------- MongoDB (Stage 6)

MONGO_PRACTICE = REPO / "mongo" / "practice"


@dataclass(frozen=True)
class LabMongo:
    """The throwaway MongoDB: its address from this machine."""

    uri: str


def _initiate_replica_set(uri: str) -> None:
    """Waits for mongod to answer, makes it a one-member replica set, and waits until it is the primary.

    The lab's own container does this in its health check (Stage 0). A replica set is needed for transactions.
    """
    deadline = time.monotonic() + 60
    with MongoClient(uri, serverSelectionTimeoutMS=1000) as probe:
        while True:
            try:
                probe.admin.command("ping")
                break
            except PyMongoError:
                if time.monotonic() > deadline:
                    raise
                time.sleep(0.5)
        try:
            probe.admin.command("replSetInitiate", {"_id": "rs0", "members": [{"_id": 0, "host": "localhost:27017"}]})
        except OperationFailure as e:
            if e.code != 23:   # 23 AlreadyInitialized: fine
                raise
        while not probe.admin.command("hello").get("isWritablePrimary"):
            if time.monotonic() > deadline:
                raise RuntimeError("the test MongoDB did not become primary within 60 seconds")
            time.sleep(0.5)


@pytest.fixture(scope="session")
def lab_mongo() -> Iterator[LabMongo]:
    """Starts mongo:8.2 as a one-member replica set for the test run, with Stage 5's collections; removed at the end.

    --setParameter enableTestCommands=1 allows configureFailPoint, used to imitate a failover. No login: this
    database exists only for the tests and only on this machine (the lab's own MongoDB has login, ADR-014).
    """
    container = (
        DockerContainer("mongo:8.2")
        .with_command("mongod --replSet rs0 --bind_ip_all --setParameter enableTestCommands=1")
        .with_exposed_ports(27017)
        # Stage 5's practice scripts, shown inside the container, so that 51-load-sample.js can build the data.
        .with_volume_mapping(str(MONGO_PRACTICE), "/practice", "ro")
    )
    with container:
        host = container.get_container_host_ip()
        host = "127.0.0.1" if host == "localhost" else host
        uri = f"mongodb://{host}:{container.get_exposed_port(27017)}/?directConnection=true"
        _initiate_replica_set(uri)
        code, output = container.exec(
            ["mongosh", "--quiet", "mongodb://localhost:27017/utility_lab?directConnection=true",
             "-f", "/practice/51-load-sample.js"])
        if code != 0:
            raise RuntimeError(f"51-load-sample.js failed: {output.decode()}")
        yield LabMongo(uri)


@pytest.fixture(scope="session")
def client(lab_mongo: LabMongo) -> Iterator[MongoClient[Doc]]:
    """One MongoClient for the run, configured as the program's own (mongo_lab.client.make_client)."""
    with make_client(lab_mongo.uri) as c:
        yield c


@pytest.fixture
def db(client: MongoClient[Doc]) -> Database[Doc]:
    """The lab's database, utility_lab, in the test MongoDB."""
    return client.get_database("utility_lab")