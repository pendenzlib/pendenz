# Chaining: follow-up tasks in the outcome's transaction

- Status: accepted
- Date: 2026-10-19

## Context

A handler often has a next step: the invoice went out, so remind in
fourteen days; the document was parsed, so run one task per section it
found; the order was placed, so notify the warehouse. Today a handler
that wants that calls `enqueue` from inside its code, in a transaction
of its own, and gets the classic pair of failures: the follow-up is
enqueued but the task's outcome is lost to a crash a moment later, so
the retry enqueues it again; or the outcome is recorded but the
follow-up never was. The planning asks for follow-ups in the same
transaction as the outcome, and for two ids that tie a chain together:
the parent, and a correlation that spans the whole chain.

## Decision

A handler collects follow-ups on its context; the worker inserts them in
the transaction that records the outcome; two columns tie them to their
origin.

- **`TaskContext.enqueue(type, payload, runAt, idempotencyKey, lane,
  priority, resource)`** buffers a follow-up. Nothing is written while
  the handler runs. When the handler returns normally, the worker
  inserts the follow-ups and the outcome in one transaction. When the
  handler throws, nothing is inserted; the retry runs the handler again
  and collects again. When the outcome is dropped because the row
  changed meanwhile, the follow-ups are dropped with it: the result they
  belong to was discarded.
- **`parentId`**, nullable, the id type of the table: the task whose
  handler enqueued this one. **`correlationId`**, nullable text: given
  by the caller at `enqueue`, inherited by every follow-up, so a whole
  chain is `WHERE correlation_id = ?`. A root task that was given none
  has none; its chain is found through `parent_id` from the root. Both
  columns are visible to handlers on the context.
- **Follow-ups inherit** the creator (they run on behalf of whoever
  started the chain), the lane (a cycle's follow-ups belong to the
  cycle, count in its progress and keep it from finishing until they
  are done) and the priority. The resource comes from the follow-up's
  own type unless given. The approval rule of the follow-up's type
  applies as for any enqueue; permissions are not checked, because the
  handler is the application's own code and principle 7 puts the
  decision there.
- No foreign key from `parent_id` to `id` in the generated DDL.
  Retention removes finished parents while children may still wait;
  an application that wants the constraint adds it with
  `ON DELETE SET NULL`.
- Tasks inserted with SQL set both columns as they like, and a cycle's
  `INSERT ... SELECT` may fill the correlation from the query through
  the same mechanism as the resource expression.
- The SQL recipes gain "the whole chain of a task" and "what spawned
  this task".

## Rejected alternatives

- **Handlers enqueue directly, in their own transaction.** What happens
  today, and the source of the duplicate-or-lost pair above.
- **Handlers run inside the outcome's transaction**, with the connection
  on the context, so that business writes commit with the outcome too.
  Tempting and dangerous: a handler that takes minutes holds a
  transaction open for minutes, with its locks and its idle connection,
  and the in-memory store has no transaction to offer. A deferred hook
  that runs business writes inside the outcome transaction just before
  the outcome is recorded would give the benefit without the open
  transaction; it is noted as a possible later addition, not decided.
- **A workflow engine**: a declared graph of steps with replay. A
  non-goal of the project; the README points to DBOS and Temporal.
- **Correlation set to the root's own id when none is given.** Uniform
  queries, but an identity column does not know its value before the
  insert, so it would cost a second statement per root task. Not worth
  it for a query that `parent_id` answers.

## Consequences

- Two columns, a few parameters on `TaskContext.enqueue`, a list of
  follow-ups the runner inserts right after `finish` in the same
  transaction (the store's `finish` is unchanged, see the note below).
- Follow-ups go with a `SUCCEEDED` outcome only. A result the validator
  refuses (ADR 0020) is `REJECTED`, and its follow-ups are dropped like
  those of a failure: the person who decides on the result decides on
  what follows it.
- Fan-out is natural: a handler may enqueue a thousand follow-ups; they
  go into one transaction with the outcome, which is one statement per
  follow-up today and a batch insert when someone measures it.
- The listener's `onEnqueued` fires for follow-ups as for any task.

## Decisions taken with the acceptance (2026-10-19)

1. Follow-ups inherit the lane; a handler may pass another one.
2. Follow-ups inherit the creator.
3. Roots have no correlation unless given one.
4. The deferred business-write hook is left for later.
5. No foreign key on `parent_id` in the generated DDL.

Corrected on review (2026-10-19): the cycle progress view and the
finishing sweep joined on the task type beside the lane, so follow-ups
of another type were not counted and a cycle could finish while they
ran. Both join on the lane alone now, see ADR 0016.

Noted during implementation: the worker inserts the follow-ups itself,
after `finish` reported that the outcome applied and inside the same
transaction, rather than passing them to the store; that keeps the
store's contract as it was and gives the worker the new ids for the
listener. A cycle's correlation is one value for the whole run, given
to `startCycle`; a per-row expression like the resource's was not
built, nobody having asked for a chain that starts inside a query.
