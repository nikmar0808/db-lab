package com.dblab.billing.jpa;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import java.util.ArrayList;
import java.util.List;

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

    // The meters installed here: the other side of Meter.premise, loaded when first used (Stage 4, lesson A5).
    @OneToMany(mappedBy = "premise")
    private List<Meter> meters = new ArrayList<>();

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

    public List<Meter> getMeters() {
        return meters;
    }
}