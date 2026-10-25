package io.github.d4niella.pendenz.javaapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.d4niella.pendenz.javaapi.GreetingCodec.Greeting;
import io.github.d4niella.pendenz.CycleTasks;
import io.github.d4niella.pendenz.TaskStatus;
import io.github.d4niella.pendenz.TaskType;
import io.github.d4niella.pendenz.schema.CycleTable;
import io.github.d4niella.pendenz.schema.IdType;
import io.github.d4niella.pendenz.schema.PostgresDdl;
import io.github.d4niella.pendenz.schema.TaskTable;
import io.github.d4niella.pendenz.testing.PostgresDatabase;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** The facade against a real PostgreSQL: the caller's transaction and a worker, from Java. */
class PendenzJavaPostgresTest {

    static final TaskType<Greeting> GREET = new TaskType<>("greet", GreetingCodec.INSTANCE);

    @RegisterExtension
    static final PostgresDatabase db = new PostgresDatabase();

    private TaskTable<Long> table;

    @BeforeEach
    void createTable() {
        table = db.createTable();
    }

    @Nested
    class Enqueue {
        @Test
        void enqueue_callersTransactionRolledBack_leavesNoTaskBehind() throws Exception {
            // Arrange
            try (PendenzJava<Long> pendenz = pendenz((ctx, p) -> {})) {
                Long id;

                // Act
                try (Connection connection = db.getDataSource().getConnection()) {
                    connection.setAutoCommit(false);
                    id = pendenz.enqueue(connection, GREET, new Greeting("Ada"));
                    connection.rollback();
                }

                assertThat(pendenz.find(id)).isEmpty();
            }
        }

        @Test
        void enqueue_callersTransactionCommitted_taskIsVisible() throws Exception {
            // Arrange
            try (PendenzJava<Long> pendenz = pendenz((ctx, p) -> {})) {
                Long id;

                // Act
                try (Connection connection = db.getDataSource().getConnection()) {
                    connection.setAutoCommit(false);
                    id = pendenz.enqueue(connection, GREET, new Greeting("Bob"));
                    connection.commit();
                }

                assertThat(pendenz.find(id).orElseThrow().getStatus()).isEqualTo(TaskStatus.PENDING);
            }
        }
    }

    @Nested
    class Worker {
        @Test
        void startWorker_pendingTask_runsTheBlockingHandlerAndRecordsSucceeded() {
            // Arrange
            List<String> greeted = new CopyOnWriteArrayList<>();
            try (PendenzJava<Long> pendenz = pendenz((ctx, p) -> greeted.add(p.name()))) {
                Long id = pendenz.enqueue(GREET, new Greeting("Cleo"));

                // Act
                try (WorkerHandle ignored =
                        pendenz.startWorker(new WorkerOptionsBuilder().pollInterval(Duration.ofMillis(50)).build())) {

                    // Assert
                    await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                            assertThat(pendenz.find(id).orElseThrow().getStatus()).isEqualTo(TaskStatus.SUCCEEDED));
                    assertThat(greeted).containsExactly("Cleo");
                }
            }
        }
    }

    @Nested
    class Cycles {
        @Test
        void startCycle_callersTransactionRolledBack_leavesNeitherCycleNorTasks() throws Exception {
            // Arrange
            CycleTable<Long> cycles = new CycleTable<>(IdType.BIGINT, table.getName() + "_cycle");
            db.sql(PostgresDdl.createCycleTable(cycles));
            try (PendenzJava<Long> pendenz = PendenzJava.builder(table)
                    .dataSource(db.getDataSource())
                    .cycles(cycles)
                    .register(GREET, (ctx, p) -> {})
                    .build()) {
                Long id;

                // Act
                try (Connection connection = db.getDataSource().getConnection()) {
                    connection.setAutoCommit(false);
                    id = pendenz.startCycle(connection, GREET, CycleTasks.of(List.of(new Greeting("Ada"))));
                    connection.rollback();
                }

                // Assert
                assertThat(pendenz.findCycle(id)).isEmpty();
                assertThat(db.count("SELECT count(*) FROM \"" + table.getName() + "\"")).isZero();
            }
        }
    }

    @Nested
    class ConnectionProviders {
        /** A transaction manager as a Java team writes one, over a plain data source. */
        static final class CountingProvider implements BlockingConnectionProvider {
            final AtomicInteger transactions = new AtomicInteger();

            @Override
            public <T> T inTransaction(Function<Connection, T> block) throws Exception {
                transactions.incrementAndGet();
                try (Connection connection = db.getDataSource().getConnection()) {
                    connection.setAutoCommit(false);
                    T result = block.apply(connection);
                    connection.commit();
                    return result;
                }
            }
        }

        @Test
        void enqueue_blockingProviderWrittenInJava_storesTheTaskThroughIt() {
            // Arrange
            CountingProvider provider = new CountingProvider();
            try (PendenzJava<Long> pendenz = PendenzJava.builder(table)
                    .connectionProvider(provider)
                    .register(GREET, (ctx, p) -> {})
                    .build()) {

                // Act
                Long id = pendenz.enqueue(GREET, new Greeting("Bob"));

                // Assert
                assertThat(pendenz.find(id).orElseThrow().getStatus()).isEqualTo(TaskStatus.PENDING);
                assertThat(provider.transactions.get()).isGreaterThanOrEqualTo(2);
            }
        }
    }

    private PendenzJava<Long> pendenz(JavaTaskHandler<Long, Greeting> handler) {
        return PendenzJava.builder(table).dataSource(db.getDataSource()).register(GREET, handler).build();
    }
}
