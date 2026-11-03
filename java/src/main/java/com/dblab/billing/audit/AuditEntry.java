package com.dblab.billing.audit;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** One audit record, as the Java program writes it (Stage 7). */
public record AuditEntry(String batchId, OffsetDateTime receivedAt, String source, String outcome,
                         long premiseId, int readingCount, BigDecimal totalKwh) {
}