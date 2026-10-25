# Bounded work per poll: full resources, empty visits, maintenance

- Status: proposed
- Date: 2026-10-25

## Context

ADR 0028 gave every statement of the claim path an index. The review of
2026-10-25 found three places where the *number* of statements, or the
work inside one, still grows with the data rather than with the
worker's free permits, and all three sit where they hurt most: under the
advisory lock that serializes claims across workers (ADR 0017), or in
the loop that claims.

- **Full resources are found row by row.** With resource limits, both
  claim statements carry a condition that runs two correlated
  subqueries for every candidate row: a count of running rows with the
  same resource and a `LIKE` search through the limit table. A
  resource at its limit fails the condition for every candidate, so
  the statement reads its whole group to find nothing. ADR 0017
  assumed "one subquery per skipped candidate until it finds a task it
  may take"; when nothing may be taken, that is every candidate.
- **`dueGroups` does not know about full resources.** It returns groups
  whose due tasks all wait on full resources, and `FairClaim` visits
  each of them. A visit that claims nothing drops the group but costs a
  statement, so a poll costs one statement per due group, not per free
  permit as ADR 0015 promised ("bounded by the permits"). Two thousand
  tenant lanes at their limit mean two thousand empty statements per
  poll per worker, all under the claim lock.
- **Retention runs inside the claim loop.** Every turn of the loop calls
  maintenance first when it is due, and maintenance sweeps until
  nothing is left. The first sweep runs before the first claim, on every
  worker at once after a deployment. With a large backlog of finished
  rows, no worker claims anything until its sweep is through.

## Decision

### Full resources are computed once per poll

With limits enabled, the poll, after taking the claim lock, reads two
small things: the number of running tasks per resource value, through
the index on running rows (ADR 0028), and the rows of the limit table.
It matches each running resource against the limits in Kotlin (longest
`LIKE` pattern wins, as ADR 0017 decided) and knows which resources are
full. Those are excluded with one plain condition, `resource IS NULL OR
resource <> ALL (?)`, in `dueGroups` and in both claim statements. The
correlated subqueries are gone.

The poll keeps the tally as it claims: a claimed task with a resource
counts one more running task, and a resource that reaches its limit
joins the excluded set for the rest of the poll. The JDBC store still
claims one row per statement while limits are enabled, since one
statement cannot respect a limit for the rows it takes itself; that
remains bounded by the permits. The in-memory store applies the same
exclusion to its map.

### Strategies see claims, not the store

Keeping the tally must not depend on every claim strategy remembering to
do it. `ClaimStrategy.claim` no longer receives the store and the
transaction. It receives a `Claims` view with three operations,
`dueGroups()`, `fromGroup(group, limit)` and `oldest(limit)`, which the
worker implements for the poll: it applies worker id, lock deadline,
excluded resources and the tally. `ClaimRequest` keeps the types with
their batch sizes and the limit. A strategy written against the new seam
cannot break a resource limit.

### Empty visits are bounded

`FairClaim` stops a poll after as many visits that claimed nothing as
the poll had free permits. The cursor stays where the poll stopped, so
the next poll continues with the next group instead of starting over.
With full resources excluded, empty visits are rare: a group taken by
another worker in the same moment, or a resource that filled during
this poll. The bound makes the promise of ADR 0015 hold even then: one
`dueGroups` statement plus at most twice the free permits in claim
statements.

### Maintenance runs beside the loop

The worker runs maintenance in a coroutine of its own, beside the claim
loop and under the same job, so cancelling the worker stops it too. A
sweep that takes long no longer delays claims.

- The first sweep runs one `maintenanceInterval` after the worker
  starts, not before the first claim, so a deployment does not begin
  with every worker sweeping.
- A `maintenanceInterval` of zero means this worker runs no maintenance,
  for applications that leave retention to one dedicated worker or to
  `Pendenz.runMaintenance()` on a schedule of their own.
- The retention statement selects its rows with `FOR UPDATE SKIP
  LOCKED`, so workers sweeping at the same time split the rows instead
  of waiting for each other.

## Rejected alternatives

- **Excluding full resources inside the claim statement**, with one
  `GROUP BY` in a CTE instead of a per-row subquery. Computes the same
  set, but once per statement instead of once per poll, and still
  cannot see what this poll has claimed so far.
- **A window function to respect limits within one statement.** ADR
  0015 rejected window functions for the claim already: `FOR UPDATE
  SKIP LOCKED` cannot be applied to them.
- **Approximate limits without the lock.** ADR 0017 rejected them; this
  decision makes the lock cheaper rather than optional.
- **Tallying in the strategies.** Every strategy, including those users
  write, would have to call it correctly; one that forgets breaks a
  limit without notice.
- **A cap on visited groups instead of empty visits.** A poll with many
  free permits and many small lanes would stop before its permits are
  used, though every visit claimed something.
- **Maintenance in a separate process**, or a scheduler of the
  library's own. ADR 0011 rejected the process; a coroutine beside the
  loop is the smallest change that takes retention off the claim path.
- **A random delay for the first sweep.** Spreads deployments better,
  but adds randomness the tests would have to control; one interval of
  delay is enough to keep a deployment's first seconds for claiming.

## Consequences

- `ClaimStrategy`, `ClaimRequest` and the store's claim operations
  change (API change before 1.0). `FairClaim` and `FifoClaim` move to
  the `Claims` view; `TaskStore.dueGroups`, `claimFromGroup` and `claim`
  take the excluded resources; `ResourceLimitStore` gains a read of the
  running counts per resource. `TaskSql` loses the subquery condition
  and its statements bind the excluded resources as an array.
- With limits enabled a poll costs two small reads more, and no
  subquery per candidate.
- `maintenanceInterval` of zero changes meaning, from "every turn of
  the loop" to "no maintenance on this worker"; nothing relied on the
  old meaning, which made every poll a sweep.
- ADR 0011, 0015 and 0017 get a note pointing here.
- Tests: a resource at its limit with a long lane behind it is skipped
  without its group being visited; many groups that all come back
  empty cost a bounded number of visits; a resource limit of two holds
  for a poll with ten free permits; a claim succeeds while a sweep is
  blocked; a worker with a maintenance interval of zero never sweeps.

## Open questions

1. **`Claims` view instead of store and transaction** for strategies, or
   keep the seam and let the store keep the tally per transaction?
   Proposal: the view; it keeps exactness out of user code.
2. **Bound of empty visits**: the free permits of the poll, or a fixed
   number? Proposal: the free permits, as ADR 0015 promised.
3. **First sweep after one interval**, or right after start but in the
   background? Proposal: after one interval.
4. **Zero interval disables maintenance** on the worker? Proposal: yes.
5. **`FOR UPDATE SKIP LOCKED` in the retention statement**? Proposal:
   yes.
