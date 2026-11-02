// Stage 5, lessons A5 to A7: the lab's sample data as documents, in the shapes chosen for the two tasks,
// with a validator on each collection. Run as lab_app inside the MongoDB container (local or Atlas):
//   mongosh "<lab_app address>" --quiet -f /practice/51-load-sample.js
// The same made-up data as sql/02-sample-data.sql: 8 customers, 10 premises, 12 meters and 627 daily
// readings for September and October 2026, produced from the same formulas, so every total can be checked
// against PostgreSQL. Safe to run again: it drops and rebuilds its five collections.
//
//   tariffs          one document per tariff; referenced by code (shared by many premises)
//   customers        one document per customer, with its premises and each premise's meters inside (embedded)
//   meter_readings   one document per meter per month, with that month's readings inside (the bucket pattern)
//   readings_flat    one document per reading: the alternative shape, kept for comparison (no validator)
//   bills            empty here; filled by the report in 54-reports.js

// ------------------------------------------------------------------ the source data, as in 02-sample-data.sql
const TARIFFS = [
  { _id: 'DOM-1', description: 'Domestic, single rate', rate_per_kwh: '6.5000', standing_charge: '2.00' },
  { _id: 'COM-1', description: 'Commercial, single rate', rate_per_kwh: '8.7500', standing_charge: '5.00' }
];
const CUSTOMERS = [   // [id, name, email]: the ids PostgreSQL gave them on a fresh database
  [1, 'A. Rao', 'a.rao@example.com'], [2, 'B. Iyer', 'b.iyer@example.com'], [3, 'C. Menon', 'c.menon@example.com'],
  [4, 'D. Shah', 'd.shah@example.com'], [5, 'E. Nair', 'e.nair@example.com'], [6, 'F. Gupta', 'f.gupta@example.com'],
  [7, 'Green Leaf Cafe', 'accounts@greenleaf.example.com'], [8, 'Northgate Printers', 'billing@northgate.example.com']
];
const PREMISES = [    // [id, customer id, address, tariff]
  [1, 1, '12 MG Road', 'DOM-1'], [2, 1, '7 Lake View', 'DOM-1'], [3, 2, '3 Temple Street', 'DOM-1'],
  [4, 2, '18 Station Road', 'DOM-1'], [5, 3, '44 Park Lane', 'DOM-1'], [6, 4, '9 Hill Crescent', 'DOM-1'],
  [7, 5, '21 River Walk', 'DOM-1'], [8, 6, '5 Market Square', 'DOM-1'], [9, 7, '2 Church Street', 'COM-1'],
  [10, 8, 'Unit 4, Northgate', 'COM-1']
];
const METERS = [      // [id, premise id, serial number, installed on]
  [1, 1, 'MTR-000088', '2024-03-01'], [2, 1, 'MTR-000089', '2025-06-15'], [3, 2, 'MTR-000101', '2023-11-20'],
  [4, 3, 'MTR-000117', '2024-01-10'], [5, 4, 'MTR-000123', '2026-10-15'], [6, 5, 'MTR-000130', '2022-08-05'],
  [7, 6, 'MTR-000142', '2025-02-28'], [8, 7, 'MTR-000156', '2024-09-09'], [9, 8, 'MTR-000161', '2026-10-30'],
  [10, 9, 'MTR-000170', '2021-05-01'], [11, 9, 'MTR-000171', '2021-05-01'], [12, 10, 'MTR-000185', '2023-03-14']
];

// A calendar day as a BSON date at midnight UTC: how a day with no time (PostgreSQL's date) is usually stored.
const day = (iso) => new Date(iso + 'T00:00:00Z');
// Midnight India time on that day, as an exact moment: what read_at holds in PostgreSQL (18:30 UTC the day before).
const indiaMidnight = (y, m, d) => new Date(Date.UTC(y, m - 1, d) - 330 * 60 * 1000);

// kWh for one meter on one day of the month, the formula of 02-sample-data.sql, as text with 3 decimals.
function kwh(meterId, dayOfMonth, tariff) {
  const v = tariff === 'COM-1'
    ? 25 + (meterId * 5 + dayOfMonth) % 13
    : 6 + ((meterId * 7 + dayOfMonth * 3) % 11) * 0.5;
  return v.toFixed(3);
}

