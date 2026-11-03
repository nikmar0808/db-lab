# The audit record both ways: 200,000 records

## Loading

- PostgreSQL, COPY, with the GIN index in place: 9.2 s
- MongoDB, insert_many in chunks of 5,000, majority: 11.5 s
- PostgreSQL, 1,000 single writes, each its own commit: 1.63 s
- MongoDB, 1,000 single writes, each its own commit: 2.19 s

## Space

- PostgreSQL: table 117.0 MB; GIN 62.7 MB; time index 5.0 MB; primary key 4.3 MB
- MongoDB: documents 34.5 MB on disk (100.4 MB before compression); _id_ 4.7 MB; audit_batch 5.1 MB; audit_error_time 7.3 MB; audit_received 8.9 MB
- The same GIN index with the default operator class, jsonb_ops: 122.8 MB

## Search 1: one batch by its id

```text
Bitmap Heap Scan on audit_record  (cost=1772.46..1850.51 rows=20 width=544) (actual time=2.693..2.694 rows=1.00 loops=1)
  Recheck Cond: (document @> '{"batch_id": "B-0123456"}'::jsonb)
  Heap Blocks: exact=1
  Buffers: shared hit=1 read=416
  ->  Bitmap Index Scan on audit_record_document_ix  (cost=0.00..1772.45 rows=20 width=0) (actual time=2.263..2.264 rows=1.00 loops=1)
        Index Cond: (document @> '{"batch_id": "B-0123456"}'::jsonb)
        Index Searches: 1
        Buffers: shared hit=1 read=415
Planning:
  Buffers: shared hit=5 read=15 dirtied=2
Planning Time: 1.955 ms
Execution Time: 2.729 ms
```

MongoDB: FETCH < IXSCAN; keys 1, docs 1, 1 ms

## Search 2: the same batch written with ->>, which the GIN index cannot serve

```text
Gather  (cost=1000.00..17327.75 rows=1005 width=544) (actual time=17.867..25.573 rows=1.00 loops=1)
  Workers Planned: 2
  Workers Launched: 2
  Buffers: shared hit=44 read=14927
  ->  Parallel Seq Scan on audit_record  (cost=0.00..16227.25 rows=419 width=544) (actual time=15.063..17.089 rows=0.33 loops=3)
        Filter: ((document ->> 'batch_id'::text) = 'B-0123456'::text)
        Rows Removed by Filter: 67000
        Buffers: shared hit=44 read=14927
Planning:
  Buffers: shared hit=3 read=5
Planning Time: 0.782 ms
Execution Time: 25.599 ms
```

## Search 3: a field inside the raw message that no one planned for

```text
Aggregate  (cost=1850.56..1850.57 rows=1 width=8) (actual time=1.818..1.818 rows=1.00 loops=1)
  Buffers: shared hit=414 read=11
  ->  Bitmap Heap Scan on audit_record  (cost=1772.46..1850.51 rows=20 width=0) (actual time=1.607..1.813 rows=9.00 loops=1)
        Recheck Cond: (document @> '{"raw": {"meter_serial": "MTR-000123"}}'::jsonb)
        Heap Blocks: exact=9
        Buffers: shared hit=414 read=11
        ->  Bitmap Index Scan on audit_record_document_ix  (cost=0.00..1772.45 rows=20 width=0) (actual time=1.460..1.460 rows=9.00 loops=1)
              Index Cond: (document @> '{"raw": {"meter_serial": "MTR-000123"}}'::jsonb)
              Index Searches: 1
              Buffers: shared hit=414 read=2
Planning:
  Buffers: shared hit=4 read=3
Planning Time: 0.638 ms
Execution Time: 1.859 ms
```

MongoDB, no index on this field: COLLSCAN; keys 0, docs 201000, 131 ms

## Search 4: a rare error code in the last 30 days

```text
Aggregate  (cost=4575.06..4575.07 rows=1 width=8) (actual time=6.998..6.999 rows=1.00 loops=1)
  Buffers: shared hit=417 read=213
  ->  Bitmap Heap Scan on audit_record  (cost=2817.95..4573.78 rows=515 width=0) (actual time=6.671..6.990 rows=51.00 loops=1)
        Recheck Cond: ((received_at >= '2026-10-04 07:39:33+00'::timestamp with time zone) AND (document @> '{"errors": [{"code": "E900"}]}'::jsonb))
        Heap Blocks: exact=51
        Buffers: shared hit=417 read=213
        ->  BitmapAnd  (cost=2817.95..2817.95 rows=515 width=0) (actual time=6.450..6.451 rows=0.00 loops=1)
              Buffers: shared hit=417 read=162
              ->  Bitmap Index Scan on audit_record_received_ix  (cost=0.00..1034.94 rows=51003 width=0) (actual time=4.792..4.793 rows=50577.00 loops=1)
                    Index Cond: (received_at >= '2026-10-04 07:39:33+00'::timestamp with time zone)
                    Index Searches: 1
                    Buffers: shared hit=2 read=161
              ->  Bitmap Index Scan on audit_record_document_ix  (cost=0.00..1782.50 rows=2030 width=0) (actual time=1.158..1.158 rows=211.00 loops=1)
                    Index Cond: (document @> '{"errors": [{"code": "E900"}]}'::jsonb)
                    Index Searches: 1
                    Buffers: shared hit=415 read=1
Planning:
  Buffers: shared hit=6 read=1
Planning Time: 0.403 ms
Execution Time: 7.033 ms
```

