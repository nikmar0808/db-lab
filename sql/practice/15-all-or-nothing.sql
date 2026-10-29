-- Stage 1 practice: a transaction that fails part-way leaves nothing behind.
-- Run as lab_app, as one transaction that stops at the first error:
--   psql ... --single-transaction -v ON_ERROR_STOP=1 -f /sql/practice/15-all-or-nothing.sql
-- The two readings are inserted, then the bill is refused, so psql rolls the whole transaction back:
-- the readings disappear too. The 2013 counterpart is an EJB method throwing a system exception.

\echo '--- 1. Two November readings for meter 1'
INSERT INTO meter_reading (meter_id, read_at, kwh) VALUES
    (1, '2026-11-01 00:00+05:30', 7.000),
    (1, '2026-11-02 00:00+05:30', 8.000);

\echo '--- 2. A bill with a status the rules do not allow: refused, and everything above is undone'
INSERT INTO bill (customer_id, premise_id, period_start, period_end, amount, status)
VALUES (1, 1, DATE '2026-11-01', DATE '2026-11-30', 120.00, 'SENT');