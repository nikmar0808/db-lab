package com.dblab.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dblab.billing.TestData.Fixture;
import com.dblab.billing.dbfunction.CalculateBillCall;
import com.dblab.billing.domain.BillResult;
import com.dblab.billing.domain.BillingService;
import com.dblab.billing.domain.ReadingInput;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The main task, built four ways, held to the same checks (Stage 2, lesson A6).
 *
 * <p>{@code @ParameterizedTest} runs a test once for each value in {@code @ValueSource}: here, the Spring
 * bean names of the four BillingService classes. A failure report names the value it failed with.
 */
@SpringBootTest
class MainTaskTest extends PostgresTestBase {

    static final YearMonth OCTOBER = YearMonth.of(2026, 10);

    @Autowired
    ApplicationContext context;     // Spring's container of beans, used to fetch each way by its name

    @Autowired
    JdbcClient jdbc;

    @Autowired
    CalculateBillCall calculateBill;

    @ParameterizedTest
    @ValueSource(strings = {"plainJdbcBilling", "jdbcClientBilling", "jpaBilling", "databaseFunctionBilling"})
    void recordsOctoberReadingsAndBillsThem(String way) {
        BillingService billing = context.getBean(way, BillingService.class);
        Fixture f = TestData.premiseWithMeter(jdbc);

        BillResult bill = billing.recordReadingsAndBill(
                f.premiseId(), TestData.dailyReadings(f.meterId(), OCTOBER, "8.000"), OCTOBER);

        // 31 days x 8 kWh = 248 kWh; 248 x 6.50 = 1612.00; plus 31 days x 2.00 = 62.00.
        assertThat(bill.amount()).isEqualByComparingTo("1674.00");
        assertThat(bill.periodStart()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(bill.periodEnd()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(TestData.countReadings(jdbc, f.meterId())).isEqualTo(31);

        // The Java rule and the database function agree.
        assertThat(calculateBill.viaCallableStatement(f.premiseId(), bill.periodStart(), bill.periodEnd()))
                .isEqualByComparingTo(bill.amount());

        String status = jdbc.sql("SELECT status FROM bill WHERE id = ?").param(bill.billId())
                .query(String.class).single();
        assertThat(status).isEqualTo("DRAFT");
    }

    @ParameterizedTest
    @ValueSource(strings = {"plainJdbcBilling", "jdbcClientBilling", "jpaBilling", "databaseFunctionBilling"})
    void aFailureLeavesNothingHalfDone(String way) {
        BillingService billing = context.getBean(way, BillingService.class);
        Fixture f = TestData.premiseWithMeter(jdbc);

        // Three good readings, then the first one again: the fourth breaks the one-reading-per-moment rule.
        List<ReadingInput> readings = new ArrayList<>(TestData.dailyReadings(f.meterId(), OCTOBER, "8.000").subList(0, 3));
        readings.add(readings.get(0));

        assertThatThrownBy(() -> billing.recordReadingsAndBill(f.premiseId(), readings, OCTOBER))
                .isInstanceOf(RuntimeException.class);

        // The three good readings were inserted before the failure, and rolled back with it.
        assertThat(TestData.countReadings(jdbc, f.meterId())).isZero();
        assertThat(TestData.countBills(jdbc, f.premiseId())).isZero();
    }
}