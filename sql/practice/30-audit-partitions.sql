-- Stage 7: removing old rows, two ways, on 400,000 made-up audit rows. Run as lab_owner:
--   psql -U lab_owner -d utility_lab -f /sql/practice/30-audit-partitions.sql
-- Removes its own tables at the end.
\timing off
DROP TABLE IF EXISTS audit_demo_plain, audit_demo_by_month;

\echo === One plain table
CREATE TABLE audit_demo_plain (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    received_at timestamptz NOT NULL,
    document jsonb NOT NULL);
CREATE INDEX audit_demo_plain_time_ix ON audit_demo_plain (received_at);
CREATE INDEX audit_demo_plain_doc_ix ON audit_demo_plain USING GIN (document jsonb_path_ops);

\echo === The same, partitioned by month. The partition key must be part of the primary key.
CREATE TABLE audit_demo_by_month (
    id bigint GENERATED ALWAYS AS IDENTITY,
    received_at timestamptz NOT NULL,
    document jsonb NOT NULL,
    PRIMARY KEY (id, received_at)
) PARTITION BY RANGE (received_at);
CREATE TABLE audit_demo_2026_07 PARTITION OF audit_demo_by_month FOR VALUES FROM ('2026-07-01 00:00+05:30') TO ('2026-08-01 00:00+05:30');
CREATE TABLE audit_demo_2026_08 PARTITION OF audit_demo_by_month FOR VALUES FROM ('2026-08-01 00:00+05:30') TO ('2026-09-01 00:00+05:30');
CREATE TABLE audit_demo_2026_09 PARTITION OF audit_demo_by_month FOR VALUES FROM ('2026-09-01 00:00+05:30') TO ('2026-10-01 00:00+05:30');
CREATE TABLE audit_demo_2026_10 PARTITION OF audit_demo_by_month FOR VALUES FROM ('2026-10-01 00:00+05:30') TO ('2026-11-01 00:00+05:30');
-- An index on the parent is created on every partition, present and future.
CREATE INDEX audit_demo_by_month_time_ix ON audit_demo_by_month (received_at);
CREATE INDEX audit_demo_by_month_doc_ix ON audit_demo_by_month USING GIN (document jsonb_path_ops);

\echo === 400,000 rows into each
INSERT INTO audit_demo_plain (received_at, document)
SELECT timestamptz '2026-07-01 00:00+05:30' + (i * interval '123 days' / 400000),
       jsonb_build_object('batch_id', 'B-' || i, 'source', 'HES-' || (i % 4), 'outcome', 'ACCEPTED', 'readings', repeat('x', 300))
FROM generate_series(1, 399999) AS i;
INSERT INTO audit_demo_by_month (received_at, document) SELECT received_at, document FROM audit_demo_plain;
ANALYZE audit_demo_plain;
ANALYZE audit_demo_by_month;

\echo === A question about October looks only in the October partition (partition pruning)
EXPLAIN (COSTS OFF) SELECT count(*) FROM audit_demo_by_month WHERE received_at >= '2026-10-05 00:00+05:30' AND received_at < '2026-10-06 00:00+05:30';

\echo === A month with no partition: the insert is refused, so a forgotten partition stops every audit write
INSERT INTO audit_demo_by_month (received_at, document) VALUES ('2026-11-15 00:00+05:30', '{"batch_id":"B-NEW"}');

\echo === Space before removing July
SELECT pg_size_pretty(pg_total_relation_size('audit_demo_plain')) AS plain,
       (SELECT pg_size_pretty(sum(pg_total_relation_size(inhrelid))) FROM pg_inherits WHERE inhparent = 'audit_demo_by_month'::regclass) AS partitioned;

\timing on
\echo === Way 1: DELETE July from the plain table
DELETE FROM audit_demo_plain WHERE received_at < '2026-08-01 00:00+05:30';
\echo === Way 2: DROP the July partition
DROP TABLE audit_demo_2026_07;
\timing off

\echo === Space after removing July, then after VACUUM
SELECT pg_size_pretty(pg_total_relation_size('audit_demo_plain')) AS plain,
       (SELECT pg_size_pretty(sum(pg_total_relation_size(inhrelid))) FROM pg_inherits WHERE inhparent = 'audit_demo_by_month'::regclass) AS partitioned;
VACUUM audit_demo_plain;
SELECT pg_size_pretty(pg_total_relation_size('audit_demo_plain')) AS plain_after_vacuum;

DROP TABLE audit_demo_plain, audit_demo_by_month;
\echo === Done