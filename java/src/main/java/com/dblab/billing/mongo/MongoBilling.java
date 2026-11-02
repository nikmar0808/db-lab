package com.dblab.billing.mongo;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

import com.dblab.billing.domain.BillCalculator;
import com.dblab.billing.domain.BillingPeriod;
import com.mongodb.client.result.UpdateResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The main task of POC 3 against MongoDB, from Spring (Stage 6, lesson A9): record readings into the monthly
 * buckets, produce a bill, and both in one transaction. The same rules as the Python module mongo_lab.
 *
 * <p>{@link MongoTemplate} is MongoDB's counterpart of {@code JdbcClient}: it runs the filters and updates written
 * here. The repositories are used where a query can be built from a method name.
 */
@Service
public class MongoBilling {

    private final MongoTemplate mongo;
    private final MeterReadingBucketRepository buckets;
    private final CustomerDocumentRepository customers;
    private final TransactionTemplate transaction;

    public MongoBilling(MongoTemplate mongo, MongoDatabaseFactory factory,
                        MeterReadingBucketRepository buckets, CustomerDocumentRepository customers) {
        this.mongo = mongo;
        this.buckets = buckets;
        this.customers = customers;
        // A transaction manager for MongoDB, used only here. It is deliberately NOT a Spring bean: Spring Boot
        // creates its JPA transaction manager only when no other one exists, so a second bean would quietly
        // switch off the @Transactional of every PostgreSQL service in the program (lesson A9).
        this.transaction = new TransactionTemplate(new MongoTransactionManager(factory));
    }

    /** The India month of a moment, as the bucket key "2026-11". */
    static String monthOf(Instant readAt) {
        return YearMonth.from(readAt.atZone(BillingPeriod.BILLING_ZONE)).toString();
    }

    /** Adds one reading to its bucket, once only. Returns "added", "duplicate" or "new bucket". */
    public String recordReading(long premiseId, long meterId, Instant readAt, BigDecimal kwh) {
        String month = monthOf(readAt);
        Date at = Date.from(readAt);
        Decimal128 exact = new Decimal128(kwh);
        UpdateResult added = mongo.updateFirst(
                query(where("meter_id").is(meterId).and("month").is(month).and("readings.read_at").ne(at)),
                new Update().push("readings", new Document("read_at", at).append("kwh", exact))
                        .inc("reading_count", 1).inc("total_kwh", exact),
                MeterReadingBucket.class);
        if (added.getMatchedCount() == 1) {
            return "added";
        }
        if (buckets.findByMeterIdAndMonth(meterId, month).isPresent()) {
            return "duplicate";
        }
        mongo.insert(new MeterReadingBucket(null, meterId, premiseId, month,
                List.of(new MeterReadingBucket.Reading(readAt, kwh)), 1, kwh));
        return "new bucket";
    }

    /** Totals the premise's buckets for the month, applies the tariff, saves the bill; returns its amount. */
    public BigDecimal produceBill(long premiseId, YearMonth month) {
        CustomerDocument customer = mongo.findOne(query(where("premises.premise_id").is(premiseId)), CustomerDocument.class);
        if (customer == null) {
            throw new IllegalArgumentException("premise " + premiseId + " does not exist");
        }
        CustomerDocument.Premise premise = customer.premises().stream()
                .filter(p -> p.premiseId() == premiseId).findFirst().orElseThrow();
        Document tariff = mongo.findById(premise.tariffCode(), Document.class, "tariffs");
        if (tariff == null) {
            throw new IllegalStateException("tariff " + premise.tariffCode() + " does not exist");
        }
        BigDecimal rate = tariff.get("rate_per_kwh", Decimal128.class).bigDecimalValue();
        BigDecimal standing = tariff.get("standing_charge", Decimal128.class).bigDecimalValue();
        BigDecimal kwh = buckets.findByPremiseIdAndMonth(premiseId, month.toString()).stream()
                .map(MeterReadingBucket::totalKwh).reduce(BigDecimal.ZERO, BigDecimal::add);
        BillingPeriod period = new BillingPeriod(month);
        BigDecimal amount = BillCalculator.amount(kwh, rate, standing, period.days());
        Document bill = new Document("schema_version", 2)
                .append("customer_id", customer.id()).append("premise_id", premiseId)
                .append("period_start", Date.from(period.firstDay().atStartOfDay().toInstant(ZoneOffset.UTC)))
                .append("period_end", Date.from(period.lastDay().atStartOfDay().toInstant(ZoneOffset.UTC)))
                .append("kwh", new Decimal128(kwh))
                .append("lines", List.of(
                        new Document("kind", "energy").append("quantity", new Decimal128(kwh))
                                .append("rate", new Decimal128(rate))
                                .append("amount", new Decimal128(kwh.multiply(rate).setScale(2, RoundingMode.HALF_UP))),
                        new Document("kind", "standing charge").append("quantity", period.days())
                                .append("rate", new Decimal128(standing))
                                .append("amount", new Decimal128(standing.multiply(BigDecimal.valueOf(period.days()))))))
                .append("amount", new Decimal128(amount))
                .append("status", "DRAFT");
        mongo.insert(bill, "bills");      // the unique index on (premise_id, period_start) refuses a second one
        return amount;
    }

    /** One reading to record, as the main task receives it. */
    public record ReadingIn(long meterId, Instant readAt, BigDecimal kwh) {
    }

    /**
     * Records the readings and produces the month's bill in one MongoDB transaction: all of it, or none of it.
     * Inside execute(...), every MongoTemplate and repository call joins the transaction's session.
     */
    public BigDecimal recordReadingsAndBill(long premiseId, List<ReadingIn> readings, YearMonth month) {
        return transaction.execute(status -> {
            for (ReadingIn r : readings) {
                recordReading(premiseId, r.meterId(), r.readAt(), r.kwh());
            }
            return produceBill(premiseId, month);
        });
    }

    /** The account summary's data, in one query. */
    public CustomerDocument customer(long customerId) {
        return customers.findById(customerId).orElseThrow();
    }
}