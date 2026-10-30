package com.dblab.billing.dbfunction;

import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.Types;
import java.time.LocalDate;
import javax.sql.DataSource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlOutParameter;
import org.springframework.jdbc.core.SqlParameter;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.SimpleJdbcCall;
import org.springframework.stereotype.Component;

/**
 * Two ways to call the database function calculate_bill from Java (Stage 2, lesson A6).
 *
 * <p>Both run inside the caller's transaction, if there is one: JdbcTemplate and SimpleJdbcCall borrow the
 * connection that the transaction is already using.
 */
@Component
public class CalculateBillCall {

    private final JdbcTemplate jdbcTemplate;
    private final SimpleJdbcCall call;

    public CalculateBillCall(DataSource dataSource, JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        // Described once, used many times. The parameters are declared by hand instead of being read from the
        // database's catalogue at the first call, so the call does not depend on what the driver reports.
        this.call = new SimpleJdbcCall(dataSource)
                .withFunctionName("calculate_bill")
                .withoutProcedureColumnMetaDataAccess()
                .declareParameters(
                        new SqlOutParameter("amount", Types.NUMERIC),   // a function's result comes first
                        new SqlParameter("p_premise_id", Types.BIGINT),
                        new SqlParameter("p_period_start", Types.DATE),
                        new SqlParameter("p_period_end", Types.DATE));
    }

    /**
     * The 2013 way: a CallableStatement with the JDBC escape syntax {? = call ...}. JdbcTemplate lends the
     * connection to the code between the braces and takes it back afterwards (a ConnectionCallback).
     */
    public BigDecimal viaCallableStatement(long premiseId, LocalDate start, LocalDate end) {
        return jdbcTemplate.execute((ConnectionCallback<BigDecimal>) con -> {
            try (CallableStatement cs = con.prepareCall("{? = call calculate_bill(?, ?, ?)}")) {
                cs.registerOutParameter(1, Types.NUMERIC);          // the function's result
                cs.setLong(2, premiseId);
                cs.setObject(3, start);
                cs.setObject(4, end);
                cs.execute();
                return cs.getBigDecimal(1);
            }
        });
    }

    /** The Spring way: SimpleJdbcCall registers the parameters and reads the result. */
    public BigDecimal viaSimpleJdbcCall(long premiseId, LocalDate start, LocalDate end) {
        MapSqlParameterSource in = new MapSqlParameterSource()
                .addValue("p_premise_id", premiseId)
                .addValue("p_period_start", start)
                .addValue("p_period_end", end);
        return call.executeFunction(BigDecimal.class, in);
    }
}