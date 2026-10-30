package com.dblab.billing.dbfunction;

import com.dblab.billing.domain.BillResult;
import com.dblab.billing.domain.BillingPeriod;
import com.dblab.billing.domain.BillingService;
import com.dblab.billing.domain.ReadingInput;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Way 4: the amount comes from the database function calculate_bill, the way 2013 code called a PL/SQL
 * package procedure (Stage 2, lesson A6). The readings and the bill are written with JdbcClient, as in Way 2.
 */
@Service("databaseFunctionBilling")
public class DatabaseFunctionBilling implements BillingService {

    private final JdbcClient jdbc;
    private final CalculateBillCall calculateBill;

    public DatabaseFunctionBilling(JdbcClient jdbc, CalculateBillCall calculateBill) {
        this.jdbc = jdbc;
        this.calculateBill = calculateBill;
    }

    @Override
    @Transactional
    public BillResult recordReadingsAndBill(long premiseId, List<ReadingInput> readings, YearMonth month) {
        BillingPeriod period = new BillingPeriod(month);

        for (ReadingInput r : readings) {
            jdbc.sql("INSERT INTO meter_reading (meter_id, read_at, kwh) VALUES (:meterId, :readAt, :kwh)")
                    .param("meterId", r.meterId())
                    .param("readAt", r.readAt())
                    .param("kwh", r.kwh())
                    .update();
        }

        // Runs in this method's transaction, so the function sees the readings inserted just above.
        // It also raises an error if the premise does not exist.
        BigDecimal amount = calculateBill.viaSimpleJdbcCall(premiseId, period.firstDay(), period.lastDay());

        Long billId = jdbc.sql("""
                        INSERT INTO bill (customer_id, premise_id, period_start, period_end, amount)
                        SELECT p.customer_id, p.id, :periodStart, :periodEnd, :amount
                        FROM premise p WHERE p.id = :premiseId
                        RETURNING id""")
                .param("periodStart", period.firstDay())
                .param("periodEnd", period.lastDay())
                .param("amount", amount)
                .param("premiseId", premiseId)
                .query(Long.class)
                .single();

        return new BillResult(billId, premiseId, period.firstDay(), period.lastDay(), amount);
    }
}