# Retention of finished tasks

- Status: accepted
- Date: 2026-10-19

## Context

Tasks are rows and rows accumulate. A queue that processes a few thousand
tasks a day holds millions after a year, the claim index grows, and the
table that was meant to be readable with plain SQL becomes a haystack.
Teams either forget to clean up or write a cron job that deletes rows the
library still needs. The planning asks for a rule per task type: delete
final rows after a period, or move them to an archive table.

## Decision

- The mapping gains a `finishedAt` column, nullable timestamp. The
  library sets it when a task reaches `SUCCEEDED`, `FAILED` or, since
  ADR 0020, `REJECTED`, and clears it when a task is claimed again. The SQL recipes for cancelling and
  completing set it too. It is useful on its own: how long a task took,
  what finished in the last hour.
- A task type carries a `Retention`: `keep()` forever, the default;
  `delete(after)`; or `archive(after, table)`. The period counts from
  `finishedAt`, or from `createdAt` for a final row that has no
  `finishedAt`, so that nothing lingers forever because a hand-written
  statement forgot the column.
- Retention applies to final states only: `SUCCEEDED` and `CANCELLED`.
  `FAILED`, `REJECTED` and `QUARANTINED` wait for a human and are not
  touched; a human who wants them gone cancels them, and the rule takes
  it from there.
- Archiving moves whole rows with `SELECT *`, so the archive table must
  have the same columns in the same order. The recommended way to create
  it is `CREATE TABLE billing_task_archive (LIKE billing_task INCLUDING
  ALL EXCLUDING INDEXES)`; extra columns the application added travel
  along.
- The worker runs retention as maintenance every
  `WorkerOptions.maintenanceInterval` (one minute by default), in batches
  of `retentionBatchSize` rows per statement so that a large backlog
  never holds one long lock. `Pendenz.runMaintenance()` runs one sweep
  on demand, for tests and for schedulers of your own. Several workers
  sweeping at once are harmless: each statement selects and removes its
  own rows.

## Rejected alternatives

- **Retention by `createdAt` only.** No new column, but a task scheduled
  for next month and completed then would be deleted the moment it
  finishes, because it was created long ago.
- **Retention on `FAILED` rows.** Deleting what a human has not looked at
  defeats the point of the status. The SQL recipe for bulk cancelling
  exists for teams that decide otherwise.
- **A separate maintenance process.** Another thing to deploy and
  schedule; the worker is already there and already polls.
- **Copying only the mapped columns into the archive.** Would silently
  drop the columns the application added, which principle 2 invites.

## Consequences

- One more column, two more worker options, two more store operations.
- The archive table is the application's, like the task table: Pendenz
  generates nothing for it beyond the recommendation above.
- A row removed by retention frees its idempotency key (ADR 0009).

Noted on review (2026-10-25): the retention query compares
`finished_at` and, for rows without one, `created_at` directly instead
of through `COALESCE`, so the index of ADR 0028 can serve it.

Noted on review (2026-10-25): the sweep runs beside the claim loop, not
inside it, first one interval after the start, and a zero interval turns
it off on a worker; see ADR 0029.
