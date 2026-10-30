package com.dblab.billing.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The table customer, as a JPA entity (Stage 2, lesson A7). The EJB 2 counterpart is an entity bean with
 * container-managed persistence, without the home and component interfaces and without the XML.
 *
 * <p>Column names come from the field names: Spring Boot tells Hibernate to write createdAt as created_at.
 */
@Entity
public class Customer {

    @Id                                                     // the primary key
    @GeneratedValue(strategy = GenerationType.IDENTITY)     // numbered by the database's identity column
    private Long id;

    private String name;

    private String email;

    // Filled in by the database (DEFAULT now()): Hibernate never writes it, only reads it.
    @Column(insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    // The other side of Premise.customer. mappedBy names the field that owns the link (the foreign key).
    // Loaded only when first used (lazy), and only inside a transaction (lesson A8).
    @OneToMany(mappedBy = "customer")
    private List<Premise> premises = new ArrayList<>();

    protected Customer() {                                  // JPA needs a constructor with no arguments
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public List<Premise> getPremises() {
        return premises;
    }
}