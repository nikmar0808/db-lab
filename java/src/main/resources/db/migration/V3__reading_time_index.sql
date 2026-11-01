-- Migration 3 (Stage 4, lessons A4 and A7): an index on (read_at, id) for questions about one moment across
-- every meter, and for reading all readings page by page in time order (keyset paging needs the unique id
-- after read_at, so that every position in the order is exact).
--
-- CONCURRENTLY builds the index without blocking inserts and updates, as a live table needs. It cannot run
-- inside a transaction; Flyway recognises the statement and runs this migration outside one, which is why
-- this file holds this one statement only. If the build fails part-way, PostgreSQL leaves an INVALID index
-- behind, to be dropped before the migration is run again.
CREATE INDEX CONCURRENTLY meter_reading_read_at_id_ix ON meter_reading (read_at, id);