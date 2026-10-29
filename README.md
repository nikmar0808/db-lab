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

## Layout

| Path | Contents |
|---|---|
| `docker-compose.yml` | The two databases |
| `postgres/init/` | PostgreSQL first-start script: accounts and database |
| `mongo/scripts/` | MongoDB start-up (key file) and health-check scripts |
| `mongo/init/` | MongoDB first-start script: the application's account |
| `docs/adr/` | Decision records |
| `docs/versions.md` | Versions in use, recorded from the tools themselves |