package com.dblab.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Flyway built the tables, and the program connects as lab_app, which cannot change them (Stage 2, lesson A5).
 *
 * <p>{@code @SpringBootTest} starts the whole program, as main() would, but against the test database:
 * the connection pool, Flyway, Hibernate and every service. {@code @Autowired} asks Spring to fill the field
 * with the object it built, as the constructor injection does in the program's own classes.
 */
@SpringBootTest
class MigrationsTest extends PostgresTestBase {

    @Autowired
    JdbcClient jdbc;

    @Test
    void flywayAppliedBothMigrationsAsTheOwner() {
        List<String> applied = jdbc.sql("""
                        SELECT version || ' ' || description || ' by ' || installed_by
                        FROM flyway_schema_history WHERE success ORDER BY installed_rank""")
                .query(String.class).list();

        assertThat(applied).containsExactly("1 tables by lab_owner", "2 calculate bill by lab_owner");
    }

    @Test
    void theProgramConnectsAsLabApp() {
        assertThat(jdbc.sql("SELECT current_user").query(String.class).single()).isEqualTo("lab_app");
    }

    @Test
    void labAppCannotCreateTables() {
        assertThatThrownBy(() -> jdbc.sql("CREATE TABLE app_try (x int)").update())
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("42501"));   // insufficient_privilege
    }

    /** The SQLSTATE of the SQLException at the bottom of Spring's exception (lesson A6). */
    static String sqlState(Throwable e) {
        return ((SQLException) NestedExceptionUtils.getMostSpecificCause(e)).getSQLState();
    }
}