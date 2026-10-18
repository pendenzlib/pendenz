# Test database: Testcontainers with embedded PostgreSQL as fallback

- Status: accepted
- Date: 2026-10-18

## Context

The claim query depends on `FOR UPDATE SKIP LOCKED`, the payload column on
`jsonb`. H2 has neither; tests against H2 would not check what matters.
Testcontainers provides a real PostgreSQL but needs Docker. Not every
developer machine has Docker, and on the machine where Pendenz is being
built it was not reachable when the first feature was written.

## Decision

The test helper `PostgresDatabase` starts PostgreSQL through Testcontainers
when Docker is reachable, and otherwise through the embedded PostgreSQL of
`io.zonky.test:embedded-postgres`, which runs real PostgreSQL binaries as a
process of the current user. Both are real PostgreSQL servers. The test log
states which path was taken.

CI runs on GitHub runners with Docker and therefore through Testcontainers.

## Rejected alternatives

- **Testcontainers only.** Tests could not run on machines without Docker.
  That contradicts principle 9, testable without infrastructure.
- **Embedded PostgreSQL only.** Does not work on every platform and does not
  reflect the version users run in production.
- **H2 in PostgreSQL mode.** Knows neither `SKIP LOCKED` nor `jsonb`.

## Consequences

- One additional test dependency. The core is untouched.
- Tests may run at different speeds on the two paths; assertions therefore
  wait on states with Awaitility, not on time.
