// Stage 5, lessons A5 and A7: what the chosen shapes make easy, and what they make hard. Run as lab_app after
// 51-load-sample.js:   mongosh "<lab_app address>" --quiet -f /practice/52-shapes.js
// Every change it makes is put back, so the data still matches PostgreSQL afterwards.

const show = (x) => printjson(x);

print('=== 1. The account summary, the second task: one read of one document (PostgreSQL: three joins)');
show(db.customers.findOne({ _id: 1 }));

print('=== 2. Find the customer who has a given meter: a filter on a field inside two levels of lists');
// 'premises.meters.serial_number' looks inside every premise and every meter. The unique index made by
// 51-load-sample.js on this path serves it (a multikey index, lesson A8).
show(db.customers.findOne({ 'premises.meters.serial_number': 'MTR-000171' }, { _id: 1, name: 1 }));

print('=== 3. Only the matching part of a list: $elemMatch in the projection');
show(db.customers.findOne({ 'premises.address': '7 Lake View' },
                          { _id: 0, name: 1, premises: { $elemMatch: { address: '7 Lake View' } } }));

print('=== 4. One meter, one month (what a bill needs): one bucket, with its total already kept');
show(db.meter_readings.findOne({ meter_id: 1, month: '2026-10' },
                               { _id: 0, meter_id: 1, month: 1, reading_count: 1, total_kwh: 1 }));

print('=== 5. The same question on the flat shape: 31 documents, added up by the database');
show(db.readings_flat.aggregate([
  { $match: { meter_id: 1, read_at: { $gte: ISODate('2026-09-30T18:30:00Z'), $lt: ISODate('2026-10-31T18:30:00Z') } } },
  { $group: { _id: '$meter_id', readings: { $sum: 1 }, total_kwh: { $sum: '$kwh' } } }
]).toArray());

print('=== 6. Recording a new reading in a bucket: one atomic update, with upsert');
// Meter 9 (MTR-000161) has no readings yet. Its first one, for 1 November, creates the November bucket.
// $push adds the reading, $inc keeps the count and the total right, $setOnInsert fills the fields of a new
// bucket only. All of it happens to one document at once: no other session sees the list without the total.
const first = {
  read_at: ISODate('2026-10-31T18:30:00Z'), kwh: NumberDecimal('6.500')
};
let r = db.meter_readings.updateOne(
  { meter_id: 9, month: '2026-11' },
  { $push: { readings: first }, $inc: { reading_count: 1, total_kwh: NumberDecimal('6.500') },
    $setOnInsert: { premise_id: 8 } },
  { upsert: true });
print('first reading - upserted:', r.upsertedCount, ' modified:', r.modifiedCount);
r = db.meter_readings.updateOne(
  { meter_id: 9, month: '2026-11' },
  { $push: { readings: { read_at: ISODate('2026-11-01T18:30:00Z'), kwh: NumberDecimal('7.000') } },
    $inc: { reading_count: 1, total_kwh: NumberDecimal('7.000') }, $setOnInsert: { premise_id: 8 } },
  { upsert: true });
print('second reading - upserted:', r.upsertedCount, ' modified:', r.modifiedCount);
show(db.meter_readings.findOne({ meter_id: 9, month: '2026-11' }, { _id: 0 }));
db.meter_readings.deleteOne({ meter_id: 9, month: '2026-11' });          // put back: no November readings

print('=== 7. The price of copying: renaming tariff DOM-1 (lesson A5)');
// PostgreSQL: one UPDATE of one tariff row; ON UPDATE CASCADE carries the new code to every premise.
// Here the code is copied into every premise inside every customer document, so each copy is changed.
// $[p] with arrayFilters means "every element of premises that matches p".
r = db.customers.updateMany(
  { 'premises.tariff_code': 'DOM-1' },
  { $set: { 'premises.$[p].tariff_code': 'DOM-2' } },
  { arrayFilters: [{ 'p.tariff_code': 'DOM-1' }] });
print('customer documents changed:', r.modifiedCount,
      '| premises now on DOM-2:', db.customers.aggregate([{ $unwind: '$premises' },
        { $match: { 'premises.tariff_code': 'DOM-2' } }, { $count: 'n' }]).toArray()[0].n);
// The tariff itself is a separate document, whose _id cannot be changed: a new one, then the old one removed.
const old = db.tariffs.findOne({ _id: 'DOM-1' });
db.tariffs.insertOne({ ...old, _id: 'DOM-2' });
db.tariffs.deleteOne({ _id: 'DOM-1' });
print('Two separate writes, to two collections: between them, premises pointed at a tariff that did not exist.');
// Put everything back.
db.tariffs.insertOne({ ...old });
db.tariffs.deleteOne({ _id: 'DOM-2' });
db.customers.updateMany({ 'premises.tariff_code': 'DOM-2' }, { $set: { 'premises.$[p].tariff_code': 'DOM-1' } },
                        { arrayFilters: [{ 'p.tariff_code': 'DOM-2' }] });
print('put back - premises on DOM-1:', db.customers.aggregate([{ $unwind: '$premises' },
  { $match: { 'premises.tariff_code': 'DOM-1' } }, { $count: 'n' }]).toArray()[0].n);

print('=== 8. Nothing checks a reference: a bucket for a meter that does not exist is accepted');
r = db.meter_readings.insertOne({ meter_id: 999, premise_id: 999, month: '2026-10', readings: [],
                                   reading_count: 0, total_kwh: NumberDecimal('0') });
print('inserted a bucket for meter 999:', r.acknowledged, '(PostgreSQL refuses this with SQLSTATE 23503)');
db.meter_readings.deleteOne({ meter_id: 999 });

print('=== 9. Size of the two reading shapes (the same 627 readings)');
for (const name of ['meter_readings', 'readings_flat']) {
  const s = db.getCollection(name).aggregate([{ $collStats: { storageStats: {} } }]).toArray()[0].storageStats;
  print(name.padEnd(15), '| documents:', String(s.count).padStart(4),
        '| data:', String(s.size).padStart(6), 'bytes | average document:', String(s.avgObjSize).padStart(4),
        'bytes | indexes:', Object.keys(s.indexSizes).join(', '));
}