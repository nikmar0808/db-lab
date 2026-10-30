package com.dblab.billing.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

/** Loading meters. Everything needed comes from JpaRepository. */
public interface MeterRepository extends JpaRepository<Meter, Long> {
}