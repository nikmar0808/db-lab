package com.dblab.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dblab.billing.TestData.Fixture;
import com.dblab.billing.domain.ReadingInput;
import com.dblab.billing.traps.TransactionTraps;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The @Transactional traps, shown by what is left in the table afterwards (Stage 2, lesson A8).
 */
@SpringBootTest
class TransactionTrapsTest extends PostgresTestBase {

    @Autowired
    TransactionTraps traps;

    @Autowired
    JdbcClient jdbc;

    private ReadingInput readingFor(Fixture f) {
        return new ReadingInput(f.meterId(), OffsetDateTime.parse("2026-10-01T00:00+05:30"), new BigDecimal("7.5"));
    }

    @Test
    void uncheckedExceptionRollsBack() {
        Fixture f = TestData.premiseWithMeter(jdbc);
        assertThatThrownBy(() -> traps.saveThenThrowUnchecked(readingFor(f))).isInstanceOf(IllegalStateException.class);
        assertThat(TestData.countReadings(jdbc, f.meterId())).isZero();
    }

    @Test
    void checkedExceptionCommits() {
        Fixture f = TestData.premiseWithMeter(jdbc);
        assertThatThrownBy(() -> traps.saveThenThrowChecked(readingFor(f))).isInstanceOf(Exception.class);
        assertThat(TestData.countReadings(jdbc, f.meterId())).isEqualTo(1);     // the trap: it was saved
    }

    @Test
    void rollbackForMakesCheckedExceptionsRollBack() {
        Fixture f = TestData.premiseWithMeter(jdbc);
        assertThatThrownBy(() -> traps.saveThenThrowCheckedWithRollbackFor(readingFor(f))).isInstanceOf(Exception.class);
        assertThat(TestData.countReadings(jdbc, f.meterId())).isZero();
    }

    @Test
    void callingATransactionalMethodOfTheSameObjectStartsNoTransaction() {
        Fixture f = TestData.premiseWithMeter(jdbc);
        assertThatThrownBy(() -> traps.selfCallThenThrowUnchecked(readingFor(f))).isInstanceOf(IllegalStateException.class);
        assertThat(TestData.countReadings(jdbc, f.meterId())).isEqualTo(1);     // the trap: nothing rolled back
    }
}