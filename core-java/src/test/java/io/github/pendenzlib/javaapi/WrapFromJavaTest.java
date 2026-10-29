package io.github.pendenzlib.javaapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pendenzlib.Pendenz;
import io.github.pendenzlib.TaskStatus;
import io.github.pendenzlib.TaskType;
import io.github.pendenzlib.javaapi.GreetingCodec.Greeting;
import io.github.pendenzlib.schema.IdType;
import io.github.pendenzlib.schema.TaskTable;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/** The facade in front of a queue built with the Kotlin builder, as a framework integration builds it. */
class WrapFromJavaTest {

    static final TaskType<Greeting> GREET = new TaskType<>("greet", GreetingCodec.INSTANCE);

    @Test
    void wrap_queueFromTheKotlinBuilder_facadeRunsTheJavaHandlerRegisteredThere() {
        // Arrange
        List<String> greeted = new CopyOnWriteArrayList<>();
        Pendenz<Long> queue = Pendenz.builder(new TaskTable<>(IdType.BIGINT))
                .inMemory()
                .register(GREET, JavaHandlers.<Long, Greeting>asTaskHandler((ctx, g) -> greeted.add(g.name())))
                .build();

        try (PendenzJava<Long> pendenz = PendenzJava.wrap(queue)) {
            Long id = pendenz.enqueue(GREET, new Greeting("Ada"));

            // Act
            pendenz.runPending();

            // Assert
            assertThat(greeted).containsExactly("Ada");
            assertThat(pendenz.find(id)).hasValueSatisfying(t -> assertThat(t.getStatus()).isEqualTo(TaskStatus.SUCCEEDED));
            assertThat(pendenz.asKotlin()).isSameAs(queue);
        }
    }

    @Test
    void asTaskHandler_ownExecutor_runsTheHandlerOnItsThread() {
        // Arrange
        ExecutorService mail = Executors.newSingleThreadExecutor(r -> new Thread(r, "mail-handlers"));
        List<String> threads = new CopyOnWriteArrayList<>();
        Pendenz<Long> queue = Pendenz.builder(new TaskTable<>(IdType.BIGINT))
                .inMemory()
                .register(GREET, JavaHandlers.<Long, Greeting>asTaskHandler(
                        (ctx, g) -> threads.add(Thread.currentThread().getName()), mail))
                .build();
        try (PendenzJava<Long> pendenz = PendenzJava.wrap(queue)) {
            pendenz.enqueue(GREET, new Greeting("Bob"));

            // Act
            pendenz.runPending();

            // Assert
            assertThat(threads).singleElement().asString().startsWith("mail-handlers");
        } finally {
            mail.shutdown();
        }
    }
}
