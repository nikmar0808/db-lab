-- Stage 4, lesson A4: what one more index costs on every insert. Run as lab_owner:
--   psql ... -f /sql/practice/21-index-write-cost.sql
-- Two temporary copies of meter_reading's structure: the first with the table's own two indexes (the primary
-- key and the one-reading-per-meter-per-moment rule), the second with the index on (read_at, id) as well.
-- The same 200,000 rows go into each, twice; the second round is the one to compare, because the first also
-- pays for warming up. Temporary tables vanish when this session ends, so nothing in the lab changes.
\timing off
CREATE TEMP TABLE two_indexes (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    meter_id bigint NOT NULL,
    read_at timestamptz NOT NULL,
    kwh numeric(10,3) NOT NULL,
    UNIQUE (meter_id, read_at)
);
CREATE TEMP TABLE three_indexes (LIKE two_indexes INCLUDING ALL);
CREATE INDEX ON three_indexes (read_at, id);

-- The rows: 400 meters x 500 days, in the order a meter-data system receives them (day by day).
CREATE TEMP VIEW new_rows AS
SELECT m AS meter_id, d AS read_at, 7.5 AS kwh
FROM generate_series(timestamptz '2025-06-18 18:30+00', timestamptz '2026-10-30 18:30+00', interval '1 day') AS d
CROSS JOIN generate_series(1, 400) AS m;

\echo '--- round 1 (warming up)'
\timing on
INSERT INTO two_indexes (meter_id, read_at, kwh) SELECT * FROM new_rows;
INSERT INTO three_indexes (meter_id, read_at, kwh) SELECT * FROM new_rows;
\timing off
TRUNCATE two_indexes, three_indexes;

\echo '--- round 2 (compare these two times)'
\timing on
INSERT INTO two_indexes (meter_id, read_at, kwh) SELECT * FROM new_rows;
INSERT INTO three_indexes (meter_id, read_at, kwh) SELECT * FROM new_rows;
\timing off

\echo '--- the size of each index'
SELECT c.relname AS table_name, i.relname AS index_name, pg_size_pretty(pg_relation_size(i.oid)) AS size
FROM pg_index x
JOIN pg_class c ON c.oid = x.indrelid
JOIN pg_class i ON i.oid = x.indexrelid
WHERE c.relname IN ('two_indexes', 'three_indexes', 'meter_reading')
ORDER BY c.relname, i.relname;