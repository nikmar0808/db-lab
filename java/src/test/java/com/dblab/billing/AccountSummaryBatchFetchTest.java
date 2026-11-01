package com.dblab.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.dblab.billing.summary.AccountSummaries;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The same natural code with one setting changed: batch fetching (Stage 4, lesson A5). When the first lazy list
 * is loaded, Hibernate also loads the lists of up to 50 other objects it holds, in the same query. No code
 * changes; the repository call per meter is untouched, because batch fetching applies only to lazy loading.
 *
 * <p>Different properties mean Spring starts a second program context for this class, on the same database.
 */
@SpringBootTest(properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.jpa.properties.hibernate.default_batch_fetch_size=50"})
class AccountSummaryBatchFetchTest extends PostgresTestBase {

    @Autowired
    AccountSummaries summaries;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    EntityManagerFactory emf;

    @Test
    void lazyListsAreLoadedFiftyOwnersAtATime() {
        List<Long> ids = SummaryTestData.customers(jdbc, 5);
        Statistics statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        summaries.naive(ids);

        // 1 for the customers, 1 for all their premises, 1 for all the premises' meters, and still 10 for the
        // latest readings: 13 instead of 26.
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1 + 1 + 1 + 10);
    }
}