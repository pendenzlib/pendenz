package io.github.d4niella.pendenz.javaapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.d4niella.pendenz.ExponentialBackoff;
import io.github.d4niella.pendenz.javaapi.GreetingCodec.Greeting;
import io.github.d4niella.pendenz.Retention;
import io.github.d4niella.pendenz.TaskContext;
import io.github.d4niella.pendenz.TaskListener;
import io.github.d4niella.pendenz.TaskStatus;
import io.github.d4niella.pendenz.TaskType;
import io.github.d4niella.pendenz.WorkerOptions;
import io.github.d4niella.pendenz.schema.IdType;
import io.github.d4niella.pendenz.schema.TaskTable;
import io.github.d4niella.pendenz.testing.MutableClock;
import io.github.d4niella.pendenz.TaskRecord;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Written in Java on purpose: these tests fail to compile if the facade stops being pleasant to
 * call from Java. They run against the in-memory store; the queue itself is covered in Kotlin.
 */
class PendenzJavaTest {

    static final TaskType<Greeting> GREET = new TaskType<>("greet", GreetingCodec.INSTANCE);

    @Nested
    class Enqueue {
        @Test
        void enqueue_defaultTable_returnsALongIdAndFindReadsItBack() {
            // Arrange
            try (PendenzJava<Long> pendenz = PendenzJava.builder().inMemory().register(GREET, (ctx, p) -> {}).build()) {

                Long id = pendenz.enqueue(GREET, new Greeting("Ada"));

                // Assert
                Optional<TaskRecord<Long>> record = pendenz.find(id);
                assertThat(record).isPresent();
                assertThat(record.get().getStatus()).isEqualTo(TaskStatus.PENDING);
                assertThat(record.get().getPayload().get("payload").toString()).contains("Ada");
            }
        }

        @Test
        void enqueue_uuidTableWithOptions_storesKeyCreatorAndChosenId() {
            // Arrange
            TaskTable<UUID> table = new TaskTable<>(IdType.UUID, "greeting_task");
            UUID chosen = UUID.fromString("7d3f0a5e-2c1b-4e8a-9f6d-0b1c2d3e4f50");
            try (PendenzJava<UUID> pendenz = PendenzJava.builder(table).inMemory().register(GREET, (ctx, p) -> {}).build()) {
                EnqueueOptions<UUID> options =
                        EnqueueOptions.<UUID>none().id(chosen).idempotencyKey("ticket-4711").createdBy("ada");

                UUID id = pendenz.enqueue(GREET, new Greeting("Bob"), options);

                // Assert
                assertThat(id).isEqualTo(chosen);
                TaskRecord<UUID> record = pendenz.find(chosen).orElseThrow();
                assertThat(record.getIdempotencyKey()).isEqualTo("ticket-4711");
                assertThat(record.getCreatedBy()).isEqualTo("ada");
            }
        }

        @Test
        void enqueue_optionsWithResourceAndCorrelation_storesBoth() {
            try (PendenzJava<Long> pendenz = PendenzJava.builder().inMemory().register(GREET, (ctx, p) -> {}).build()) {
                EnqueueOptions<Long> options =
                        EnqueueOptions.<Long>none().resource("mail-server").correlationId("order-77");

                Long id = pendenz.enqueue(GREET, new Greeting("Dan"), options);

                // Assert
                TaskRecord<Long> record = pendenz.find(id).orElseThrow();
                assertThat(record.getResource()).isEqualTo("mail-server");
                assertThat(record.getCorrelationId()).isEqualTo("order-77");
            }
        }

        @Test
        void enqueueAsync_defaultTable_completesWithTheId() throws Exception {
            try (PendenzJava<Long> pendenz = PendenzJava.builder().inMemory().register(GREET, (ctx, p) -> {}).build()) {

                Long id = pendenz.enqueueAsync(GREET, new Greeting("Cleo")).get();

                assertThat(pendenz.findAsync(id).get()).isPresent();
            }
        }
    }

