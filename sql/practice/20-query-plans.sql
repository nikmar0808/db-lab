-- Stage 4, lessons A2 to A4 and A7: six questions about meter readings, each shown with its real plan.
-- Run as lab_owner on the large data set:  psql ... -f /sql/practice/20-query-plans.sql
-- Run it twice in Stage 4: before and after the index on (read_at, id) is added. Nothing is changed.
-- EXPLAIN (ANALYZE, BUFFERS) runs each statement and shows the steps, real row counts, times and pages read.

-- Values the questions need, kept in psql variables by \gset: the meter whose bill is asked for, and the
-- position 90% of the way through all readings in time order, for the paging questions.
SELECT id AS meter_id FROM meter WHERE serial_number = 'MTR-200500' \gset
SELECT (count(*) * 9 / 10) AS deep FROM meter_reading \gset
SELECT read_at AS last_read_at, id AS last_id
FROM meter_reading ORDER BY read_at, id OFFSET :deep - 1 LIMIT 1 \gset
\echo 'meter' :meter_id ', deep page starts after row' :deep ', whose read_at and id are' :'last_read_at' :last_id

\echo
\echo '=== 1. One meter, one month: the bill (lesson A2)'
EXPLAIN (ANALYZE, BUFFERS)
SELECT sum(kwh) FROM meter_reading
WHERE meter_id = :meter_id AND read_at >= '2026-09-30 18:30+00' AND read_at < '2026-10-31 18:30+00';
-- === 1. One meter, one month: the bill (lesson A2)
--                                                                                      QUERY PLAN                                                                                      
-- -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
--  Aggregate  (cost=130.88..130.89 rows=1 width=32) (actual time=0.074..0.074 rows=1.00 loops=1)
--    Buffers: shared hit=37
--    ->  Bitmap Heap Scan on meter_reading  (cost=4.85..130.79 rows=33 width=5) (actual time=0.025..0.065 rows=31.00 loops=1)
--          Recheck Cond: ((meter_id = 502) AND (read_at >= '2026-09-30 18:30:00+00'::timestamp with time zone) AND (read_at < '2026-10-31 18:30:00+00'::timestamp with time zone))
--          Heap Blocks: exact=31
--          Buffers: shared hit=37
--          ->  Bitmap Index Scan on meter_reading_meter_time_uk  (cost=0.00..4.84 rows=33 width=0) (actual time=0.018..0.018 rows=31.00 loops=1)
--                Index Cond: ((meter_id = 502) AND (read_at >= '2026-09-30 18:30:00+00'::timestamp with time zone) AND (read_at < '2026-10-31 18:30:00+00'::timestamp with time zone))
--                Index Searches: 1
--                Buffers: shared hit=6
--  Planning:
--    Buffers: shared hit=32
--  Planning Time: 0.132 ms
--  Execution Time: 0.095 ms
-- (14 rows)
-- === 1. One meter, one month: the bill (lesson A2)
--                                                                                      QUERY PLAN                                                                                      
-- -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
--  Aggregate  (cost=123.79..123.80 rows=1 width=32) (actual time=0.339..0.340 rows=1.00 loops=1)
--    Buffers: shared hit=36 read=1
--    ->  Bitmap Heap Scan on meter_reading  (cost=4.82..123.71 rows=31 width=5) (actual time=0.287..0.322 rows=31.00 loops=1)
--          Recheck Cond: ((meter_id = 502) AND (read_at >= '2026-09-30 18:30:00+00'::timestamp with time zone) AND (read_at < '2026-10-31 18:30:00+00'::timestamp with time zone))
--          Heap Blocks: exact=31
--          Buffers: shared hit=36 read=1
--          ->  Bitmap Index Scan on meter_reading_meter_time_uk  (cost=0.00..4.81 rows=31 width=0) (actual time=0.266..0.266 rows=31.00 loops=1)
--                Index Cond: ((meter_id = 502) AND (read_at >= '2026-09-30 18:30:00+00'::timestamp with time zone) AND (read_at < '2026-10-31 18:30:00+00'::timestamp with time zone))
--                Index Searches: 1
--                Buffers: shared hit=5 read=1
--  Planning:
--    Buffers: shared hit=36 read=1
--  Planning Time: 0.569 ms
--  Execution Time: 0.358 ms
-- (14 rows)

