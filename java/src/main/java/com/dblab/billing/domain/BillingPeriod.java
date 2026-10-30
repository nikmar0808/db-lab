package com.dblab.billing.domain;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;

/**
 * One billing month, in India days (Stage 2, lesson A9).
 *
 * <p>A month starts at midnight India time on its first day and ends at midnight India time after its last
 * day, the same rule as the database function calculate_bill. Readings are selected with
 * {@code read_at >= from() AND read_at < to()}: from is included, to is not, so a reading at exactly
 * midnight belongs to the day it starts.
 */
public record BillingPeriod(YearMonth month) {

    /** The zone that defines a billing day. */
    public static final ZoneId BILLING_ZONE = ZoneId.of("Asia/Kolkata");

    /** The first day billed. */
    public LocalDate firstDay() {
        return month.atDay(1);
    }

    /** The last day billed (inclusive), as stored in bill.period_end. */
    public LocalDate lastDay() {
        return month.atEndOfMonth();
    }

    /** The moment the period starts: midnight India time on the first day. */
    public OffsetDateTime from() {
        return firstDay().atStartOfDay(BILLING_ZONE).toOffsetDateTime();
    }

    /** The moment just after the period ends: midnight India time after the last day. */
    public OffsetDateTime to() {
        return lastDay().plusDays(1).atStartOfDay(BILLING_ZONE).toOffsetDateTime();
    }

    /** The number of days billed. */
    public int days() {
        return month.lengthOfMonth();
    }
}