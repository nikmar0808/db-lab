package com.dblab.billing.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

/** Loading customers. Used by the lazy-loading test (lesson A8); Stage 4 uses it for the account summary. */
public interface CustomerRepository extends JpaRepository<Customer, Long> {
}