// Stage 5, lesson A6: the validators of 51-load-sample.js at work. Each insert below breaks one rule and is
// refused, as Stage 1's inserts were refused by CHECK and NOT NULL. Run as lab_app after 51-load-sample.js:
//   mongosh "<lab_app address>" --quiet -f /practice/53-validator.js

function tryInsert(label, collection, doc) {
  try {
    db.getCollection(collection).insertOne(doc);
    print(label, '-> ACCEPTED');
  } catch (e) {
    // Code 121 is DocumentValidationFailed. errInfo.details says which rule failed, as JSON.
    const rules = e.errInfo && e.errInfo.details ? e.errInfo.details.schemaRulesNotSatisfied : [];
    print(label, '-> refused, code', e.code, e.code === 121 ? '(DocumentValidationFailed)' : '');
    print('   rule broken:', JSON.stringify(rules).slice(0, 300));
  }
}

const reading = (kwh) => ({ meter_id: 1, premise_id: 1, month: '2026-12', reading_count: 1, total_kwh: kwh,
                            readings: [{ read_at: ISODate('2026-11-30T18:30:00Z'), kwh }] });

print('=== 1. A customer without an e-mail (NOT NULL)');
tryInsert('customer 99, no email', 'customers',
          { _id: 99, name: 'No Mail', created_at: new Date(), premises: [] });

print('=== 2. A negative reading (CHECK kwh >= 0)');
tryInsert('kwh -1.000', 'meter_readings', reading(NumberDecimal('-1.000')));

print('=== 3. The right value in the wrong type: 7.5 as a double instead of a decimal');
// In mongosh a plain number is a double. The rule asks for Decimal128, so money and kWh stay exact.
tryInsert('kwh 7.5 (double)', 'meter_readings', reading(7.5));

print('=== 4. A month written the wrong way');
tryInsert('month "Dec 2026"', 'meter_readings', { ...reading(NumberDecimal('7.500')), month: 'Dec 2026' });

print('=== 5. An extra field inside a reading (additionalProperties: false)');
tryInsert('reading with a note', 'meter_readings',
          { ...reading(NumberDecimal('7.500')),
            readings: [{ read_at: ISODate('2026-11-30T18:30:00Z'), kwh: NumberDecimal('7.500'), note: 'estimated' }] });

print('=== 6. A correct document is accepted, then removed');
tryInsert('a valid December bucket', 'meter_readings', reading(NumberDecimal('7.500')));
db.meter_readings.deleteOne({ meter_id: 1, month: '2026-12' });

print('=== 7. Updates are checked too');
try {
  db.meter_readings.updateOne({ meter_id: 1, month: '2026-10' }, { $set: { total_kwh: -5 } });
} catch (e) {
  print('$set total_kwh: -5 -> refused, code', e.code, e.code === 121 ? '(DocumentValidationFailed)' : '');
}

print('=== 8. The collection without a validator accepts anything');
db.readings_flat.insertOne({ meter_id: 'one', kwh: 'lots' });
print('readings_flat accepted { meter_id: "one", kwh: "lots" }');
db.readings_flat.deleteOne({ meter_id: 'one' });

print('=== 9. The rule itself, as stored with the collection');
const info = db.getCollectionInfos({ name: 'meter_readings' })[0].options;
print('validationLevel:', info.validationLevel || 'strict (default)', '| validationAction:', info.validationAction || 'error (default)');
print('required fields:', info.validator.$jsonSchema.required.join(', '));