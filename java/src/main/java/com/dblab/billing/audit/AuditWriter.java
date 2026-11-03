package com.dblab.billing.audit;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes one audit record (Stage 7). The same statement under three transaction settings, so that the tests can
 * show what each does when the write fails.
 *
 * <p>jsonb_build_object takes values of any type, so PostgreSQL cannot work out a parameter's type by itself:
 * each is cast. A null source breaks the table's check rule, which is how the tests make the write fail.
 */
@Service
public class AuditWriter {

    private final JdbcClient jdbc;

    public AuditWriter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Runs in whatever transaction the caller has; with none, it commits on its own. */
    public void writeNoAnnotation(AuditEntry e) {
        insert(e);
    }

    /** Joins the caller's transaction. On failure, marks that whole transaction to be rolled back. */
    @Transactional(propagation = Propagation.REQUIRED)
    public void writeRequired(AuditEntry e) {
        insert(e);
    }

    /** Always its own transaction, on a second connection: the counterpart of EJB's RequiresNew. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeRequiresNew(AuditEntry e) {
        insert(e);
    }

    private void insert(AuditEntry e) {
        jdbc.sql("""
                        INSERT INTO audit_record (received_at, document)
                        VALUES (:at, jsonb_build_object(
                            'batch_id', CAST(:batchId AS text), 'source', CAST(:source AS text),
                            'outcome', CAST(:outcome AS text), 'premise_id', CAST(:premiseId AS bigint),
                            'reading_count', CAST(:readingCount AS int), 'total_kwh', CAST(:totalKwh AS text)))""")
                .param("at", e.receivedAt())
                .param("batchId", e.batchId())
                .param("source", e.source())
                .param("outcome", e.outcome())
                .param("premiseId", e.premiseId())
                .param("readingCount", e.readingCount())
                .param("totalKwh", e.totalKwh().toPlainString())
                .update();
    }
}