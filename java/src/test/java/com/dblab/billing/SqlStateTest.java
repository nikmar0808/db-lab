package com.dblab.billing;

import static com.dblab.billing.MigrationsTest.sqlState;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dblab.billing.TestData.Fixture;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * What a broken rule looks like in Java: Stage 1's SQLSTATE codes, reached from code (Stage 2, lesson A6).
 *
 * <p>Plain JDBC throws SQLException, and getSQLState() gives the code. Spring translates the same error into
 * an exception class that says what went wrong (DuplicateKeyException, DataIntegrityViolationException),
 * with the SQLException kept underneath.
 */
@SpringBootTest
class SqlStateTest extends PostgresTestBase {

    static final String INSERT_READING = "INSERT INTO meter_reading (meter_id, read_at, kwh) VALUES (?, ?, ?)";
    static final OffsetDateTime MOMENT = OffsetDateTime.parse("2026-10-01T00:00+05:30");

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DataSource dataSource;

    @Test
    void duplicateReadingIsADuplicateKey() {
        Fixture f = TestData.premiseWithMeter(jdbc);
        jdbc.sql(INSERT_READING).params(f.meterId(), MOMENT, 7.5).update();

        assertThatThrownBy(() -> jdbc.sql(INSERT_READING).params(f.meterId(), MOMENT, 9.0).update())
                .isInstanceOf(DuplicateKeyException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23505"));      // unique_violation, ORA-00001
    }

    @Test
    void readingForAMissingMeterIsAnIntegrityViolation() {
        assertThatThrownBy(() -> jdbc.sql(INSERT_READING).params(-1L, MOMENT, 7.5).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23503"));      // foreign_key_violation, ORA-02291
    }

    @Test
    void afterAnErrorTheWholeTransactionIsRefused() throws SQLException {
        Fixture f = TestData.premiseWithMeter(jdbc);
        try (Connection con = dataSource.getConnection()) {
            con.setAutoCommit(false);
            try (PreparedStatement ps = con.prepareStatement(INSERT_READING)) {
                ps.setLong(1, f.meterId());
                ps.setObject(2, MOMENT);
                ps.setBigDecimal(3, new java.math.BigDecimal("-1"));      // breaks the kWh rule
                assertThatThrownBy(ps::executeUpdate)
                        .isInstanceOfSatisfying(SQLException.class,
                                e -> assertThat(e.getSQLState()).isEqualTo("23514"));   // check_violation

                ps.setBigDecimal(3, new java.math.BigDecimal("7.5"));     // a perfectly good row ...
                assertThatThrownBy(ps::executeUpdate)
                        .isInstanceOfSatisfying(SQLException.class,
                                e -> assertThat(e.getSQLState()).isEqualTo("25P02"));   // ... refused: transaction aborted
            } finally {
                con.rollback();
                con.setAutoCommit(true);
            }
        }
    }
}