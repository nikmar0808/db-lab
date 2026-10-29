-- Stage 1 practice: the main task in plain SQL - produce October's bills with calculate_bill.
-- Run as lab_app, as one transaction:
--   psql ... --single-transaction -v ON_ERROR_STOP=1 -f /sql/practice/14-produce-october-bills.sql
-- This one keeps its changes: the bills are committed. Running it again adds nothing, because
-- ON CONFLICT DO NOTHING skips a premise that already has a bill for the period.

INSERT INTO bill (customer_id, premise_id, period_start, period_end, amount)
SELECT p.customer_id, p.id, DATE '2026-10-01', DATE '2026-10-31',
       calculate_bill(p.id, DATE '2026-10-01', DATE '2026-10-31')
FROM premise p
ORDER BY p.id
ON CONFLICT (premise_id, period_start) DO NOTHING
RETURNING premise_id, amount, status;