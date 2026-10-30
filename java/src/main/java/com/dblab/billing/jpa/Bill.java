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
}