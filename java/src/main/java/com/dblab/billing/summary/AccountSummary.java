package com.dblab.billing.summary;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * The account summary, the second task of POC 3 (Stage 4, lesson A5): one customer, their premises, the meters at
 * each premise, and each meter's latest reading. Plain records, so that the three ways of building it can be
 * compared with equals(). Premises and meters are always listed in id order.
 */
public record AccountSummary(long customerId, String customerName, List<PremiseLine> premises) {

    public record PremiseLine(long premiseId, String address, List<MeterLine> meters) {
    }

    /** latestReadAt and latestKwh are null for a meter with no readings yet. */
    public record MeterLine(long meterId, String serialNumber, OffsetDateTime latestReadAt, BigDecimal latestKwh) {
    }
}