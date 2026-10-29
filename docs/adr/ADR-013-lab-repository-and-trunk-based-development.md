# ADR-013: The database lab is a separate repository, with one main branch

- Status: Accepted
- Date: 28 October 2026

## Context

POC 3 teaches PostgreSQL and MongoDB from the application developer's side. The existing service,
eai-backend-poc, is already shown publicly as finished work and has nine required checks on its
branches. Practice code mixed into it would blur what the service is and slow every practice change.

## Decision

1. The practice lives in its own public repository, db-lab. eai-backend-poc is changed only when
   the lab's lessons are applied to it, under its own rules.
2. db-lab uses trunk-based development: one long-lived branch, main. Each change is made on a
   short-lived branch feature/EPA-54-<description> and merged into main by a squash-merged pull
   request whose checks pass. There is no develop branch and no promotion step.

## Alternatives considered

- A folder inside eai-backend-poc: every lab change would run all of that repository's checks,
  and the public repository would mix a system with exercises.
- develop and main, as in eai-backend-poc (ADR-002): that model separates "integrated" from
  "released". The lab is never released, so a second branch would add a step with nothing to promote.

## Consequences

- Two repositories to keep tidy.
- The learner works with both branching models: develop/main in eai-backend-poc, trunk-based here.
- Decision-record numbers are shared across repositories, so this repository's records are not
  numbered from 001 (see README.md in this folder).