    @Nested
    class Execution {
        @Test
        void runPending_blockingHandler_returnsAfterItRanAndRecordsSucceeded() {
            // Arrange
            List<String> greeted = new CopyOnWriteArrayList<>();
            try (PendenzJava<Long> pendenz =
                    PendenzJava.builder().inMemory().register(GREET, (ctx, p) -> greeted.add(p.name())).build()) {
                Long id = pendenz.enqueue(GREET, new Greeting("Dan"));

                int ran = pendenz.runPending();

                // Assert
                assertThat(ran).isEqualTo(1);
                assertThat(greeted).containsExactly("Dan");
                assertThat(pendenz.find(id).orElseThrow().getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
            }
        }

        @Test
        void startWorker_blockingHandlerThatThrows_recordsRetryPendingWithTheMessage() {
            // Arrange
            TaskType<Greeting> flaky =
                    new TaskType<>("flaky", GreetingCodec.INSTANCE, new ExponentialBackoff(3, Duration.ofMinutes(1)));
            try (PendenzJava<Long> pendenz = PendenzJava.builder()
                    .inMemory()
                    .register(flaky, (ctx, p) -> {
                        throw new IllegalStateException("mail server down");
                    })
                    .build()) {
                Long id = pendenz.enqueue(flaky, new Greeting("Eve"));

                // Act
                try (WorkerHandle worker = pendenz.startWorker(new WorkerOptionsBuilder().pollInterval(Duration.ofMillis(20)).build())) {

                    // Assert
                    await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                        TaskRecord<Long> record = pendenz.find(id).orElseThrow();
                        assertThat(record.getStatus()).isEqualTo(TaskStatus.RETRY_PENDING);
                        assertThat(record.getLastError()).contains("mail server down");
                    });
                    assertThat(worker.isRunning()).isTrue();
                }
            }
        }

        @Test
        void close_blockingHandlerIgnoresInterruption_itsResultIsRecordedNotGivenBack() {
            // Arrange: a handler that sleeps through the interrupt, as legacy code does
            PendenzJava<Long> pendenz = PendenzJava.builder()
                    .inMemory()
                    .register(GREET, (ctx, p) -> {
                        long until = System.currentTimeMillis() + 400;
                        while (System.currentTimeMillis() < until) {
                            try {
                                Thread.sleep(50);
                            } catch (InterruptedException ignored) {
                                // keeps going on purpose
                            }
                        }
                    })
                    .build();
            Long id = pendenz.enqueue(GREET, new Greeting("Gus"));
            pendenz.startWorker(new WorkerOptionsBuilder()
                    .pollInterval(Duration.ofMillis(20))
                    .shutdownTimeout(Duration.ofMillis(50))
                    .build());
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertThat(pendenz.find(id).orElseThrow().getStatus()).isEqualTo(TaskStatus.RUNNING));

            // Act
            pendenz.close();

            // Assert
            assertThat(pendenz.find(id).orElseThrow().getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
        }

        @Test
        void close_workerRunning_stopsItAndReturns() {
            // Arrange
            PendenzJava<Long> pendenz = PendenzJava.builder().inMemory().register(GREET, (ctx, p) -> {}).build();
            WorkerHandle worker = pendenz.startWorker();

            pendenz.close();

            assertThat(worker.isRunning()).isFalse();
        }
    }

    @Nested
    class Hooks {
        /** Implements one hook only: proves the other methods have Java default implementations. */
        static class CountingListener implements TaskListener<Long> {
            final List<Long> succeeded = new CopyOnWriteArrayList<>();

            @Override
            public void onSucceeded(TaskContext<Long> task, Duration duration) {
                succeeded.add(task.getId());
            }
        }

        @Test
        void listener_implementsOneHookOnly_isCalledForThatHook() {
            // Arrange
            CountingListener listener = new CountingListener();
            try (PendenzJava<Long> pendenz =
                    PendenzJava.builder().inMemory().listener(listener).register(GREET, (ctx, p) -> {}).build()) {
                Long id = pendenz.enqueue(GREET, new Greeting("Fay"));

                pendenz.runPending();

                assertThat(listener.succeeded).containsExactly(id);
            }
        }
    }

    @Nested
    class Options {
        @Test
        void workerOptionsBuilder_nothingSet_buildsTheDefaultsOfWorkerOptions() {
            WorkerOptions built = new WorkerOptionsBuilder().build();

            assertThat(built).usingRecursiveComparison().isEqualTo(new WorkerOptions());
        }
    }

    @Nested
    class TaskTypes {
        @Test
        void runMaintenance_retentionFromJavaPassed_deletesTheSucceededTask() {
            // Arrange
            TaskType<Greeting> type = new TaskType<>(
                    "greet", GreetingCodec.INSTANCE, new ExponentialBackoff(), 2, java.util.Map.of(), Retention.delete(Duration.ofDays(7)));
            MutableClock clock = new MutableClock();
            try (PendenzJava<Long> pendenz =
                    PendenzJava.builder().inMemory().clock(clock).register(type, (ctx, p) -> {}).build()) {
                Long id = pendenz.enqueue(type, new Greeting("Gus"));
                pendenz.runPending();
                clock.advance(Duration.ofDays(8));

                int removed = pendenz.runMaintenance();

                // Assert
                assertThat(removed).isEqualTo(1);
                assertThat(pendenz.find(id)).isEmpty();
            }
        }
    }
}
