package com.dblab.billing.domain;

import java.time.YearMonth;
import java.util.List;

/**
 * The main task of POC 3, as one business operation (Stage 2, lesson A6).
 *
 * <p>Record a batch of readings, then produce the bill of one premise for one month, all in one transaction:
 * if any part fails, nothing is saved. The lab implements this interface four times, once per way of reaching
 * the database, and one test runs the same checks against all four.
 */
public interface BillingService {

    /**
     * Saves the readings, then calculates and saves the premise's bill for the month.
     *
     * @param premiseId the premise to bill
     * @param readings  readings to save first; may be empty
     * @param month     the month to bill, in India days
     * @return the saved bill
     */
    BillResult recordReadingsAndBill(long premiseId, List<ReadingInput> readings, YearMonth month);
}