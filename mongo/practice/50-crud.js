// Stage 5, lessons A3 and A4: the four basic operations (create, read, update, delete) in mongosh, each
// beside the SQL it replaces. Run as lab_app inside the MongoDB container:
//   mongosh "<lab_app address>" --quiet -f /practice/50-crud.js
// Works on a scratch collection, practice_crud, which it drops at the start and at the end.

const c = db.practice_crud;
c.drop();                                    // DROP TABLE IF EXISTS practice_crud  (no error if missing)

print('=== 1. insertOne: INSERT INTO practice_crud (...) VALUES (...)');
// No CREATE TABLE first: the collection is created by the first insert. MongoDB adds _id, an ObjectId.
const one = c.insertOne({ serial_number: 'MTR-900001', status: 'IN_STOCK', tags: ['smart'] });
print('acknowledged:', one.acknowledged, ' insertedId is an', one.insertedId.constructor.name);

print('=== 2. insertMany: one INSERT with several rows');
// The documents need not have the same fields: the third has a nested object, the fourth has no tags.
const many = c.insertMany([
  { serial_number: 'MTR-900002', status: 'IN_STOCK', tags: ['smart', 'three-phase'] },
  { serial_number: 'MTR-900003', status: 'INSTALLED', tags: ['smart'], site: { city: 'Pune', pin: '411001' } },
  { serial_number: 'MTR-900004', status: 'FAULTY' }
]);
print('inserted:', Object.keys(many.insertedIds).length);

print('=== 3. find with a filter and a projection: SELECT serial_number, status ... WHERE status = \'IN_STOCK\'');
// The filter is a document; the projection says which fields to return (1 = include, 0 = leave out).
printjson(c.find({ status: 'IN_STOCK' }, { _id: 0, serial_number: 1, status: 1 }).sort({ serial_number: 1 }).toArray());

print('=== 4. Operators: WHERE status IN (...) AND serial_number > \'MTR-900001\' ORDER BY serial_number DESC LIMIT 2');
printjson(c.find({ status: { $in: ['IN_STOCK', 'FAULTY'] }, serial_number: { $gt: 'MTR-900001' } },
                 { _id: 0, serial_number: 1 }).sort({ serial_number: -1 }).limit(2).toArray());

print('=== 5. A value inside a list, and a field inside a nested object');
// tags: 'three-phase' matches any document whose list CONTAINS that value. No join table, no LIKE.
printjson(c.find({ tags: 'three-phase' }, { _id: 0, serial_number: 1 }).toArray());
// Dotted path into the nested object: the SQL counterpart is a column of a joined child table.
printjson(c.find({ 'site.city': 'Pune' }, { _id: 0, serial_number: 1, site: 1 }).toArray());

print('=== 6. Missing is not the same as null: WHERE tags IS NULL has two meanings here');
print('documents with no tags field at all:', c.countDocuments({ tags: { $exists: false } }));
c.insertOne({ serial_number: 'MTR-900005', status: 'IN_STOCK', tags: null });
print('{ tags: null } matches missing OR null:', c.countDocuments({ tags: null }));
print('{ tags: { $type: "null" } } matches only an explicit null:', c.countDocuments({ tags: { $type: 'null' } }));

print('=== 7. updateOne with $set: UPDATE ... SET status = \'INSTALLED\' WHERE serial_number = \'MTR-900001\'');
let u = c.updateOne({ serial_number: 'MTR-900001' }, { $set: { status: 'INSTALLED' } });
print('matched:', u.matchedCount, ' modified:', u.modifiedCount);
// Running it again matches the document but changes nothing: modified 0, and no new version is written.
u = c.updateOne({ serial_number: 'MTR-900001' }, { $set: { status: 'INSTALLED' } });
print('again - matched:', u.matchedCount, ' modified:', u.modifiedCount);

print('=== 8. $push and $inc: add to a list and a counter in one atomic step');
u = c.updateOne({ serial_number: 'MTR-900001' }, { $push: { tags: 'prepaid' }, $inc: { visits: 1 } });
printjson(c.findOne({ serial_number: 'MTR-900001' }, { _id: 0 }));

print('=== 9. The trap: a replacement document instead of an operator');
// replaceOne swaps the WHOLE document (all fields except _id) for the one given. Fields not listed are gone.
c.replaceOne({ serial_number: 'MTR-900002' }, { serial_number: 'MTR-900002', status: 'INSTALLED' });
printjson(c.findOne({ serial_number: 'MTR-900002' }, { _id: 0 }));
// updateOne refuses a document without operators, so the mistake cannot happen there by accident:
try {
  c.updateOne({ serial_number: 'MTR-900003' }, { status: 'FAULTY' });
} catch (e) {
  print('updateOne without $set refused:', e.message);
}

print('=== 10. Upsert: INSERT ... ON CONFLICT DO UPDATE');
u = c.updateOne({ serial_number: 'MTR-900006' }, { $set: { status: 'IN_STOCK' } }, { upsert: true });
print('first call - matched:', u.matchedCount, ' upserted:', u.upsertedCount);
u = c.updateOne({ serial_number: 'MTR-900006' }, { $set: { status: 'FAULTY' } }, { upsert: true });
print('second call - matched:', u.matchedCount, ' upserted:', u.upsertedCount, ' modified:', u.modifiedCount);

print('=== 11. updateMany and deleteMany: UPDATE / DELETE without a key');
print('updateMany modified:', c.updateMany({ status: 'FAULTY' }, { $set: { status: 'RETURNED' } }).modifiedCount);
print('deleteMany deleted:', c.deleteMany({ status: 'RETURNED' }).deletedCount);
print('left:', c.countDocuments({}));

print('=== 12. Nothing enforces uniqueness until an index says so');
c.insertOne({ serial_number: 'MTR-900001', status: 'DUPLICATE' });
print('documents with serial MTR-900001:', c.countDocuments({ serial_number: 'MTR-900001' }));
c.deleteOne({ status: 'DUPLICATE' });
c.createIndex({ serial_number: 1 }, { unique: true });   // ALTER TABLE ... ADD CONSTRAINT ... UNIQUE
try {
  c.insertOne({ serial_number: 'MTR-900001', status: 'DUPLICATE' });
} catch (e) {
  print('refused with code', e.code, '(11000 is DuplicateKey):', e.message.split(' dup key')[0]);
}

c.drop();
print('=== done: practice_crud dropped');