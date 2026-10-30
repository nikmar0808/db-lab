package com.dblab.billing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The billing rule, in plain Java with no database (Stage 2, lesson A6).
 *
 * <p>amount = kWh x rate per kWh + days x standing charge, rounded to 2 decimal places, halves away from zero.
 * It is the same rule as the database function calculate_bill. Keeping it in Java lets a unit test check it
 * in milliseconds, without starting a database; the main-task test then checks that both give the same answer.
 */
public final class BillCalculator {

    private BillCalculator() {
    }

    public static BigDecimal amount(BigDecimal kwh, BigDecimal ratePerKwh, BigDecimal standingCharge, int days) {
        BigDecimal energy = kwh.multiply(ratePerKwh);
        BigDecimal standing = standingCharge.multiply(BigDecimal.valueOf(days));
        // BigDecimal, never double: 0.1 + 0.2 must be exactly 0.3 when money is involved.
        return energy.add(standing).setScale(2, RoundingMode.HALF_UP);
    }
}