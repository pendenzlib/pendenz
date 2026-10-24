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
  thread, so they never join the caller's transaction.
