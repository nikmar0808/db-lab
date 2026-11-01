package com.dblab.billing.measure;

import com.dblab.billing.jpa.MeterReading;
import com.dblab.billing.jpa.MeterReadingRepository;
import com.dblab.billing.jpa.MeterRepository;
import com.dblab.billing.summary.AccountSummaries;
import com.dblab.billing.summary.AccountSummary;
import jakarta.persistence.EntityManagerFactory;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.postgresql.PGConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Takes the measurements of Stage 4 against the real lab database and writes them to a results file
 * (lesson A1: a number before, one change, a number after, written down).
 *
 * <p>Runs only with the Spring profile "measure" (application-measure.properties), which also switches Flyway
 * off, so that the "before the index" run can be taken while the new migrations wait. Every timing is the
 * middle value of three runs, after one run that is not counted (it warms up the caches).
 *
 * <p>It changes nothing it does not put back: the readings it writes for the bulk-insert timings are dated
 * 2030 and deleted after every run.
 */
@Component
@Profile("measure")
class Measurements implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(Measurements.class);
    private static final int ROWS = 5_000;                     // rows written by each bulk-insert way
    private static final OffsetDateTime FIRST_MOMENT = OffsetDateTime.of(2030, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    private final JdbcClient jdbc;
    private final DataSource dataSource;
    private final AccountSummaries summaries;
    private final MeterRepository meters;
    private final MeterReadingRepository readings;
    private final TransactionTemplate transaction;
    private final Statistics statistics;
    private final String url;
    private final String user;
    private final String password;
    private final String label;

    Measurements(JdbcClient jdbc, DataSource dataSource, AccountSummaries summaries, MeterRepository meters,
                 MeterReadingRepository readings, TransactionTemplate transaction, EntityManagerFactory emf,
                 @Value("${spring.datasource.url}") String url,
                 @Value("${spring.datasource.username}") String user,
                 @Value("${spring.datasource.password}") String password,
                 @Value("${measure.label:run}") String label) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.summaries = summaries;
        this.meters = meters;
        this.readings = readings;
        this.transaction = transaction;
        // Hibernate's own counters: how many statements it prepared (generate_statistics=true in the profile).
        this.statistics = emf.unwrap(SessionFactory.class).getStatistics();
        this.url = url;
        this.user = user;
        this.password = password;
        this.label = label;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        StringBuilder out = new StringBuilder();
        out.append("# Measurements: ").append(label).append("\n\n");
        out.append("Taken ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
                .append(" by `Measurements.java` (Stage 4), on PostgreSQL ")
                .append(jdbc.sql("SHOW server_version").query(String.class).single())
                .append(", with ").append(jdbc.sql("SELECT count(*) FROM meter_reading").query(Long.class).single())
                .append(" readings. Times are the middle of three runs, in milliseconds.\n\n");
        out.append("Indexes on `meter_reading`: ")
                .append(String.join(", ", jdbc.sql("""
                        SELECT indexname FROM pg_indexes WHERE tablename = 'meter_reading' ORDER BY indexname""")
                        .query(String.class).list()))
                .append("\n\n");

        plans(out);
        accountSummary(out);
        bulkInserts(out);

        Path file = Path.of("..", "docs", "results", label + ".md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, out.toString());
        log.info("Measurements written to {}\n{}", file.toAbsolutePath().normalize(), out);
    }

    // ---------------------------------------------------------------- query plans (lessons A2, A4, A7)

    private static final Pattern SCAN = Pattern.compile(
            "(Parallel Seq Scan|Seq Scan|Index Only Scan|Index Scan|Bitmap Heap Scan)(?: using (\\S+))? on meter_reading"
            + ".*?rows=([0-9.]+) loops=([0-9]+)");
    private static final Pattern BITMAP_INDEX = Pattern.compile("Bitmap Index Scan on (\\S+)");
    private static final Pattern REMOVED = Pattern.compile("Rows Removed by Filter: ([0-9]+)");
    private static final Pattern TIME = Pattern.compile("Execution Time: ([0-9.]+) ms");

    private void plans(StringBuilder out) {
        long meterId = jdbc.sql("SELECT id FROM meter WHERE serial_number = 'MTR-200500'").query(Long.class).single();
        long deep = jdbc.sql("SELECT count(*) * 9 / 10 FROM meter_reading").query(Long.class).single();
        // The last row before the deep page: where keyset paging continues from. Values from the database
        // itself, written into the SQL below as literals so that EXPLAIN plans exactly the statement shown.
        var last = jdbc.sql("SELECT read_at, id FROM meter_reading ORDER BY read_at, id OFFSET ? LIMIT 1")
                .param(deep - 1)
                .query((rs, n) -> new Object[] {rs.getObject(1, OffsetDateTime.class), rs.getLong(2)}).single();

        out.append("## Query plans\n\n");
        out.append("| Question | How `meter_reading` was read | Rows read | Time |\n|---|---|---:|---:|\n");
        plan(out, "1. One meter, one month (the bill)", """
                SELECT sum(kwh) FROM meter_reading
                WHERE meter_id = %d AND read_at >= '2026-09-30 18:30+00' AND read_at < '2026-10-31 18:30+00'"""
                .formatted(meterId));
        plan(out, "2. Every meter, one day", """
                SELECT count(*), sum(kwh) FROM meter_reading
                WHERE read_at >= '2026-10-14 18:30+00' AND read_at < '2026-10-15 18:30+00'""");
        plan(out, "3. The same day, with a function on the column", """
                SELECT count(*), sum(kwh) FROM meter_reading
                WHERE (read_at AT TIME ZONE 'Asia/Kolkata')::date = DATE '2026-10-15'""");
        plan(out, "4. Most of the table (since 1 January 2026)", """
                SELECT count(*), sum(kwh) FROM meter_reading WHERE read_at >= '2026-01-01'""");
        plan(out, "5. A deep page with OFFSET", """
                SELECT id, meter_id, read_at, kwh FROM meter_reading ORDER BY read_at, id LIMIT 50 OFFSET %d"""
                .formatted(deep));
        plan(out, "6. The same page with keyset paging", """
                SELECT id, meter_id, read_at, kwh FROM meter_reading
                WHERE (read_at, id) > ('%s', %d) ORDER BY read_at, id LIMIT 50""".formatted(last[0], last[1]));
        out.append("\n");
    }

    private void plan(StringBuilder out, String question, String sql) {
        String text = "";
        List<Double> times = new ArrayList<>();
        for (int run = 0; run < 4; run++) {
            text = String.join("\n", jdbc.sql("EXPLAIN (ANALYZE, BUFFERS) " + sql).query(String.class).list());
            Matcher t = TIME.matcher(text);
            if (run > 0 && t.find()) {                 // run 0 warms up and is not counted
                times.add(Double.parseDouble(t.group(1)));
            }
        }
        String how = "?";
        long rowsRead = 0;
        Matcher scan = SCAN.matcher(text);
        if (scan.find()) {
            how = scan.group(1);
            if (scan.group(2) != null) {
                how += " using `" + scan.group(2) + "`";
            } else if (how.startsWith("Bitmap")) {
                Matcher bitmap = BITMAP_INDEX.matcher(text);
                how += bitmap.find() ? " of `" + bitmap.group(1) + "`" : "";
            }
            double rows = Double.parseDouble(scan.group(3));
            int loops = Integer.parseInt(scan.group(4));
            Matcher removed = REMOVED.matcher(text.substring(scan.end()));
            long removedPerLoop = removed.find() ? Long.parseLong(removed.group(1)) : 0;
            rowsRead = Math.round((rows + removedPerLoop) * loops);
        }
        out.append("| ").append(question).append(" | ").append(how).append(" | ")
                .append(String.format("%,d", rowsRead)).append(" | ").append(String.format("%.2f", median(times)))
                .append(" |\n");
    }

    // ---------------------------------------------------------------- the account summary (lesson A5)

    private void accountSummary(StringBuilder out) {
        List<Long> ids = jdbc.sql("SELECT id FROM customer WHERE email LIKE 'load%@example.com' ORDER BY id LIMIT 100")
                .query(Long.class).list();
        out.append("## The account summary for ").append(ids.size()).append(" customers\n\n");
        out.append("| Way | Statements sent | Time |\n|---|---:|---:|\n");
        List<AccountSummary> expected = summaries.naive(ids);
        summary(out, "1. Natural JPA code (lazy lists, one query per meter)", ids, summaries::naive, expected, true);
        summary(out, "2. One query per level", ids, summaries::perLevel, expected, true);
        summary(out, "3. One hand-written query (JdbcClient)", ids, summaries::oneQuery, expected, false);
        out.append("\n");
    }

    private void summary(StringBuilder out, String way, List<Long> ids,
                         Function<List<Long>, List<AccountSummary>> build, List<AccountSummary> expected,
                         boolean countedByHibernate) {
        List<Double> times = new ArrayList<>();
        long statements = 1;
        for (int run = 0; run < 4; run++) {
            statistics.clear();
            long start = System.nanoTime();
            List<AccountSummary> result = build.apply(ids);
            double ms = (System.nanoTime() - start) / 1e6;
            if (!result.equals(expected)) {
                throw new IllegalStateException(way + " returned a different summary");
            }
            if (run > 0) {
                times.add(ms);
            }
            if (countedByHibernate) {
                statements = statistics.getPrepareStatementCount();
            }
        }
        out.append("| ").append(way).append(" | ").append(String.format("%,d", statements)).append(" | ")
                .append(String.format("%.1f", median(times))).append(" |\n");
    }

    // ---------------------------------------------------------------- writing many rows (lesson A6)

    private interface Way {
        void write(long meterId) throws Exception;
    }

    private void bulkInserts(StringBuilder out) throws Exception {
        out.append("## Writing ").append(String.format("%,d", ROWS)).append(" readings\n\n");
        out.append("| Way | Time | Rows per second |\n|---|---:|---:|\n");
        long meterId = jdbc.sql("SELECT id FROM meter WHERE serial_number >= 'MTR-200000' ORDER BY id LIMIT 1")
                .query(Long.class).single();
        String insert = "INSERT INTO meter_reading (meter_id, read_at, kwh) VALUES (?, ?, ?)";

        bulk(out, "1. One statement per row, each committed on its own", meterId, m -> {
            try (Connection con = dataSource.getConnection()) {
                con.setAutoCommit(true);
                try (PreparedStatement ps = con.prepareStatement(insert)) {
                    for (int i = 0; i < ROWS; i++) {
                        bind(ps, m, i);
                        ps.executeUpdate();
                    }
                }
            }
        });
        bulk(out, "2. One statement per row, one transaction", meterId, m -> {
            try (Connection con = dataSource.getConnection()) {
                con.setAutoCommit(false);
                try (PreparedStatement ps = con.prepareStatement(insert)) {
                    for (int i = 0; i < ROWS; i++) {
                        bind(ps, m, i);
                        ps.executeUpdate();
                    }
                }
                con.commit();
            }
        });
        bulk(out, "3. JDBC batches of 1,000 (addBatch, executeBatch)", meterId, m -> {
            try (Connection con = dataSource.getConnection()) {
                batches(con, insert, m);
            }
        });
        bulk(out, "4. JDBC batches, with reWriteBatchedInserts=true", meterId, m -> {
            String rewriting = url + (url.contains("?") ? "&" : "?") + "reWriteBatchedInserts=true";
            try (Connection con = DriverManager.getConnection(rewriting, user, password)) {
                batches(con, insert, m);
            }
        });
        bulk(out, "5. JPA saveAll, hibernate.jdbc.batch_size=1000", meterId, m -> {
            statistics.clear();
            transaction.executeWithoutResult(status -> {
                List<MeterReading> objects = new ArrayList<>();
                for (int i = 0; i < ROWS; i++) {
                    objects.add(new MeterReading(meters.getReferenceById(m), FIRST_MOMENT.plusMinutes(i),
                            new BigDecimal("7.500")));
                }
                readings.saveAll(objects);
            });
            log.info("JPA saveAll sent {} statements for {} rows", statistics.getPrepareStatementCount(), ROWS);
        });
        bulk(out, "6. One set-based INSERT ... SELECT", meterId, m ->
                jdbc.sql("""
                        INSERT INTO meter_reading (meter_id, read_at, kwh)
                        SELECT ?, ?::timestamptz + n * interval '1 minute', 7.500
                        FROM generate_series(0, ? - 1) AS n""")
                        .params(m, FIRST_MOMENT, ROWS).update());
        bulk(out, "7. COPY ... FROM STDIN", meterId, m -> {
            StringBuilder csv = new StringBuilder();
            for (int i = 0; i < ROWS; i++) {
                csv.append(m).append(',').append(FIRST_MOMENT.plusMinutes(i)).append(",7.500\n");
            }
            try (Connection con = dataSource.getConnection()) {
                con.setAutoCommit(true);
                // The driver's own COPY interface: the rows travel as one stream of CSV text.
                con.unwrap(PGConnection.class).getCopyAPI()
                        .copyIn("COPY meter_reading (meter_id, read_at, kwh) FROM STDIN (FORMAT csv)",
                                new StringReader(csv.toString()));
            }
        });
        out.append("\n");
    }

    private void bind(PreparedStatement ps, long meterId, int i) throws SQLException {
        ps.setLong(1, meterId);
        ps.setObject(2, FIRST_MOMENT.plusMinutes(i));
        ps.setBigDecimal(3, new BigDecimal("7.500"));
    }

    private void batches(Connection con, String insert, long meterId) throws SQLException {
        con.setAutoCommit(false);
        try (PreparedStatement ps = con.prepareStatement(insert)) {
            for (int i = 0; i < ROWS; i++) {
                bind(ps, meterId, i);
                ps.addBatch();                         // collect the row; nothing is sent yet
                if ((i + 1) % 1_000 == 0) {
                    ps.executeBatch();                 // send the collected rows together
                }
            }
            ps.executeBatch();
        }
        con.commit();
    }

    private void bulk(StringBuilder out, String way, long meterId, Way write) throws Exception {
        List<Double> times = new ArrayList<>();
        for (int run = 0; run < 4; run++) {
            removeTestRows(meterId);
            long start = System.nanoTime();
            write.write(meterId);
            double ms = (System.nanoTime() - start) / 1e6;
            int written = jdbc.sql("SELECT count(*) FROM meter_reading WHERE meter_id = ? AND read_at >= ?")
                    .params(meterId, FIRST_MOMENT).query(Integer.class).single();
            if (written != ROWS) {
                throw new IllegalStateException(way + " wrote " + written + " rows, not " + ROWS);
            }
            if (run > 0) {
                times.add(ms);
            }
        }
        removeTestRows(meterId);
        double ms = median(times);
        out.append("| ").append(way).append(" | ").append(String.format("%.0f", ms)).append(" | ")
                .append(String.format("%,.0f", ROWS / (ms / 1000))).append(" |\n");
        log.info("{}: {} ms", way, String.format("%.0f", ms));
    }

    private void removeTestRows(long meterId) {
        jdbc.sql("DELETE FROM meter_reading WHERE meter_id = ? AND read_at >= ?").params(meterId, FIRST_MOMENT).update();
    }

    private static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.isEmpty() ? Double.NaN : sorted.get(sorted.size() / 2);
    }
}