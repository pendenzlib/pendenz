# From Java

`pendenz-core-java` puts a facade in front of the queue: blocking methods
and `CompletableFuture` instead of suspend functions, and handlers that are
plain blocking methods. Its tests are written in Java, as proof that the
API reads well from there.

```java
record SendInvoice(long invoiceId, String customer) {}

TaskType<SendInvoice> sendInvoice = new TaskType<>("send-invoice", JacksonCodec.of(SendInvoice.class));

try (PendenzJava<Long> pendenz = PendenzJava.builder(new TaskTable<>(IdType.BIGINT, "billing_task"))
        .dataSource(dataSource)
        .register(sendInvoice, (ctx, payload) -> invoiceService.send(payload.invoiceId()))
        .build()) {
    pendenz.enqueue(sendInvoice, new SendInvoice(42L, "Globex"));
    try (WorkerHandle worker = pendenz.startWorker()) {
        // ...
    }
}
```

- `JacksonCodec` comes from `pendenz-serialization-jackson`, for teams
  without kotlinx-serialization; payload upgrades work through it too.
- Handlers run on `Dispatchers.IO`, so blocking inside them is expected.
  A cancelled task interrupts the handler's thread.
- `enqueue(connection, type, payload)` enqueues inside the caller's
  transaction; `connectionProvider(...)` on the builder hands the queue
  connections from a transaction manager, such as Spring's, see
  [`spring.md`](spring.md).
- `PendenzJava.wrap(pendenz)` puts the facade in front of a queue built
  with the Kotlin builder; `asKotlin()` goes the other way.
- `*Async` methods return a `CompletableFuture` and run on another
  thread, so they never join the caller's transaction. Closing the facade
  stops its workers and lets `*Async` calls already under way finish;
  afterwards it refuses new ones.
- `EnqueueOptions` and `CycleOptions` carry the optional parts of an
  enqueue and of a cycle. Each setter returns new options, so a shared
  base can be refined per call: `base.lane("tenant-a")` leaves `base` as
  it was.
- A handler enqueues follow-ups with `ctx.enqueue(type, payload)`; the
  follow-up inherits the handler's lane, creator and correlation.
- A transaction manager written in Java implements
  `BlockingConnectionProvider` and goes to the builder's
  `connectionProvider(...)`; the queue calls it on the caller's thread.
- `handlerDispatcher(...)` on the builder applies to every handler,
  whether registered before or after it.