\echo '=== 2. Every meter, one day: 15 October, India time (lesson A4)'
EXPLAIN (ANALYZE, BUFFERS)
SELECT count(*), sum(kwh) FROM meter_reading
WHERE read_at >= '2026-10-14 18:30+00' AND read_at < '2026-10-15 18:30+00';
-- === 2. Every meter, one day: 15 October, India time (lesson A4)
--                                                                            QUERY PLAN                                                                           
-- ----------------------------------------------------------------------------------------------------------------------------------------------------------------
--  Aggregate  (cost=11312.26..11312.27 rows=1 width=40) (actual time=6.996..6.997 rows=1.00 loops=1)
--    Buffers: shared hit=7245
--    ->  Bitmap Heap Scan on meter_reading  (cost=6237.06..11300.04 rows=2442 width=5) (actual time=6.789..6.877 rows=2011.00 loops=1)
--          Recheck Cond: ((read_at >= '2026-10-14 18:30:00+00'::timestamp with time zone) AND (read_at < '2026-10-15 18:30:00+00'::timestamp with time zone))
--          Heap Blocks: exact=21
--          Buffers: shared hit=7245
--          ->  Bitmap Index Scan on meter_reading_meter_time_uk  (cost=0.00..6236.45 rows=2442 width=0) (actual time=6.781..6.781 rows=2011.00 loops=1)
--                Index Cond: ((read_at >= '2026-10-14 18:30:00+00'::timestamp with time zone) AND (read_at < '2026-10-15 18:30:00+00'::timestamp with time zone))
--                Index Searches: 2001
--                Buffers: shared hit=7224
--  Planning Time: 0.050 ms
--  Execution Time: 7.023 ms
-- (12 rows)
-- === 2. Every meter, one day: 15 October, India time (lesson A4)
--                                                                          QUERY PLAN                                                                          
-- -------------------------------------------------------------------------------------------------------------------------------------------------------------
--  Aggregate  (cost=3149.85..3149.86 rows=1 width=40) (actual time=0.339..0.339 rows=1.00 loops=1)
--    Buffers: shared hit=32
--    ->  Index Scan using meter_reading_read_at_id_ix on meter_reading  (cost=0.42..3140.18 rows=1935 width=5) (actual time=0.014..0.205 rows=2011.00 loops=1)
--          Index Cond: ((read_at >= '2026-10-14 18:30:00+00'::timestamp with time zone) AND (read_at < '2026-10-15 18:30:00+00'::timestamp with time zone))
--          Index Searches: 1
--          Buffers: shared hit=32
--  Planning Time: 0.043 ms
--  Execution Time: 0.351 ms
-- (8 rows)

\echo '=== 3. The same day, asked with a function on the column (lesson A4)'
EXPLAIN (ANALYZE, BUFFERS)
SELECT count(*), sum(kwh) FROM meter_reading
WHERE (read_at AT TIME ZONE 'Asia/Kolkata')::date = DATE '2026-10-15';
-- === 3. The same day, asked with a function on the column (lesson A4)
--                                                                    QUERY PLAN                                                                   
-- ------------------------------------------------------------------------------------------------------------------------------------------------
--  Finalize Aggregate  (cost=15664.89..15664.90 rows=1 width=40) (actual time=72.256..74.887 rows=1.00 loops=1)
--    Buffers: shared hit=7358
--    ->  Gather  (cost=15664.67..15664.88 rows=2 width=40) (actual time=72.182..74.879 rows=3.00 loops=1)
--          Workers Planned: 2
--          Workers Launched: 2
--          Buffers: shared hit=7358
--          ->  Partial Aggregate  (cost=14664.67..14664.68 rows=1 width=40) (actual time=63.478..63.479 rows=1.00 loops=3)
--                Buffers: shared hit=7358
--                ->  Parallel Seq Scan on meter_reading  (cost=0.00..14654.24 rows=2085 width=5) (actual time=38.001..63.423 rows=670.33 loops=3)
--                      Filter: (((read_at AT TIME ZONE 'Asia/Kolkata'::text))::date = '2026-10-15'::date)
--                      Rows Removed by Filter: 332872
--                      Buffers: shared hit=7358
--  Planning:
--    Buffers: shared hit=6
--  Planning Time: 0.038 ms
--  Execution Time: 74.931 ms
-- (16 rows)
-- === 3. The same day, asked with a function on the column (lesson A4)
--                                                                    QUERY PLAN                                                                   
-- ------------------------------------------------------------------------------------------------------------------------------------------------
--  Finalize Aggregate  (cost=16693.89..16693.90 rows=1 width=40) (actual time=68.290..70.206 rows=1.00 loops=1)
--    Buffers: shared hit=8387
--    ->  Gather  (cost=16693.67..16693.88 rows=2 width=40) (actual time=68.210..70.198 rows=3.00 loops=1)
--          Workers Planned: 2
--          Workers Launched: 2
--          Buffers: shared hit=8387
--          ->  Partial Aggregate  (cost=15693.67..15693.68 rows=1 width=40) (actual time=66.728..66.729 rows=1.00 loops=3)
--                Buffers: shared hit=8387
--                ->  Parallel Seq Scan on meter_reading  (cost=0.00..15683.24 rows=2085 width=5) (actual time=42.460..66.671 rows=670.33 loops=3)
--                      Filter: (((read_at AT TIME ZONE 'Asia/Kolkata'::text))::date = '2026-10-15'::date)
--                      Rows Removed by Filter: 332872
--                      Buffers: shared hit=8387
--  Planning:
--    Buffers: shared hit=6
--  Planning Time: 0.045 ms
--  Execution Time: 70.227 ms
-- (16 rows)

