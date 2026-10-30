package com.dblab.billing.domain;

/**
 * Thrown by the plain-JDBC way when the main task fails.
 *
 * <p>JDBC reports every database problem as {@link java.sql.SQLException}, a checked exception that each caller
 * would have to declare. Wrapping it in this unchecked exception keeps the BillingService interface free of
 * JDBC types, and keeps the original SQLException, with its SQLSTATE, as the cause.
 */
public class BillingFailedException extends RuntimeException {

    public BillingFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}