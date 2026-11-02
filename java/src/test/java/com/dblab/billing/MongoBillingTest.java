package com.dblab.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dblab.billing.mongo.CustomerDocument;
import com.dblab.billing.mongo.CustomerDocumentRepository;
import com.dblab.billing.mongo.MeterReadingBucketRepository;
import com.dblab.billing.mongo.MongoBilling;
import com.dblab.billing.mongo.MongoBilling.ReadingIn;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * The main task against MongoDB, from Spring Data MongoDB (Stage 6, lesson A9), on Stage 5's collections.
 * Each test uses its own meter or month, so the tests do not depend on each other.
 */
@SpringBootTest
class MongoBillingTest extends MongoTestBase {

    @Autowired
    MongoBilling billing;

    @Autowired
    MeterReadingBucketRepository buckets;

    @Autowired
    CustomerDocumentRepository customers;

    @Autowired
    MongoTemplate mongo;

    /** Midnight India time on a day, as an exact moment. */
    static Instant indiaMidnight(int year, int month, int day) {
        return LocalDate.of(year, month, day).atStartOfDay(com.dblab.billing.domain.BillingPeriod.BILLING_ZONE).toInstant();
    }

    @Test
    void readingsGoIntoTheMonthsBucketAsExactDecimals() {
        // Meter 9 (MTR-000161, premise 8) has no readings in the sample data.
        assertThat(billing.recordReading(8, 9, indiaMidnight(2026, 11, 1), new BigDecimal("6.500"))).isEqualTo("new bucket");
        assertThat(billing.recordReading(8, 9, indiaMidnight(2026, 11, 2), new BigDecimal("7.000"))).isEqualTo("added");

        var bucket = buckets.findByMeterIdAndMonth(9, "2026-11").orElseThrow();
        assertThat(bucket.readingCount()).isEqualTo(2);
        assertThat(bucket.totalKwh()).isEqualByComparingTo("13.500");
        // The stored value, read without any mapping: a Decimal128, as Stage 5's validator demands.
        Document raw = mongo.getCollection("meter_readings").find(new Document("meter_id", 9).append("month", "2026-11")).first();
        assertThat(raw).isNotNull();
        assertThat(raw.get("total_kwh")).isInstanceOf(Decimal128.class);
    }

    @Test
    void theSameReadingSentTwiceIsStoredOnce() {
        Instant at = indiaMidnight(2026, 12, 1);
        assertThat(billing.recordReading(8, 9, at, new BigDecimal("6.000"))).isEqualTo("new bucket");
        assertThat(billing.recordReading(8, 9, at, new BigDecimal("6.000"))).isEqualTo("duplicate");
        assertThat(buckets.findByMeterIdAndMonth(9, "2026-12").orElseThrow().readingCount()).isEqualTo(1);
    }

    @Test
    void theOctoberBillEqualsPostgresql() {
        // PostgreSQL's calculate_bill(1, October) gives 3507.00.
        assertThat(billing.produceBill(1, YearMonth.of(2026, 10))).isEqualByComparingTo("3507.00");
    }

    @Test
    void aFailedBillRollsBackTheReadingsToo() {
        YearMonth march = YearMonth.of(2027, 3);
        billing.produceBill(8, march);                                  // March is already billed
        List<ReadingIn> readings = List.of(new ReadingIn(9, indiaMidnight(2027, 3, 1), new BigDecimal("9.000")));

        assertThatThrownBy(() -> billing.recordReadingsAndBill(8, readings, march))
                .isInstanceOf(DuplicateKeyException.class);               // Spring's name for MongoDB error 11000
        assertThat(buckets.findByMeterIdAndMonth(9, "2027-03")).isEmpty();   // rolled back with the bill
    }

    @Test
    void readingsAndBillAreSavedTogether() {
        List<ReadingIn> readings = List.of(
                new ReadingIn(9, indiaMidnight(2027, 2, 1), new BigDecimal("10.000")),
                new ReadingIn(9, indiaMidnight(2027, 2, 2), new BigDecimal("10.000")),
                new ReadingIn(9, indiaMidnight(2027, 2, 3), new BigDecimal("10.000")));
        // 30 kWh at 6.50, plus 28 days at 2.00.
        assertThat(billing.recordReadingsAndBill(8, readings, YearMonth.of(2027, 2))).isEqualByComparingTo("251.00");
        assertThat(buckets.findByMeterIdAndMonth(9, "2027-02").orElseThrow().readingCount()).isEqualTo(3);
    }

    @Test
    void aQueryDerivedFromTheMethodNameLooksInsideTheLists() {
        CustomerDocument owner = customers.findByPremisesMetersSerialNumber("MTR-000171").orElseThrow();
        assertThat(owner.name()).isEqualTo("Green Leaf Cafe");
        assertThat(owner.premises()).hasSize(1);
        assertThat(owner.premises().get(0).meters()).extracting(CustomerDocument.Meter::serialNumber)
                .containsExactly("MTR-000170", "MTR-000171");
    }

    /** A document class with a BigDecimal and no targetType: how Spring Data MongoDB stores it by default. */
    @org.springframework.data.mongodb.core.mapping.Document("stage6_probe")
    record Probe(@Id String id, BigDecimal value) {
    }

    @Test
    void springDataMongodb5StoresABigDecimalAsDecimal128EvenWithoutTheAnnotation() {
        // Spring Data MongoDB 4 (Spring Boot 3) stored a BigDecimal as text unless told otherwise; version 5
        // (Spring Boot 4) stores Decimal128. Most examples on the web, and many AI answers, still describe the old
        // default: the lab's documents therefore say targetType = DECIMAL128 explicitly, whatever the version.
        Probe saved = mongo.insert(new Probe(null, new BigDecimal("7.500")));
        Document raw = mongo.getCollection("stage6_probe").find(new Document("_id", new org.bson.types.ObjectId(saved.id()))).first();
        assertThat(raw).isNotNull();
        assertThat(raw.get("value")).isInstanceOf(Decimal128.class).isEqualTo(new Decimal128(new BigDecimal("7.500")));
    }
}