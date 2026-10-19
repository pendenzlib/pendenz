# Results: validation, provenance and preview

- Status: accepted
- Date: 2026-10-19

## Context

A handler returns nothing. Its outcome is "returned" or "threw", and
the row records which. Three things in the planning need more than
that, and they turn out to be one thing seen from three sides:

- **Validation**: a check after the handler ran that may refuse what it
  produced. The status model (ADR 0002) reserved `REJECTED` for it: not
  a failure, a retry does not help, a person decides.
- **Provenance**: what produced the result, as key-value pairs: the
  version of the code, of the rule set, of the model an AI handler
  called. For an auditor, and for the person who looks at a rejected
  result and wants to know which version made it.
- **Preview**: what an execution would do, computed without doing it,
  for the person who has to approve the task (ADR 0018) and would like
  to see what they approve.

All three are about the task's result, before or after the fact, and
all three must be visible in the row, because approval screens and
audits are built on SQL as much as on the API.

## Decision

The handler may produce a result; the task type may validate it; the
row stores result, provenance and preview as JSON.

- **Three columns**, nullable: `result` (jsonb), `provenance` (jsonb),
  `preview` (text).
- **Results are typed at registration, not on the task type.** A task
  type stays `TaskType<P>`. A handler that produces a result is a
  `ResultHandler<ID, P, R>` and is registered with a `TaskResult<R>`
  that says how to encode it and how to validate it:

  ```kotlin
  register(scoreTicket, TaskResult.json<Score>(validate = { payload, score ->
      if (score.value in 0..100) Verdict.accept() else Verdict.reject("score out of range")
  })) { context, ticket -> model.score(ticket) }
  ```

  The existing `register(type) { context, payload -> }` stays as it is;
  a handler without a result has nothing to validate.
- **Validation runs in the worker after the handler returned**, on the
  payload and the result. `Verdict.accept()` records `SUCCEEDED` with
  the result; `Verdict.reject(reason)` records `REJECTED` with the
  result and the reason in the error column. The retry policy is not
  consulted: a rejected result is a decision for a person, who restarts
  the task after fixing what produced it, or completes it by hand. A
  validator that throws is a handler failure and retries.
- **Provenance is attached by the handler**: `context.provenance("model",
  "claude-sonnet-5-5")`, `context.provenance("rules", "2026-10")`. Pairs
  are stored with every outcome, success, retry, failure and rejection
  alike, because provenance of a failure matters as much.
- **Preview is a function of the task type**: `TaskType(preview = {
  payload -> "Refund 120.00 EUR to customer 4711" })`, deterministic and
  without side effects. It is computed at `enqueue`, whenever the type
  has one, and stored in the `preview` column, so an approval screen in
  SQL shows it beside `created_by`. `TaskRecord` carries it too. For
  tasks a cycle creates from a query there is no payload in the JVM to
  preview; the column stays empty for them.
- **The `_needs_human` view** gains `preview`, `result` and
  `provenance`, so that what waits for a person comes with what the
  person needs to decide.
- The in-memory store keeps the three as the JDBC store does, the
  Jackson module offers `TaskResult.jackson(...)`, and the listener's
  `onSucceeded` and a new `onRejected` carry the result.

## Rejected alternatives

- **`TaskType<P, R>`.** Puts a second type parameter on every type,
  every registration and every facade, for the half of the types that
  have no result.
- **The handler's return value as the result.** A `TaskHandler` lambda
  returns whatever its last expression returns; `seen.add(payload)`
  returns a `Boolean`, and that must not become a result by accident.
  Results are explicit or they do not exist.
- **Validation inside the handler.** Then `REJECTED` would be a status
  the handler sets, with no guarantee that the result it rejects is the
  one that is stored. The worker validates what it is about to write.
- **Results in typed columns.** The payload columns of ADR 0013 could
  carry a result too. A result is for people and UIs, and JSON serves
  them; typed result columns can come through the same mapping when
  someone needs to join on a result.
- **Preview computed on demand by the API.** Invisible to the approval
  screens built on SQL, which are the ones principle 3 promises.
- **Provenance inside the result document.** Mixes what the handler
  produced with what produced it; an auditor wants the second without
  parsing the first.

## Consequences

- Three columns, a `ResultHandler` and a `TaskResult`, a `Verdict`,
  one more `register` overload, two listener events, provenance on the
  context, a preview on the type.
- `Outcome.Succeeded` carries the result and provenance;
  `Outcome.Rejected` joins the outcomes; `finish` writes the three
  columns.
- The MCP module (v0.4) can show an agent the preview of what it is
  about to enqueue and the result of what ran.
- Retention and archiving carry the three columns along, being whole
  rows.

## Decisions taken with the acceptance (2026-10-19)

1. Results are JSON; typed result columns wait for a request.
2. The validator sees payload and result.
3. The preview is computed at every enqueue of a type that has one.
4. Provenance has a column of its own.
5. `REJECTED` keeps the result.
6. Restarting a `REJECTED` task re-runs the handler.

Noted during implementation: a `TaskResult` pairs a codec with a
`ResultValidator`; the Jackson module needs no helper of its own, since
`JacksonCodec` is a `PayloadCodec` and fits the constructor directly.
`onSucceeded` keeps its signature; the result is read from the row.
