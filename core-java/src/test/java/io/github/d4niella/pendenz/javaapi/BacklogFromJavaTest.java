package io.github.d4niella.pendenz.javaapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.d4niella.pendenz.Backlog;
import io.github.d4niella.pendenz.TaskStatus;
import io.github.d4niella.pendenz.TaskType;
import io.github.d4niella.pendenz.javaapi.GreetingCodec.Greeting;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Reading the backlog from Java. */
class BacklogFromJavaTest {

    static final TaskType<Greeting> GREET = new TaskType<>("greet", GreetingCodec.INSTANCE);

    @Test
    void backlog_twoPendingTasks_countsTwoPendingForTheType() {
        try (PendenzJava<Long> pendenz = PendenzJava.builder()
                .inMemory()
                .register(GREET, (ctx, p) -> {})
                .build()) {
            // Arrange
            pendenz.enqueue(GREET, new Greeting("Ada"));
            pendenz.enqueue(GREET, new Greeting("Grace"));

            // Act
            List<Backlog> backlog = pendenz.backlog();

            // Assert
            assertThat(backlog).extracting(Backlog::getType).containsExactly("greet");
            assertThat(backlog.get(0).count(TaskStatus.PENDING)).isEqualTo(2L);
        }
    }
}
