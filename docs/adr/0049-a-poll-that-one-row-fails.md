# A poll that one row fails

- Status: proposed
- Date: 2026-11-26

## Context

A worker polls in one transaction (`Worker.pollOnce`). In this order it

1. promotes every due `SCHEDULED` and `RETRY_PENDING` row of the table
   to `PENDING`, in one `UPDATE` that is not limited to the worker's
   task types;
2. records the expired locks of its task types as failed attempts, up
   to 100 per poll, one `UPDATE` per row with the text of a
   `LockExpiredException` or what the application's `ErrorDescription`
   makes of it (ADR 0026, ADR 0043);
3. reads limits, rates and blocks and claims tasks of its types.

When one statement fails, the transaction fails. The worker logs
`could not poll` at ERROR and polls again after the poll interval. The
next poll finds the same rows and fails at the same statement. While
the row that fails the statement exists, the worker claims nothing.

ADR 0048 gave the write of an outcome a second, smaller write. It noted
that a row which refuses that one too fails the poll that finds its
expired lock, and left the case out.

The schema belongs to the application (principle 2), so a column can be
narrower than the generated DDL makes it, and a constraint or a trigger
can refuse a row. Three cases, of different likelihood:

- **The text of an expired lock does not fit.** The message of a
  `LockExpiredException` has up to 97 characters plus the worker id;
  the default worker id is the host name and nine more characters. A
  `last_error varchar(100)`, or a `CHECK` on its length, takes the
  messages of the application's handlers and refuses this one. It is
  first written when a worker dies with a task, which can be long after
  the schema was made.
- **A constraint or trigger refuses the failed attempt** for another
  reason, such as a rule about `run_at` or about the status of rows the
  application considers closed.
- **A trigger refuses the promotion or the claim of a row.** Those
  writes carry no value of the application: status, version, and for
  the claim the worker id, the lock, `finished_at` and `attempts`.

### Measured before the decision

Measured on 2026-11-26 against PostgreSQL 17 with today's code. One
worker, poll interval 50 ms, lock timeout 2 s. One task of the type
`close-invoice` is the row that refuses; twenty tasks of the type
`send-reminder` with an empty handler are `PENDING` beside it.

- **Expired lock, `last_error varchar(60)`.** The `close-invoice` row
  is `RUNNING` with a lock that expired a minute ago. A worker of both
  types had run none of the twenty reminders after 5 s; every poll
  failed with SQL state `22001`, `value too long for type character
  varying(60)`. A second worker that handles only `send-reminder`,
  started then, ran all twenty within 2 s. After the column was widened
  to `text`, the first worker recorded the expired lock in its next
  poll. The same with `CHECK (length(last_error) <= 60)`: no reminder
  after 3 s, 49 failed polls in that time, SQL state `23514`.
- **Promotion refused.** A trigger raises on every update of the
  `close-invoice` row, which is `SCHEDULED` and due. A worker that
  handles only `send-reminder` had run none of the twenty reminders
  after 3 s. The promotion covers the whole table, so a worker fails
  on a row of a type it does not handle.
- **Claim refused.** The same trigger on a `PENDING` row. A worker of
  both types had run no reminder after 3 s; a worker of
  `send-reminder` alone ran all twenty within 2 s.
- **The log.** Each failed poll writes one ERROR entry with the stack
  trace: about 16 per second and worker at a poll interval of 50 ms,
  two per second at the default of 500 ms.

Read from the code, not measured: the maintenance sweep runs beside the
loop in transactions of its own and is not affected. A `RUNNING` row
counts against the limit of its resource whether its lock is live or
not (`runningPerResource`), so a row whose expired lock cannot be
recorded keeps a slot of its resource. The view `_stuck` lists such a
row once its lock has been expired for five minutes.

## Decision

A poll that failed is repeated step by step, so that a failing step
costs that step and not the claim. A row whose expired lock cannot be
recorded is left alone for a while. The poll that succeeds stays one
transaction, as today.

### A failed poll is followed by a poll in steps

