# Rate limits per resource

- Status: accepted
- Date: 2026-11-08

## Context

ADR 0017 limits how many tasks of a resource run at the same moment and
names the other kind of limit as later work:

> **Rate**: how many starts per minute. That is what a partner API with
> a quota cares about. It needs a clock and a history, not a count.

A concurrency limit does not bound a rate. A partner API that allows
100 calls per minute is exceeded by two tasks in flight if each call
takes half a second. Today the handler finds out from the API, usually
as HTTP 429, and reports it by throwing `ResourceUnavailableException`,
which blocks the resource until a given time (ADR 0035). That reacts
after the quota is gone, and every task claimed until the block is
written spends an attempt on the refusal.

What the existing machinery offers:

- The `resource` column and the limit table with `LIKE` patterns, the
  longest matching name winning (ADR 0017).
- A poll that, with limits enabled, takes an advisory lock, reads the
  running tasks per resource and the limit rows once, leaves full
  resources out of the claim through `ResourceFilter.full`, and counts
  every task it claims so the next claim of the same poll sees it
  (ADR 0029).
- A maintenance sweep beside the claim loop that deletes rows past
  their time (ADR 0029, ADR 0035).

The task table cannot answer "how many starts in the last minute": a
row keeps only its latest claim, a retry overwrites it, and retention
removes finished rows.

## Decision

A rate is a row in a rate table: at most `max_starts` claims of a
matching resource within any window of length `per`. Every claim of a
task whose resource matches a rate is written as a row into a start
table. The poll counts those rows inside the window and treats a
resource that has used its rate like a full one.

### Tables

- **`ResourceRateTable`**, mapped like the limit table, by default
  `pendenz_resource_rate` with `name` (exact resource or `LIKE`
  pattern, primary key), `max_starts` (at least 1) and `per` (an
  `interval`, at least one millisecond). The longest matching name
  wins, as for limits, and the two tables are matched independently:
  a resource may have a limit, a rate, both or neither.
- **`ResourceStartTable`**, by default `pendenz_resource_start` with
  `resource` and `started_at`, and an index on `started_at`. One row
  per claim of a rate-limited resource. It is the history ADR 0017
  asks for and readable with SQL: `SELECT resource, count(*) FROM
  pendenz_resource_start WHERE started_at > now() - interval '1 minute'
  GROUP BY resource`.
- `PostgresDdl.createResourceRateTable` and
  `PostgresDdl.createResourceStartTable` generate them.
  `Pendenz.tables` reports both, so the Spring starter's schema check
  covers them (ADR 0037).

### Enabling and operating

- **`enableResourceRates(rateTable, startTable)`** on the builder, both
  with defaults. It requires `enableResourceLimits()`: rates use its
  claim lock, its filter in the claim statements and its tally in the
  poll. The limit table may stay empty. Building a queue with rates and
  without limits fails with a message that says so.
- **The rate is data**, as the limit is: operators change it with
  `UPDATE`, effective at the next poll. The API offers
  `setResourceRate(name, maxStarts, per)`, `removeResourceRate(name)`
  and `resourceRates()`, and the Java facade the same.
- **Seeding from code.** `TaskResource.fixed(name, maxRunning,
  maxStarts, per)` and `TaskResource.fromPayload(resourceOf, pattern,
  maxRunning, maxStarts, per)` declare a rate that is inserted at
  startup only where no row exists, as for limits (ADR 0017, decision
  3). `maxStarts` and `per` go together.

### In the poll

Under the claim lock, after the running tasks and limits:

1. Read the rate rows and, in one statement, the number of starts per
   resource value inside the window of the rate that matches it. Only
   values with starts in their window are returned.
2. A value whose starts reached `max_starts` goes into
   `ResourceFilter.full`, beside the values at their concurrency limit.
3. Every claimed task whose resource matches a rate is counted, so the
   next claim of the same poll sees the start; with rates, the poll
   claims one task per statement, as it does with limits (ADR 0017,
   noted during implementation).
