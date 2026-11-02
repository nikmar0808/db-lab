// Stage 5, lesson A8: indexes and query plans on about a million readings, read against the PostgreSQL plans
// of Stage 4. Run as lab_app on the LOCAL MongoDB only (never on Atlas: the free cluster holds 512 MB):
//   mongosh "<lab_app address>" --quiet -f /practice/55-large-and-plans.js
// Size: 2,000 meters x 500 days = 1,000,000 readings, as sql/03-large-data.sql. For a slower laptop, run it with
// LAB_METERS=600 in the container's environment (docker compose exec -e LAB_METERS=600 ...): 300,000 readings.
// It drops and rebuilds readings_large and buckets_large each time; db.readings_large.drop() and
// db.buckets_large.drop() remove them (about 150 MB on disk).

const METERS = Number(process.env.LAB_METERS || 2000);
const FIRST = Date.UTC(2025, 5, 19) - 330 * 60 * 1000;   // 19 June 2025, midnight India time
const DAYS = 500;
const DAY_MS = 24 * 3600 * 1000;
const flat = db.readings_large;
const buckets = db.buckets_large;

// ------------------------------------------------------------------ helpers
function median(xs) { const s = [...xs].sort((a, b) => a - b); return s[Math.floor(s.length / 2)]; }

// The chain of plan stages, outermost first, e.g. "LIMIT <- FETCH <- IXSCAN meter_id_1_read_at_1".
function chain(stage) {
  const parts = [];
  for (let s = stage; s; s = s.inputStage || (s.inputStages && s.inputStages[0])) {
    parts.push(s.indexName ? `${s.stage} ${s.indexName}` : s.stage);
  }
  return parts.join(' <- ');
}

// Runs a query's explain four times, keeps the last three times, and prints one row of the results table.
function plan(label, makeCursor) {
  let last;
  const times = [];
  for (let run = 0; run < 4; run++) {
    last = makeCursor().explain('executionStats');
    if (run > 0) times.push(last.executionStats.executionTimeMillis);
  }
  const wp = last.queryPlanner.winningPlan;
  const s = last.executionStats;
  print(`| ${label} | ${chain(wp.queryPlan || wp)} | ${s.totalKeysExamined} | ${s.totalDocsExamined} | ${s.nReturned} | ${median(times)} |`);
}

function timed(label, fn) {
  const t = Date.now();
  const r = fn();
  print(`${label}: ${((Date.now() - t) / 1000).toFixed(1)} s`);
  return r;
}

function sizes(c) {
  const s = c.aggregate([{ $collStats: { storageStats: {} } }]).toArray()[0].storageStats;
  const mb = (b) => (b / 1024 / 1024).toFixed(1) + ' MB';
  print(`| ${c.getName()} | ${s.count} | ${s.avgObjSize} bytes | ${mb(s.size)} | ${mb(s.storageSize)} | ${mb(s.totalIndexSize)} |`);
}

// ------------------------------------------------------------------ the data
flat.drop();
buckets.drop();
print(`Meters: ${METERS}, days: ${DAYS}, readings to write: ${METERS * DAYS}`);
timed('Written in batches of 10,000 (insertMany)', () => {
  let batch = [];
  for (let d = 0; d < DAYS; d++) {                        // day by day, as a meter-data system receives them
    const readAt = new Date(FIRST + d * DAY_MS);
    for (let m = 1; m <= METERS; m++) {
      batch.push({ meter_id: m, read_at: readAt, kwh: NumberDecimal((6 + ((m * 7 + d * 3) % 11) * 0.5).toFixed(3)) });
      if (batch.length === 10000) { flat.insertMany(batch, { ordered: false }); batch = []; }
    }
  }
  if (batch.length) flat.insertMany(batch, { ordered: false });
});
print('readings_large:', flat.estimatedDocumentCount());

