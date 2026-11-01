package com.dblab.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dblab.billing.TestData.Fixture;
import com.dblab.billing.jpa.Bill;
import com.dblab.billing.jpa.BillRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Two clerks correct the same bill (Stage 4, lesson A9). Without the version column, the second save would
 * silently overwrite the first: the lost update. With @Version, the second save is refused.
 */
@SpringBootTest
class OptimisticLockTest extends PostgresTestBase {

    @Autowired
    BillRepository bills;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate transaction;

    @Test
    void theSecondClerksSaveIsRefused() {
        Fixture f = TestData.premiseWithMeter(jdbc);
        long billId = jdbc.sql("""
                        INSERT INTO bill (customer_id, premise_id, period_start, period_end, amount)
                        VALUES (?, ?, DATE '2026-10-01', DATE '2026-10-31', 1674.00) RETURNING id""")
                .params(f.customerId(), f.premiseId()).query(Long.class).single();

        // Each clerk's screen reads the bill in a transaction of its own, which ends straight away, as a web
        // request would. Both copies hold version 0.
        Bill clerkA = transaction.execute(status -> bills.findById(billId).orElseThrow());
        Bill clerkB = transaction.execute(status -> bills.findById(billId).orElseThrow());

        clerkA.adjustAmount(new BigDecimal("50.00"));     // a late fee
        bills.save(clerkA);                               // UPDATE ... WHERE id = ? AND version = 0: 1 row; now 1

        clerkB.adjustAmount(new BigDecimal("-100.00"));   // a goodwill credit, worked out from the old amount
        assertThatThrownBy(() -> bills.save(clerkB))      // still holds version 0: refused
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        Bill saved = bills.findById(billId).orElseThrow();
        assertThat(saved.getAmount()).isEqualByComparingTo("1724.00");   // clerk A's change, not lost
        assertThat(saved.getVersion()).isEqualTo(1);
    }
}