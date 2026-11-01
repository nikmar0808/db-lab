-- Stage 4: the large, made-up data set for the performance work (lesson A1).
-- Run as lab_owner, after the sample data (02-sample-data.sql):
--   psql ... -v ON_ERROR_STOP=1 -f /sql/03-large-data.sql
-- Size: 1,000 customers by default, each with 2 premises and 1 meter per premise, and one reading per meter per
-- day from 19 June 2025 to 31 October 2026 (500 days): 2,000 meters and 1,000,000 readings. For a slower laptop,
-- -v customers=300 makes 600 meters and 300,000 readings; the plans keep the same shape.
-- The rows are told apart from the sample data by their names: e-mails load<n>@example.com, addresses
-- "<k> Load Street, <n>", serial numbers from MTR-200001. 04-remove-large-data.sql removes exactly these rows.

-- psql: use 1000 unless -v customers=... was given.
\if :{?customers}
\else
\set customers 1000
\endif

\echo 'Customers to create:' :customers
\timing on

-- Refuse to run twice: the second run would break the unique e-mail rule half-way through.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM customer WHERE email LIKE 'load%@example.com') THEN
        RAISE EXCEPTION 'the large data set is already loaded: run 04-remove-large-data.sql first';
    END IF;
END
$$;

BEGIN;

INSERT INTO customer (name, email)
SELECT 'Load customer ' || n, 'load' || n || '@example.com'
FROM generate_series(1, :customers) AS n;

-- Two premises per load customer; every tenth customer is commercial.
INSERT INTO premise (customer_id, address, tariff_code)
SELECT c.id, k || ' Load Street, ' || c.id, CASE WHEN c.id % 10 = 0 THEN 'COM-1' ELSE 'DOM-1' END
FROM customer c CROSS JOIN generate_series(1, 2) AS k
WHERE c.email LIKE 'load%@example.com'
ORDER BY c.id, k;

-- One meter per load premise. 200000 + the premise id keeps the serial numbers clear of the sample data's.
INSERT INTO meter (premise_id, serial_number, installed_on)
SELECT p.id, 'MTR-' || lpad((200000 + p.id)::text, 6, '0'), DATE '2025-01-01'
FROM premise p
WHERE p.address LIKE '% Load Street, %'
ORDER BY p.id;

-- One reading per meter per day, at midnight India time, as in the sample data. The kWh come from a fixed
-- formula, so every run produces the same data. Rows are inserted day by day, as a real meter-data system
-- receives them, so one meter's readings are spread across the table (lesson A2).
INSERT INTO meter_reading (meter_id, read_at, kwh)
SELECT m.id,
       d.day::timestamp AT TIME ZONE 'Asia/Kolkata',
       6 + ((m.id * 7 + extract(doy FROM d.day)::int * 3) % 11) * 0.5
FROM generate_series(DATE '2025-06-19', DATE '2026-10-31', INTERVAL '1 day') AS d (day)
CROSS JOIN meter m
WHERE m.serial_number >= 'MTR-200000'
ORDER BY d.day, m.id;

COMMIT;

-- Fresh statistics now, rather than whenever autovacuum gets to it (lesson A3).
ANALYZE customer;
ANALYZE premise;
ANALYZE meter;
ANALYZE meter_reading;

\timing off
SELECT (SELECT count(*) FROM customer) AS customers,
       (SELECT count(*) FROM meter) AS meters,
       (SELECT count(*) FROM meter_reading) AS readings,
       pg_size_pretty(pg_table_size('meter_reading')) AS table_size,
       pg_size_pretty(pg_indexes_size('meter_reading')) AS index_size;