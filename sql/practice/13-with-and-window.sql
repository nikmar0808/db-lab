-- Stage 1 practice: WITH clauses and analytic (window) functions, plus two PostgreSQL extras.
-- Run as lab_app:  psql ... -v ON_ERROR_STOP=1 -f /sql/practice/13-with-and-window.sql
-- Reads only. Dates typed as text below, such as '2026-10-01', are read in the session's time zone,
-- so the session is set to India time first.

SET TIME ZONE 'Asia/Kolkata';

\echo '--- 1. WITH: October kWh per premise, including a premise with no readings'
WITH october AS (
    SELECT m.premise_id, sum(r.kwh) AS kwh
    FROM meter_reading r
    JOIN meter m ON m.id = r.meter_id
    WHERE r.read_at >= '2026-10-01' AND r.read_at < '2026-11-01'
    GROUP BY m.premise_id
)
SELECT p.id, p.address, coalesce(o.kwh, 0) AS october_kwh
FROM premise p
LEFT JOIN october o ON o.premise_id = p.id
ORDER BY p.id;

\echo '--- 2. Latest reading per meter with ROW_NUMBER, written exactly as in Oracle'
SELECT meter_id, read_at, kwh
FROM (
    SELECT r.meter_id, r.read_at, r.kwh,
           row_number() OVER (PARTITION BY r.meter_id ORDER BY r.read_at DESC) AS rn
    FROM meter_reading r
) AS ranked
WHERE rn = 1
ORDER BY meter_id;

\echo '--- 3. The same with DISTINCT ON, which only PostgreSQL has: the first row of each meter_id group'
SELECT DISTINCT ON (meter_id) meter_id, read_at, kwh
FROM meter_reading
ORDER BY meter_id, read_at DESC;

\echo '--- 4. Running total and day-on-day change for the meter installed mid-October (first 5 days)'
SELECT read_at::date AS day,
       kwh,
       sum(kwh) OVER (ORDER BY read_at) AS running_total,
       kwh - lag(kwh) OVER (ORDER BY read_at) AS change
FROM meter_reading
WHERE meter_id = 5
ORDER BY read_at
LIMIT 5;

\echo '--- 5. FILTER: September and October side by side (Oracle: SUM(CASE WHEN ... THEN kwh END))'
SELECT m.premise_id,
       sum(r.kwh) FILTER (WHERE r.read_at <  '2026-10-01') AS september_kwh,
       sum(r.kwh) FILTER (WHERE r.read_at >= '2026-10-01') AS october_kwh
FROM meter_reading r
JOIN meter m ON m.id = r.meter_id
GROUP BY m.premise_id
ORDER BY m.premise_id;