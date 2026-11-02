package com.dblab.billing.mongo;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * One document of the collection customers: the customer, with its premises and each premise's meters inside
 * (Stage 5, lesson A5). Read whole, in one query: the JPA entities of Stage 2 needed a join or a query per level
 * for the same data (Stage 4, lesson A5).
 *
 * <p>Dates are read as Instant, never as LocalDate: Spring would turn a BSON date into a LocalDate using the JVM's
 * own time zone, which is a different day in India and in UTC for anything near midnight.
 */
@Document("customers")
public record CustomerDocument(
        @Id long id,
        String name,
        String email,
        @Field("created_at") Instant createdAt,
        List<Premise> premises) {

    public record Premise(
            @Field("premise_id") long premiseId,
            String address,
            @Field("tariff_code") String tariffCode,
            List<Meter> meters) {
    }

    public record Meter(
            @Field("meter_id") long meterId,
            @Field("serial_number") String serialNumber,
            @Field("installed_on") Instant installedOn) {
    }
}