-- Stage 1: the database function that calculates a bill's amount.
-- Run as lab_owner, after 01-tables.sql:  psql ... -v ON_ERROR_STOP=1 -f /sql/03-calculate-bill.sql
-- In Stage 2 it becomes a Flyway migration, and Java calls it the way 2013 code called a package procedure.
--
-- The amount for a premise and a period is:
--   (total kWh of all the premise's meters in the period) x (tariff rate per kWh)
--   + (number of days in the period) x (tariff standing charge per day),
-- rounded to 2 decimal places. Days are India days: the period starts at midnight India time on
-- period_start and ends at midnight India time after period_end (period_end is the last day billed).

CREATE OR REPLACE FUNCTION calculate_bill(
    p_premise_id    bigint,     -- "p_" marks parameters, so they never clash with column names
    p_period_start  date,
    p_period_end    date
)
RETURNS numeric
LANGUAGE plpgsql
STABLE                          -- reads the database but changes nothing
AS $$
DECLARE
    v_rate      numeric;        -- "v_" marks local variables
    v_standing  numeric;
    v_kwh       numeric;
    v_days      integer;
BEGIN
    IF p_period_end < p_period_start THEN
        -- The counterpart of Oracle's RAISE_APPLICATION_ERROR. % is replaced by the next value.
        RAISE EXCEPTION 'period end % is before period start %', p_period_end, p_period_start
            USING ERRCODE = 'invalid_parameter_value';
    END IF;

    -- The premise's tariff. Unlike Oracle, a plain SELECT ... INTO that finds no row does not
    -- raise NO_DATA_FOUND; it leaves the variables NULL and sets FOUND to false.
    SELECT t.rate_per_kwh, t.standing_charge
      INTO v_rate, v_standing
      FROM premise p
      JOIN tariff t ON t.code = p.tariff_code
     WHERE p.id = p_premise_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'premise % does not exist', p_premise_id
            USING ERRCODE = 'no_data_found';
    END IF;

    -- Total kWh of all the premise's meters, for readings inside the period (India days).
    -- COALESCE turns "no readings" (a NULL sum) into 0.
    SELECT coalesce(sum(r.kwh), 0)
      INTO v_kwh
      FROM meter m
      JOIN meter_reading r ON r.meter_id = m.id
     WHERE m.premise_id = p_premise_id
       AND r.read_at >= p_period_start::timestamp AT TIME ZONE 'Asia/Kolkata'
       AND r.read_at <  (p_period_end + 1)::timestamp AT TIME ZONE 'Asia/Kolkata';

    -- Subtracting two dates gives a whole number of days, as in Oracle.
    v_days := p_period_end - p_period_start + 1;

    RETURN round(v_kwh * v_rate + v_days * v_standing, 2);
END;
$$;

COMMENT ON FUNCTION calculate_bill(bigint, date, date)
    IS 'Amount of a bill: kWh in the period x rate + days x standing charge, India days';