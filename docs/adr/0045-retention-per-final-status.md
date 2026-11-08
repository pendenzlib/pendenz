# Retention per final status

- Status: proposed
- Date: 2026-11-08

## Context

ADR 0011 gives a task type one `Retention`: keep its final rows, delete
them after a period, or move them to an archive table after a period.
Final rows are those in `SUCCEEDED` and `CANCELLED`; `FAILED`, `REJECTED`
and `QUARANTINED` wait for a person and are never touched.

The two final statuses carry different information:

- A `SUCCEEDED` row says the work was done. After a short while it
  is rarely read again, and it makes up most of the rows a busy type
  produces.
- A `CANCELLED` row says the work was not done, and who decided that:
  an operator with a reason (ADR 0031), an approver who declined
  (ADR 0018), or a person who cancelled a failed task instead of
  restarting it, as ADR 0011 recommends for a failed row nobody wants
  back. These rows are few, and they are what someone asks about weeks
  later.

With one rule per type, an application chooses between keeping every
success as long as the cancellations or losing the cancellations as
early as the successes. Writing the difference as a cron job with SQL is
what ADR 0011 set out to make unnecessary.

## Decision

`Retention` gains a fourth form that applies one rule to each final
status:

```kotlin
Retention.byStatus(
    succeeded = Retention.delete(Duration.ofDays(1)),
    cancelled = Retention.archive(Duration.ofDays(365), "billing_task_archive"),
)
```

- **`Retention.ByStatus(succeeded, cancelled)`**, each part one of
  `keep()`, `delete(after)` or `archive(after, table)`. A `ByStatus`
  inside a `ByStatus` is refused.
- **The sweep runs each part over its status only**, in the same
  batches, from `finishedAt` or, without it, from `createdAt`, as
  ADR 0011 describes. `onRetentionApplied` reports the sum of both
  parts.
- **Finished cycles** of the type go once no task of theirs is left, as
  before, after the shorter of the two periods; a part that keeps rows
  keeps its cycles with them, because their tasks are still there.
- **No change for `FAILED`, `REJECTED` and `QUARANTINED`.** ADR 0011
  rejected retention for them, and this ADR keeps that decision.

Underneath:

- The statements `deleteFinished` and `archiveFinished` already bind
  the two final statuses as separate parameters. A part that applies to
  one status binds that status twice; `TaskSql` documents it, and no
  dialect changes.
- `TaskStore` gains overloads of `deleteFinished` and `archiveFinished`
  that take `statuses: Set<TaskStatus>`, the final statuses to remove.
  The worker calls them, with both statuses for the existing forms and
  one per part for `ByStatus`. Their default implementation hands both
  statuses to the existing methods and refuses a single one with an
  `UnsupportedOperationException`, so a custom store keeps compiling and
  fails only for a type that uses `ByStatus`, as `resourceRates` did in
  ADR 0040.

## Rejected alternatives

- **A period per status on `delete` and `archive`**, such as
  `delete(after, cancelledAfter)`. It cannot delete one status and
  archive the other, and every form would grow the same parameters.
- **An open `Retention` interface** for rules of the application's own.
  The sweep runs as batched statements in the database; a rule in
  application code would have to be expressed as statements too, which
  is what the store and dialect seams are for (ADR 0006, ADR 0008).
- **Retention for the statuses that wait for a person.** Rejected in
  ADR 0011; a person who wants such a row gone cancels it, and the
  `cancelled` part takes it from there.

## Consequences

- No change for existing types; no schema change.
- New public API: `Retention.ByStatus`, `Retention.byStatus`, and the
  two `TaskStore` overloads with statuses.
- A custom store that does not override the overloads cannot serve
  `ByStatus`; the sweep logs the refusal for that type and goes on, as
  it does for any failing part (ADR 0029).
- The guide shows the example above in the retention section.
