// Stage 5, lesson A8: an index that deletes old documents by itself (a TTL index, "time to live"). Run as
// lab_app:   mongosh "<lab_app address>" --quiet -f /practice/56-ttl.js
// reading_batches stands for the raw messages a meter-data system receives: kept 90 days, then removed.
// The 2013 counterpart is a nightly purge job, or dropping old date partitions. Takes up to two minutes.

const c = db.reading_batches;
c.drop();
const DAY = 24 * 3600 * 1000;
const now = Date.now();

// Documents older than expireAfterSeconds (measured from the date in received_at) are deleted.
c.createIndex({ received_at: 1 }, { expireAfterSeconds: 90 * 24 * 3600 });

c.insertMany([
  { batch: 'B-1', received_at: new Date(now - 120 * DAY), readings: 2000 },   // 120 days old: due for removal
  { batch: 'B-2', received_at: new Date(now - 95 * DAY), readings: 2000 },    // 95 days old: due for removal
  { batch: 'B-3', received_at: new Date(now - 10 * DAY), readings: 2000 },    // 10 days old: kept
  { batch: 'B-4', received_at: 'yesterday', readings: 2000 }                  // not a date: never removed
]);
print('inserted:', c.countDocuments({}));

// A background task on the server, the TTL monitor, runs once every 60 seconds and deletes what has expired.
const start = Date.now();
while (c.countDocuments({}) > 2 && Date.now() - start < 150 * 1000) {
  sleep(5000);
}
print(`after ${Math.round((Date.now() - start) / 1000)} s:`, c.countDocuments({}), 'left:',
      c.find({}, { _id: 0, batch: 1 }).sort({ batch: 1 }).toArray().map((d) => d.batch).join(', '));
const idx = c.getIndexes().find((i) => i.expireAfterSeconds !== undefined);
print('TTL index:', idx.name, '| expireAfterSeconds:', idx.expireAfterSeconds, `(${idx.expireAfterSeconds / 86400} days)`);
c.drop();