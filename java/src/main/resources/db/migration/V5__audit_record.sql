-- Migration 5: the audit record, as JSON in PostgreSQL (Stage 7).
-- One row per batch of readings received. Only the moment is a real column, because it decides which rows
-- expire and which month a report covers. Everything else stays inside the document, in the shape it arrived.
CREATE TABLE audit_record (
    id           bigint      GENERATED ALWAYS AS IDENTITY,
    received_at  timestamptz NOT NULL,
    document     jsonb       NOT NULL,
    CONSTRAINT audit_record_pk PRIMARY KEY (id),
    -- The few fields every record must have: PostgreSQL's counterpart of a MongoDB validator.
    CONSTRAINT audit_record_document_ck CHECK (
        jsonb_typeof(document) = 'object'
        AND document ->> 'batch_id' IS NOT NULL
        AND document ->> 'source' IS NOT NULL
        AND document ->> 'outcome' IN ('ACCEPTED', 'PARTIAL', 'REJECTED', 'FAILED'))
);
-- GIN: an index of every key and value inside the document. jsonb_path_ops serves only the containment
-- operator @>, and is smaller and faster for it than the default operator class.
CREATE INDEX audit_record_document_ix ON audit_record USING GIN (document jsonb_path_ops);
-- An ordinary index for time ranges: reports by period, and the scheduled delete of expired rows.
CREATE INDEX audit_record_received_ix ON audit_record (received_at);
COMMENT ON TABLE audit_record IS 'One record per batch of readings received, kept 90 days';