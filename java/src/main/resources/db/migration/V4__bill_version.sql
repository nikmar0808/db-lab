-- Migration 4 (Stage 4, lesson A9): a version number on bill, for optimistic locking. Every update increases
-- it by one and checks it was not changed by someone else since the row was read. JPA's @Version does both.
-- Existing bills start at 0. Adding a column with a constant default is quick in PostgreSQL, even on a large
-- table: the default is recorded once, not written into every row.
ALTER TABLE bill ADD COLUMN version integer NOT NULL DEFAULT 0;
COMMENT ON COLUMN bill.version IS 'Optimistic-locking version: increased by every update';