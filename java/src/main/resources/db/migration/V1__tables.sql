-- Migration 1: the six tables of the utility data set, with their keys and rules.
-- Written in Stage 1 as sql/01-tables.sql; moved here in Stage 2. Flyway runs it once, as lab_owner, inside a
-- transaction of its own, which is why the file has no BEGIN or COMMIT. Never edit it after it has run:
-- a change to the tables is a new migration, V3 and onwards.
-- Names: lower case with underscores; constraints named <table>_<what>_<kind>, where kind is
-- pk (primary key), fk (foreign key), uk (unique), ck (check); indexes end in _ix.

-- A customer of the utility: the person or company that receives bills.
CREATE TABLE customer (
    id          bigint      GENERATED ALWAYS AS IDENTITY,   -- numbered by the database; cannot be supplied
    name        text        NOT NULL,
    email       text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),          -- the moment the row was inserted
    CONSTRAINT customer_pk PRIMARY KEY (id),
    CONSTRAINT customer_email_uk UNIQUE (email),
    CONSTRAINT customer_name_length_ck CHECK (length(name) BETWEEN 1 AND 200),
    CONSTRAINT customer_email_format_ck CHECK (email LIKE '%_@_%')
);
COMMENT ON TABLE customer IS 'A person or company that receives bills';

-- A tariff: the price list applied to a premise. Its code is its key, so premises refer to it by code.
CREATE TABLE tariff (
    code             text         NOT NULL,
    description      text         NOT NULL,
    rate_per_kwh     numeric(8,4) NOT NULL,                  -- price of one kWh
    standing_charge  numeric(8,2) NOT NULL,                  -- fixed price per day
    CONSTRAINT tariff_pk PRIMARY KEY (code),
    CONSTRAINT tariff_code_format_ck CHECK (code ~ '^[A-Z]{3}-[0-9]+$'),   -- e.g. DOM-1
    CONSTRAINT tariff_rate_ck CHECK (rate_per_kwh > 0),
    CONSTRAINT tariff_standing_charge_ck CHECK (standing_charge >= 0)
);
COMMENT ON TABLE tariff IS 'Price list: rate per kWh and standing charge per day';

-- A premise: a supplied address, belonging to one customer and billed on one tariff.
CREATE TABLE premise (
    id           bigint GENERATED ALWAYS AS IDENTITY,
    customer_id  bigint NOT NULL,
    address      text   NOT NULL,
    tariff_code  text   NOT NULL,
    CONSTRAINT premise_pk PRIMARY KEY (id),
    CONSTRAINT premise_customer_fk FOREIGN KEY (customer_id) REFERENCES customer (id),
    -- If a tariff code is renamed, every premise using it follows automatically.
    CONSTRAINT premise_tariff_fk FOREIGN KEY (tariff_code) REFERENCES tariff (code) ON UPDATE CASCADE
);
-- PostgreSQL, like Oracle, does not index the referencing side of a foreign key by itself.
CREATE INDEX premise_customer_ix ON premise (customer_id);
CREATE INDEX premise_tariff_ix ON premise (tariff_code);
COMMENT ON TABLE premise IS 'A supplied address: one customer, one tariff';

-- A meter installed at a premise. Its serial number is printed on the device and is unique.
CREATE TABLE meter (
    id             bigint GENERATED ALWAYS AS IDENTITY,
    premise_id     bigint NOT NULL,
    serial_number  text   NOT NULL,
    installed_on   date   NOT NULL,                          -- a calendar day, no time of day
    CONSTRAINT meter_pk PRIMARY KEY (id),
    CONSTRAINT meter_premise_fk FOREIGN KEY (premise_id) REFERENCES premise (id),
    CONSTRAINT meter_serial_number_uk UNIQUE (serial_number),
    CONSTRAINT meter_serial_number_format_ck CHECK (serial_number ~ '^MTR-[0-9]{6}$')   -- e.g. MTR-000088
);
CREATE INDEX meter_premise_ix ON meter (premise_id);
COMMENT ON TABLE meter IS 'A meter installed at a premise';

-- One reading: the energy a meter recorded for the day that starts at read_at.
CREATE TABLE meter_reading (
    id        bigint        GENERATED ALWAYS AS IDENTITY,
    meter_id  bigint        NOT NULL,
    read_at   timestamptz   NOT NULL,                        -- an exact moment, stored as UTC
    kwh       numeric(10,3) NOT NULL,
    CONSTRAINT meter_reading_pk PRIMARY KEY (id),
    CONSTRAINT meter_reading_meter_fk FOREIGN KEY (meter_id) REFERENCES meter (id),
    -- One reading per meter per moment. The index behind this rule starts with meter_id,
    -- so it also serves the foreign key: no separate index on meter_id is needed.
    CONSTRAINT meter_reading_meter_time_uk UNIQUE (meter_id, read_at),
    CONSTRAINT meter_reading_kwh_ck CHECK (kwh >= 0)
);
COMMENT ON TABLE meter_reading IS 'Energy recorded by a meter for the day starting at read_at';

-- A bill for one premise and one period. customer_id records who was billed at the time,
-- which stays true even if the premise later passes to another customer.
CREATE TABLE bill (
    id            bigint        GENERATED ALWAYS AS IDENTITY,
    customer_id   bigint        NOT NULL,
    premise_id    bigint        NOT NULL,
    period_start  date          NOT NULL,
    period_end    date          NOT NULL,                    -- inclusive: the last day billed
    amount        numeric(12,2) NOT NULL,
    status        text          NOT NULL DEFAULT 'DRAFT',
    CONSTRAINT bill_pk PRIMARY KEY (id),
    CONSTRAINT bill_customer_fk FOREIGN KEY (customer_id) REFERENCES customer (id),
    CONSTRAINT bill_premise_fk FOREIGN KEY (premise_id) REFERENCES premise (id),
    CONSTRAINT bill_period_ck CHECK (period_end >= period_start),
    CONSTRAINT bill_amount_ck CHECK (amount >= 0),
    CONSTRAINT bill_status_ck CHECK (status IN ('DRAFT', 'ISSUED', 'PAID')),
    CONSTRAINT bill_premise_period_uk UNIQUE (premise_id, period_start)   -- one bill per premise per period
);
CREATE INDEX bill_customer_ix ON bill (customer_id);
COMMENT ON TABLE bill IS 'A bill for one premise and one period';