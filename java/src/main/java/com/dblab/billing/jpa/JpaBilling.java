package com.dblab.billing.jpa;

import com.dblab.billing.domain.BillCalculator;
import com.dblab.billing.domain.BillResult;
import com.dblab.billing.domain.BillingPeriod;
import com.dblab.billing.domain.BillingService;
import com.dblab.billing.domain.ReadingInput;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Way 3: Spring Data JPA with Hibernate. Objects in, objects out; Hibernate writes the SQL (Stage 2, lesson A7).
 *
 * <p>Only the total kWh is a hand-written query (JPQL). Everything else is loading and saving objects.
 */
@Service("jpaBilling")
public class JpaBilling implements BillingService {

    private final PremiseRepository premises;
    private final MeterRepository meters;
    private final MeterReadingRepository readings;
    private final BillRepository bills;

    public JpaBilling(PremiseRepository premises, MeterRepository meters,
                      MeterReadingRepository readings, BillRepository bills) {
        this.premises = premises;
        this.meters = meters;
        this.readings = readings;
        this.bills = bills;
    }

    @Override
    @Transactional
    public BillResult recordReadingsAndBill(long premiseId, List<ReadingInput> input, YearMonth month) {
        BillingPeriod period = new BillingPeriod(month);

        for (ReadingInput r : input) {
            // getReferenceById returns a stand-in for the meter without reading it: only its id is needed
            // to fill meter_reading.meter_id.
            Meter meter = meters.getReferenceById(r.meterId());
            readings.save(new MeterReading(meter, r.readAt(), r.kwh()));
        }

        Premise premise = premises.findById(premiseId)
                .orElseThrow(() -> new IllegalArgumentException("premise " + premiseId + " does not exist"));
        Tariff tariff = premise.getTariff();                // lazy: read now, inside the transaction

        // Before running this query, Hibernate sends the readings saved above to the database (a "flush"),
        // so the total includes them.
        BigDecimal kwh = readings.totalKwh(premiseId, period.from(), period.to());

        BigDecimal amount = BillCalculator.amount(kwh, tariff.getRatePerKwh(), tariff.getStandingCharge(), period.days());
        Bill bill = bills.save(new Bill(premise.getCustomer(), premise, period.firstDay(), period.lastDay(), amount));

        return new BillResult(bill.getId(), premiseId, period.firstDay(), period.lastDay(), amount);
    }
}