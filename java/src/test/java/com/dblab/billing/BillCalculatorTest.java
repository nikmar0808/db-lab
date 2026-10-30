package com.dblab.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.dblab.billing.domain.BillCalculator;
import com.dblab.billing.domain.BillingPeriod;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

/**
 * Unit tests: plain Java, no Spring, no database, so they run in milliseconds (Stage 2, lesson A10).
 *
 * <p>Each method marked {@code @Test} is one test. JUnit creates a new instance of the class for each,
 * calls it, and reports it as passed unless an assertion fails or an exception escapes.
 * {@code assertThat(actual).isEqualTo(expected)} comes from AssertJ, the assertion library Spring Boot includes.
 */
class BillCalculatorTest {

    @Test
    void octoberBillOfStageOnePremiseOne() {
        // Stage 1: premise 1 used 530 kWh in October on DOM-1 (6.50 per kWh, 2.00 per day): 3445.00 + 62.00.
        BigDecimal amount = BillCalculator.amount(
                new BigDecimal("530.000"), new BigDecimal("6.5000"), new BigDecimal("2.00"), 31);

        assertThat(amount).isEqualByComparingTo("3507.00");     // compares the value, ignoring trailing zeros
        assertThat(amount.scale()).isEqualTo(2);                 // always two decimal places
    }

    @Test
    void roundsHalfAwayFromZeroLikeTheDatabase() {
        // 0.001 kWh x 5.0000 = 0.005: exactly half a paisa, rounded up, as PostgreSQL's round() does.
        BigDecimal amount = BillCalculator.amount(
                new BigDecimal("0.001"), new BigDecimal("5.0000"), BigDecimal.ZERO, 0);

        assertThat(amount).isEqualByComparingTo("0.01");
    }

    @Test
    void octoberStartsAndEndsAtMidnightIndiaTime() {
        BillingPeriod october = new BillingPeriod(YearMonth.of(2026, 10));

        // Midnight on 1 October in India is 18:30 on 30 September in UTC.
        assertThat(october.from().toInstant()).isEqualTo(OffsetDateTime.parse("2026-09-30T18:30Z").toInstant());
        assertThat(october.to().toInstant()).isEqualTo(OffsetDateTime.parse("2026-10-31T18:30Z").toInstant());
        assertThat(october.days()).isEqualTo(31);
    }
}