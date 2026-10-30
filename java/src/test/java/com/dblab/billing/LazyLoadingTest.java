package com.dblab.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dblab.billing.TestData.Fixture;
import com.dblab.billing.jpa.Customer;
import com.dblab.billing.jpa.CustomerRepository;
import org.hibernate.LazyInitializationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Lazy loading works only while the transaction that loaded the object is open (Stage 2, lesson A8).
 *
 * <p>This is what spring.jpa.open-in-view hides: with it on, a web request keeps the session open until the
 * response is written, so this error never appears, and a database connection stays borrowed the whole time.
 */
@SpringBootTest
class LazyLoadingTest extends PostgresTestBase {

    @Autowired
    CustomerRepository customers;

    @Autowired
    TransactionTemplate transaction;    // runs a piece of code inside a transaction, without @Transactional

    @Autowired
    JdbcClient jdbc;

    @Test
    void premisesCannotBeLoadedAfterTheTransactionHasEnded() {
        Fixture f = TestData.premiseWithMeter(jdbc);

        // findById runs in a transaction of its own, which ends when it returns.
        Customer customer = customers.findById(f.customerId()).orElseThrow();

        assertThatThrownBy(() -> customer.getPremises().size())
                .isInstanceOf(LazyInitializationException.class);
    }

    @Test
    void premisesLoadInsideATransaction() {
        Fixture f = TestData.premiseWithMeter(jdbc);

        Integer count = transaction.execute(status ->
                customers.findById(f.customerId()).orElseThrow().getPremises().size());

        assertThat(count).isEqualTo(1);
    }
}