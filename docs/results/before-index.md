# Measurements: before-index

Taken 2026-11-01 11:27 by `Measurements.java` (Stage 4), on PostgreSQL 18.6 (Debian 18.6-1.pgdg13+2), with 1000627 readings. Times are the middle of three runs, in milliseconds.

Indexes on `meter_reading`: meter_reading_meter_time_uk, meter_reading_pk

## Query plans

| Question | How `meter_reading` was read | Rows read | Time |
|---|---|---:|---:|
| 1. One meter, one month (the bill) | Bitmap Heap Scan of `meter_reading_meter_time_uk` | 31 | 0.04 |
| 2. Every meter, one day | Bitmap Heap Scan of `meter_reading_meter_time_uk` | 2,011 | 1.98 |
| 3. The same day, with a function on the column | Parallel Seq Scan | 1,000,627 | 78.22 |
| 4. Most of the table (since 1 January 2026) | Parallel Seq Scan | 1,000,626 | 35.81 |
| 5. A deep page with OFFSET | Seq Scan | 1,000,627 | 210.81 |
| 6. The same page with keyset paging | Parallel Seq Scan | 1,000,627 | 21.32 |

## The account summary for 100 customers

| Way | Statements sent | Time |
|---|---:|---:|
| 1. Natural JPA code (lazy lists, one query per meter) | 501 | 159.1 |
| 2. One query per level | 3 | 10.3 |
| 3. One hand-written query (JdbcClient) | 1 | 3.2 |

## Writing 5,000 readings

| Way | Time | Rows per second |
|---|---:|---:|
| 1. One statement per row, each committed on its own | 3893 | 1,284 |
| 2. One statement per row, one transaction | 1271 | 3,933 |
| 3. JDBC batches of 1,000 (addBatch, executeBatch) | 89 | 56,073 |
| 4. JDBC batches, with reWriteBatchedInserts=true | 64 | 78,099 |
| 5. JPA saveAll, hibernate.jdbc.batch_size=1000 | 2058 | 2,429 |
| 6. One set-based INSERT ... SELECT | 33 | 150,098 |
| 7. COPY ... FROM STDIN | 37 | 133,415 |

