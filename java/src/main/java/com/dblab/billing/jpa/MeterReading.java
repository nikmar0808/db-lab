package com.dblab.billing.jpa;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** The table meter_reading. OffsetDateTime is an exact moment, like the column type timestamptz. */
@Entity
public class MeterReading {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    private Meter meter;

    private OffsetDateTime readAt;

    private BigDecimal kwh;

    protected MeterReading() {
    }

    public MeterReading(Meter meter, OffsetDateTime readAt, BigDecimal kwh) {
        this.meter = meter;
        this.readAt = readAt;
        this.kwh = kwh;
    }

    public Long getId() {
        return id;
    }

    public OffsetDateTime getReadAt() {
        return readAt;
    }

    public BigDecimal getKwh() {
        return kwh;
    }
}