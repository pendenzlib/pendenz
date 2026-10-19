# Wait instead of poll: LISTEN/NOTIFY as an option

- Status: accepted
- Date: 2026-10-19

## Context

Workers poll. Every `pollInterval`, 500 ms by default, each worker runs
three statements: promote due tasks, release stale locks, claim. With
ten workers that is sixty statements per second against an idle table,
and a task enqueued right after a poll waits up to half a second. Both
numbers are harmless for most teams and unpleasant for two kinds: those
who run many workers against a small database, and those for whom
latency is the point, say a UI that enqueues and shows the result.

PostgreSQL has `LISTEN`/`NOTIFY`: a session subscribes to a channel, a
`NOTIFY` is delivered to subscribers when the sending transaction
commits. That last property fits exactly: a task enqueued inside the
caller's transaction becomes visible and announced in the same moment.

Receiving notifications needs a connection that is held open and asked
for notifications, through the PostgreSQL driver's own API
(`PGConnection.getNotifications`). The core has no driver dependency
(ADR 0003) and must not get one for a PostgreSQL feature.

## Decision

The core gets a seam, PostgreSQL gets a module, and polling stays the
default and the fallback.

- **Seam in the core.** `WorkerOptions` gets a `wakeUp: WakeUp` whose
  default is the current behaviour:

  ```kotlin
  public fun interface WakeUp {
      /** Returns when a task of one of [types] may have become pending, or after [atMost]. */
      public suspend fun await(types: Set<String>, atMost: Duration)
  }
  ```

  The worker calls it instead of `delay(pollInterval)` when a poll found
  nothing. `pollInterval` keeps its meaning as the upper bound: scheduled
  and retried tasks become due by time, not by event, and a notification
  can be lost when a connection drops, so the worker polls at the
  interval regardless. With notifications in place the interval can be
  raised to seconds.
- **The database announces, not the library.** The DDL generator emits
  an optional trigger:

  ```sql
  CREATE FUNCTION billing_task_notify() RETURNS trigger AS $$
  BEGIN
      PERFORM pg_notify('pendenz_billing_task', NEW.task_type);
      RETURN NULL;
  END $$ LANGUAGE plpgsql;

  CREATE TRIGGER billing_task_notify
      AFTER INSERT OR UPDATE OF status ON billing_task
      FOR EACH ROW WHEN (NEW.status = 'PENDING')
      EXECUTE FUNCTION billing_task_notify();
  ```

  A trigger covers every way a task becomes pending: `enqueue`, an
  `INSERT` written by hand, an operator's restart, a cycle's
  `INSERT ... SELECT`. A `NOTIFY` sent by the library would cover the
  first only. The channel is named after the table; the payload is the
  task type, so a worker can ignore types it does not handle.
- **Module `pendenz-postgres-notify`.** `PostgresNotify(dataSource,
  channel)` implements `WakeUp`: one dedicated connection, `LISTEN`, a
  loop on an IO thread that asks the driver for notifications with the
  timeout the worker passed, reconnect with backoff when the connection
  fails. While disconnected, `await` simply waits for the timeout, which
  is polling. The module depends on `org.postgresql:postgresql`; the
  core does not.
- The dedicated connection must not come from a pool that evicts idle
  connections. The module takes a `DataSource` and documents that a
  plain driver data source, not the application's HikariCP pool, is the
  right thing to hand it.

## Rejected alternatives

- **Polling only.** Simple and sufficient for most teams, which is why
  it stays the default. The trade between latency and idle load has no
  good setting for the two kinds of teams above.
- **`NOTIFY` sent by `enqueue`.** No trigger to install, but a task
  inserted with SQL, restarted by an operator or created by a cycle
  would wait for the poll. SQL is a supported interface (principle 3);
  it must wake workers like the API does.
- **The driver API in the core, reached through `Connection.unwrap`.**
  Would need the driver at compile time or reflection, both excluded.
- **Replacing the poll with the event.** Due times and dropped
  connections need a timer anyway. The poll is the safety net; the
  event is the fast path.
- **Change data capture (Debezium, logical replication).** Right for
  streaming every change to other systems; far too heavy for "wake up".

## Consequences

- One seam in the core, one new module, one optional trigger in the DDL.
  Applications that do nothing see no change.
- Every worker wakes on every notification; with `SKIP LOCKED` the
  losers find nothing and go back to waiting. At hundreds of workers
  this costs a claim statement per worker per enqueue; the per-type
  payload lets a worker skip notifications for types it does not run.
- Other databases implement `WakeUp` with their own means or keep
  polling. The seam is database-neutral.

## Decisions taken with the acceptance (2026-10-19)

1. The trigger is opt-in: `PostgresDdl.notifyTrigger(table)` is a
   statement of its own beside `createTable`, because a trigger is a
   thing a DBA wants to see coming.
2. The worker does not announce tasks it promotes from `SCHEDULED`: it
   is awake and claims them in the same poll.
3. The per-type filter ships from the start. The trigger sends the task
   type, `await` takes the worker's types, and a notification for a type
   the worker does not handle leaves it asleep. A `NOTIFY` without a
   payload wakes everyone.

One limitation accepted: a notification that arrives between a worker's
poll and its next `await` is not remembered. The worker catches that task
at its next poll interval, which is the safety net the design relies on
anyway.
