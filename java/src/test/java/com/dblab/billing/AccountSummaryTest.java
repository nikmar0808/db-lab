package com.dblab.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.dblab.billing.summary.AccountSummaries;
import com.dblab.billing.summary.AccountSummary;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The N+1 problem, counted (Stage 4, lesson A5). Five customers, each with two premises, each premise with one
 * meter. The test counts the statements Hibernate prepares; a test like this, written before a fix, stops the
 * problem from coming back unnoticed.
 *
 * <p>generate_statistics=true makes Hibernate keep its counters; the property is set for this test only.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class AccountSummaryTest extends PostgresTestBase {

    @Autowired
    AccountSummaries summaries;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    EntityManagerFactory emf;

    Statistics statistics;
    List<Long> ids;

    @BeforeEach
    void fiveCustomers() {
        ids = SummaryTestData.customers(jdbc, 5);
        statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();                         // count only what the test itself causes
    }

    @Test
    void theNaturalCodeSendsOneQueryPerCustomerPremiseAndMeter() {
        List<AccountSummary> result = summaries.naive(ids);

        // 1 for the customers, 5 for their premises, 10 for the meters of each premise, 10 for each meter's
        // latest reading.
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1 + 5 + 10 + 10);
        assertThat(result).hasSize(5);
        AccountSummary.MeterLine first = result.get(0).premises().get(0).meters().get(0);
        assertThat(first.latestKwh()).isEqualByComparingTo(new BigDecimal("3.500"));   // the reading of 3 October
    }

    @Test
    void onePerLevelSendsThreeQueriesForTheSameResult() {
        List<AccountSummary> expected = summaries.naive(ids);
        statistics.clear();

        List<AccountSummary> result = summaries.perLevel(ids);

        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
        assertThat(result).isEqualTo(expected);
    }

    @Test
    void oneHandWrittenQueryGivesTheSameResultWithoutHibernate() {
        List<AccountSummary> expected = summaries.naive(ids);
        statistics.clear();

        List<AccountSummary> result = summaries.oneQuery(ids);

        assertThat(statistics.getPrepareStatementCount()).isZero();    // JdbcClient does not go through Hibernate
        assertThat(result).isEqualTo(expected);
    }
}