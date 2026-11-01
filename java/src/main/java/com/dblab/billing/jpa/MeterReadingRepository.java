package com.dblab.billing.jpa;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Loading and saving meter readings (Stage 2, lesson A7). Only an interface: Spring Data writes the class
 * that implements it when the program starts. save, saveAll, findById, count and delete come from
 * JpaRepository with no code at all; the counterpart in 2013 is an entity bean's home interface.
 */
public interface MeterReadingRepository extends JpaRepository<MeterReading, Long> {

    // A query built from the method name alone: WHERE meter.id = ? ORDER BY read_at DESC.
    // The EJB 2 counterpart is a finder method with its EJB-QL written in ejb-jar.xml.
    List<MeterReading> findByMeterIdOrderByReadAtDesc(long meterId);

    // A query written in JPQL, the successor of EJB-QL: it names entities and fields, not tables and columns.
    @Query("""
            SELECT coalesce(sum(r.kwh), 0)
            FROM MeterReading r
            WHERE r.meter.premise.id = :premiseId AND r.readAt >= :from AND r.readAt < :to""")
    BigDecimal totalKwh(@Param("premiseId") long premiseId,
                        @Param("from") OffsetDateTime from,
                        @Param("to") OffsetDateTime to);

    // The newest reading of one meter (Stage 4, lesson A5): ORDER BY read_at DESC, first row only. Called once
    // per meter, this is the "+1" of an N+1 problem.
    Optional<MeterReading> findFirstByMeterIdOrderByReadAtDesc(long meterId);

    // The newest reading of each of many meters, in one query. JPQL has no LATERAL, so this one is written in
    // PostgreSQL's own SQL (nativeQuery): for each meter, read the last entry of its part of the index
    // (meter_id, read_at) and stop. Hibernate still turns each row into a MeterReading object.
    @Query(value = """
            SELECT r.*
            FROM meter m
            CROSS JOIN LATERAL (
                SELECT * FROM meter_reading x WHERE x.meter_id = m.id ORDER BY x.read_at DESC LIMIT 1
            ) r
            WHERE m.id IN (:meterIds)""", nativeQuery = true)
    List<MeterReading> findLatestForMeters(@Param("meterIds") Collection<Long> meterIds);
}