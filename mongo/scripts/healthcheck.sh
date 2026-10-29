#!/bin/bash
# Docker runs this every 5 seconds inside the MongoDB container (lesson A2), logged in as the administrator.
# On the first runs after a fresh start, rs.status() fails because the replica set does not exist yet,
# so the check creates it (rs.initiate, lesson A4). It exits with 0 ("healthy") once this server is the
# writable primary. During first-start set-up the image runs a temporary server that is not a replica-set
# member, so this check keeps failing until set-up has finished.
mongosh --quiet \
    -u "$MONGO_INITDB_ROOT_USERNAME" -p "$MONGO_INITDB_ROOT_PASSWORD" --authenticationDatabase admin \
    --eval "try { rs.status() } catch (e) { rs.initiate({_id: 'rs0', members: [{_id: 0, host: '127.0.0.1:27017'}]}) }; quit(db.hello().isWritablePrimary ? 0 : 1)"