\echo '=== 4. Most of the table: everything since 1 January 2026 (lesson A4)'
EXPLAIN (ANALYZE, BUFFERS)
SELECT count(*), sum(kwh) FROM meter_reading WHERE read_at >= '2026-01-01';
-- === 4. Most of the table: everything since 1 January 2026 (lesson A4)
--                                                                      QUERY PLAN                                                                     
-- ----------------------------------------------------------------------------------------------------------------------------------------------------
--  Finalize Aggregate  (cost=14829.85..14829.86 rows=1 width=40) (actual time=33.975..36.601 rows=1.00 loops=1)
--    Buffers: shared hit=7358
--    ->  Gather  (cost=14829.63..14829.84 rows=2 width=40) (actual time=33.725..36.591 rows=3.00 loops=1)
--          Workers Planned: 2
--          Workers Launched: 2
--          Buffers: shared hit=7358
--          ->  Partial Aggregate  (cost=13829.63..13829.64 rows=1 width=40) (actual time=26.120..26.121 rows=1.00 loops=3)
--                Buffers: shared hit=7358
--                ->  Parallel Seq Scan on meter_reading  (cost=0.00..12569.60 rows=252005 width=5) (actual time=0.008..13.383 rows=202209.00 loops=3)
--                      Filter: (read_at >= '2026-01-01 00:00:00+00'::timestamp with time zone)
--                      Rows Removed by Filter: 131333
--                      Buffers: shared hit=7358
--  Planning Time: 0.056 ms
--  Execution Time: 36.627 ms
-- (14 rows)
-- === 4. Most of the table: everything since 1 January 2026 (lesson A4)
--                                                                      QUERY PLAN                                                                     
-- ----------------------------------------------------------------------------------------------------------------------------------------------------
--  Finalize Aggregate  (cost=15863.51..15863.52 rows=1 width=40) (actual time=27.406..29.201 rows=1.00 loops=1)
--    Buffers: shared hit=8387
--    ->  Gather  (cost=15863.29..15863.50 rows=2 width=40) (actual time=27.264..29.193 rows=3.00 loops=1)
--          Workers Planned: 2
--          Workers Launched: 2
--          Buffers: shared hit=8387
--          ->  Partial Aggregate  (cost=14863.29..14863.30 rows=1 width=40) (actual time=25.602..25.602 rows=1.00 loops=3)
--                Buffers: shared hit=8387
--                ->  Parallel Seq Scan on meter_reading  (cost=0.00..13598.60 rows=252937 width=5) (actual time=1.706..13.384 rows=202209.00 loops=3)
--                      Filter: (read_at >= '2026-01-01 00:00:00+00'::timestamp with time zone)
--                      Rows Removed by Filter: 131333
--                      Buffers: shared hit=8387
--  Planning Time: 0.065 ms
--  Execution Time: 29.223 ms
-- (14 rows)