// Every reading, in the order the SQL inserts them: by meter, then by day.
function allReadings() {
  const tariffOf = Object.fromEntries(PREMISES.map(([id, , , t]) => [id, t]));
  const out = [];
  for (const [meterId, premiseId, serial, installedOn] of METERS) {
    if (serial === 'MTR-000161') continue;           // installed 30 October: no readings yet
    for (const [month, days] of [[9, 30], [10, 31]]) {
      for (let d = 1; d <= days; d++) {
        const iso = `2026-${String(month).padStart(2, '0')}-${String(d).padStart(2, '0')}`;
        if (iso < installedOn) continue;             // no readings before the meter was installed
        out.push({ meterId, premiseId, month: iso.slice(0, 7), readAt: indiaMidnight(2026, month, d),
                   kwh: kwh(meterId, d, tariffOf[premiseId]) });
      }
    }
  }
  return out;
}

// ------------------------------------------------------------------ the collections, with their validators
for (const name of ['tariffs', 'customers', 'meter_readings', 'readings_flat', 'bills']) {
  db.getCollection(name).drop();
}

// A validator is a rule written in JSON Schema that every insert and update must pass (lesson A6). These are
// the document counterparts of Stage 1's NOT NULL, CHECK and type rules. bsonType names the stored type:
// "decimal" is Decimal128, MongoDB's exact decimal type, used for money and metered quantities as numeric is.
db.createCollection('tariffs', { validator: { $jsonSchema: {
  bsonType: 'object',
  required: ['_id', 'description', 'rate_per_kwh', 'standing_charge'],
  properties: {
    _id: { bsonType: 'string', pattern: '^[A-Z]{3}-[0-9]+$' },            // e.g. DOM-1, as tariff_code_format_ck
    description: { bsonType: 'string' },
    rate_per_kwh: { bsonType: 'decimal', minimum: 0, exclusiveMinimum: true },
    standing_charge: { bsonType: 'decimal', minimum: 0 }
  }
} } });

db.createCollection('customers', { validator: { $jsonSchema: {
  bsonType: 'object',
  required: ['_id', 'name', 'email', 'created_at', 'premises'],
  properties: {
    _id: { bsonType: ['int', 'long'] },
    name: { bsonType: 'string', minLength: 1, maxLength: 200 },
    email: { bsonType: 'string', pattern: '^.+@.+$' },
    created_at: { bsonType: 'date' },
    premises: {
      bsonType: 'array',
      items: {
        bsonType: 'object',
        required: ['premise_id', 'address', 'tariff_code', 'meters'],
        properties: {
          premise_id: { bsonType: ['int', 'long'] },
          address: { bsonType: 'string' },
          tariff_code: { bsonType: 'string' },
          meters: {
            bsonType: 'array',
            items: {
              bsonType: 'object',
              required: ['meter_id', 'serial_number', 'installed_on'],
              properties: {
                meter_id: { bsonType: ['int', 'long'] },
                serial_number: { bsonType: 'string', pattern: '^MTR-[0-9]{6}$' },
                installed_on: { bsonType: 'date' }
              }
            }
          }
        }
      }
    }
  }
} } });

db.createCollection('meter_readings', { validator: { $jsonSchema: {
  bsonType: 'object',
  required: ['meter_id', 'premise_id', 'month', 'readings', 'reading_count', 'total_kwh'],
  properties: {
    meter_id: { bsonType: ['int', 'long'] },
    premise_id: { bsonType: ['int', 'long'] },
    month: { bsonType: 'string', pattern: '^[0-9]{4}-[0-9]{2}$' },        // the India calendar month, e.g. 2026-10
    readings: {
      bsonType: 'array',
      maxItems: 31,                                                       // a bucket can never grow past a month
      items: {
        bsonType: 'object',
        required: ['read_at', 'kwh'],
        additionalProperties: false,                                      // no other fields inside a reading
        properties: {
          read_at: { bsonType: 'date' },
          kwh: { bsonType: 'decimal', minimum: 0 }                        // as meter_reading_kwh_ck
        }
      }
    },
    reading_count: { bsonType: ['int', 'long'], minimum: 0 },
    total_kwh: { bsonType: 'decimal', minimum: 0 }
  }
} } });

