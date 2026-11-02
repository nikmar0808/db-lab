package com.dblab.billing.mongo;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads the MongoDB named by MONGO_URI and logs what it finds; changes nothing (Stage 6, step C10).
 * Runs only with the Spring profile "mongo-report":
 *   mvn -q spring-boot:run "-Dspring-boot.run.profiles=mongo-report"
 */
@Component
@Profile("mongo-report")
class MongoReport implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MongoReport.class);

    private final MongoTemplate mongo;
    private final MongoBilling billing;
    private final MeterReadingBucketRepository buckets;
    private final CustomerDocumentRepository customers;

    MongoReport(MongoTemplate mongo, MongoBilling billing, MeterReadingBucketRepository buckets,
                CustomerDocumentRepository customers) {
        this.mongo = mongo;
        this.billing = billing;
        this.buckets = buckets;
        this.customers = customers;
    }

    @Override
    public void run(ApplicationArguments args) {
        Document hello = mongo.executeCommand(new Document("hello", 1));
        Document build = mongo.executeCommand(new Document("buildInfo", 1));
        log.info("MongoDB {} at {}, replica set {}, members {}", build.getString("version"),
                hello.getString("primary"), hello.getString("setName"), hello.getList("hosts", String.class));
        CustomerDocument customer = billing.customer(1);
        customer.premises().forEach(p -> p.meters().forEach(m ->
                log.info("customer {} ({}), {}: meter {}", customer.id(), customer.name(), p.address(), m.serialNumber())));
        buckets.findByMeterIdAndMonth(1, "2026-10").ifPresent(b ->
                log.info("meter 1, 2026-10: {} readings, {} kWh", b.readingCount(), b.totalKwh()));
        customers.findByPremisesMetersSerialNumber("MTR-000171").ifPresent(c ->
                log.info("meter MTR-000171 belongs to customer {} ({})", c.id(), c.name()));
    }
}