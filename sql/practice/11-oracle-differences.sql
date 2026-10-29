-- Stage 1 practice: the Oracle differences a developer meets, each in one small query.
-- Run as lab_app, WITHOUT ON_ERROR_STOP, because step 5 fails on purpose:
--   psql ... -f /sql/practice/11-oracle-differences.sql
-- The work runs inside transactions that are rolled back, so the data is unchanged afterwards and the
-- script can be run again.

BEGIN;

\echo '--- 1. No DUAL: a SELECT needs no FROM. now() is the counterpart of SYSTIMESTAMP, current_date of TRUNC(SYSDATE)'
SELECT 1 + 1 AS two, current_date >= DATE '2026-01-01' AS date_works, now() IS NOT NULL AS now_works;

\echo '--- 2. An empty string is not NULL (in Oracle the first answer would be true and the second NULL)'
SELECT '' IS NULL AS empty_is_null, length('') AS empty_length;

\echo '--- 3. Joining text to NULL gives NULL (Oracle ignores the NULL); concat() ignores it like Oracle'
SELECT 'MTR-' || NULL AS with_bars, concat('MTR-', NULL) AS with_concat;

\echo '--- 4. NVL becomes COALESCE; DECODE becomes CASE'
SELECT serial_number,
       coalesce(NULL, installed_on) AS installed,
       CASE WHEN installed_on >= DATE '2026-01-01' THEN 'new' ELSE 'established' END AS age_band
FROM meter
WHERE id IN (1, 5)
ORDER BY id;

\echo '--- 5. Names are folded to lower case: METER and meter are the same table; "METER" is not'
SELECT count(*) AS meters FROM METER;
SELECT count(*) AS meters FROM "METER";

\echo '--- 5b. After an error, the whole transaction is refused until ROLLBACK (Oracle undoes only the failed statement)'
SELECT 1 AS after_the_error;
\echo 'SQLSTATE' :SQLSTATE

ROLLBACK;
BEGIN;

\echo '--- 6. LIMIT: the first rows of an ordered result (Oracle 12c: FETCH FIRST n ROWS ONLY, also accepted here)'
SELECT code, rate_per_kwh FROM tariff ORDER BY rate_per_kwh DESC LIMIT 1;

\echo '--- 7. RETURNING: an INSERT hands back the generated key, in plain SQL'
INSERT INTO customer (name, email) VALUES ('G. Pillai', 'g.pillai@example.com')
RETURNING id > 0 AS got_an_id, name;

\echo '--- 8. Upsert with ON CONFLICT: a corrected reading replaces the stored one (Oracle: MERGE)'
INSERT INTO meter_reading (meter_id, read_at, kwh)
VALUES (1, '2026-10-01 00:00+05:30', 9.250)
ON CONFLICT (meter_id, read_at) DO UPDATE SET kwh = EXCLUDED.kwh
RETURNING meter_id, kwh;

\echo '--- 9. The same with MERGE, which PostgreSQL also has (version 15 and later)'
MERGE INTO meter_reading AS r
USING (VALUES (1::bigint, TIMESTAMPTZ '2026-10-01 00:00+05:30', 9.500::numeric)) AS v (meter_id, read_at, kwh)
   ON r.meter_id = v.meter_id AND r.read_at = v.read_at
WHEN MATCHED THEN UPDATE SET kwh = v.kwh
WHEN NOT MATCHED THEN INSERT (meter_id, read_at, kwh) VALUES (v.meter_id, v.read_at, v.kwh);
SELECT kwh FROM meter_reading WHERE meter_id = 1 AND read_at = '2026-10-01 00:00+05:30';

ROLLBACK;

\echo '--- 10. After ROLLBACK the corrected reading is back to its original value'
SELECT kwh FROM meter_reading WHERE meter_id = 1 AND read_at = '2026-10-01 00:00+05:30';