When the transaction of a poll throws, the worker logs it as today,
waits for the poll interval and then polls in steps. Each step has a
transaction of its own:

1. the promotion;
2. the read of the expired locks;
3. the failed attempt of each expired lock, one transaction per row;
4. limits, rates, blocks and the claim, together as today.

A step that throws is logged at ERROR with what it was doing, and for
step 3 with the id of the row. The steps after it run. Step 4 decides
what the poll returns: when it throws, the worker has claimed nothing
and polls again after the poll interval, as today.

The worker goes on polling in steps until one such poll passes without
a failing step. Then it returns to the single transaction.

The trigger is any exception of the poll, not only a refused statement
in the sense of ADR 0048. Polling in steps is harmless where the cause
is a database that cannot be reached: the first step fails as the
single transaction would have, and there is one attempt per poll
interval either way.

### An expired lock that cannot be recorded

In step 3, the write of a failed attempt that throws is followed by a
second write of the same decision, `RETRY_PENDING` with the same run
time or `FAILED`, with the class name `LockExpiredException` as the
text and nothing else. That is the rule of ADR 0048 for an attempt that
had failed already and of ADR 0043 for a description that throws. The
second write is made whatever the first one threw; unlike the write of
an outcome, nothing is lost by shortening the text of an expired lock
early. After a second write that succeeds, the listener hears
`onRetryScheduled` or `onFailed` with the `LockExpiredException`, as
for any expired lock.

When the second write throws too, the row stays `RUNNING` with its
expired lock. The worker logs both exceptions at ERROR and does not
read that row's expired lock again before one lock timeout has passed.
It keeps the ids of such rows in memory, per worker, and skips them in
the single transaction as well, so that a poll without other faults
returns to one transaction. After the lock timeout the row is tried
again; if it still refuses, that one poll fails, the next one runs in
steps, and the row is set aside again.

No event is delivered for a row that could not be written: nothing was
recorded.

### What is not isolated

- **The promotion stays one statement.** When it fails in a poll in
  steps, `SCHEDULED` and `RETRY_PENDING` rows of the whole table wait
  for an operator, and the worker claims what is `PENDING`. The worker
  stays in steps, with one failed statement and one ERROR entry per
  poll.
- **The claim stays one step.** A row that refuses its claim stops the
  workers of its type, as measured. Workers that do not handle the type
  go on.

Neither write carries a value the application supplies. A row that
refuses them is refused by a rule about the status model itself, and
the guide states under "Create the table" that the schema must let the
worker make the transitions of ADR 0002.

### What stays as it is

- `TaskStore`, `SqlDialect`, `StoreTransaction` and the schema: the
  steps use the calls the single transaction uses.
- The in-memory store. It refuses no write; a poll there does not fail
  by a row.
- The poll without a fault: one transaction, the same statements.
- `TaskListener`: no new event. The fault is in the worker's log.
- A row that could not be recorded keeps its resource slot, as today.

### Tests

End-to-end on PostgreSQL, with a short lock timeout and poll interval:

- an expired lock beside twenty tasks of another type on the same
  worker, `last_error` too narrow for the message: the twenty are
  `SUCCEEDED`, the expired row is `RETRY_PENDING` or `FAILED` with the
  class name in `last_error`, and the listener heard the
  `LockExpiredException`;
- the same with a constraint that refuses every text: the twenty are
  `SUCCEEDED`, the row stays `RUNNING`; after the constraint is dropped
  the row is recorded within the lock timeout;
- two expired locks, one of which refuses every write: the other one is
  recorded;
- a trigger that refuses the promotion of one row: a `PENDING` task of
  another type is `SUCCEEDED`, and after the trigger is dropped the
  scheduled row runs;
