// Stage 5, lesson A9: two reports with the aggregation pipeline, each written beside its SQL. Run as lab_app
// after 51-load-sample.js:   mongosh "<lab_app address>" --quiet -f /practice/54-reports.js
// The second report saves October's bills into the collection bills; running it again adds nothing.

const dec = (x) => (x === null || x === undefined ? x : x.toString());

print('=== Report 1. Energy per India day, all meters, 1 to 5 October');
// SQL (Stage 1):
//   SELECT (read_at AT TIME ZONE 'Asia/Kolkata')::date AS day, count(*), sum(kwh)
//   FROM meter_reading
//   WHERE read_at >= '2026-09-30 18:30+00' AND read_at < '2026-10-05 18:30+00'
//   GROUP BY 1 ORDER BY 1;
const report1 = db.meter_readings.aggregate([
  { $match: { month: '2026-10' } },                            // WHERE: only October's buckets
  { $unwind: '$readings' },                                    // one output document per reading in the list
  { $match: { 'readings.read_at': { $lt: ISODate('2026-10-05T18:30:00Z') } } },
  { $group: {                                                  // GROUP BY the India date
      _id: { $dateToString: { date: '$readings.read_at', format: '%Y-%m-%d', timezone: 'Asia/Kolkata' } },
      readings: { $sum: 1 },                                   // count(*)
      kwh: { $sum: '$readings.kwh' } } },                      // sum(kwh)
  { $sort: { _id: 1 } }                                        // ORDER BY
]).toArray();
report1.forEach((d) => print(d._id, '| readings:', d.readings, '| kWh:', dec(d.kwh)));

print('=== Report 2. October bills for every premise, saved into bills');
// SQL (Stage 1, 14-produce-october-bills.sql, with calculate_bill written out):
//   INSERT INTO bill (customer_id, premise_id, period_start, period_end, amount)
//   SELECT p.customer_id, p.id, '2026-10-01', '2026-10-31',
//          round(coalesce(sum(r.kwh), 0) * t.rate_per_kwh + 31 * t.standing_charge, 2)
//   FROM premise p JOIN tariff t ON t.code = p.tariff_code
//   LEFT JOIN meter m ON m.premise_id = p.id
//   LEFT JOIN meter_reading r ON r.meter_id = m.id AND <October, India days>
//   GROUP BY p.customer_id, p.id, t.rate_per_kwh, t.standing_charge
//   ON CONFLICT (premise_id, period_start) DO NOTHING;
const DAYS = 31;
const pipeline = [
  { $unwind: '$premises' },                                    // one document per premise (FROM premise p)
  { $lookup: {                                                 // LEFT JOIN the premise's October buckets
      from: 'meter_readings',
      localField: 'premises.premise_id', foreignField: 'premise_id',
      pipeline: [{ $match: { month: '2026-10' } }, { $project: { _id: 0, total_kwh: 1 } }],
      as: 'october' } },
  { $lookup: {                                                 // JOIN tariff t ON t.code = p.tariff_code
      from: 'tariffs', localField: 'premises.tariff_code', foreignField: '_id', as: 'tariff' } },
  { $unwind: '$tariff' },                                      // exactly one tariff per premise
  { $project: {
      _id: 0,                                                  // let the bills collection give each bill its own _id
      customer_id: '$_id',
      premise_id: '$premises.premise_id',
      period_start: ISODate('2026-10-01T00:00:00Z'),
      period_end: ISODate('2026-10-31T00:00:00Z'),
      kwh: { $sum: '$october.total_kwh' },                     // coalesce(sum(...), 0): $sum of an empty list is 0
      amount: { $round: [{ $add: [
        { $multiply: [{ $sum: '$october.total_kwh' }, '$tariff.rate_per_kwh'] },
        { $multiply: [DAYS, '$tariff.standing_charge'] } ] }, 2] },
      status: 'DRAFT' } },
  { $sort: { premise_id: 1 } },
  { $merge: {                                                  // INSERT ... ON CONFLICT (premise_id, period_start)
      into: 'bills', on: ['premise_id', 'period_start'],
      whenMatched: 'keepExisting',                             // ... DO NOTHING
      whenNotMatched: 'insert' } }
];
db.customers.aggregate(pipeline);
db.bills.find({ period_start: ISODate('2026-10-01T00:00:00Z') }).sort({ premise_id: 1 }).forEach((b) =>
  print('premise', String(b.premise_id).padStart(2), '| customer', b.customer_id, '| kWh', dec(b.kwh).padStart(9),
        '| amount', dec(b.amount)));
db.customers.aggregate(pipeline);                              // the same report again
print('bills for October after running the report twice:',
      db.bills.countDocuments({ period_start: ISODate('2026-10-01T00:00:00Z') }));

print('=== Report 2, explained: what each stage handed to the next');
const stages = db.customers.explain('executionStats').aggregate(pipeline.slice(0, -1)).stages;
if (stages) {
  stages.forEach((s) => {
    const name = Object.keys(s).find((k) => k.startsWith('$'));
    print(name.padEnd(9), '| documents out:', Number(s.nReturned));
  });
} else {
  print('(this server explains the pipeline as a single plan)');
}