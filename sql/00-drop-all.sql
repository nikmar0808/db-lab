-- Practice only: removes everything the migrations build, so that the next start of the Java program
-- builds it again from nothing (Stage 2). Run as lab_owner:  psql ... -v ON_ERROR_STOP=1 -f /sql/00-drop-all.sql
-- Not a migration: production databases are never dropped and rebuilt. IF EXISTS makes each line safe to run
-- when the object is already gone. Tables are dropped children first, so no foreign key is left pointing at a
-- missing table. Flyway's own history table goes too: otherwise Flyway would believe the migrations had run.
DROP FUNCTION IF EXISTS calculate_bill(bigint, date, date);
DROP TABLE IF EXISTS bill;
DROP TABLE IF EXISTS meter_reading;
DROP TABLE IF EXISTS meter;
DROP TABLE IF EXISTS premise;
DROP TABLE IF EXISTS tariff;
DROP TABLE IF EXISTS customer;
DROP TABLE IF EXISTS flyway_schema_history;