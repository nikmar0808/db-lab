> **Finished learning lab, not production code.** PostgreSQL and MongoDB from the application developer's side, built on free tools, with AI assistance. Every result below was measured on the author's laptop, so the numbers show orders of magnitude, not benchmarks.

# db-lab

PostgreSQL and MongoDB, practised from Java and Python on a small, made-up utility data set (customers, premises, meters,
meter readings, tariffs, bills). What the lab taught was then applied to a real service, in
[`eai-backend-poc`](https://github.com/nikmar0808/eai-backend-poc).

Database administration (backups, server tuning, replication) is left out on purpose.

## What it shows

| Topic | What was built | One result |
|---|---|---|
| Rules in the database | Six tables, with each rule proved by an insert that the database refuses; one PL/pgSQL function | |
| Java data access, four ways | Readings and a bill in one transaction: plain JDBC, `JdbcClient`, Spring Data JPA, a database function | All four give the same bill, and tests prove it |
| Python data access, two ways | psycopg with hand-written SQL, and the typed SQLAlchemy 2 ORM | The same bill as Java |
| Migrations | Flyway owns the lab's tables; an Alembic exercise on its own database | One set of tables, one tool |
| The N+1 problem | The account summary three ways, with statements counted | 501 statements in 142 ms, then 3 in 10 ms, then 1 in 3.6 ms |
| Writing many rows | Seven ways, timed on 5,000 rows | A commit per row 3.8 s; a JDBC batch 96 ms; `COPY` 41 ms |
| Paging | `OFFSET` against keyset, on a deep page | 77 ms against 0.02 ms |
| Lost updates | Two sessions on one row, prevented by locking and by a version number | |
| MongoDB design | Embedding against referencing; readings in monthly buckets against one document each | 5.6 MB against 16.0 MB on disk; indexes 0.9 MB against 46.6 MB |
| MongoDB from applications | PyMongo and Spring Data MongoDB, against a local replica set and Atlas's free tier, with failover imitated by a fail point | |
| PostgreSQL or MongoDB | One audit record in both, 200,000 records: `jsonb` with GIN against a collection with a TTL index | The same report, 72 ms against 91 ms; 189 MB against 60 MB on disk |
| An audit write that fails | Written after the business commit, with a time limit, unable to raise; tested beside the ways that lose the business write | [ADR-007](docs/adr/ADR-007-audit-data-postgresql-or-mongodb.md) |

Full results: [`docs/performance-results.md`](docs/performance-results.md), [`docs/mongodb-design.md`](docs/mongodb-design.md)
and [`docs/results/`](docs/results/). Every pull request runs a secret scan, 46 Java tests and 46 Python tests against
throwaway databases, and a type check.

The retrospective of this lab and `eai-backend-poc` together is in
[that repository](https://github.com/nikmar0808/eai-backend-poc/blob/main/docs/RETROSPECTIVE.md).

## Start the databases

1. Copy `.env.sample` to `.env` and replace each `change-me` with a password of your own
   (letters and digits only).
2. Run `docker compose up -d --wait`.

| Database | Address from this machine | Notes |
|---|---|---|
| PostgreSQL 18 | `127.0.0.1:5433`, database `utility_lab` | Accounts: `postgres` (superuser), `lab_owner` (owns the tables), `lab_app` (read and write only) |
| MongoDB 8.2 | `mongodb://lab_app:<password>@127.0.0.1:27017/?directConnection=true&authSource=admin` | One-member replica set `rs0`, login required. Accounts: `mongo_admin` (administrator), `lab_app` (read and write on `utility_lab`). Reachable only from this machine |
| MongoDB Atlas (free cluster) | `mongodb+srv://lab_app:<password>@<ATLAS_HOST>/utility_lab` | Three-member replica set run by Atlas, encrypted connections, reachable only from addresses on the project's access list. Stands in for a shared environment |

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
| `audit_record` | One record of each batch received, kept for 90 days |

## The Java project

Spring Boot 4 with JDBC, `JdbcClient`, Spring Data JPA and Hibernate, Flyway and HikariCP. The main task,
recording readings and producing a bill in one transaction, is built four ways: plain JDBC, `JdbcClient`,
Spring Data JPA, and a call to the database function. The account summary is built three ways. `mvn verify` in `java/`
runs every test against a throwaway PostgreSQL 18 and MongoDB 8.2 started by Testcontainers, so Docker must be running.

## The Python project

The same main task from Python, two ways: psycopg with hand-written SQL, and the SQLAlchemy 2 ORM in its typed
style. Flyway (Java) owns the tables; the Python tests replay the same migration files on a throwaway
PostgreSQL 18. In `python/`, with a virtual environment:

```text
python -m venv .venv
.venv\Scripts\python.exe -m pip install -r requirements-dev.txt
.venv\Scripts\python.exe -m pytest -v
.venv\Scripts\python.exe -m mypy billing mongo_lab audit_lab
```

`python -m billing.report` reads the lab database both ways and changes nothing. `alembic_practice/` holds an
Alembic exercise on its own database, `alembic_lab`, never on `utility_lab`.

## Performance work

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

## MongoDB through its shell

`mongo/practice/` holds `mongosh` scripts, shown inside the MongoDB container as `/practice`. Each runs as
`lab_app`, for example:

```text
docker compose exec -T mongo mongosh "mongodb://lab_app:<password>@127.0.0.1:27017/utility_lab?directConnection=true&authSource=admin" --quiet -f /practice/51-load-sample.js
```

| Script | What it does |
|---|---|
| `50-crud.js` | Insert, find, update and delete, each beside its SQL, on a scratch collection |
| `51-load-sample.js` | The sample data as documents (`tariffs`, `customers`, `meter_readings` in monthly buckets, `readings_flat`), with validators and indexes. Also runs against Atlas |
| `52-shapes.js` | What embedding and referencing make easy and hard; puts back every change |
| `53-validator.js` | Inserts the validators refuse |
| `54-reports.js` | Two aggregation reports beside their SQL; the second saves October's bills into `bills`. Also runs against Atlas |
| `55-large-and-plans.js` | A million readings, query plans before and after each index, and the two shapes' sizes. Local only |
| `56-ttl.js` | A TTL index removing old documents by itself |

## MongoDB from applications

The main task against MongoDB, from Python and from Java, on the collections that `51-load-sample.js` builds.

- **Python**, `python/mongo_lab/`, with PyMongo: recording readings once only into the monthly buckets, bulk
  loading, the bill, the readings and the bill in one multi-document transaction, the account summary in two
  commands, and bills of two shapes (`schema_version`) read side by side and upgraded in bulk. The billing rule is
  the one in `billing/domain.py`, shared with the PostgreSQL code.
- **Java**, `java/src/main/java/com/dblab/billing/mongo/`, with Spring Data MongoDB: `@Document` records, a
  `MongoRepository`, `MongoTemplate`, and a transaction through `MongoTransactionManager`.

Both run against the local MongoDB or Atlas, chosen by configuration: Python reads `MONGO_TARGET` (`local` or
`atlas`) or `--target`; Java reads the whole address from `MONGO_URI`. With the passwords from `.env` loaded:

```text
cd python
.venv\Scripts\python.exe -m mongo_lab.report --target atlas
cd ..\java
mvn -q spring-boot:run -Dspring-boot.run.profiles=mongo-report
```

The tests start a throwaway MongoDB 8.2 replica set with Testcontainers and load it with `51-load-sample.js`;
two of them imitate a failover with the test-only command `configureFailPoint`.

## The audit record both ways

The same record of each batch of readings received, kept for 90 days, in a PostgreSQL `jsonb` column with a GIN
index (`V5__audit_record.sql`) and in a MongoDB collection with a TTL index, written and reported on by
`python/audit_lab/`. An audit write that fails does not fail the business write: the business transaction commits
first, and the audit write follows in its own transaction, under a short time limit, and cannot raise an error
(`audited.py`; in Java, the `audit` package, beside the ways of writing it that lose the business write).

With the passwords from `.env` loaded, the comparison loads 200,000 records into both and writes
`docs/results/audit-comparison.md`, leaving both stores empty:

```text
cd python
.venv\Scripts\python.exe -m audit_lab.compare
```

`sql/practice/30-audit-partitions.sql` compares `DELETE` with dropping a monthly partition. The decision is in
[ADR-007](docs/adr/ADR-007-audit-data-postgresql-or-mongodb.md).

## Layout

| Path | Contents |
|---|---|
| `docker-compose.yml` | The two databases |
| `postgres/init/` | PostgreSQL first-start script: accounts and database (also used by the Java tests) |
| `mongo/scripts/` | MongoDB start-up (key file) and health-check scripts |
| `mongo/init/` | MongoDB first-start script: the application's account |
| `mongo/practice/` | `mongosh` exercises: CRUD, documents and validators, shapes, reports, indexes and plans, TTL |
| `java/` | The Spring Boot project: migrations, the four ways, the `mongo` and `audit` packages, tests |
| `python/` | The Python project: psycopg and SQLAlchemy ways, `mongo_lab` (PyMongo), `audit_lab` (the audit record both ways), tests, the Alembic exercise |
| `sql/` | Sample data, the large data set, and scripts to remove them |
| `sql/practice/` | SQL exercises: rules, Oracle differences, time zones, window functions, bills, query plans, index cost, partitions |
| `docs/adr/` | Decision records |
| `docs/versions.md` | Versions in use, recorded from the tools themselves |
| `docs/results/` | Measurement results: PostgreSQL (written by the Java program's `measure` profile), MongoDB plans, the audit comparison |
| `docs/performance-results.md` | What the PostgreSQL measurements show |
| `docs/mongodb-design.md` | The MongoDB document design, and what the comparisons showed |