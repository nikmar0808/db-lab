package com.dblab.billing.jdbcclient;

import com.dblab.billing.domain.BillCalculator;
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
 * Way 2: Spring's JdbcClient. The same SQL as Way 1, without the plumbing (Stage 2, lesson A6).
 *
 * <p>JdbcClient borrows the connection, prepares the statement, binds the parameters, maps the result and
 * returns the connection. {@code @Transactional} replaces setAutoCommit, commit and rollback: every JdbcClient
 * call inside the method joins the same transaction, on the same connection.
 */
@Service("jdbcClientBilling")
public class JdbcClientBilling implements BillingService {

    private final JdbcClient jdbc;

    public JdbcClientBilling(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional                                          // the transaction attribute "Required" of an EJB
    public BillResult recordReadingsAndBill(long premiseId, List<ReadingInput> readings, YearMonth month) {
        BillingPeriod period = new BillingPeriod(month);

        for (ReadingInput r : readings) {
            jdbc.sql("INSERT INTO meter_reading (meter_id, read_at, kwh) VALUES (:meterId, :readAt, :kwh)")
                    .param("meterId", r.meterId())          // named parameters instead of ?
                    .param("readAt", r.readAt())
                    .param("kwh", r.kwh())
                    .update();
        }

        // Each column of the row goes into the record component with the matching name:
        // customer_id -> customerId, rate_per_kwh -> ratePerKwh, standing_charge -> standingCharge.
        PremiseTariff tariff = jdbc.sql("""
                        SELECT p.customer_id, t.rate_per_kwh, t.standing_charge
                        FROM premise p JOIN tariff t ON t.code = p.tariff_code
                        WHERE p.id = :premiseId""")
                .param("premiseId", premiseId)
                .query(PremiseTariff.class)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("premise " + premiseId + " does not exist"));

        BigDecimal kwh = jdbc.sql("""
                        SELECT coalesce(sum(r.kwh), 0)
                        FROM meter m JOIN meter_reading r ON r.meter_id = m.id
                        WHERE m.premise_id = :premiseId AND r.read_at >= :from AND r.read_at < :to""")
                .param("premiseId", premiseId)
                .param("from", period.from())
                .param("to", period.to())
                .query(BigDecimal.class)
                .single();

        BigDecimal amount = BillCalculator.amount(kwh, tariff.ratePerKwh(), tariff.standingCharge(), period.days());

        Long billId = jdbc.sql("""
                        INSERT INTO bill (customer_id, premise_id, period_start, period_end, amount)
                        VALUES (:customerId, :premiseId, :periodStart, :periodEnd, :amount)
                        RETURNING id""")
                .param("customerId", tariff.customerId())
                .param("premiseId", premiseId)
                .param("periodStart", period.firstDay())
                .param("periodEnd", period.lastDay())
                .param("amount", amount)
                .query(Long.class)
                .single();

        return new BillResult(billId, premiseId, period.firstDay(), period.lastDay(), amount);
    }

    /** The three values the bill needs from the premise and its tariff. */
    record PremiseTariff(long customerId, BigDecimal ratePerKwh, BigDecimal standingCharge) {
    }
}