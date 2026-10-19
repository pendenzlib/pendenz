package io.github.d4niella.pendenz.javaapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.d4niella.pendenz.javaapi.GreetingCodec.Greeting;
import io.github.d4niella.pendenz.TaskStatus;
import io.github.d4niella.pendenz.TaskType;
import io.github.d4niella.pendenz.schema.TaskTable;
import io.github.d4niella.pendenz.testing.PostgresDatabase;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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

    private PendenzJava<Long> pendenz(JavaTaskHandler<Long, Greeting> handler) {
        return PendenzJava.builder(table).dataSource(db.getDataSource()).register(GREET, handler).build();
    }
}
