package com.dblab.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.dblab.billing.TestData.Fixture;
import com.dblab.billing.jpa.MeterReading;
import com.dblab.billing.jpa.MeterReadingRepository;
import com.dblab.billing.jpa.MeterRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A repository test with the JPA test slice (Stage 2, lesson A10).
 *
 * <p>{@code @DataJpaTest} starts only the database part of the program: the pool, Flyway, Hibernate and the
 * repositories, not the services. Each test runs in a transaction that is rolled back at the end, so it leaves
 * no rows behind. {@code @AutoConfigureTestDatabase(replace = NONE)} keeps the real PostgreSQL; without it the
 * slice would try to swap in an in-memory database.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class MeterReadingRepositoryTest extends PostgresTestBase {

    @Autowired
    MeterReadingRepository readings;

    @Autowired
    MeterRepository meters;

    @Autowired
    DataSource dataSource;

    @Test
    void latestReadingComesFirstAndTotalsAddUp() {
        Fixture f = TestData.premiseWithMeter(JdbcClient.create(dataSource));  // joins the test's transaction
        var meter = meters.getReferenceById(f.meterId());
        readings.save(new MeterReading(meter, OffsetDateTime.parse("2026-10-01T00:00+05:30"), new BigDecimal("6.000")));
        readings.save(new MeterReading(meter, OffsetDateTime.parse("2026-10-03T00:00+05:30"), new BigDecimal("9.000")));
        readings.save(new MeterReading(meter, OffsetDateTime.parse("2026-10-02T00:00+05:30"), new BigDecimal("7.500")));

        List<MeterReading> latestFirst = readings.findByMeterIdOrderByReadAtDesc(f.meterId());

        assertThat(latestFirst).extracting(MeterReading::getKwh)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("9"), new BigDecimal("7.5"), new BigDecimal("6"));
        assertThat(readings.totalKwh(f.premiseId(),
                OffsetDateTime.parse("2026-10-01T00:00+05:30"), OffsetDateTime.parse("2026-11-01T00:00+05:30")))
                .isEqualByComparingTo("22.5");
    }
}