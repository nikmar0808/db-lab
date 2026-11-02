# MongoDB document design (Stage 5)

How the lab's data is shaped in MongoDB, and why. Written after building it in `mongo/practice/51-load-sample.js`
and measuring it in `52-shapes.js` and `55-large-and-plans.js`. The numbers are from a laptop (MongoDB 8.2 in
Docker on Windows); what matters is each comparison, on the same machine and the same data.

## The two questions the design starts from

1. **The account summary:** one customer, their premises, the meters at each premise.
2. **The bill:** the total kWh of one premise's meters for one month.

## The collections

| Collection | One document holds | Embedded or referenced, and why |
|---|---|---|
| `customers` | One customer, with its premises and each premise's meters inside | **Embedded.** The account summary reads them together; a customer has a handful of premises and they change rarely. One read, no join |
| `tariffs` | One tariff, its code as `_id` | **Referenced** from each premise by code. Shared by many premises; its prices change on their own schedule |
| `meter_readings` | One meter's readings for one India calendar month, with `reading_count` and `total_kwh` kept beside the list (the *bucket pattern*) | **Separate, referenced by `meter_id` and `premise_id`.** Readings grow without end, so they cannot live inside the customer. A bucket can never pass 31 readings, which the validator enforces |
| `bills` | One bill | Written once by the report in `54-reports.js`, read whole |

## What was tried, and what it showed

| Comparison | Result | Why it matters |
|---|---|---|
| Bucket per meter-month against one document per reading (1,000,000 readings) | 34,000 buckets against 1,000,000 documents; 6.1 MB against 16.0 MB on disk; indexes 0.9 MB against 46.6 MB | Fewer, larger documents: less to store, far smaller indexes |
| One meter's month (the bill) | Bucket: 1 index key, 1 document. Flat, with an index on `(meter_id, read_at)`: 31 keys, 31 documents | The bucket already holds the total; nothing is added up when billing |
| Every meter, one day | Bucket: needs the readings unwound out of every bucket of the month. Flat: one index search with `(read_at, _id)` | The bucket favours the bill; a "one moment, every meter" question favours the flat shape |
| Renaming a tariff code | PostgreSQL: one `UPDATE`, carried by `ON UPDATE CASCADE`. Here: 6 customer documents changed, and a new tariff document, as separate writes | The cost of copying a shared value into many documents |
| A reading for a meter that does not exist | Accepted | References are not checked; the application keeps them right |

## Rules the lab follows

- Design each collection around the questions asked of it: embed what is read together, reference what grows or is shared.
- Never let a list grow without limit inside one document; bucket it by a period instead.
- Store money and metered quantities as `Decimal128`, never as a double. The validators refuse a double.
- Give every collection a validator for the fields the application relies on.
- One document is the unit of change: a reading is added to its bucket, with the count and the total, in one update.