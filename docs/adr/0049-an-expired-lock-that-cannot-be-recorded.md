# An expired lock that cannot be recorded

- Status: accepted
- Date: 2026-11-26

## Context

A worker polls in one transaction (`Worker.pollOnce`). In this order it

1. promotes every due `SCHEDULED` and `RETRY_PENDING` row of the table
   to `PENDING`;
2. records the expired locks of its task types as failed attempts, up
   to 100 per poll, one `UPDATE` per row with the text of a
   `LockExpiredException` or what the application's `ErrorDescription`
   makes of it (ADR 0026, ADR 0043);
3. reads limits, rates and blocks and claims tasks of its types.

When one statement fails, the transaction fails. The worker logs
`could not poll` at ERROR and polls again after the poll interval. The
next poll finds the same rows and fails at the same statement. While
the row that fails the statement exists, the worker claims nothing.

This ADR is about step 2. ADR 0048 gave the write of an outcome a
second, smaller write, noted that a row which refuses that one too
fails the poll that finds its expired lock, and left the case out.

The schema belongs to the application (principle 2), so a column can be
narrower than the generated DDL makes it, and a constraint or a trigger
can refuse a row:

- **The text of an expired lock does not fit.** The message of a
  `LockExpiredException` has up to 97 characters plus the worker id;
  the default worker id is the host name and nine more characters. A
  `last_error varchar(100)`, or a `CHECK` on its length, takes the
  messages of the application's handlers and refuses this one. It is
  first written when a worker dies with a task, which can be long after
  the schema was made.
- **A constraint or trigger refuses the failed attempt** for another
  reason, such as a rule about `run_at` or about rows the application
  considers closed.

### Measured before the decision

Measured on 2026-11-26 against PostgreSQL 17 with today's code. One
worker, poll interval 50 ms, lock timeout 2 s. One task of the type
`close-invoice` is `RUNNING` with a lock that expired a minute ago;
twenty tasks of the type `send-reminder` with an empty handler are
`PENDING` beside it.

- **`last_error varchar(60)`.** A worker of both types had run none of
  the twenty reminders after 5 s; every poll failed with SQL state
  `22001`, `value too long for type character varying(60)`. A second
  worker that handles only `send-reminder`, started then, ran all
  twenty within 2 s. After the column was widened to `text`, the first
  worker recorded the expired lock in its next poll.
- **`CHECK (length(last_error) <= 60)`.** No reminder after 3 s, 49
  failed polls in that time, SQL state `23514`.
- **The log.** Each failed poll writes one ERROR entry with the stack
  trace: about 16 per second and worker at a poll interval of 50 ms,
  two per second at the default of 500 ms.

Read from the code, not measured:

- The heartbeat renews a lock by id, version and status. It does not
  raise the version and does not ask whether the lock had expired. A
  worker that is late, not dead, can renew a lock that another worker
  has already read as expired. Today the row lock of that read, held
  until the failed attempt is written in the same transaction, makes
  the heartbeat wait and then find the row changed.
- A `RUNNING` row counts against the limit of its resource whether its
  lock is live or not (`runningPerResource`).
- A schedule gets no next occurrence while its newest one is `RUNNING`
  (`Occurrences.ensure`).
- The read of the expired locks binds its limit as a parameter.
- The view `_stuck` lists a row once its lock has been expired for five
  minutes.

## Decision

The poll stays the one transaction it is today. When it fails while it
records the expired locks, the worker records them one by one in
transactions of their own and polls again. A row that cannot be
recorded costs its own transaction, is set aside for a while and is
reported to the listener.

### A poll that fails at the expired locks is followed by a pass

The poll reads the expired locks of its types and writes the failed
attempt of each, as today. A poll in which these writes succeed is not
changed by this ADR.

When one of these writes throws, the poll's transaction is rolled back
as today, and the worker knows that it failed at the expired locks and
not at the promotion or the claim. If the database answered (below), it
then

1. records the expired locks **one by one**: each row is read again and
   written in a transaction of its own, with the retry policy asked and
   the text described as in the poll;
2. **polls again** at once, in one transaction as before. Rows that the
   pass has set aside are skipped by this and by later polls.

The read and the write of a row are in one transaction in the poll and
in the pass, so the row lock closes the window for a late heartbeat as
it does today.

The poll that failed is logged at WARN with its exception, not as
`could not poll` at ERROR: the worker goes on. One pass per poll: when
the second poll fails too, it is logged at ERROR and the worker polls
again after the poll interval, as today.

The listener hears `onRetryScheduled` or `onFailed` after the
transaction that recorded the row, as today.

A worker that is stopping finishes the transaction it is in, leaves the
remaining rows to another poll and does not poll again.

### A row whose write fails

In the pass one by one, what follows a write that threw depends on what
the database answered:

- **The write was refused**, in the sense of ADR 0048: an
  `SQLException` of SQL state class 22, 23 or 42 among the causes. A
  second transaction reads the row again and writes the same decision
  with the class name `LockExpiredException` as the text and nothing
  else. That is the rule of ADR 0048 for an attempt that had failed
  already and of ADR 0043 for a description that throws. The retry
  policy is not asked a second time. When this write succeeds, the
  listener hears the event of the decision with the
  `LockExpiredException`. When it throws, the row is set aside.
- **The database answered with another error**, such as an exception a
  trigger raises without an error code (`P0001`), a deadlock or a
  serialization failure: the row is set aside. No second write; the
  text is not what such an error is about.
- **The database did not answer**: no `SQLException` with an SQL state
  among the causes, or one of class 08 (connection exception). The pass
  ends, nothing is set aside, and the worker polls again after the poll
  interval. The same holds for the poll that failed: without an answer
  of the database there is no pass.

### A row that is set aside

A row that is set aside stays `RUNNING` with its expired lock. The
worker

- logs the exception of the last write at ERROR, with the id of the
  row;
- calls `TaskListener.onExpiredLockNotRecorded(task, error)` with that
  exception;
- keeps the id in memory and skips the row in its polls until one lock
  timeout has passed. After that the next poll writes the row again;
  if it still refuses, that poll fails, and the pass sets the row
  aside again.

The read of the expired locks asks for 100 rows plus the number of rows
set aside, so that rows set aside do not take the places of others.

The ids are kept per worker and are lost with it. Each worker of the
type finds such a row for itself and reports it once per lock timeout.

`pendenz-micrometer` counts the event in `pendenz.tasks.unrecorded`,
tagged with the task type.

### What stays as it is

- The poll without a fault: one transaction with the same statements.
- `TaskStore`, `SqlDialect`, `StoreTransaction` and the schema. The
  pass uses the calls the poll uses today.
- The decision for an expired lock: a failed attempt, the retry policy
  decides (ADR 0026).
- The in-memory store. It refuses no write, so its polls do not fail
  at an expired lock.
- A row that could not be recorded keeps its resource slot and holds
  its schedule. Whether an expired lock should count against a limit
  is a question for ADR 0017 and not decided here.
- A promotion or a claim that fails still fails the poll. The promotion
  is decided separately. A row that refuses its claim stops the workers
  of its type, and the guide says so.

### Tests

End-to-end on PostgreSQL, with a short lock timeout and poll interval:

- an expired lock beside twenty tasks of another type on the same
  worker, `last_error` too narrow for the message: the twenty are
  `SUCCEEDED`, the expired row has the decision of its policy with the
  class name in `last_error`, and the listener heard the
  `LockExpiredException`;
- the same with a trigger that refuses every update of the row with
  `check_violation`: the twenty are `SUCCEEDED`, the row stays
  `RUNNING`, and the listener heard `onExpiredLockNotRecorded`; after
  the trigger is dropped the row is recorded;
- the same trigger raising without an error code: the row is set aside
  without a second write;
- two expired locks, one of which refuses every write: the other one is
  recorded with its full text;
- a database that stops answering while the worker records expired
  locks, by a store of the test that throws without an SQL state: no
  row is set aside, and the rows are recorded once it answers again;
- `runPending` with a row that refuses every write: returns, having run
  the other tasks;
- a poll without a refusing row records its expired locks as today
  (existing tests).

In `pendenz-micrometer`: the counter after a row was set aside. In
Java: a listener overrides the new method.

## Rejected alternatives

- **Leave it and document it.** One row stops every worker of its type
  until someone reads the log of a worker.
- **Repeat a failed poll step by step from then on**, as the first
  draft of this ADR proposed: after a poll that threw, the promotion,
  the expired locks and the claim each in a transaction of their own in
  the following polls, until one passes without a failing step. The
  worker would carry a mode between polls that a test can only see
  through an internal property; `runPending`, which builds its worker
  anew and has no next poll, would not get the behaviour; and against
  a database that does not answer, each poll would wait for a
  connection once per step.
- **Always record expired locks after the poll**, in a transaction of
  their own that reads them again, as the second draft proposed. The
  poll would never fail at them. It changes the path of every expired
  lock and not only of those that are refused: two more transactions
  whenever a lock expired, and the resource slot of a recorded row
  counted as free one poll later.
- **Read the expired locks in the poll and write them afterwards
  without reading again.** The version does not tell that a heartbeat
  renewed the lock in between, so a worker that was late would lose a
  task whose lock is live.
- **A savepoint per expired lock inside the poll's transaction.**
  `StoreTransaction` has no savepoints (ADR 0006); adding them changes
  the seam every store implements, and the in-memory store has no
  rollback to offer behind it.
- **Record expired locks in the maintenance sweep.** The sweep runs
  once a minute by default, so a task of a dead worker would wait up to
  a minute longer for its next attempt, and the sweep can be switched
  off.