const METER = Math.min(500, METERS);                  // the meter whose bill is asked for
const deep = Math.floor(flat.estimatedDocumentCount() * 9 / 10);
const oct = { $gte: ISODate('2026-09-30T18:30:00Z'), $lt: ISODate('2026-10-31T18:30:00Z') };
const oneDay = { $gte: ISODate('2026-10-14T18:30:00Z'), $lt: ISODate('2026-10-15T18:30:00Z') };

function allPlans(title) {
  print('');
  print(`### ${title}`);
  print('| Question | Plan (outermost first) | Keys examined | Documents examined | Returned | ms |');
  print('|---|---|---:|---:|---:|---:|');
  plan('1. One meter, one month (the bill)', () => flat.find({ meter_id: METER, read_at: oct }, { _id: 0, kwh: 1 }));
  plan('2. Every meter, one day', () => flat.find({ read_at: oneDay }, { _id: 0, kwh: 1 }));
  plan('3. The same day, with a function on the field', () => flat.find({ $expr: { $eq: [
    { $dateToString: { date: '$read_at', format: '%Y-%m-%d', timezone: 'Asia/Kolkata' } }, '2026-10-15'] } },
    { _id: 0, kwh: 1 }));
  plan('4. Most of the collection (since 1 January 2026)',
       () => flat.find({ read_at: { $gte: ISODate('2026-01-01T00:00:00Z') } }, { _id: 0, kwh: 1 }));
  plan('5. A deep page with skip', () => flat.find({}).sort({ read_at: 1, _id: 1 }).skip(deep).limit(50));
  const last = flat.find({}, { read_at: 1 }).sort({ read_at: 1, _id: 1 }).skip(deep - 1).limit(1).next();
  plan('6. The same page, keyset', () => flat.find({ $or: [
    { read_at: { $gt: last.read_at } }, { read_at: last.read_at, _id: { $gt: last._id } }] })
    .sort({ read_at: 1, _id: 1 }).limit(50));
}

// ------------------------------------------------------------------ plans before, between and after the indexes
allPlans('Only the _id index');

timed('\nIndex { meter_id: 1, read_at: 1 }, unique, built in',
      () => flat.createIndex({ meter_id: 1, read_at: 1 }, { unique: true }));
allPlans('With { meter_id: 1, read_at: 1 }');

timed('\nIndex { read_at: 1, _id: 1 } built in', () => flat.createIndex({ read_at: 1, _id: 1 }));
allPlans('With { read_at: 1, _id: 1 } as well');

// ------------------------------------------------------------------ the same readings as monthly buckets
// $group gathers each meter's readings for each India month into one document; $out writes the result to a
// new collection (lesson A9). The sort uses { meter_id, read_at }, so each list comes out in time order.
print('');
timed('buckets_large built from readings_large with $sort, $group and $out', () => flat.aggregate([
  { $sort: { meter_id: 1, read_at: 1 } },
  { $group: {
      _id: { meter_id: '$meter_id',
             month: { $dateToString: { date: '$read_at', format: '%Y-%m', timezone: 'Asia/Kolkata' } } },
      readings: { $push: { read_at: '$read_at', kwh: '$kwh' } },
      reading_count: { $sum: 1 },
      total_kwh: { $sum: '$kwh' } } },
  { $project: { _id: 0, meter_id: '$_id.meter_id', month: '$_id.month', readings: 1, reading_count: 1, total_kwh: 1 } },
  { $out: 'buckets_large' }
]));
buckets.createIndex({ meter_id: 1, month: 1 }, { unique: true });
print('');
print('| Question | Plan (outermost first) | Keys examined | Documents examined | Returned | ms |');
print('|---|---|---:|---:|---:|---:|');
plan('7. One meter, one month, from its bucket',
     () => buckets.find({ meter_id: METER, month: '2026-10' }, { _id: 0, total_kwh: 1, reading_count: 1 }));

print('');
print('### The two shapes on disk');
print('| Collection | Documents | Average document | Data | On disk (compressed) | Indexes |');
print('|---|---:|---:|---:|---:|---:|');
sizes(flat);
sizes(buckets);