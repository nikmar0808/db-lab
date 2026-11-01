package com.dblab.billing.jpa;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Loading meters. */
public interface MeterRepository extends JpaRepository<Meter, Long> {

    // The meters of many premises in one query (Stage 4, lesson A5): WHERE premise_id IN (...).
    @Query("SELECT m FROM Meter m WHERE m.premise.id IN :premiseIds")
    List<Meter> findByPremiseIds(@Param("premiseIds") Collection<Long> premiseIds);
}