#!/bin/bash
# Runs every time the MongoDB container starts, in place of the image's own start-up script (lesson A4).
# mongod refuses a key file that others can read, and must be able to read it as its own user, mongodb.
# A file mounted from C:\ cannot meet both checks, so the key is created here, inside a Docker volume.
set -e
KEY=/data/keyfile/mongo.key
# First start only: 512 random bytes, written as base64 text (letters, digits, + and /) on one line.
if [ ! -s "$KEY" ]; then
    head -c 512 /dev/urandom | base64 -w 0 > "$KEY"
fi
chmod 400 "$KEY"                 # the owner may read it; nobody else may do anything
chown mongodb:mongodb "$KEY"     # the owner is the user mongod runs as
# Hand over to the image's own start-up script, with the arguments from "command" in docker-compose.yml.
exec docker-entrypoint.sh "$@"