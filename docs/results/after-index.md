# Measurements: after-index

Taken 2026-11-01 11:44 by `Measurements.java` (Stage 4), on PostgreSQL 18.6 (Debian 18.6-1.pgdg13+2), with 1000627 readings. Times are the middle of three runs, in milliseconds.

Indexes on `meter_reading`: meter_reading_meter_time_uk, meter_reading_pk, meter_reading_read_at_id_ix

## Query plans

| Question | How `meter_reading` was read | Rows read | Time |
|---|---|---:|---:|
| 1. One meter, one month (the bill) | Bitmap Heap Scan of `meter_reading_meter_time_uk` | 31 | 0.04 |
| 2. Every meter, one day | Index Scan using `meter_reading_read_at_id_ix` | 2,011 | 0.27 |
| 3. The same day, with a function on the column | Parallel Seq Scan | 1,000,627 | 79.76 |
| 4. Most of the table (since 1 January 2026) | Parallel Seq Scan | 1,000,626 | 28.56 |
| 5. A deep page with OFFSET | Index Scan using `meter_reading_read_at_id_ix` | 900,614 | 77.00 |
| 6. The same page with keyset paging | Index Scan using `meter_reading_read_at_id_ix` | 50 | 0.02 |

## The account summary for 100 customers

| Way | Statements sent | Time |
|---|---:|---:|
| 1. Natural JPA code (lazy lists, one query per meter) | 501 | 142.0 |
| 2. One query per level | 3 | 10.1 |
| 3. One hand-written query (JdbcClient) | 1 | 3.6 |

## Writing 5,000 readings

| Way | Time | Rows per second |
|---|---:|---:|
| 1. One statement per row, each committed on its own | 3771 | 1,326 |
| 2. One statement per row, one transaction | 1249 | 4,003 |
| 3. JDBC batches of 1,000 (addBatch, executeBatch) | 96 | 51,900 |
| 4. JDBC batches, with reWriteBatchedInserts=true | 70 | 71,758 |
| 5. JPA saveAll, hibernate.jdbc.batch_size=1000 | 1375 | 3,637 |
| 6. One set-based INSERT ... SELECT | 38 | 132,310 |
| 7. COPY ... FROM STDIN | 41 | 122,241 |

