package com.dblab.billing.jpa;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;

/** The table premise, with its links to its customer and its tariff. */
@Entity
public class Premise {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Many premises belong to one customer. The column customer_id holds the link (the foreign key).
    // LAZY: the customer is read from the database only when the code first uses it.
    @ManyToOne(fetch = FetchType.LAZY)
    private Customer customer;

    private String address;

    // The column is called tariff_code, not tariff_id, so it is named here.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tariff_code")
    private Tariff tariff;

    protected Premise() {
    }

    public Long getId() {
        return id;
    }

    public Customer getCustomer() {
        return customer;
    }

    public String getAddress() {
        return address;
    }

    public Tariff getTariff() {
        return tariff;
    }
}