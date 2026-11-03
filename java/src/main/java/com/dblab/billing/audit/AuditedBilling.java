package com.dblab.billing.audit;

import com.dblab.billing.domain.BillResult;
import com.dblab.billing.domain.BillingService;
import com.dblab.billing.domain.ReadingInput;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * The main task, audited: the decision of Stage 7 in Java. Deliberately not @Transactional: the billing service
 * commits its own transaction first, and the audit write comes after it, in another. Holding no transaction open
 * here, it never needs two connections at once.
 */
@Service
public class AuditedBilling {

    private static final Logger log = LoggerFactory.getLogger(AuditedBilling.class);

    private final BillingService billing;
    private final AuditWriter audit;

    public AuditedBilling(@Qualifier("jdbcClientBilling") BillingService billing, AuditWriter audit) {
        this.billing = billing;
        this.audit = audit;
    }

    public BillResult recordAndAudit(String batchId, String source, long premiseId, List<ReadingInput> readings,
                                     YearMonth month) {
        OffsetDateTime received = OffsetDateTime.now(ZoneOffset.UTC);
        BigDecimal total = readings.stream().map(ReadingInput::kwh).reduce(BigDecimal.ZERO, BigDecimal::add);
        BillResult result;
        try {
            result = billing.recordReadingsAndBill(premiseId, readings, month);
        } catch (RuntimeException e) {
            writeSafely(new AuditEntry(batchId, received, source, "FAILED", premiseId, readings.size(), total));
            throw e;
        }
        writeSafely(new AuditEntry(batchId, received, source, "ACCEPTED", premiseId, readings.size(), total));
        return result;
    }

    /** Never throws. In production the failure also goes to a fallback store and a metric with an alert. */
    private void writeSafely(AuditEntry entry) {
        try {
            audit.writeRequiresNew(entry);
        } catch (RuntimeException e) {
            log.warn("audit write failed for {}: {}", entry.batchId(), e.getClass().getSimpleName());
        }
    }
}