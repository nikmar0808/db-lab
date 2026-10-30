package com.dblab.billing;

import com.dblab.billing.domain.ReadingInput;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.simple.JdbcClient;

import static com.dblab.billing.domain.BillingPeriod.BILLING_ZONE;

/**
 * Creates the rows a test needs, as lab_app, and returns their ids (Stage 2, lesson A10).
 *
 * <p>Every call creates a new tariff, customer, premise and meter with names no other test uses, so tests
 * never depend on each other's rows or on the order they run in. The tariff is priced like DOM-1 in Stage 1.
 */
public final class TestData {

    private static final AtomicInteger NEXT = new AtomicInteger(1);

    public static final BigDecimal RATE = new BigDecimal("6.5000");
    public static final BigDecimal STANDING_CHARGE = new BigDecimal("2.00");

    private TestData() {
    }

    /** The ids of one customer with one premise and one meter. */
    public record Fixture(long customerId, long premiseId, long meterId) {
    }

    public static Fixture premiseWithMeter(JdbcClient jdbc) {
        int n = NEXT.getAndIncrement();
        String tariff = "TST-" + n;
        jdbc.sql("INSERT INTO tariff (code, description, rate_per_kwh, standing_charge) VALUES (?, 'Test', ?, ?)")
                .params(tariff, RATE, STANDING_CHARGE)      // positional parameters, in order
                .update();
        long customerId = jdbc.sql("INSERT INTO customer (name, email) VALUES (?, ?) RETURNING id")
                .params("Test customer " + n, "test" + n + "@example.com")
                .query(Long.class).single();
        long premiseId = jdbc.sql("INSERT INTO premise (customer_id, address, tariff_code) VALUES (?, ?, ?) RETURNING id")
                .params(customerId, n + " Test Street", tariff)
                .query(Long.class).single();
        long meterId = jdbc.sql("INSERT INTO meter (premise_id, serial_number, installed_on) VALUES (?, ?, ?) RETURNING id")
                .params(premiseId, String.format("MTR-%06d", 900000 + n), LocalDate.of(2026, 1, 1))
                .query(Long.class).single();
        return new Fixture(customerId, premiseId, meterId);
    }

    /** One reading per day of the month, at midnight India time, each of the given kWh. */
    public static List<ReadingInput> dailyReadings(long meterId, YearMonth month, String kwh) {
        List<ReadingInput> readings = new ArrayList<>();
        for (int day = 1; day <= month.lengthOfMonth(); day++) {
            readings.add(new ReadingInput(meterId,
                    month.atDay(day).atStartOfDay(BILLING_ZONE).toOffsetDateTime(),
                    new BigDecimal(kwh)));
        }
        return readings;
    }

    public static int countReadings(JdbcClient jdbc, long meterId) {
        return jdbc.sql("SELECT count(*) FROM meter_reading WHERE meter_id = ?")
                .param(meterId).query(Integer.class).single();
    }

    public static int countBills(JdbcClient jdbc, long premiseId) {
        return jdbc.sql("SELECT count(*) FROM bill WHERE premise_id = ?")
                .param(premiseId).query(Integer.class).single();
    }
}