- **A second write after every failure.** One rule less. A write that
  failed because the connection broke would lose the worker id and the
  time from the text when the second write gets through.
- **Set the row aside in the database**, for example by moving it to
  `QUARANTINED`. The row refuses writes; a write that says so is as
  likely to be refused, and the transition would be a new one in the
  status model.
- **Shorten the text to a length the column takes.** The worker does
  not know the column's limit or the rule of a `CHECK`. The class name
  is the shortest text that still names the cause.
- **No event, the log alone.** A row that is set aside holds a resource
  slot and a schedule; an ERROR entry once per lock timeout is the only
  place that says so before `_stuck` does.
- **Try a row that cannot be recorded in every poll.** No state in the
  worker. Each such row would cost up to two failed transactions and
  an ERROR entry per poll.

## Consequences

- A row whose expired lock cannot be written no longer stops the
  workers of its type. With a text that is too long, the row is
  recorded with the class name instead of the message; the worker id
  and the time the lock ran until are then in the log only.
- A row that refuses every write stays `RUNNING`, keeps its resource
  slot and holds its schedule. It is reported to the listener and the
  log once per lock timeout and worker, and it shows in `_stuck`.
- On a table that refuses the text of an expired lock, each lock that
  expires costs one poll that is rolled back, a WARN entry, and two
  transactions for the row, before the worker polls again.
- A pass records at most the 100 rows a poll reads, each in up to two
  transactions, before the worker claims again.
- The exception of the poll is recognised as one of the expired locks
  only when a write of them throws. A failure that shows at the commit,
  such as a deferred constraint, fails the poll as today.
- The retry policy and the `ErrorDescription` are asked again for the
  same attempt in the pass, and each time a row set aside is tried.
- A transient error of the database on one row, such as a deadlock,
  delays the record of that row by one lock timeout.
- The worker has state between polls that it did not have: the rows it
  has set aside. `runPending` builds a worker per call, so each call
  tries such a row once.
- Public API: `TaskListener.onExpiredLockNotRecorded` with an empty
  default, and the meter name in `pendenz-micrometer`; both visible in
  the `*.api` files. Behaviour named in the changelog under Added and
  Fixed.
- Documentation: the guide under "When a handler fails" and under
  "Create the table" (what the worker writes without a handler), the
  SQL recipes under "What is stuck?", the KDoc of `TaskListener`, the
  meter table of the metrics documentation, a note in ADR 0026 and
  ADR 0048.

## Decisions taken with the acceptance (2026-11-26)

1. Expired locks are recorded in the poll as today, and one by one
   after a poll that failed at them; the worker then polls again.
2. The second write, with the class name as the text, follows a refused
   write only.
3. A row that cannot be recorded is set aside for one lock timeout.
4. A row that is set aside is reported by a listener event and counted
   by `pendenz-micrometer`.
5. The names are `onExpiredLockNotRecorded` and
   `pendenz.tasks.unrecorded`.
6. A poll that is followed by a pass is logged at WARN.

## Noted during implementation

- The poll counts as failed at the expired locks when the write of a
  failed attempt throws. The read of the expired locks is not among it:
  a read that throws names no row, and the poll fails as before.
- In the pass, the decision of the retry policy and the described text
  of a row are taken once and kept for the second write, which replaces
  the text only.
- A pass handles at most 100 rows, the number a poll records. When the
  poll after the pass fails at the expired locks again, because more
  rows refuse than one pass handled, the store's exception is logged as
  for any failed poll and the next poll starts the next pass.
- A transaction of the pass that throws before it has read a row, or
  when it commits, ends the pass like a database that did not answer.
- The rows set aside are kept by id with the time each is tried again,
  taken from the queue's clock.
- Tests: the cases of a refusal, of another answer and of no answer run
  on the in-memory store behind a store of the test that throws
  `SQLException`s with the SQL states in question; a row that cannot be
  recorded beside one that can, and `runPending`, run there as well.
  On PostgreSQL: the narrow `last_error`, a trigger with
  `check_violation`, a trigger without an error code that is dropped
  while the row is set aside, and 101 refusing rows before a newer
  expired lock.
- The Java test reaches the event through a store written in Kotlin
  under the test sources, since a store cannot be delegated to from
  Java.
- The pass is a class of its own, `ExpiredLockPass`, beside
  `LockExpiry`, which records in the poll and keeps the rows set aside.

## Noted in the review of the implementation (2026-11-26)

- The marker by which the poll knows that it failed at the expired
  locks is looked for among the causes of what the transaction threw,
  so that a store or connection provider of the application that wraps
  exceptions gets the pass as well.
- In the pass, the read of the row to record asks for one row beyond
  those set aside, not for the hundred of a poll, so that it locks no
  rows it does not write.
- With the pass disabled, twelve of the fourteen new tests of the core
  fail; the two that pass describe a database that does not answer,
  where nothing changed.
