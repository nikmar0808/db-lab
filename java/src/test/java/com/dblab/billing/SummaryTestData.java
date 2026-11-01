package com.dblab.billing;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Customers for the account-summary tests (Stage 4, lesson A5), created as lab_app: each with two premises, one
 * meter at each premise, and three readings per meter. Names and serial numbers no other test uses.
 */
final class SummaryTestData {

    private static final AtomicInteger NEXT = new AtomicInteger(1);

    private SummaryTestData() {
    }

    /** Creates the customers and returns their ids. */
    static List<Long> customers(JdbcClient jdbc, int count) {
        int batch = NEXT.getAndIncrement();
        String tariff = "SUM-" + batch;
        jdbc.sql("INSERT INTO tariff (code, description, rate_per_kwh, standing_charge) VALUES (?, 'Test', 6.5, 2)")
                .param(tariff).update();
        List<Long> ids = new ArrayList<>();
        for (int c = 1; c <= count; c++) {
            String key = batch + "-" + c;
            long customerId = jdbc.sql("INSERT INTO customer (name, email) VALUES (?, ?) RETURNING id")
                    .params("Summary customer " + key, "summary" + key + "@example.com")
                    .query(Long.class).single();
            for (int p = 1; p <= 2; p++) {
                long premiseId = jdbc.sql("""
                                INSERT INTO premise (customer_id, address, tariff_code) VALUES (?, ?, ?) RETURNING id""")
                        .params(customerId, p + " Summary Street, " + key, tariff)
                        .query(Long.class).single();
                long meterId = jdbc.sql("""
                                INSERT INTO meter (premise_id, serial_number, installed_on)
                                VALUES (?, ?, DATE '2026-01-01') RETURNING id""")
                        .params(premiseId, String.format("MTR-%06d", 700000 + premiseId))
                        .query(Long.class).single();
                for (int day = 1; day <= 3; day++) {
                    jdbc.sql("INSERT INTO meter_reading (meter_id, read_at, kwh) VALUES (?, ?, ?)")
                            .params(meterId, OffsetDateTime.of(2026, 10, day, 18, 30, 0, 0, ZoneOffset.UTC),
                                    new BigDecimal(day + ".500"))
                            .update();
                }
            }
            ids.add(customerId);
        }
        return ids;
    }
}