package com.dblab.billing;

import static com.dblab.billing.MigrationsTest.sqlState;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dblab.billing.TestData.Fixture;
import com.dblab.billing.dbfunction.CalculateBillCall;
import com.dblab.billing.domain.ReadingInput;
import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/** calculate_bill, called the 2013 way and the Spring way, gives the same answer (Stage 2, lesson A6). */
@SpringBootTest
class CalculateBillCallTest extends PostgresTestBase {

    static final LocalDate START = LocalDate.of(2026, 10, 1);
    static final LocalDate END = LocalDate.of(2026, 10, 31);

    @Autowired
    CalculateBillCall calculateBill;

    @Autowired
    JdbcClient jdbc;

    @Test
    void bothWaysGiveTheSameAmount() {
        Fixture f = TestData.premiseWithMeter(jdbc);
        for (ReadingInput r : TestData.dailyReadings(f.meterId(), YearMonth.of(2026, 10), "8.000")) {
            jdbc.sql("INSERT INTO meter_reading (meter_id, read_at, kwh) VALUES (?, ?, ?)")
                    .params(r.meterId(), r.readAt(), r.kwh()).update();
        }

        assertThat(calculateBill.viaCallableStatement(f.premiseId(), START, END)).isEqualByComparingTo("1674.00");
        assertThat(calculateBill.viaSimpleJdbcCall(f.premiseId(), START, END)).isEqualByComparingTo("1674.00");
    }

    @Test
    void theFunctionsOwnErrorReachesJava() {
        assertThatThrownBy(() -> calculateBill.viaSimpleJdbcCall(-1L, START, END))
                .hasMessageContaining("premise -1 does not exist")
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("P0002"));      // no_data_found, from RAISE ... USING ERRCODE
    }
}