db.createCollection('readings_flat');    // no validator: the comparison shape only
db.createCollection('bills');

// ------------------------------------------------------------------ fill them
db.tariffs.insertMany(TARIFFS.map((t) => ({
  _id: t._id, description: t.description,
  rate_per_kwh: NumberDecimal(t.rate_per_kwh), standing_charge: NumberDecimal(t.standing_charge)
})));

// One customer document holds the whole account: its premises, and each premise's meters (lesson A5).
db.customers.insertMany(CUSTOMERS.map(([id, name, email]) => ({
  _id: id, name, email, created_at: new Date('2026-08-01T04:30:00Z'),
  premises: PREMISES.filter((p) => p[1] === id).map(([premiseId, , address, tariffCode]) => ({
    premise_id: premiseId, address, tariff_code: tariffCode,
    meters: METERS.filter((m) => m[1] === premiseId).map(([meterId, , serial, installedOn]) => ({
      meter_id: meterId, serial_number: serial, installed_on: day(installedOn)
    }))
  }))
})));

const readings = allReadings();

// The bucket pattern (lesson A7): one document per meter per month, with a count and a running total kept
// beside the list, so that a bill reads one small document and adds nothing up.
const buckets = new Map();
for (const r of readings) {
  const key = `${r.meterId}|${r.month}`;
  if (!buckets.has(key)) {
    buckets.set(key, { meter_id: r.meterId, premise_id: r.premiseId, month: r.month, readings: [], total: 0 });
  }
  const b = buckets.get(key);
  b.readings.push({ read_at: r.readAt, kwh: NumberDecimal(r.kwh) });
  b.total += Number(r.kwh);
}
db.meter_readings.insertMany([...buckets.values()].map((b) => ({
  meter_id: b.meter_id, premise_id: b.premise_id, month: b.month, readings: b.readings,
  reading_count: b.readings.length, total_kwh: NumberDecimal(b.total.toFixed(3))
})));

// The flat shape: one small document per reading, like one row of meter_reading.
db.readings_flat.insertMany(readings.map((r) => ({ meter_id: r.meterId, read_at: r.readAt, kwh: NumberDecimal(r.kwh) })));

// ------------------------------------------------------------------ the indexes the two tasks need
// One bucket per meter per month: the counterpart of meter_reading_meter_time_uk, and what an upsert relies on.
db.meter_readings.createIndex({ meter_id: 1, month: 1 }, { unique: true });
// A meter's serial number is unique across every customer's premises: an index on a field inside lists.
db.customers.createIndex({ 'premises.meters.serial_number': 1 }, { unique: true });
db.customers.createIndex({ email: 1 }, { unique: true });
db.readings_flat.createIndex({ meter_id: 1, read_at: 1 }, { unique: true });
db.bills.createIndex({ premise_id: 1, period_start: 1 }, { unique: true });

// ------------------------------------------------------------------ check
const sumFlat = db.readings_flat.aggregate([{ $group: { _id: null, t: { $sum: '$kwh' } } }]).toArray()[0].t;
const sumBuckets = db.meter_readings.aggregate([{ $group: { _id: null, t: { $sum: '$total_kwh' } } }]).toArray()[0].t;
print('tariffs:', db.tariffs.countDocuments({}),
      '| customers:', db.customers.countDocuments({}),
      '| premises:', db.customers.aggregate([{ $unwind: '$premises' }, { $count: 'n' }]).toArray()[0].n,
      '| meters:', db.customers.aggregate([{ $unwind: '$premises' }, { $unwind: '$premises.meters' }, { $count: 'n' }]).toArray()[0].n);
print('meter_readings buckets:', db.meter_readings.countDocuments({}),
      '| readings inside them:', db.meter_readings.aggregate([{ $group: { _id: null, n: { $sum: '$reading_count' } } }]).toArray()[0].n,
      '| readings_flat:', db.readings_flat.countDocuments({}));
print('total kWh, buckets:', sumBuckets.toString(), '| flat:', sumFlat.toString());