\echo '=== 5. A deep page with OFFSET: 50 rows, 90% of the way through (lesson A7)'
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, meter_id, read_at, kwh FROM meter_reading
ORDER BY read_at, id LIMIT 50 OFFSET :deep;
-- === 5. A deep page with OFFSET: 50 rows, 90% of the way through (lesson A7)
--                                                                QUERY PLAN                                                               
-- ----------------------------------------------------------------------------------------------------------------------------------------
--  Limit  (cost=143284.03..143284.16 rows=50 width=29) (actual time=201.472..201.477 rows=50.00 loops=1)
--    Buffers: shared hit=7358, temp read=9446 written=9795
--    ->  Sort  (cost=141032.62..143534.19 rows=1000627 width=29) (actual time=139.619..181.421 rows=900614.00 loops=1)
--          Sort Key: read_at, id
--          Sort Method: external merge  Disk: 39096kB
--          Buffers: shared hit=7358, temp read=9446 written=9795
--          ->  Seq Scan on meter_reading  (cost=0.00..17364.27 rows=1000627 width=29) (actual time=0.009..29.215 rows=1000627.00 loops=1)
--                Buffers: shared hit=7358
--  Planning Time: 0.176 ms
--  JIT:
--    Functions: 2
--    Options: Inlining false, Optimization false, Expressions true, Deforming true
--    Timing: Generation 0.057 ms (Deform 0.000 ms), Inlining 0.000 ms, Optimization 0.073 ms, Emission 0.701 ms, Total 0.831 ms
--  Execution Time: 203.595 ms
-- (14 rows)
-- === 5. A deep page with OFFSET: 50 rows, 90% of the way through (lesson A7)
--                                                                              QUERY PLAN                                                                              
-- ---------------------------------------------------------------------------------------------------------------------------------------------------------------------
--  Limit  (cost=44914.36..44916.85 rows=50 width=29) (actual time=79.443..79.448 rows=50.00 loops=1)
--    Buffers: shared hit=10147
--    ->  Index Scan using meter_reading_read_at_id_ix on meter_reading  (cost=0.42..49904.81 rows=1000627 width=29) (actual time=0.010..59.286 rows=900614.00 loops=1)
--          Index Searches: 1
--          Buffers: shared hit=10147
--  Planning Time: 0.075 ms
--  Execution Time: 79.465 ms
-- (7 rows)

\echo '=== 6. The same page with keyset paging: the rows after the last one seen (lesson A7)'
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, meter_id, read_at, kwh FROM meter_reading
WHERE (read_at, id) > (:'last_read_at', :last_id)
ORDER BY read_at, id LIMIT 50;
-- === 6. The same page with keyset paging: the rows after the last one seen (lesson A7)
--                                                                     QUERY PLAN                                                                    
-- --------------------------------------------------------------------------------------------------------------------------------------------------
--  Limit  (cost=16027.95..16033.77 rows=50 width=29) (actual time=18.363..20.704 rows=50.00 loops=1)
--    Buffers: shared hit=7390
--    ->  Gather Merge  (cost=16027.95..27942.70 rows=102302 width=29) (actual time=18.362..20.700 rows=50.00 loops=1)
--          Workers Planned: 2
--          Workers Launched: 2
--          Buffers: shared hit=7390
--          ->  Sort  (cost=15027.92..15134.49 rows=42626 width=29) (actual time=10.998..10.999 rows=33.67 loops=3)
--                Sort Key: read_at, id
--                Sort Method: top-N heapsort  Memory: 30kB
--                Buffers: shared hit=7390
--                Worker 0:  Sort Method: top-N heapsort  Memory: 28kB
--                Worker 1:  Sort Method: top-N heapsort  Memory: 28kB
--                ->  Parallel Seq Scan on meter_reading  (cost=0.00..13611.92 rows=42626 width=29) (actual time=2.932..9.656 rows=33354.33 loops=3)
--                      Filter: (ROW(read_at, id) > ROW('2026-09-11 18:30:00+00'::timestamp with time zone, 901071))
--                      Rows Removed by Filter: 300188
--                      Buffers: shared hit=7358
--  Planning:
--    Buffers: shared hit=3
--  Planning Time: 0.375 ms
--  Execution Time: 20.742 ms
-- (20 rows)
-- === 6. The same page with keyset paging: the rows after the last one seen (lesson A7)
--                                                                           QUERY PLAN                                                                          
-- --------------------------------------------------------------------------------------------------------------------------------------------------------------
--  Limit  (cost=0.42..9.82 rows=50 width=29) (actual time=0.009..0.015 rows=50.00 loops=1)
--    Buffers: shared hit=4
--    ->  Index Scan using meter_reading_read_at_id_ix on meter_reading  (cost=0.42..18487.03 rows=98383 width=29) (actual time=0.009..0.012 rows=50.00 loops=1)
--          Index Cond: (ROW(read_at, id) > ROW('2026-09-11 18:30:00+00'::timestamp with time zone, 901071))
--          Index Searches: 1
--          Buffers: shared hit=4
--  Planning:
--    Buffers: shared hit=3
--  Planning Time: 0.082 ms
--  Execution Time: 0.031 ms
-- (10 rows)
