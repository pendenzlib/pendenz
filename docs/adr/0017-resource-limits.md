# Concurrency limits per resource

- Status: accepted
- Date: 2026-10-19

## Context

A worker runs `parallelism` tasks at once, whatever they are. Nothing
limits how many of them talk to the same thing. The mail server accepts
four connections; the partner API allows ten calls in flight; one
customer's database must not see more than two of our jobs at a time.
With three workers at parallelism four, twelve tasks may hit the mail
server together, and the retry policy then spends its attempts on
"connection refused". The planning asks for limits per resource, in
addition to limits per task type, and it asks for them across workers:
a limit that each JVM keeps for itself is a limit times the number of
JVMs, which is no limit.

Two kinds of limit hide under one word and are different:

- **Concurrency**: how many tasks may be running against a resource at
  the same moment. That is what the mail server and the customer
  database care about, and it is what the task table can tell: count
  the `RUNNING` rows.
- **Rate**: how many starts per minute. That is what a partner API with
  a quota cares about. It needs a clock and a history, not a count.

This decision is about concurrency. Rate is noted as later work.

## Decision

A task names at most one resource; a limit table says how many tasks
of a resource may run at once; the claim skips tasks whose resource is
full; a lock makes the count exact across workers.

- **`resource`**, nullable text column on the task table. Set at
  enqueue, by the caller (`enqueue(..., resource = "mail-server")`) or
  by the task type: `TaskType(resource = "mail-server")` gives every
  task of the type the same resource, `TaskType(resourceOf = { payload
  -> "customer:${payload.customerId}" })` derives it from the payload.
  A cycle gives its tasks one resource for the whole run, or a SQL
  expression over the query's columns, see the open questions.
- **A limit table**, mapped like the others: `ResourceLimitTable` with
  `name` and `max_running`. `name` is an exact resource name or a
  `LIKE` pattern such as `customer:%`; when several rows match, the
  longest name wins, so `customer:acme` beats `customer:%`. A resource
  without a matching row is unlimited. `PostgresDdl.createResourceLimitTable`
  generates it.
- **The limit is data.** Operators change it with `UPDATE`, effective
  at the next claim, with no restart. The API offers
  `setResourceLimit(name, maxRunning)` and `removeResourceLimit(name)` as
  the same thing from code, and a task type may declare a default that
  is inserted at startup only where no row exists, so an operator's
  change survives a restart.
- **The claim skips full resources.** Both claim statements get one
  more condition on their candidates: the task has no resource, or the
  number of `RUNNING` tasks with the same resource value is below the
  limit that matches it. A full resource does not block the lane; the
  lane's other tasks and the other lanes proceed, and the skipped task
  waits in `PENDING` until a slot frees. The count is per resource
  *value*, so `customer:%` with `max_running = 2` means two per customer.
- **Exact, not approximate.** Two workers that poll in the same instant
  would both count below the limit and both claim. To rule that out,
  a poll that may claim takes a transaction-scoped advisory lock on the
  task table's name before it counts and claims, so claims across all
  workers run one at a time. A claim transaction lasts milliseconds;
  with a few dozen workers polling every half second the lock is idle
  most of the time. The lock is taken only when limits are enabled on
  the builder, so applications without limits keep today's claim.
- **Nothing is enforced outside the claim.** A handler that opens two
  connections counts as one task. The limit is about tasks, which is
  what the table can see.

Where the store decides: `claim` and `claimFromGroup` take the
condition; the in-memory store counts the same way over its map and
needs no lock, since it is one process. `TaskStore` gains a
`ResourceLimitStore` half like the cycle half, enabled by
`enableResourceLimits()` on the builder.

## Rejected alternatives

- **A semaphore per resource inside each worker.** Simple, and wrong
  by a factor of the number of JVMs. Workers do not know each other;
  the table does.
- **Limits declared in code only.** Consistent only while every worker
  runs the same version, and not changeable while the mail server is
  struggling. The row is the truth; code may seed it.
- **Approximate limits without the lock.** Saves a lock that is cheap
  and makes the limit a suggestion exactly when it matters, under load.
  If the lock ever shows up in measurements, the approximate mode can
  become an option; it should not be the default.
