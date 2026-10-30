package com.dblab.billing.domain;

/** The allowed values of bill.status; the table's CHECK rule lists the same three (Stage 1). */
public enum BillStatus {
    DRAFT, ISSUED, PAID
}