- a trigger that refuses the claim of one row: a worker of another type
  alone runs its tasks (pins today's behaviour);
- after a fault is gone, the worker polls in one transaction again.
  This needs a seam to observe; see decision 6.

## Rejected alternatives

- **Leave it and document it.** One row stops every worker of its type,
  for the promotion every worker on the table, until someone reads the
  log of a worker.
- **A savepoint per expired lock inside the poll's transaction.** One
  transaction would do. `StoreTransaction` has no savepoints (ADR
  0006); adding them changes the seam every store implements, and the
  in-memory store has no rollback to offer behind it.
- **Always poll in steps.** No mode to keep. A poll would commit three
  times or more where it commits once today, on every worker and every
  poll interval, for a fault that is rare.
- **Record expired locks in the maintenance sweep instead of the
  poll.** The poll would no longer meet them. The sweep runs once a
  minute by default, so a task of a dead worker would wait up to a
  minute longer for its next attempt, and the sweep can be switched
  off.
- **Promote and claim row by row after a failure.** It would isolate
  those rows too. Both are set statements today; a row-wise form needs
  new statements in `TaskSql`, which every dialect of an application
  would have to supply, for rows that refuse the status model itself.
- **Set the row aside in the database**, for example by moving it to
  `QUARANTINED`. The row refuses writes; a write that says so is as
  likely to be refused, and the transition would be a new one in the
  status model.
- **Shorten the text to a length the column takes.** The worker does
  not know the column's limit or the rule of a `CHECK`. The class name
  is the shortest text that still names the cause.
- **A listener event for a failed poll or an unrecordable row.** An
  alert could hang on it. `TaskListener` reports what happened to
  tasks; a poll that fails has no task, and `pendenz-micrometer` would
  need a meter of its own. It can be added without changing this
  decision.
- **Try an unrecordable row again in every poll.** Simpler, no state in
  the worker. Each such row would cost two failed transactions and two
  ERROR entries per poll.

## Consequences

- A row whose expired lock cannot be written no longer stops the
  workers of its type. With a text that is too long, the row is
  recorded with the class name instead of the message; the worker id
  and the time the lock ran until are then in the log only.
- A row that refuses every write stays `RUNNING` and keeps its resource
  slot. It shows in `_stuck` and, once per lock timeout and worker, in
  the log.
- A refused promotion no longer stops claims. Scheduled tasks and
  retries of the whole table still wait until the row is repaired.
- A refused claim behaves as today.
- More than 100 rows whose expired locks cannot be recorded hide
  further expired locks of the same worker's types: the read takes the
  100 oldest, and the worker skips those it has set aside.
- Between the steps other workers can act. A lock recorded as expired
  in step 3 can be claimed by another worker before this worker's step
  4; that is the case today between two polls.
- The worker has state between polls that it did not have: whether it
  polls in steps, and the rows it has set aside. Both are lost with the
  worker and rebuilt by the next failed poll.
- `runPending` uses the same poll and gains the same behaviour.
- Public API: none. Behaviour named in the changelog under Changed and
  Fixed.
- Documentation: the guide under "When a handler fails" and under
  "Create the table" (what the worker writes without a handler:
  promotion, claim, expired lock), the SQL recipes under "What is
  stuck?", a note in ADR 0026 and ADR 0048.

## Open decisions

Each with the proposal this draft follows.

1. **What switches to steps.** Any exception of the poll (proposed), or
   only a refused statement, SQL state classes 22, 23 and 42 as in
   ADR 0048. The second leaves a trigger that raises without an error
   code, `P0001`, blocking as today.
2. **The second write of an expired lock.** After any exception of the
   first write (proposed), or only after a refused one.
3. **How long an unrecordable row is set aside.** One lock timeout
   (proposed), a fixed time, or an option of the worker.
4. **Promotion and claim.** Not isolated row by row, and documented as
   a requirement on the schema (proposed), or isolated with new
   statements in `TaskSql`.
5. **A signal beyond the log.** None (proposed), or a listener event
   and a meter.
6. **How a test sees the mode.** An internal property of the worker
   read by a test in the same module (proposed), or no test of the
   return to one transaction.
7. **The cap of 100 expired locks per poll with rows set aside.** Left
   as a known limit (proposed), or the read excludes the ids set aside,
   which needs a changed statement in `TaskSql`.
