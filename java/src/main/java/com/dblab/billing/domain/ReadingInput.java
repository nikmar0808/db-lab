package com.dblab.billing.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One reading to be saved: which meter, at what moment, how many kWh.
 *
 * <p>A {@code record} is a short way to write a class that only carries values: Java generates the
 * constructor, the accessors ({@code meterId()}, ...), {@code equals}, {@code hashCode} and {@code toString}.
 * It is the modern counterpart of a 2013 value object or data transfer object with getters only.
 */
public record ReadingInput(long meterId, OffsetDateTime readAt, BigDecimal kwh) {
}