package com.dblab.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.Test;

/**
 * What happens when every connection in the pool is lent out (Stage 2, lesson A4).
 *
 * <p>The test builds its own small pool, two connections and a one-second timeout, so that it never starves
 * the pool the other tests share. No Spring here: HikariCP is an ordinary library.
 */
class ConnectionPoolTest extends PostgresTestBase {

    @Test
    void aThirdBorrowerWaitsThenGivesUp() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl());
        config.setUsername("lab_app");
        config.setPassword("app-test");
        config.setPoolName("tiny-pool");
        config.setMaximumPoolSize(2);           // WebLogic console: Maximum Capacity
        config.setConnectionTimeout(1000);      // WebLogic console: Connection Reserve Timeout, in milliseconds

        try (HikariDataSource pool = new HikariDataSource(config);
             Connection first = pool.getConnection();
             Connection second = pool.getConnection()) {

            assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isEqualTo(2);

            long start = System.nanoTime();
            assertThatThrownBy(pool::getConnection)
                    .isInstanceOf(SQLTransientConnectionException.class)
                    .hasMessageContaining("Connection is not available, request timed out after");
            long waitedMillis = (System.nanoTime() - start) / 1_000_000;
            assertThat(waitedMillis).isGreaterThanOrEqualTo(1000);
        }
    }
}