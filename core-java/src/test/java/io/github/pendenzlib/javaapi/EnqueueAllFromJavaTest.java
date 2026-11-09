package io.github.pendenzlib.javaapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pendenzlib.EnqueueRequest;
import io.github.pendenzlib.TaskRecord;
import io.github.pendenzlib.TaskType;
import io.github.pendenzlib.javaapi.GreetingCodec.Greeting;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Many tasks in one call from Java, with requests built by position (ADR 0046). */
class EnqueueAllFromJavaTest {

    static final TaskType<Greeting> GREET = TaskType.builder("greet", GreetingCodec.INSTANCE).build();

    @Test
    void enqueueAll_requestsWithAndWithoutKey_returnsIdsInOrderWithTheCreatorOnEach() {
        try (PendenzJava<Long> pendenz =
                PendenzJava.builder().inMemory().register(GREET, (ctx, p) -> {}).build()) {
            // Arrange
            List<EnqueueRequest<Greeting>> requests = List.of(
                    new EnqueueRequest<>(GREET, new Greeting("Ada"), "welcome-ada"),
                    new EnqueueRequest<>(GREET, new Greeting("Bob")));

            // Act
            List<Long> ids = pendenz.enqueueAll(requests, "signup-import");

            // Assert
            List<TaskRecord<Long>> tasks =
                    ids.stream().map(id -> pendenz.find(id).orElseThrow()).toList();
            assertThat(tasks).extracting(TaskRecord::getIdempotencyKey).containsExactly("welcome-ada", null);
            assertThat(tasks).extracting(TaskRecord::getCreatedBy).containsOnly("signup-import");
        }
    }
}
