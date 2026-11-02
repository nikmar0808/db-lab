package com.dblab.billing.mongo;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Loading and saving buckets. As with Spring Data JPA (Stage 2), only an interface: Spring Data MongoDB writes
 * the class at start-up and builds each query from the method's name. findByMeterIdAndMonth becomes the filter
 * { meter_id: ?, month: ? }, using the field names of {@code @Field}.
 */
public interface MeterReadingBucketRepository extends MongoRepository<MeterReadingBucket, String> {

    Optional<MeterReadingBucket> findByMeterIdAndMonth(long meterId, String month);

    List<MeterReadingBucket> findByPremiseIdAndMonth(long premiseId, String month);
}