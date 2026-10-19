# Claim strategy: fairness across lanes, priority as tiers

- Status: proposed
- Date: 2026-10-19

## Context

A worker claims the oldest due tasks: `ORDER BY run_at, id LIMIT n FOR
UPDATE SKIP LOCKED`, across every type it handles. That is first come,
first served, and it is wrong the moment v0.2 arrives. A cycle inserts
ten thousand tasks in one statement, all due now. A user then presses
"send this invoice", which enqueues one task, due now, a second later.
It waits behind ten thousand. The planning calls this out: single tasks
must not starve behind large cycles, and that is the default, not an
option someone has to find.

Two needs hide in "priorities and fairness", and they are different:

- **Fairness** is about groups competing for the same workers: the
  cycle versus the singles, cycle A versus cycle B, tenant X versus
  tenant Y. Nobody is more important; nobody should wait for everybody
  else.
- **Priority** is about one task being more urgent than the rest, by
  explicit decision: the payroll run before the newsletter.

Whatever the strategy does, an operator must be able to read from the
table why a task has not run yet. "It is in a lane with 9,000 others and
each lane gets a turn" is readable. A weight formula is not.

## Decision

Claiming becomes a strategy with a default that is fair across lanes and
strict across priorities. Two columns carry what it needs.

- **`lane`**, nullable text. The fairness group within a task type. A
  cycle writes its id into the lane of every task it creates. A caller
  may set it when enqueuing, for example a tenant id. `NULL` is a lane
  of its own: the singles of a type. The claim treats `(type, lane)` as
  one group.
- **`priority`**, integer, `NOT NULL DEFAULT 0`, higher runs first. A
  caller sets it when enqueuing; a cycle sets it for all its tasks.

The default strategy, `FairClaim`, works in two steps per poll:

1. Ask the store which groups have due work:
   `SELECT DISTINCT priority, type, lane FROM t WHERE status = 'PENDING'
   AND run_at <= now AND type = ANY (types) ORDER BY priority DESC`.
   This is an index-only scan over the pending rows; with a hundred
   thousand pending tasks it reads a hundred thousand index entries,
   which is milliseconds.
2. Take the highest priority present. Visit its groups in round-robin
   order, continuing where the last poll stopped, and claim the oldest
   due task from each with the statement used today, narrowed to that
   group, until the free permits are used up. If permits remain after
   every group of that priority was visited once, go around again. If
   the groups run dry, move to the next priority.

So priority is a strict tier: as long as any task of priority 5 is due,
nothing of priority 0 runs. That is what "priority" means, it is set by
explicit decision, and it is the one place where starvation is intended.
Within a tier every lane gets a turn, so a cycle of ten thousand and a
single task alternate, and the single task runs second.

The strategy is a seam, as principle 1 asks:

```kotlin
public interface ClaimStrategy {
    public suspend fun <ID : Any> claim(
        store: TaskStore<ID>, tx: StoreTransaction,
        workerId: String, types: Set<String>, limit: Int, now: Instant,
    ): List<ClaimedTask<ID>>
}
```

`WorkerOptions.claimStrategy` defaults to `FairClaim`. `FifoClaim` keeps
today's behaviour for teams that want strict arrival order and know why.
The store gains two operations the strategies compose, `dueGroups` and
`claimFromGroup`; the current `claim` becomes `claimFromGroup` without a
group. The claim index becomes
`(status, type, lane, priority, run_at)`.

The round-robin cursor lives in the strategy instance. Workers in one JVM
that share options share the cursor, which makes them fairer together,
not less fair. Workers in other JVMs have cursors of their own;
`SKIP LOCKED` keeps them from colliding, as today.

## Rejected alternatives

- **First come, first served, as today.** The starvation the planning
  forbids.
- **Priority alone.** A cycle and a single task of equal priority still
  queue in arrival order; and a knob everybody can turn gets turned by
  everybody, until "high" is the new normal.
- **One statement with a window function**, `row_number() OVER
  (PARTITION BY type, lane ORDER BY run_at)` and `ORDER BY row_number`.
  Elegant, and it ranks every pending row on every poll, which is a full
  scan of the pending set per worker per interval. `FOR UPDATE SKIP
  LOCKED` cannot be applied inside the window query either, so the lock
  would come in a second step anyway.
- **Random order.** Fair on average, unreadable in the table, and no
  arrival order within a lane.
- **Weighted fair queuing**, a weight per lane. Lets a tenant buy a
  bigger share. Nobody has asked; equal turns are the simpler story and
  weights can be added to the lane later without changing the shape.
- **A table per cycle or per tenant.** Keeps the claim simple and breaks
  the promise of one table that SQL can read whole.

## Consequences

- Two columns, one index change, two store operations, one seam, two
  strategies. The DDL generator and both stores change; the in-memory
  store implements the same two operations over its map.
- Every poll runs one `DISTINCT` statement plus one claim statement per
  group visited, instead of one claim statement. With few lanes that is
  two or three statements; with many lanes and many free permits it is
  more, bounded by the permits.
- An operator reads fairness from the table: `SELECT lane, count(*)
  FROM t WHERE status = 'PENDING' GROUP BY lane` says who is waiting
  and how long the line is.
- Cycles (next ADR) get their grouping for free: the lane. Progress and
  cancellation per cycle can use the same column, or a column of their
  own, see the open questions.
- `pendenz-postgres-notify` is unaffected: a wake-up leads to a poll,
  and the poll decides what to take.

## Open questions for the discussion

1. **Priority as strict tiers**, as proposed, or as a bias inside the
   round-robin? Tiers are readable and are what most people mean. A bias
   needs a formula.
2. **Higher number, higher priority**, default 0? The alternative, lower
   runs first as in Unix nice, confuses everyone who has not read the
   docs.
3. **One column for lane and cycle, or two?** One column means a cycle's
   tasks cannot also carry a tenant lane; two columns mean the claim must
   decide which one groups. Proposal: one column, `lane`, which cycles
   fill with their id; a cycle inside a tenant is a cycle per tenant.
4. **One task per lane per visit**, as proposed, or a batch per lane
   once batch claiming (v0.2) exists? Proposal: the batch size applies
   per visit, so a lane with a batch of ten and a lane with one task
   still alternate.
5. **Keep `FifoClaim` public?** Proposal: yes, with the warning in its
   documentation. Some teams have ordering requirements that fairness
   breaks, and they should opt into that explicitly.
