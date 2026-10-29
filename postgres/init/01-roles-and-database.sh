#!/bin/bash
# Runs once, the first time the PostgreSQL container starts with an empty volume (Part A, lesson A2).
# Creates the two lab accounts and the lab database, as a DBA would have done for a new project.
set -e   # stop at the first failing command

# psql reads the SQL below from this script. The passwords come from the container's environment
# (set from .env through docker-compose.yml) and are passed in as psql variables, so they never
# appear in this file. :'owner_pw' inserts a variable's value as a quoted SQL string.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
     -v owner_pw="$LAB_OWNER_PASSWORD" -v app_pw="$LAB_APP_PASSWORD" <<'SQL'
-- The "schema owner": will own the database and every table built in it.
CREATE ROLE lab_owner LOGIN PASSWORD :'owner_pw';
-- The application's account: may read and change data, may not create or drop tables.
CREATE ROLE lab_app LOGIN PASSWORD :'app_pw';

-- The lab database, owned by lab_owner. Owning it lets lab_owner create tables in its schema "public".
CREATE DATABASE utility_lab OWNER lab_owner;
-- By default every role may connect to every database. Take that away, then allow only lab_app.
-- (The owner and the superuser can always connect.)
REVOKE ALL ON DATABASE utility_lab FROM PUBLIC;
GRANT CONNECT ON DATABASE utility_lab TO lab_app;

-- The remaining statements apply inside utility_lab, so connect to it.
\connect utility_lab
-- lab_app may use objects in the schema "public", but not create any there.
GRANT USAGE ON SCHEMA public TO lab_app;
-- Rights on tables and number generators that lab_owner creates from now on (Part A, lesson A3).
ALTER DEFAULT PRIVILEGES FOR ROLE lab_owner IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO lab_app;
ALTER DEFAULT PRIVILEGES FOR ROLE lab_owner IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO lab_app;
SQL