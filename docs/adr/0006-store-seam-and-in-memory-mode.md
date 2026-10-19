# Store seam and in-memory mode

- Status: accepted
- Date: 2026-10-19

## Context

Principle 9 promises tests without infrastructure: a synchronous in-memory
mode that runs handlers directly. `TaskStore` is the seam meant for that,
but every one of its operations takes a `java.sql.Connection`, and
`Pendenz` obtains connections through `ConnectionProvider`. An in-memory
store would have to be handed a JDBC connection it does not use, and the
application would still need a data source to build the queue at all.

At the same time, `enqueue(connection, type, payload)` must keep working:
enqueuing inside the caller's transaction is the reason the library exists.

## Decision

The store owns its unit of work. `TaskStore` gains two operations and the
rest take an opaque handle instead of a connection:

- `transaction(block)` runs `block` inside a transaction the store manages
  and hands it a `StoreTransaction`.
- `adopt(connection)` wraps a JDBC connection the caller owns into a
  `StoreTransaction`. The caller commits or rolls back; the store never
  does. A store without a database accepts the connection and ignores it,
  so application code that enqueues inside its own transaction runs
  unchanged against the in-memory store.
- `insert`, `claim`, `finish` and the others take a `StoreTransaction`.

`StoreTransaction` is a marker interface. Each store defines its own
implementation and rejects handles that are not its own. This keeps the
interface free of generics and usable from Java.

`ConnectionProvider` moves from `Pendenz` into `JdbcTaskStore`, which is
the only place that needs connections. The builder keeps `dataSource(...)`
and `connectionProvider(...)` as the way to configure the default store.

`InMemoryTaskStore` implements the same contract with a map and a lock. It
has no transactions: every operation is atomic, nothing rolls back. The
builder enables it with `inMemory()`.

`Pendenz.runPending()` claims and runs due tasks in the calling coroutine,
one after the other, until nothing is due, and returns how many ran.
Outcomes are recorded exactly as a worker would record them: a throwing
handler leads to `RETRY_PENDING` or `FAILED`, not to an exception in the
test. The method works against any store; with the in-memory store and an
injected `Clock` it gives tests full control over time and order.

## Rejected alternatives

- **A generic `TaskStore<TX>`.** Type-safe handles, but the generic
  parameter spreads into `Pendenz` and the Java facade for no gain the
  user can see.
- **A fake `Connection` for the in-memory store.** Fifty methods of
  `java.sql.Connection` to stub, or a dynamic proxy, which the core does
  not use.
- **Running handlers inside `enqueue` in in-memory mode.** Makes `enqueue`
  mean two different things and hides retry behaviour. An explicit
  `runPending()` keeps the test readable: arrange, enqueue, run, assert.

## Consequences

- `TaskStore` changes incompatibly. Nothing is released yet.
- The in-memory store lives in the core: no new dependency, one more
  class to keep in step with `JdbcTaskStore`. Both run the same contract
  tests.
- `runPending()` is also a way to drive the queue from a cron job or a
  test against a real database without starting a worker.
