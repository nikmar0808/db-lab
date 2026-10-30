package com.dblab.billing.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

/** Loading premises. Everything needed comes from JpaRepository. */
public interface PremiseRepository extends JpaRepository<Premise, Long> {
}