-- Stage 4: removes the large data set made by 03-large-data.sql, and nothing else. Run as lab_owner:
--   psql ... -v ON_ERROR_STOP=1 -f /sql/04-remove-large-data.sql
-- Children first, so no foreign key is left pointing at a removed row. One transaction: all or nothing.
BEGIN;
DELETE FROM meter_reading WHERE meter_id IN (SELECT id FROM meter WHERE serial_number >= 'MTR-200000');
DELETE FROM bill WHERE premise_id IN (SELECT id FROM premise WHERE address LIKE '% Load Street, %');
DELETE FROM meter WHERE serial_number >= 'MTR-200000';
DELETE FROM premise WHERE address LIKE '% Load Street, %';
DELETE FROM customer WHERE email LIKE 'load%@example.com';
COMMIT;
-- Give the space back for re-use and refresh the statistics (lesson A11).
VACUUM ANALYZE meter_reading;
SELECT count(*) AS readings FROM meter_reading;