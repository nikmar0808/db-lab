# MongoDB query plans (Stage 5)

Written by `mongo/practice/55-large-and-plans.js` on 2026-11-02 19:29, MongoDB 8.2 in Docker on this laptop. Times are the middle of three runs, in milliseconds.

Meters: 2000, days: 500, readings to write: 1000000
Written in batches of 10,000 (insertMany): 7.8 s
readings_large: 1000000

### Only the _id index
| Question | Plan (outermost first) | Keys examined | Documents examined | Returned | ms |
|---|---|---:|---:|---:|---:|
| 1. One meter, one month (the bill) | PROJECTION_SIMPLE <- COLLSCAN | 0 | 1000000 | 31 | 383 |
| 2. Every meter, one day | PROJECTION_SIMPLE <- COLLSCAN | 0 | 1000000 | 2000 | 481 |
| 3. The same day, with a function on the field | PROJECTION_SIMPLE <- COLLSCAN | 0 | 1000000 | 2000 | 1459 |
| 4. Most of the collection (since 1 January 2026) | PROJECTION_SIMPLE <- COLLSCAN | 0 | 1000000 | 606000 | 459 |
| 5. A deep page with skip | SKIP <- SORT <- COLLSCAN | 0 | 1000000 | 50 | 1492 |
| 6. The same page, keyset | SUBPLAN <- SORT <- COLLSCAN | 0 | 1000000 | 50 | 610 |

Index { meter_id: 1, read_at: 1 }, unique, built in: 1.4 s

### With { meter_id: 1, read_at: 1 }
| Question | Plan (outermost first) | Keys examined | Documents examined | Returned | ms |
|---|---|---:|---:|---:|---:|
| 1. One meter, one month (the bill) | PROJECTION_SIMPLE <- FETCH <- IXSCAN meter_id_1_read_at_1 | 31 | 31 | 31 | 0 |
| 2. Every meter, one day | PROJECTION_SIMPLE <- COLLSCAN | 0 | 1000000 | 2000 | 494 |
| 3. The same day, with a function on the field | PROJECTION_SIMPLE <- COLLSCAN | 0 | 1000000 | 2000 | 1535 |
| 4. Most of the collection (since 1 January 2026) | PROJECTION_SIMPLE <- COLLSCAN | 0 | 1000000 | 606000 | 480 |
| 5. A deep page with skip | SKIP <- SORT <- COLLSCAN | 0 | 1000000 | 50 | 1603 |
| 6. The same page, keyset | SUBPLAN <- SORT <- COLLSCAN | 0 | 1000000 | 50 | 618 |

Index { read_at: 1, _id: 1 } built in: 1.2 s

### With { read_at: 1, _id: 1 } as well
| Question | Plan (outermost first) | Keys examined | Documents examined | Returned | ms |
|---|---|---:|---:|---:|---:|
| 1. One meter, one month (the bill) | PROJECTION_SIMPLE <- FETCH <- IXSCAN meter_id_1_read_at_1 | 31 | 31 | 31 | 0 |
| 2. Every meter, one day | PROJECTION_SIMPLE <- FETCH <- IXSCAN read_at_1__id_1 | 2000 | 2000 | 2000 | 2 |
| 3. The same day, with a function on the field | PROJECTION_SIMPLE <- COLLSCAN | 0 | 1000000 | 2000 | 1575 |
| 4. Most of the collection (since 1 January 2026) | PROJECTION_SIMPLE <- FETCH <- IXSCAN read_at_1__id_1 | 606000 | 606000 | 606000 | 589 |
| 5. A deep page with skip | LIMIT <- FETCH <- SKIP <- IXSCAN read_at_1__id_1 | 900050 | 50 | 50 | 527 |
| 6. The same page, keyset | SUBPLAN <- LIMIT <- FETCH <- SORT_MERGE <- IXSCAN read_at_1__id_1 | 50 | 50 | 50 | 0 |

buckets_large built from readings_large with $sort, $group and $out: 5.0 s

| Question | Plan (outermost first) | Keys examined | Documents examined | Returned | ms |
|---|---|---:|---:|---:|---:|
| 7. One meter, one month, from its bucket | PROJECTION_SIMPLE <- FETCH <- IXSCAN meter_id_1_month_1 | 1 | 1 | 1 | 0 |

### The two shapes on disk
| Collection | Documents | Average document | Data | On disk (compressed) | Indexes |
|---|---:|---:|---:|---:|---:|
| readings_large | 1000000 | 74 bytes | 70.6 MB | 16.0 MB | 46.6 MB |
| buckets_large | 34000 | 1488 bytes | 48.3 MB | 5.6 MB | 0.9 MB |
