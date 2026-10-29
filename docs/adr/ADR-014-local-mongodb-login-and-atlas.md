# ADR-014: Local MongoDB runs with login as a one-member replica set; Atlas stands in for shared environments

- Status: Accepted
- Date: 29 October 2026

## Context

The lab's MongoDB was first planned without passwords, protected only by being reachable from this
machine alone. Paid work uses MongoDB with login, encrypted connections and several members, usually on
Atlas or a company cluster run by a platform team, while each developer runs a local container for
development and tests.

## Decision

1. The local MongoDB is a one-member replica set, rs0, with login required: an administrator
   (mongo_admin, role root) and an application account (lab_app, role readWrite on utility_lab),
   both kept in the database admin, with passwords in .env.
2. The key file that login on a replica set requires is generated at container start inside a Docker
   volume, with mode 400 and owner mongodb. It is never stored in the repository folder.
3. From Stage 5, a free Atlas M0 cluster stands in for a shared environment. The same code reaches
   either database through a connection address read from configuration.

## Alternatives considered

- No login: simpler, but unlike every real deployment.
- Three local members with login: about 2 to 4 hours, mostly on container networking under Windows,
  more memory, and operations work outside POC 3.
- Atlas only: no local or test database, which every team has.
- A key file mounted from the repository folder: refused by mongod, because files mounted from
  Windows show open permissions, and it would put a secret in the working folder.

## Consequences

- Every local mongosh and Compass connection gives a user, a password and authSource=admin.
- Changing a first-start script needs docker compose down -v, which also replaces the key file.
- From Stage 5 an Atlas account and an access-list entry for the learner's IP address are needed;
  the Atlas password joins .env.
- In production the key file is managed by Atlas, a Kubernetes Secret or the operations team, or
  replaced by per-member certificates.