4. Before the transaction ends, one start row per such task is
   inserted with the poll's `now`.

The count is per resource value, so `partner-api:%` with `max_starts =
100` and `per = 1 minute` gives every partner its own hundred.

### What counts as a start

Every claim of a matching task: the first attempt, a retry, and the
claim after an expired lock. A task the worker claims and gives back
because it is stopping (ADR 0005) still counts; the rate errs towards
fewer calls. A deferral (ADR 0035) is a claim that ran and counts.
Tasks whose resource has no matching rate write no start row.

### Clean-up

The maintenance sweep deletes start rows older than the longest `per`
of all rates, or all start rows when there is no rate. Between sweeps
the table holds at most the starts of the longest window plus one
maintenance interval.

### In-memory mode

`InMemoryTaskStore` keeps rates and starts in memory, always enabled,
like its limits; `runPending()` respects them with the clock the queue
was given (principle 9).

## Rejected alternatives

- **A token bucket per resource value**, with tokens and a refill time.
  Needs a state row per resource value for patterns such as
  `partner-api:%`, a write per claim like the start row, and a refill
  formula an operator has to understand to read it. The start table
  says plainly what happened.
- **Fixed windows**, a counter per resource and minute. One write per
  claim as well, and up to twice the rate across the edge of two
  windows, which is what a quota counted as a sliding window refuses.
- **Rate columns in the limit table.** Every deployment with limits
  would need an `ALTER TABLE` before upgrading, rates would follow the
  longest-match rule of the concurrency limit, and a row would mean
  two things.
- **Counting claims from the task table.** Retries overwrite the claim
  time and retention deletes rows; see the context.
- **Rates without the limit machinery.** Would need a second claim
  lock statement and a second way into the claim filter, which means a
  change to `SqlDialect.statements` and to every custom dialect, to save
  an empty table.
- **Waking the worker when a window frees a slot.** Nothing announces
  it; the poll interval bounds the wait, as for a slot another worker
  freed (ADR 0017, corrected on review).
- **A rate on the task type instead of the resource.** A quota belongs
  to what is called, which several types may share. A type that wants
  its own rate gets a resource of its own name, as for limits.

## Consequences

- Two optional tables, one store half (`ResourceRateStore`), one dialect
  method (`resourceRateStatements`, with a default that refuses), one
  builder method, three API calls on each facade.
- A poll with rates runs one more statement and, when it claimed a
  rate-limited task, one insert. The lateral match of start rows to
  rate rows reads only the longest window, through the index on
  `started_at`.
- The rate holds across workers because the start rows are written
  under the same lock that serialises the claims. Workers whose clocks
  differ count with their own `now`; the difference shifts the window
  by that much.
- Rates complement blocks: a rate keeps the quota, a block from the
  handler reacts when the partner refuses anyway.
- The guide gains a section on rates next to limits; the SQL recipes
  show the query above.

## Decisions taken with the acceptance (2026-11-08)

1. A sliding window over a start table, one row per claim; no token
   bucket, no fixed windows.
2. A rate table of its own, matched independently of the limit table;
   no rate columns in the limit table.
3. Rates require `enableResourceLimits()`, whose claim lock and filter
   they use; the limit table may stay empty.
4. `per` is an `interval`, bound and read in whole milliseconds;
   `max_starts` is at least 1, a pause stays a limit of 0 or a block.
5. Every claim counts as a start: first attempt, retry, the claim after
   an expired lock, and a task given back by a stopping worker.
6. Task types seed a rate through `TaskResource.fixed` and
   `fromPayload` with `maxStarts` and `per`.
7. `TaskStore.resourceRates` and `SqlDialect.resourceRateStatements`
   come with defaults, so custom stores and dialects keep compiling.
8. No wake-up when a window frees a slot, and no Micrometer meter for
   claims a rate held back.
