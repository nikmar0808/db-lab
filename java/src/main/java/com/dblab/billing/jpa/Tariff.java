package com.dblab.billing.jpa;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.math.BigDecimal;

/** The table tariff. Its key is its code, a natural key, so there is no @GeneratedValue. */
@Entity
public class Tariff {

    @Id
    private String code;

    private String description;

    private BigDecimal ratePerKwh;

    private BigDecimal standingCharge;

    protected Tariff() {
    }

    public String getCode() {
        return code;
    }

    public BigDecimal getRatePerKwh() {
        return ratePerKwh;
    }

    public BigDecimal getStandingCharge() {
        return standingCharge;
    }
}