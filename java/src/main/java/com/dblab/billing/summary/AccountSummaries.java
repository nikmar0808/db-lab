package com.dblab.billing.summary;

import com.dblab.billing.jpa.Customer;
import com.dblab.billing.jpa.CustomerRepository;
import com.dblab.billing.jpa.Meter;
import com.dblab.billing.jpa.MeterReading;
import com.dblab.billing.jpa.MeterReadingRepository;
import com.dblab.billing.jpa.MeterRepository;
import com.dblab.billing.jpa.Premise;
import com.dblab.billing.summary.AccountSummary.MeterLine;
import com.dblab.billing.summary.AccountSummary.PremiseLine;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The account summary for a list of customers, built three ways (Stage 4, lesson A5). All three return exactly
 * the same result; they differ in how many statements they send.
 */
@Service
public class AccountSummaries {

    private final CustomerRepository customers;
    private final MeterRepository meters;
    private final MeterReadingRepository readings;
    private final JdbcClient jdbc;

    public AccountSummaries(CustomerRepository customers, MeterRepository meters,
                            MeterReadingRepository readings, JdbcClient jdbc) {
        this.customers = customers;
        this.meters = meters;
        this.readings = readings;
        this.jdbc = jdbc;
    }

    /**
     * Way 1, the natural JPA code, and an N+1 problem at three levels: 1 query for the customers, then one per
     * customer for its premises, one per premise for its meters, and one per meter for its latest reading.
     * readOnly = true: a transaction that only reads; Hibernate also skips checking the objects for changes.
     */
    @Transactional(readOnly = true)
    public List<AccountSummary> naive(List<Long> customerIds) {
        List<AccountSummary> result = new ArrayList<>();
        for (Customer customer : sortedById(customers.findAllById(customerIds), Customer::getId)) {
            List<PremiseLine> premiseLines = new ArrayList<>();
            for (Premise premise : sortedById(customer.getPremises(), Premise::getId)) {        // lazy list: 1 query
                List<MeterLine> meterLines = new ArrayList<>();
                for (Meter meter : sortedById(premise.getMeters(), Meter::getId)) {             // lazy list: 1 query
                    MeterReading latest = readings.findFirstByMeterIdOrderByReadAtDesc(meter.getId())   // 1 query
                            .orElse(null);
                    meterLines.add(meterLine(meter, latest));
                }
                premiseLines.add(new PremiseLine(premise.getId(), premise.getAddress(), meterLines));
            }
            result.add(new AccountSummary(customer.getId(), customer.getName(), premiseLines));
        }
        return result;
    }

    /**
     * Way 2, one query per level: customers with their premises (a fetch join), then the meters of all those
     * premises, then the latest reading of all those meters. Three queries, however many customers.
     */
    @Transactional(readOnly = true)
    public List<AccountSummary> perLevel(List<Long> customerIds) {
        List<Customer> loaded = sortedById(customers.findWithPremises(customerIds), Customer::getId);
        List<Long> premiseIds = loaded.stream()
                .flatMap(c -> c.getPremises().stream()).map(Premise::getId).toList();
        // Grouped by premise in the program, instead of touching each premise's lazy list of meters.
        Map<Long, List<Meter>> metersByPremise = meters.findByPremiseIds(premiseIds).stream()
                .collect(Collectors.groupingBy(m -> m.getPremise().getId()));
        List<Long> meterIds = metersByPremise.values().stream().flatMap(List::stream).map(Meter::getId).toList();
        Map<Long, MeterReading> latestByMeter = readings.findLatestForMeters(meterIds).stream()
                .collect(Collectors.toMap(r -> r.getMeter().getId(), Function.identity()));

        List<AccountSummary> result = new ArrayList<>();
        for (Customer customer : loaded) {
            List<PremiseLine> premiseLines = new ArrayList<>();
            for (Premise premise : sortedById(customer.getPremises(), Premise::getId)) {
                List<MeterLine> meterLines = new ArrayList<>();
                for (Meter meter : sortedById(metersByPremise.getOrDefault(premise.getId(), List.of()), Meter::getId)) {
                    meterLines.add(meterLine(meter, latestByMeter.get(meter.getId())));
                }
                premiseLines.add(new PremiseLine(premise.getId(), premise.getAddress(), meterLines));
            }
            result.add(new AccountSummary(customer.getId(), customer.getName(), premiseLines));
        }
        return result;
    }

    /**
     * Way 3, one hand-written query that returns exactly what the screen shows: no entities at all. LEFT JOIN
     * LATERAL finds each meter's newest reading through the index (meter_id, read_at), and keeps meters that have
     * none. The rows come back flat, one per meter, and are grouped into the nested records here.
     */
    @Transactional(readOnly = true)
    public List<AccountSummary> oneQuery(List<Long> customerIds) {
        record Row(long customerId, String name, long premiseId, String address, Long meterId, String serial,
                   OffsetDateTime readAt, BigDecimal kwh) {
        }
        List<Row> rows = jdbc.sql("""
                        SELECT c.id, c.name, p.id, p.address, m.id, m.serial_number, r.read_at, r.kwh
                        FROM customer c
                        JOIN premise p ON p.customer_id = c.id
                        LEFT JOIN meter m ON m.premise_id = p.id
                        LEFT JOIN LATERAL (
                            SELECT read_at, kwh FROM meter_reading x
                            WHERE x.meter_id = m.id ORDER BY x.read_at DESC LIMIT 1
                        ) r ON true
                        WHERE c.id IN (:ids)
                        ORDER BY c.id, p.id, m.id""")
                .param("ids", customerIds)       // a list: Spring writes one ? per element inside IN (...)
                .query((rs, n) -> new Row(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4),
                        rs.getObject(5, Long.class), rs.getString(6),
                        rs.getObject(7, OffsetDateTime.class), rs.getBigDecimal(8)))
                .list();

        // Customer -> premise -> meters, keeping the order of the rows.
        Map<Long, Map<Long, List<Row>>> grouped = new LinkedHashMap<>();
        for (Row row : rows) {
            grouped.computeIfAbsent(row.customerId(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(row.premiseId(), k -> new ArrayList<>())
                    .add(row);
        }
        List<AccountSummary> result = new ArrayList<>();
        grouped.forEach((customerId, premises) -> {
            List<PremiseLine> premiseLines = new ArrayList<>();
            premises.forEach((premiseId, meterRows) -> {
                List<MeterLine> meterLines = meterRows.stream()
                        .filter(r -> r.meterId() != null)          // a premise with no meter yet
                        .map(r -> new MeterLine(r.meterId(), r.serial(), r.readAt(), r.kwh()))
                        .toList();
                premiseLines.add(new PremiseLine(premiseId, meterRows.get(0).address(), meterLines));
            });
            result.add(new AccountSummary(customerId, premises.values().iterator().next().get(0).name(), premiseLines));
        });
        return result;
    }

    private static MeterLine meterLine(Meter meter, MeterReading latest) {
        return latest == null
                ? new MeterLine(meter.getId(), meter.getSerialNumber(), null, null)
                : new MeterLine(meter.getId(), meter.getSerialNumber(), latest.getReadAt(), latest.getKwh());
    }

    private static <T> List<T> sortedById(List<T> items, Function<T, Long> id) {
        return items.stream().sorted(Comparator.comparing(id)).toList();
    }
}