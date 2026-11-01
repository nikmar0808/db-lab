> **Work in progress.** A learning lab, built stage by stage. Not production code.

# db-lab

PostgreSQL and MongoDB from the application developer's side, practised from Java and Python on a
small, made-up utility data set (customers, premises, meters, meter readings, tariffs, bills).

## Start the databases

1. Copy `.env.sample` to `.env` and replace each `change-me` with a password of your own
   (letters and digits only).
2. Run `docker compose up -d --wait`.

| Database | Address from this machine | Notes |
|---|---|---|
| PostgreSQL 18 | `127.0.0.1:5433`, database `utility_lab` | Accounts: `postgres` (superuser), `lab_owner` (owns the tables), `lab_app` (read and write only) |
| MongoDB 8.2 | `mongodb://lab_app:<password>@127.0.0.1:27017/?directConnection=true&authSource=admin` | One-member replica set `rs0`, login required. Accounts: `mongo_admin` (administrator), `lab_app` (read and write on `utility_lab`). Reachable only from this machine |

`docker compose down` stops both and keeps the data; `docker compose down -v` also deletes the data.

## Build the PostgreSQL tables

The tables and the function `calculate_bill` are Flyway migrations in
`java/src/main/resources/db/migration/`. The Java program applies any that have not run yet, as `lab_owner`,
every time it starts. With the passwords from `.env` in the environment:

```text
cd java
mvn spring-boot:run
```

Then, for the sample data, run `sql/02-sample-data.sql` as `lab_owner` inside the PostgreSQL container
(`/sql/02-sample-data.sql`). `sql/00-drop-all.sql` removes everything, Flyway's history included, so that the
next start builds it all again.

| Table | Holds |
|---|---|
| `customer` | People and companies that receive bills |
| `tariff` | Price lists: rate per kWh and standing charge per day |
| `premise` | Supplied addresses, each with one customer and one tariff |
| `meter` | Meters installed at premises |
| `meter_reading` | Energy recorded by a meter for the day starting at `read_at` |
| `bill` | One bill per premise and period |

## The Java project

Spring Boot 4 with JDBC, `JdbcClient`, Spring Data JPA and Hibernate, Flyway and HikariCP. The main task,
recording readings and producing a bill in one transaction, is built four ways: plain JDBC, `JdbcClient`,
Spring Data JPA, and a call to the database function. The account summary is built three ways (Stage 4). `mvn verify` in `java/` runs every test against a
throwaway PostgreSQL 18 started by Testcontainers, so Docker must be running.

## The Python project

The same main task from Python, two ways: psycopg with hand-written SQL, and the SQLAlchemy 2 ORM in its typed
style. Flyway (Java) owns the tables; the Python tests replay the same migration files on a throwaway
PostgreSQL 18. In `python/`, with a virtual environment:

```text
python -m venv .venv
.venv\Scripts\python.exe -m pip install -r requirements-dev.txt
.venv\Scripts\python.exe -m pytest -v
.venv\Scripts\python.exe -m mypy billing
```

`python -m billing.report` reads the lab database both ways and changes nothing. `alembic_practice/` holds an
Alembic exercise on its own database, `alembic_lab`, never on `utility_lab`.

## Performance work (Stage 4)

`sql/03-large-data.sql` adds about a million made-up readings (`-v customers=300` for 300,000);
`sql/04-remove-large-data.sql` removes exactly those rows. On that data:

- `sql/practice/20-query-plans.sql` shows six questions with their real plans (`EXPLAIN (ANALYZE, BUFFERS)`);
- `sql/practice/21-index-write-cost.sql` times what one more index costs on inserts;
- the Java program, run with the Spring profile `measure`, times query plans, three ways of building the account
  summary, and seven ways of writing many rows, and writes the results to `docs/results/<label>.md`:

```text
cd java
mvn spring-boot:run -Dspring-boot.run.profiles=measure -Dspring-boot.run.arguments=--measure.label=<label>
```

`docs/performance-results.md` explains what the measurements show. The PostgreSQL container loads
`pg_stat_statements`, which records every statement the server runs (`CREATE EXTENSION pg_stat_statements;` once,
as `postgres`, in `utility_lab`).

## Layout

| Path | Contents |
|---|---|
| `docker-compose.yml` | The two databases |
| `postgres/init/` | PostgreSQL first-start script: accounts and database (also used by the Java tests) |
| `mongo/scripts/` | MongoDB start-up (key file) and health-check scripts |
| `mongo/init/` | MongoDB first-start script: the application's account |
| `java/` | The Spring Boot project: migrations, the four ways, tests |
| `python/` | The Python project: psycopg and SQLAlchemy ways, tests, the Alembic exercise |
| `sql/` | Sample data, the large data set, and scripts to remove them |
| `sql/practice/` | SQL exercises: rules, Oracle differences, time zones, window functions, bills, query plans, index cost |
| `docs/adr/` | Decision records |
| `docs/versions.md` | Versions in use, recorded from the tools themselves |
| `docs/results/` | Measurement results written by the Java program's `measure` profile |
| `docs/performance-results.md` | What the measurements show |