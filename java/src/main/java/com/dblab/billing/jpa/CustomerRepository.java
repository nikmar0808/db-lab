package com.dblab.billing.jpa;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Loading customers: the lazy-loading test (Stage 2) and the account summary (Stage 4, lesson A5). */
public interface CustomerRepository extends JpaRepository<Customer, Long> {

    // A fetch join: the customers and their premises in one query. Without JOIN FETCH, each customer's
    // premises would be loaded by a query of their own the first time the code touches them.
    @Query("SELECT DISTINCT c FROM Customer c JOIN FETCH c.premises WHERE c.id IN :ids")
    List<Customer> findWithPremises(@Param("ids") Collection<Long> ids);
}