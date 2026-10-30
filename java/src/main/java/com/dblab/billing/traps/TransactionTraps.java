package com.dblab.billing.traps;

import com.dblab.billing.domain.ReadingInput;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Two well-known @Transactional traps, written on purpose so the tests can show them (Stage 2, lesson A8).
 * Every method saves one reading, then fails.
 */
@Service
public class TransactionTraps {

    private final JdbcClient jdbc;

    public TransactionTraps(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Trap 1: a checked exception does not roll back. The reading is committed. */
    @Transactional
    public void saveThenThrowChecked(ReadingInput reading) throws Exception {
        save(reading);
        throw new Exception("checked: the transaction still commits");
    }

    /** Trap 1, fixed: rollbackFor makes every exception roll back. The reading is not saved. */
    @Transactional(rollbackFor = Exception.class)
    public void saveThenThrowCheckedWithRollbackFor(ReadingInput reading) throws Exception {
        save(reading);
        throw new Exception("checked, with rollbackFor: rolled back");
    }

    /** The default case: an unchecked exception rolls back. The reading is not saved. */
    @Transactional
    public void saveThenThrowUnchecked(ReadingInput reading) {
        save(reading);
        throw new IllegalStateException("unchecked: rolled back");
    }

    /**
     * Trap 2: this method has no @Transactional and calls a @Transactional method of the same object.
     * The call goes straight to the method, not through Spring's proxy, so no transaction starts:
     * the INSERT commits on its own, and the exception rolls nothing back.
     */
    public void selfCallThenThrowUnchecked(ReadingInput reading) {
        this.saveThenThrowUnchecked(reading);
    }

    private void save(ReadingInput r) {
        jdbc.sql("INSERT INTO meter_reading (meter_id, read_at, kwh) VALUES (:meterId, :readAt, :kwh)")
                .param("meterId", r.meterId())
                .param("readAt", r.readAt())
                .param("kwh", r.kwh())
                .update();
    }
}