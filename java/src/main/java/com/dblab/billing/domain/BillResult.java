package com.dblab.billing.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A saved bill, as returned to the caller. */
public record BillResult(long billId, long premiseId, LocalDate periodStart, LocalDate periodEnd, BigDecimal amount) {
}