MongoDB: FETCH < IXSCAN; keys 51, docs 51, 1 ms

## The report: batches, readings and kWh by source and outcome, last 30 days

```text
GroupAggregate  (cost=24600.97..32836.24 rows=51003 width=112) (actual time=33.286..70.630 rows=12.00 loops=1)
  Group Key: ((document ->> 'source'::text)), ((document ->> 'outcome'::text))
  Buffers: shared hit=462 read=14585, temp read=3553 written=3560
  ->  Gather Merge  (cost=24600.97..30541.11 rows=51003 width=608) (actual time=31.908..55.366 rows=50577.00 loops=1)
        Workers Planned: 2
        Workers Launched: 2
        Buffers: shared hit=462 read=14585, temp read=3553 written=3560
        ->  Sort  (cost=23600.95..23654.07 rows=21251 width=608) (actual time=29.703..33.611 rows=16859.00 loops=3)
              Sort Key: ((document ->> 'source'::text)), ((document ->> 'outcome'::text))
              Sort Method: external merge  Disk: 11648kB
              Buffers: shared hit=462 read=14585, temp read=3553 written=3560
              Worker 0:  Sort Method: external merge  Disk: 8280kB
              Worker 1:  Sort Method: external merge  Disk: 8496kB
              ->  Parallel Seq Scan on audit_record  (cost=0.00..16333.50 rows=21251 width=608) (actual time=0.135..15.067 rows=16859.00 loops=3)
                    Filter: ((received_at >= '2026-10-04 07:39:33+00'::timestamp with time zone) AND (received_at < '2026-11-03 07:39:34+00'::timestamp with time zone))
                    Rows Removed by Filter: 50141
                    Buffers: shared hit=387 read=14584
Planning:
  Buffers: shared hit=49 read=12
Planning Time: 1.604 ms
Execution Time: 71.511 ms
```

MongoDB: GROUP < FETCH < IXSCAN; keys 50577, docs 50577, 138 ms

PostgreSQL 72 ms, MongoDB 91 ms, from the program. The two reports are equal: True

- FIELD-APP ACCEPTED: 2248 batches, 10160 readings, 156792.127 kWh
- FIELD-APP PARTIAL: 183 batches, 740 readings, 11478.141 kWh
- FIELD-APP REJECTED: 70 batches, 320 readings, 4926.092 kWh
- HES-NORTH ACCEPTED: 22937 batches, 103483 readings, 1604620.101 kWh
- HES-NORTH PARTIAL: 1748 batches, 7875 readings, 123939.060 kWh
- HES-NORTH REJECTED: 714 batches, 3146 readings, 49071.704 kWh
- HES-SOUTH ACCEPTED: 13766 batches, 61719 readings, 955456.275 kWh
- HES-SOUTH PARTIAL: 1043 batches, 4572 readings, 70214.783 kWh
- HES-SOUTH REJECTED: 436 batches, 2011 readings, 32191.199 kWh
- MDM-BATCH ACCEPTED: 6708 batches, 30404 readings, 470591.851 kWh
- MDM-BATCH PARTIAL: 502 batches, 2285 readings, 34883.025 kWh
- MDM-BATCH REJECTED: 222 batches, 997 readings, 16104.512 kWh

## Expiry

Older than 90 days in PostgreSQL: 50,197. In MongoDB: 50,197

- PostgreSQL before the delete: table 117.0 MB; GIN 62.7 MB; time index 5.0 MB; primary key 4.3 MB
- PostgreSQL DELETE of 50,197 rows: 0.31 s
- PostgreSQL after the delete: table 117.0 MB; GIN 62.7 MB; time index 5.0 MB; primary key 4.3 MB
- PostgreSQL VACUUM: 0.90 s
- PostgreSQL after VACUUM: table 117.0 MB; GIN 64.9 MB; time index 5.0 MB; primary key 4.3 MB
- MongoDB before the TTL index: documents 34.5 MB on disk (100.4 MB before compression); _id_ 4.7 MB; audit_batch 5.1 MB; audit_error_time 7.3 MB; audit_received 8.9 MB
- MongoDB builds the TTL index audit_received_ttl: 0.46 s
- MongoDB: every expired document gone 25 s after the index was built, with nothing else running
- MongoDB after expiry: documents 60.6 MB on disk (75.4 MB before compression); _id_ 5.5 MB; audit_batch 5.9 MB; audit_error_time 10.1 MB; audit_received_ttl 3.6 MB
