package com.dblab.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dblab.billing.TestData.Fixture;
import com.dblab.billing.audit.AuditEntry;
import com.dblab.billing.audit.AuditTraps;
import com.dblab.billing.audit.AuditedBilling;
import com.dblab.billing.domain.ReadingInput;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/** An audit write that fails must not fail the business write: what each way really does (Stage 7). */
@SpringBootTest
class AuditTest extends PostgresTestBase {

    @Autowired
    AuditTraps traps;

    @Autowired
    AuditedBilling audited;

    @Autowired
    JdbcClient jdbc;

    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-10-01T00:00+05:30");

    private ReadingInput readingFor(Fixture f) {
        return new ReadingInput(f.meterId(), AT, new BigDecimal("7.5"));
    }

    /** A null source breaks the table's check rule, so this entry can never be saved. */
    private AuditEntry badEntry() {
        return new AuditEntry("BAD", AT, null, "ACCEPTED", 1, 1, BigDecimal.ONE);
    }

    private int auditRecords(String batchId) {
        return jdbc.sql("SELECT count(*) FROM audit_record WHERE document @> CAST(? AS jsonb)")
                .param("{\"batch_id\": \"" + batchId + "\"}").query(Integer.class).single();
    }

    @Test
    void catchingTheAuditErrorInTheSameTransactionLosesTheReadingWithoutAnyError() {
        Fixture f = TestData.premiseWithMeter(jdbc);
        traps.sameTransactionCaught(readingFor(f), badEntry());            // returns normally: no exception at all
        assertThat(TestData.countReadings(jdbc, f.meterId())).isZero();    // and the reading is gone
    }

    @Test
    void catchingTheErrorOfARequiredMethodEndsInUnexpectedRollback() {
        Fixture f = TestData.premiseWithMeter(jdbc);
        assertThatThrownBy(() -> traps.requiredCaught(readingFor(f), badEntry()))
                .isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        assertThat(TestData.countReadings(jdbc, f.meterId())).isZero();
    }

    @Test
    void requiresNewCaughtKeepsTheBusinessWrite() {
        Fixture f = TestData.premiseWithMeter(jdbc);
        traps.requiresNewCaught(readingFor(f), badEntry());
        assertThat(TestData.countReadings(jdbc, f.meterId())).isEqualTo(1);
    }

    @Test
    void requiresNewSurvivesTheBusinessRollback() {
        Fixture f = TestData.premiseWithMeter(jdbc);
        AuditEntry entry = new AuditEntry("RN-1", AT, "HES-NORTH", "ACCEPTED", f.premiseId(), 1, BigDecimal.ONE);
        assertThatThrownBy(() -> traps.requiresNewThenBusinessFails(readingFor(f), entry))
                .isInstanceOf(IllegalStateException.class);
        assertThat(TestData.countReadings(jdbc, f.meterId())).isZero();
        assertThat(auditRecords("RN-1")).isEqualTo(1);
    }

    @Test
    void anAcceptedBatchIsAudited() {
        Fixture f = TestData.premiseWithMeter(jdbc);
        audited.recordAndAudit("OK-1", "HES-NORTH", f.premiseId(), List.of(readingFor(f)), YearMonth.of(2026, 10));
        assertThat(TestData.countBills(jdbc, f.premiseId())).isEqualTo(1);
        assertThat(auditRecords("OK-1")).isEqualTo(1);
    }

    @Test
    void aFailedAuditLeavesTheBillInPlace() {
        Fixture f = TestData.premiseWithMeter(jdbc);
        audited.recordAndAudit("NO-SOURCE", null, f.premiseId(), List.of(readingFor(f)), YearMonth.of(2026, 10));
        assertThat(TestData.countBills(jdbc, f.premiseId())).isEqualTo(1);
        assertThat(auditRecords("NO-SOURCE")).isZero();
    }

    @Test
    void aFailedBillRollsBackAndItsAuditRecordStays() {
        Fixture f = TestData.premiseWithMeter(jdbc);
        YearMonth october = YearMonth.of(2026, 10);
        audited.recordAndAudit("FIRST", "HES-NORTH", f.premiseId(), List.of(readingFor(f)), october);
        ReadingInput second = new ReadingInput(f.meterId(), AT.plusDays(1), new BigDecimal("7.5"));
        assertThatThrownBy(() -> audited.recordAndAudit("SECOND", "HES-NORTH", f.premiseId(), List.of(second), october))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(TestData.countReadings(jdbc, f.meterId())).isEqualTo(1);
        assertThat(jdbc.sql("SELECT document ->> 'outcome' FROM audit_record WHERE document @> CAST(? AS jsonb)")
                .param("{\"batch_id\": \"SECOND\"}").query(String.class).single()).isEqualTo("FAILED");
    }
}