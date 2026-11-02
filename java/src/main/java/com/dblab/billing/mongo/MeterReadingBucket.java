package com.dblab.billing.mongo;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * One document of the collection meter_readings: one meter's readings for one India month (Stage 5's bucket).
 *
 * <p>The counterpart of a JPA {@code @Entity} (Stage 2), for MongoDB: {@code @Document} names the collection, as
 * {@code @Table} named the table; {@code @Field} names a field, as {@code @Column} named a column. A record works,
 * because Spring Data MongoDB builds objects through their constructor.
 *
 * <p>{@code targetType = FieldType.DECIMAL128}: store the BigDecimal as MongoDB's exact decimal, as Stage 5's
 * validator demands. Spring Data MongoDB 5 does that by default; version 4 (Spring Boot 3) stored a BigDecimal as
 * <b>text</b> ("13.500"), which sorts and adds up wrongly. Saying it explicitly keeps the code right whichever
 * version it meets, and tells the reader what is stored (Stage 6, lesson A9).
 */
@Document("meter_readings")
public record MeterReadingBucket(
        @Id String id,                                    // the ObjectId, as text; null on a new bucket
        @Field("meter_id") long meterId,
        @Field("premise_id") long premiseId,
        String month,                                     // the India month, e.g. "2026-11"
        List<Reading> readings,
        @Field("reading_count") int readingCount,
        @Field(name = "total_kwh", targetType = FieldType.DECIMAL128) BigDecimal totalKwh) {

    /** One reading inside the bucket. Instant is an exact moment, as a BSON date is. */
    public record Reading(
            @Field("read_at") Instant readAt,
            @Field(targetType = FieldType.DECIMAL128) BigDecimal kwh) {
    }
}