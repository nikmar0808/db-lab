package com.dblab.billing;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * The real PostgreSQL every database test runs against (Stage 2, lesson A10).
 *
 * <p>Testcontainers starts a brand-new postgres:18 container the first time a test class extending this one
 * is loaded, and Docker removes it when the test run ends. Stage 0's first-start script is copied into the
 * container's /docker-entrypoint-initdb.d, so the test database has exactly the lab's accounts: lab_owner owns
 * the tables (Flyway uses it), lab_app reads and writes data (the program uses it). The tests therefore also
 * prove the rights work.
 *
 * <p>One container is shared by every test class in the run, because starting PostgreSQL takes a few seconds.
 * Tests keep out of each other's way by creating their own customers, premises and meters (see TestData).
 */
public abstract class PostgresTestBase {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18")
            .withCopyFileToContainer(
                    // The path is relative to the java folder, where Maven runs the tests. 0755: may be executed.
                    MountableFile.forHostPath("../postgres/init/01-roles-and-database.sh", 0755),
                    "/docker-entrypoint-initdb.d/01-roles-and-database.sh")
            .withEnv("LAB_OWNER_PASSWORD", "owner-test")      // test-only passwords, for a throwaway database
            .withEnv("LAB_APP_PASSWORD", "app-test");

    static {
        POSTGRES.start();
    }

    /** The JDBC address of the test database utility_lab, on the port Docker chose for this run. */
    public static String jdbcUrl() {
        // Testcontainers reports the host as "localhost"; 127.0.0.1 avoids the long delays localhost
        // caused on this Windows machine in POC 2.
        String host = "localhost".equals(POSTGRES.getHost()) ? "127.0.0.1" : POSTGRES.getHost();
        return "jdbc:postgresql://" + host + ":" + POSTGRES.getMappedPort(5432) + "/utility_lab";
    }

    /**
     * Replaces, for tests only, the connection settings of application.properties. Spring calls this before it
     * creates the connection pool and runs Flyway.
     */
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestBase::jdbcUrl);
        registry.add("spring.datasource.username", () -> "lab_app");
        registry.add("spring.datasource.password", () -> "app-test");
        registry.add("spring.flyway.user", () -> "lab_owner");
        registry.add("spring.flyway.password", () -> "owner-test");
    }
}