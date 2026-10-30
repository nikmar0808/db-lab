package com.dblab.billing.plainjdbc;

import com.dblab.billing.domain.BillCalculator;
import com.dblab.billing.domain.BillResult;
import com.dblab.billing.domain.BillingFailedException;
import com.dblab.billing.domain.BillingPeriod;
import com.dblab.billing.domain.BillingService;
import com.dblab.billing.domain.ReadingInput;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.YearMonth;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.stereotype.Service;

/**
 * Way 1: plain JDBC, the 2013 way, on purpose (Stage 2, lesson A6).
 *
 * <p>Everything a 2013 DAO did by hand is here: borrowing a connection, switching off auto-commit, preparing
 * statements, binding parameters, reading result sets, committing or rolling back, and giving the connection
 * back. The differences from 2013 are that the DataSource is passed in by Spring instead of looked up in JNDI,
 * and that try-with-resources closes each resource instead of a finally block.
 *
 * <p>No {@code @Transactional}: this class manages its own transaction, as code did before Spring.
 */
@Service("plainJdbcBilling")
public class PlainJdbcBilling implements BillingService {

    private final DataSource dataSource;

    public PlainJdbcBilling(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public BillResult recordReadingsAndBill(long premiseId, List<ReadingInput> readings, YearMonth month) {
        BillingPeriod period = new BillingPeriod(month);
        // try-with-resources: the connection is closed, that is, returned to the pool, however the block ends.
        try (Connection con = dataSource.getConnection()) {
            con.setAutoCommit(false);                       // start a transaction (the default is to commit each statement)
            try {
                insertReadings(con, readings);
                PremiseTariff tariff = findTariff(con, premiseId);
                BigDecimal kwh = totalKwh(con, premiseId, period);
                BigDecimal amount = BillCalculator.amount(kwh, tariff.ratePerKwh(), tariff.standingCharge(), period.days());
                long billId = insertBill(con, tariff.customerId(), premiseId, period, amount);
                con.commit();                               // all of it, or ...
                return new BillResult(billId, premiseId, period.firstDay(), period.lastDay(), amount);
            } catch (SQLException | RuntimeException e) {
                con.rollback();                             // ... none of it
                throw e;
            } finally {
                // The connection goes back to the pool and will be lent to someone else: leave it as it was found.
                con.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new BillingFailedException("billing premise " + premiseId + " failed: " + e.getMessage(), e);
        }
    }

    private void insertReadings(Connection con, List<ReadingInput> readings) throws SQLException {
        String sql = "INSERT INTO meter_reading (meter_id, read_at, kwh) VALUES (?, ?, ?)";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            for (ReadingInput r : readings) {
                ps.setLong(1, r.meterId());
                ps.setObject(2, r.readAt());               // OffsetDateTime -> timestamptz (JDBC 4.2)
                ps.setBigDecimal(3, r.kwh());
                ps.addBatch();                              // collect the rows ...
            }
            ps.executeBatch();                              // ... and send them together
        }
    }

    private PremiseTariff findTariff(Connection con, long premiseId) throws SQLException {
        String sql = """
                SELECT p.customer_id, t.rate_per_kwh, t.standing_charge
                FROM premise p JOIN tariff t ON t.code = p.tariff_code
                WHERE p.id = ?""";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, premiseId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("premise " + premiseId + " does not exist");
                }
                return new PremiseTariff(rs.getLong(1), rs.getBigDecimal(2), rs.getBigDecimal(3));
            }
        }
    }

    private BigDecimal totalKwh(Connection con, long premiseId, BillingPeriod period) throws SQLException {
        String sql = """
                SELECT coalesce(sum(r.kwh), 0)
                FROM meter m JOIN meter_reading r ON r.meter_id = m.id
                WHERE m.premise_id = ? AND r.read_at >= ? AND r.read_at < ?""";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, premiseId);
            ps.setObject(2, period.from());
            ps.setObject(3, period.to());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();                                  // an aggregate always returns exactly one row
                return rs.getBigDecimal(1);
            }
        }
    }

    private long insertBill(Connection con, long customerId, long premiseId, BillingPeriod period, BigDecimal amount)
            throws SQLException {
        String sql = """
                INSERT INTO bill (customer_id, premise_id, period_start, period_end, amount)
                VALUES (?, ?, ?, ?, ?)
                RETURNING id""";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, customerId);
            ps.setLong(2, premiseId);
            ps.setObject(3, period.firstDay());            // LocalDate -> date
            ps.setObject(4, period.lastDay());
            ps.setBigDecimal(5, amount);
            try (ResultSet rs = ps.executeQuery()) {        // INSERT ... RETURNING gives back a result set
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** The three values the bill needs from the premise and its tariff. */
    private record PremiseTariff(long customerId, BigDecimal ratePerKwh, BigDecimal standingCharge) {
    }
}