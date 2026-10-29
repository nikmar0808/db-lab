-- Practice only: removes everything Stage 1 builds, so the scripts can be run again from the start.
-- Run as lab_owner:  psql ... -v ON_ERROR_STOP=1 -f /sql/00-drop-all.sql
-- Not a migration: Stage 2 never drops tables this way. IF EXISTS makes each line safe to run when
-- the object is already gone. Tables are dropped children first, so no foreign key is left pointing
-- at a missing table.
DROP FUNCTION IF EXISTS calculate_bill(bigint, date, date);
DROP TABLE IF EXISTS bill;
DROP TABLE IF EXISTS meter_reading;
DROP TABLE IF EXISTS meter;
DROP TABLE IF EXISTS premise;
DROP TABLE IF EXISTS tariff;
DROP TABLE IF EXISTS customer;