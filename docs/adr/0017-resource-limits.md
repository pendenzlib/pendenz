# Concurrency limits per resource

- Status: proposed
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
  `limitResource(name, maxRunning)` and `removeResourceLimit(name)` as
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
`resourceLimits()` on the builder.

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

## Open questions for the discussion

1. **Exact with the advisory lock, as proposed, or approximate?** Exact
   serializes claims across workers for milliseconds per poll.
   Approximate can exceed the limit by up to the number of workers.
2. **`LIKE` patterns in the limit table, as proposed, or exact names
   only?** Patterns are what "two per customer" needs without a row
   per customer. Exact names only would need the application to insert
   a row per customer.
3. **A `maxRunning` shorthand on the task type**, which sets the
   resource to the type's name and seeds the limit? Proposal: yes; it
   is the per-type limit the planning asked for, in one parameter.
4. **Resources for a cycle's tasks**: one resource for the whole cycle,
   as a parameter of `startCycle`, or a SQL expression over the query's
   columns, such as `'customer:' || q.customer_id`, so that each task
   gets its own? Proposal: both, the expression being the general form
   and the parameter the shorthand.
5. **May a resource change after enqueue?** Operators can `UPDATE` the
   column in any case. Proposal: no API for it; the column is set at
   enqueue, and an operator who moves a task to another resource does
   so with SQL and knows why.