- **Several resources per task.** A task that touches the mail server
  and a customer database could carry both. It needs an array column
  or a side table and a claim condition over each of them. Nobody has
  asked; one resource per task covers the cases named, and a task type
  that needs two can be split in two.
- **Rate limits in the same feature.** A different mechanism: a
  per-resource token bucket needs starts per time, a history and a
  clock. Worth its own ADR when someone has an API quota to respect.
- **Counting at execution time, releasing to `PENDING` when over the
  limit.** Claims, then un-claims. Doubles the writes for every task of
  a busy resource and makes `attempts` lie.

## Consequences

- One column, one mapped table, one condition in two claim statements,
  one advisory lock, one store half, two API calls.
- A full resource with a long lane behind it costs the claim one
  subquery per skipped candidate until it finds a task it may take; the
  index on `(status, resource)` keeps that to index lookups.
- The progress of a limited resource is visible: `SELECT resource,
  count(*) FROM t WHERE status = 'RUNNING' GROUP BY resource` beside
  the limit table says who is at the limit.
- Per-task-type limits, planned for v0.1 and never built, fall out of
  this: a type that sets `resource` to its own name and a row with its
  limit. A `maxRunning` shorthand on the task type is one of the open
  questions.

## Decisions taken with the acceptance (2026-10-19)

1. Exact: a poll with limits enabled takes a transaction-scoped advisory
   lock on the task table's name before it counts and claims.
2. `LIKE` patterns in the limit table; the longest matching name wins.
3. The per-type shorthand is `TaskResource.fixed(name, maxRunning)`
   with the type's own name, rather than a separate `maxRunning`
   parameter: one mechanism, no second way to say the same thing.
4. A cycle's tasks get their resource from `CycleResource.fixed(name)`
   for the whole run or `CycleResource.expression(sql)` over the query's
   columns, aliased `q`.
5. No API moves a task to another resource; an operator does it with
   SQL.

Known limitation (noted on review, 2026-10-19): `name` is matched with
`LIKE`, so `%` and `_` in a name are wildcards even in a row meant as an
exact name; `mail_server` matches `mailXserver`. Resource names should
avoid both characters, or escape them as `\_` in the row.

Corrected on review (2026-10-19): a task skipped because its resource
was full stayed `PENDING` until the next poll interval, because nothing
announces a freed slot: no trigger fires when a task finishes, and
`pendenz-postgres-notify` listens for tasks that become pending, not
for slots. With a long poll interval the wait was long. The worker now
wakes itself whenever one of its own handlers ends, beside whatever
`WakeUp` it was given, so a slot it freed is used at once. A slot freed
by another worker still waits for the poll, which the poll interval
bounds; see ADR 0014.

Noted during implementation: the condition in the claim statement
counts what is `RUNNING` when the statement starts, not what the
statement itself claims, so one statement with `LIMIT 10` would take ten
tasks of a resource limited to two. With limits enabled, the JDBC store
therefore claims one row per statement until a statement comes back
empty: up to as many statements as free permits per poll, under the
same lock. Without limits, the single statement stays.

Noted on review (2026-10-25): the partial index on running rows is now
called `_running_idx` and serves the expired locks of ADR 0026 as well;
see ADR 0028.

Changed with ADR 0029 (2026-10-25): the claim no longer counts running
tasks per candidate row. The poll reads the running tasks per resource
and the limit rows once under the lock, excludes full and paused
resources with bound arrays and tallies what it claims; the store does
not count limits itself any more.

Amended on review (2026-10-25): the advisory lock was keyed on
`hashtext` of the table name as configured. `billing_task` and
`public.billing_task` are the same table but gave two locks, so two
deployments spelling it differently did not serialize their claims, and
the one-key form shared its key space with the application's own
advisory locks. The lock now takes the two-key form: a fixed class for
Pendenz and the OID of the table.

Renamed on review (2026-10-25), before the first release: the builder
enables limits with `enableResourceLimits()` and cycles with
`enableCycles()`, and a limit is set with `setResourceLimit`, so the
three operations on limits read alike and `resourceLimits()` only lists
them.
