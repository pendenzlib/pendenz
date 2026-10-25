package io.github.d4niella.pendenz.javaapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.d4niella.pendenz.TaskType;
import io.github.d4niella.pendenz.javaapi.GreetingCodec.Greeting;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/** How the facade is configured and closed: the handler dispatcher, and what close leaves standing. */
class LifecycleFromJavaTest {

    static final TaskType<Greeting> GREET = new TaskType<>("greet", GreetingCodec.INSTANCE);

    @Test
    void handlerExecutor_setAfterTheHandlerWasRegistered_runsThatHandlerToo() {
        // Arrange
        ExecutorService billing = Executors.newSingleThreadExecutor(r -> new Thread(r, "billing-handlers"));
        List<String> threads = new CopyOnWriteArrayList<>();
        try (PendenzJava<Long> pendenz = PendenzJava.builder()
                .inMemory()
                .register(GREET, (ctx, p) -> threads.add(Thread.currentThread().getName()))
                .handlerExecutor(billing)
                .build()) {
            pendenz.enqueue(GREET, new Greeting("Ada"));

            // Act
            pendenz.runPending();

            // Assert
            assertThat(threads).singleElement().asString().startsWith("billing-handlers");
        } finally {
            billing.shutdown();
        }
    }

    @Test
    void enqueueAsync_afterClose_throwsIllegalStateException() {
        // Arrange
        PendenzJava<Long> pendenz = PendenzJava.builder().inMemory().register(GREET, (ctx, p) -> {}).build();
        pendenz.close();

        // Act
        Throwable thrown = catchThrowable(() -> pendenz.enqueueAsync(GREET, new Greeting("Bob")));

        // Assert
        assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");
    }

    @Test
    void close_whileAnAsyncEnqueueIsUnderWay_theEnqueueStillCompletes() throws Exception {
        // Arrange
        PendenzJava<Long> pendenz = PendenzJava.builder().inMemory().register(GREET, (ctx, p) -> {}).build();
        CompletableFuture<Long> enqueued = pendenz.enqueueAsync(GREET, new Greeting("Cleo"));

        // Act
        pendenz.close();

        // Assert
        Long id = enqueued.get();
        assertThat(pendenz.find(id)).isPresent();
    }
}
