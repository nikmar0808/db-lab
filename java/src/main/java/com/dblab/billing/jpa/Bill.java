package com.dblab.billing.jpa;

import com.dblab.billing.domain.BillStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDate;

/** The table bill. */
@Entity
public class Bill {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    private Premise premise;

    private LocalDate periodStart;

    private LocalDate periodEnd;

    private BigDecimal amount;

    // STRING stores the name, 'DRAFT'; the default, ORDINAL, would store its position, 0, and break
    // silently the day someone reorders the enum.
    @Enumerated(EnumType.STRING)
    private BillStatus status;

    // Optimistic locking (Stage 4, lesson A9; column added by V4). Hibernate adds "AND version = ?" to every
    // UPDATE of a bill and increases the number by one. If the UPDATE changes no row, someone else changed the
    // bill after it was read, and Hibernate throws an OptimisticLockException instead of overwriting their change.
    @Version
    private int version;

    protected Bill() {
    }

    public Bill(Customer customer, Premise premise, LocalDate periodStart, LocalDate periodEnd, BigDecimal amount) {
        this.customer = customer;
        this.premise = premise;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.amount = amount;
        this.status = BillStatus.DRAFT;
    }

    public Long getId() {
        return id;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public int getVersion() {
        return version;
    }

    /** A late fee (positive) or a credit (negative), added to the amount. */
    public void adjustAmount(BigDecimal change) {
        this.amount = this.amount.add(change);
    }
}