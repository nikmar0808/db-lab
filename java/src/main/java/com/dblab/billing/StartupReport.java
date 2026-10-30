package com.dblab.billing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Logs, once the program has started, who it is connected as and what Flyway has applied (Stage 2, lesson A5).
 *
 * <p>An {@link ApplicationRunner} is called by Spring Boot after everything is set up. The 2013 counterpart is
 * a start-up class registered in the application server, which ran once when the EAR was deployed.
 */
@Component
class StartupReport implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupReport.class);

    private final JdbcClient jdbc;

    // Spring calls this constructor and passes in the JdbcClient it built (dependency injection, lesson A3).
    StartupReport(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        String user = jdbc.sql("SELECT current_user").query(String.class).single();
        String version = jdbc.sql("SHOW server_version").query(String.class).single();
        String zone = jdbc.sql("SHOW TimeZone").query(String.class).single();
        Integer migrations = jdbc.sql("SELECT count(*) FROM flyway_schema_history WHERE success")
                .query(Integer.class).single();
        log.info("Connected as {} to PostgreSQL {}; session time zone {}; {} migrations applied",
                user, version, zone, migrations);
    }
}