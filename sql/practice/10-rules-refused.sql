-- Stage 1 practice: every rule in 01-tables.sql, proved by a change the database refuses.
-- Run as lab_app, WITHOUT ON_ERROR_STOP, so psql reports each refusal and carries on:
--   psql ... -f /sql/practice/10-rules-refused.sql
-- psql commits each statement on its own (autocommit), and every statement here fails, so nothing changes.
-- After each statement, :SQLSTATE prints the five-character error code PostgreSQL returned.

\echo '--- 1. NOT NULL: a customer without an e-mail (Oracle: ORA-01400)'
INSERT INTO customer (name) VALUES ('No Email Ltd');
\echo 'SQLSTATE' :SQLSTATE

\echo '--- 2. An empty string is not NULL, so NOT NULL lets it through; the length rule stops it'
INSERT INTO customer (name, email) VALUES ('', 'empty.name@example.com');
\echo 'SQLSTATE' :SQLSTATE

\echo '--- 3. CHECK: a negative reading (Oracle: ORA-02290)'
INSERT INTO meter_reading (meter_id, read_at, kwh) VALUES (1, '2026-11-01 00:00+05:30', -2.5);
\echo 'SQLSTATE' :SQLSTATE

\echo '--- 4. UNIQUE: a second reading for the same meter and moment (Oracle: ORA-00001)'
INSERT INTO meter_reading (meter_id, read_at, kwh) VALUES (1, '2026-10-01 00:00+05:30', 7.5);
\echo 'SQLSTATE' :SQLSTATE

\echo '--- 5. FOREIGN KEY: a reading for a meter that does not exist (Oracle: ORA-02291)'
INSERT INTO meter_reading (meter_id, read_at, kwh) VALUES (999, '2026-11-01 00:00+05:30', 7.5);
\echo 'SQLSTATE' :SQLSTATE

\echo '--- 6. FOREIGN KEY: deleting a customer who still has premises (Oracle: ORA-02292)'
DELETE FROM customer WHERE email = 'a.rao@example.com';
\echo 'SQLSTATE' :SQLSTATE

\echo '--- 7. CHECK: a bill status outside the allowed list'
INSERT INTO bill (customer_id, premise_id, period_start, period_end, amount, status)
VALUES (1, 1, DATE '2026-10-01', DATE '2026-10-31', 100, 'PENDING');
\echo 'SQLSTATE' :SQLSTATE

\echo '--- 8. IDENTITY: supplying an id that the database must generate (Oracle 12c and later: ORA-32795)'
INSERT INTO customer (id, name, email) VALUES (100, 'Own Id Ltd', 'own.id@example.com');
\echo 'SQLSTATE' :SQLSTATE

\echo '--- 9. Privileges: the application account tries to change a table'
ALTER TABLE customer ADD COLUMN phone text;
\echo 'SQLSTATE' :SQLSTATE