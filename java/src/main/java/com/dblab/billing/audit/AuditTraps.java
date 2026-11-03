package com.dblab.billing.audit;

import com.dblab.billing.domain.ReadingInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A business write and an audit write in one @Transactional method, with the audit failure caught: three ways, of
 * which only the last keeps the business write (Stage 7).
 */
@Service
public class AuditTraps {

    private static final Logger log = LoggerFactory.getLogger(AuditTraps.class);

    private final JdbcClient jdbc;
    private final AuditWriter audit;

    public AuditTraps(JdbcClient jdbc, AuditWriter audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    private void saveReading(ReadingInput r) {
        jdbc.sql("INSERT INTO meter_reading (meter_id, read_at, kwh) VALUES (?, ?, ?)")
                .params(r.meterId(), r.readAt(), r.kwh()).update();
    }

    @Transactional
    public void sameTransactionCaught(ReadingInput r, AuditEntry e) {
        saveReading(r);
        try {
            audit.writeNoAnnotation(e);
        } catch (DataAccessException ex) {
            log.warn("audit failed: {}", ex.getClass().getSimpleName());
        }
    }

    @Transactional
    public void requiredCaught(ReadingInput r, AuditEntry e) {
        saveReading(r);
        try {
            audit.writeRequired(e);
        } catch (DataAccessException ex) {
            log.warn("audit failed: {}", ex.getClass().getSimpleName());
        }
    }

    @Transactional
    public void requiresNewCaught(ReadingInput r, AuditEntry e) {
        saveReading(r);
        try {
            audit.writeRequiresNew(e);
        } catch (DataAccessException ex) {
            log.warn("audit failed: {}", ex.getClass().getSimpleName());
        }
    }

    /** The business write fails after the audit write: does the audit record survive? */
    @Transactional
    public void requiresNewThenBusinessFails(ReadingInput r, AuditEntry e) {
        saveReading(r);
        audit.writeRequiresNew(e);
        throw new IllegalStateException("the business work failed after the audit write");
    }
}