-- Stage 1 practice: timestamptz, session time zones, and why a month depends on the zone.
-- Run as lab_app:  psql ... -v ON_ERROR_STOP=1 -f /sql/practice/12-time-zones.sql
-- Reads only; SET TIME ZONE lasts for this session alone (Oracle: ALTER SESSION SET TIME_ZONE).

\echo '--- 1. The session time zone a new connection gets from the server'
SHOW TimeZone;

\echo '--- 2. The first October reading of meter 1, shown in that zone'
SELECT meter_id, read_at FROM meter_reading
WHERE meter_id = 1 ORDER BY read_at OFFSET 30 LIMIT 1;

SET TIME ZONE 'Asia/Kolkata';

\echo '--- 3. The same row after SET TIME ZONE: the same moment, shown in India time'
SELECT meter_id, read_at FROM meter_reading
WHERE meter_id = 1 ORDER BY read_at OFFSET 30 LIMIT 1;

\echo '--- 4. timestamp keeps the clock reading as typed; timestamptz is an exact moment'
SELECT TIMESTAMP '2026-10-01 00:00' AS plain_timestamp,
       TIMESTAMPTZ '2026-10-01 00:00' AS moment,
       TIMESTAMPTZ '2026-10-01 00:00' AT TIME ZONE 'Europe/London' AS that_moment_on_a_london_clock;

\echo '--- 5. October readings of meter 1, counted by the month they fall in, in India time'
SELECT count(*) AS october_india
FROM meter_reading
WHERE meter_id = 1 AND date_trunc('month', read_at) = TIMESTAMPTZ '2026-10-01 00:00';

SET TIME ZONE 'UTC';

\echo '--- 6. The same count in UTC: the 1 October reading falls on 30 September there'
SELECT count(*) AS october_utc
FROM meter_reading
WHERE meter_id = 1 AND date_trunc('month', read_at) = TIMESTAMPTZ '2026-10-01 00:00';