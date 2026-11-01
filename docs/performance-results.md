# What the measurements show (Stage 4)

The numbers are in two files written by the Java program's `measure` profile, on this laptop (PostgreSQL 18 in
Docker on Windows), with about a million readings:

- [`results/before-index.md`](results/before-index.md): before migration V3 added the index `(read_at, id)`;
- [`results/after-index.md`](results/after-index.md): after it.

Laptop numbers mean little on their own. What matters is each pair, before and after, on the same machine and
the same data. Every time is the middle of three runs, after one run that is not counted.

## Query plans

| Question | What changed with the index, and why |
|---|---|
| 1. One meter, one month (the bill) | Nothing: the unique rule's index `(meter_id, read_at)` already serves it, and always did |
| 2. Every meter, one day | PostgreSQL 18 already used `(meter_id, read_at)` by *skip scan*, one search per meter. The index that starts with `read_at` needs one search, and is several times faster again |
| 3. The same day, with a function on the column | Nothing: no index holds "the India date of `read_at`". The cure is to ask for a range of `read_at` (question 2), or an expression index |
| 4. Most of the table | Nothing, rightly: reading every page once beats fetching most of the rows one by one |
| 5. A deep page with OFFSET | Faster (no sort), but it still reads every row before the page and throws them away |
| 6. The same page with keyset paging | From a full scan to 50 rows read: page 18,000 costs the same as page 1 |

## The account summary

The natural JPA code sends one query per customer, per premise and per meter: 501 statements for 100 customers.
One query per level sends 3, and one hand-written query sends 1, for exactly the same result. The time falls
with the statement count, and in production each statement also crosses a network.

## Writing many rows

From slowest to fastest: a commit per row, then a statement per row in one transaction, then JPA (which, for
tables numbered by an identity column, sends one statement per row whatever `hibernate.jdbc.batch_size` says),
then JDBC batches, batches rewritten into multi-row inserts, and finally one set-based statement or `COPY`.

## Not measured here, but shown by hand in Stage 4

- What one more index costs on every insert (`sql/practice/21-index-write-cost.sql`).
- The lost update between two sessions, and its cures: arithmetic in SQL, `SELECT ... FOR UPDATE`, a version
  column (V4, and `OptimisticLockTest`).
- Dead rows after a mass update, and `VACUUM`.