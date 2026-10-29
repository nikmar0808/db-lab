-- Stage 1: a small, made-up data set for learning (a few hundred rows).
-- Run as lab_owner, as one transaction, after 01-tables.sql:
--   psql ... --single-transaction -v ON_ERROR_STOP=1 -f /sql/02-sample-data.sql
-- Rows are linked by their natural keys (e-mail, address, serial number), never by assumed id numbers,
-- so the script does not depend on which numbers the identity columns hand out. Each INSERT also
-- lists its rows in a fixed order, so on a fresh database the numbers follow that order
-- (customer 1 is A. Rao, premise 1 is 12 MG Road, meter 1 is MTR-000088), which the exercises use.

-- Two tariffs. Prices are made up.
INSERT INTO tariff (code, description, rate_per_kwh, standing_charge) VALUES
    ('DOM-1', 'Domestic, single rate',   6.5000, 2.00),
    ('COM-1', 'Commercial, single rate', 8.7500, 5.00);

-- Eight customers.
INSERT INTO customer (name, email) VALUES
    ('A. Rao',             'a.rao@example.com'),
    ('B. Iyer',            'b.iyer@example.com'),
    ('C. Menon',           'c.menon@example.com'),
    ('D. Shah',            'd.shah@example.com'),
    ('E. Nair',            'e.nair@example.com'),
    ('F. Gupta',           'f.gupta@example.com'),
    ('Green Leaf Cafe',    'accounts@greenleaf.example.com'),
    ('Northgate Printers', 'billing@northgate.example.com');

-- Ten premises. The first two customers have two premises each.
INSERT INTO premise (customer_id, address, tariff_code)
SELECT c.id, v.address, v.tariff_code
FROM (VALUES
    ( 1, 'a.rao@example.com',              '12 MG Road',          'DOM-1'),
    ( 2, 'a.rao@example.com',              '7 Lake View',         'DOM-1'),
    ( 3, 'b.iyer@example.com',             '3 Temple Street',     'DOM-1'),
    ( 4, 'b.iyer@example.com',             '18 Station Road',     'DOM-1'),
    ( 5, 'c.menon@example.com',            '44 Park Lane',        'DOM-1'),
    ( 6, 'd.shah@example.com',             '9 Hill Crescent',     'DOM-1'),
    ( 7, 'e.nair@example.com',             '21 River Walk',       'DOM-1'),
    ( 8, 'f.gupta@example.com',            '5 Market Square',     'DOM-1'),
    ( 9, 'accounts@greenleaf.example.com', '2 Church Street',     'COM-1'),
    (10, 'billing@northgate.example.com',  'Unit 4, Northgate',   'COM-1')
) AS v (n, email, address, tariff_code)
JOIN customer c ON c.email = v.email
ORDER BY v.n;   -- insert in the listed order, so the identity numbers follow it

-- Twelve meters. Two premises have a second meter; one meter was installed mid-October,
-- and one was installed on 30 October and has no readings yet.
INSERT INTO meter (premise_id, serial_number, installed_on)
SELECT p.id, v.serial_number, v.installed_on
FROM (VALUES
    ( 1, '12 MG Road',        'MTR-000088', DATE '2024-03-01'),
    ( 2, '12 MG Road',        'MTR-000089', DATE '2025-06-15'),
    ( 3, '7 Lake View',       'MTR-000101', DATE '2023-11-20'),
    ( 4, '3 Temple Street',   'MTR-000117', DATE '2024-01-10'),
    ( 5, '18 Station Road',   'MTR-000123', DATE '2026-10-15'),
    ( 6, '44 Park Lane',      'MTR-000130', DATE '2022-08-05'),
    ( 7, '9 Hill Crescent',   'MTR-000142', DATE '2025-02-28'),
    ( 8, '21 River Walk',     'MTR-000156', DATE '2024-09-09'),
    ( 9, '5 Market Square',   'MTR-000161', DATE '2026-10-30'),
    (10, '2 Church Street',   'MTR-000170', DATE '2021-05-01'),
    (11, '2 Church Street',   'MTR-000171', DATE '2021-05-01'),
    (12, 'Unit 4, Northgate', 'MTR-000185', DATE '2023-03-14')
) AS v (n, address, serial_number, installed_on)
JOIN premise p ON p.address = v.address
ORDER BY v.n;   -- insert in the listed order, so the identity numbers follow it

-- One reading per meter per day, for September and October 2026, from the day each meter was
-- installed. Each day starts at midnight India time, so read_at is midnight in Asia/Kolkata,
-- stored as the matching UTC moment. The kWh values come from a fixed formula, not from random
-- numbers, so every run produces the same data. Commercial premises use more energy.
-- The meter installed on 30 October has no readings: its first one is not due until 1 November
-- in this made-up world.
INSERT INTO meter_reading (meter_id, read_at, kwh)
SELECT m.id,
       d.day::timestamp AT TIME ZONE 'Asia/Kolkata',
       CASE WHEN p.tariff_code = 'COM-1'
            THEN 25 + (m.id * 5 + extract(day FROM d.day)::int) % 13
            ELSE 6 + ((m.id * 7 + extract(day FROM d.day)::int * 3) % 11) * 0.5
       END
FROM meter m
JOIN premise p ON p.id = m.premise_id
CROSS JOIN generate_series(DATE '2026-09-01', DATE '2026-10-31', INTERVAL '1 day') AS d (day)
WHERE d.day >= m.installed_on
  AND m.serial_number <> 'MTR-000161'
ORDER BY m.id, d.day;