# ADR-007: Where audit data lives, and choosing between PostgreSQL and MongoDB

- Status: Accepted
- Date: 2 November 2026

## Context

A system that receives batches of meter readings must keep a record of each batch for 90 days: where it came
from, what happened to it, and the raw message. The records are written once, read rarely, vary in shape from
source to source, and then expire. The business data, customers to bills, is in PostgreSQL.

The lab built the same record both ways and measured it on one laptop: 200,000 records, PostgreSQL 18 and
MongoDB 8.2 in Docker (`docs/results/audit-comparison.md`, `sql/practice/30-audit-partitions.sql`). These numbers
compare the two on the same machine and data. They are not a load test.

| | PostgreSQL, `jsonb` with a GIN index | MongoDB, a collection with a TTL index |
|---|---|---|
| Load 200,000 | 7.7 s (`COPY`) | 9.5 s (`insert_many`) |
| 1,000 single writes | 1.45 s | 2.16 s |
| Space | About 191 MB: table 117, GIN 64.5, other indexes 9.3 | About 52 MB: documents 32.6 compressed, indexes 19 |
| Find a batch by id | Under 1 ms | Under 1 ms |
| Search a field nobody planned for | Under 1 ms: the GIN index covers every key | 119 ms: full scan, until an index is added |
| Rare error code, last 30 days | About 1 ms; 211 rows fetched for 51 | Under 1 ms; 51 for 51, with a compound index |
| The report (a quarter of the data) | 69 ms | 105 ms |
| Removing expired records | A job must exist. `DELETE`, 0.26 s for 50,197 rows; or `DROP` of a month's partition, about 5 ms | Nothing to run: a TTL index removed them within 25 s |
| Disk after expiry | `DELETE`: nothing returned, the space is reused. `DROP`: returned at once | Nothing returned, the space is reused |

## Decision

1. **Where this audit data lives.** In a system whose business data is in PostgreSQL and which runs no MongoDB,
   audit records go in PostgreSQL: a `jsonb` column, a GIN index with `jsonb_path_ops`, and real columns for
   anything that decides expiry or period. In production the table is partitioned by month, with a scheduled job
   that creates next month's partition and drops expired ones. The lab's own table is left unpartitioned so that
   the two ways of removing rows can be compared.
2. **When MongoDB is the right home instead.** When the organisation already runs it; or when disk use and hands-off
   expiry outweigh running a second database, as with a high volume of records. A second database is not added for
   audit alone: its patching, backups, monitoring, skills and client code cost more than the disk it saves.
3. **An audit write that fails must not fail the business write.** The business transaction commits first and alone.
   The audit write follows in its own transaction, under a short time limit, and cannot raise an error. A failed
   write goes to a fallback file and a counter, for a later reload and an alert. A business failure is audited too.
4. **PostgreSQL or MongoDB in general.** PostgreSQL is the default: for related entities, rules across tables,
   transactions and money. MongoDB is chosen for data that really is a set of self-contained documents, read whole,
   whose shape varies; or where the rest of the system is built around it.

## Alternatives considered

- **The audit write inside the business transaction, with the error caught.** Loses the business write silently:
  after a failed statement PostgreSQL aborts the transaction, and the commit becomes a rollback with no error
  (proved in both languages).
- **A `REQUIRED` inner method, with the error caught.** Spring marks the transaction for rollback, and the commit
  throws `UnexpectedRollbackException`.
- **A savepoint.** Keeps the business write, but the audit record is lost whenever the business work rolls back, and
  the audit waits inside the business transaction, holding its locks.
- **`REQUIRES_NEW` inside the business method.** Works, and survives a business rollback, but holds two pooled
  connections per call, so a busy pool can wait on itself.
- **An outbox: the audit row in the business transaction, moved later by a worker.** The right choice when the record
  is legally required, because nothing is lost; but then a failed audit write does fail the business write, which
  this decision rules out.
- **A log file or a log platform.** Cheap, and fine for diagnostics. Not queryable as records, and retention is
  usually set for the whole platform.

## Consequences

- A crash between the commit and the audit write loses that one record. Accepted for this data; not acceptable for
  records a regulator requires, where the outbox applies instead.
- Someone must watch the fallback file's counter, and reload the file. Without an alert, a failed store goes unnoticed.
- JSON has no date or exact decimal type: in `jsonb`, the moment is a real column and kWh travels as text, cast by SQL.
- A GIN index serves `@>` only. A search written with `->>` reads the whole table.
- With partitions, a missing month stops every audit write, so the job that creates them needs its own alert, or a
  `DEFAULT` partition as a net.
- The application's account can still update and delete audit rows in the lab. In production it holds `INSERT` only,
  and records that must resist tampering go to write-once storage.