package com.dblab.billing.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

/** Saving bills. Everything needed comes from JpaRepository. */
public interface BillRepository extends JpaRepository<Bill, Long> {
}