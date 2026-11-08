package io.github.pendenzlib.javaapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pendenzlib.OnDuplicateKey;
import io.github.pendenzlib.TaskType;
import io.github.pendenzlib.javaapi.GreetingCodec.Greeting;
import org.junit.jupiter.api.Test;

/** A task type built in Java whose repeat enqueue replaces the waiting task (ADR 0044). */
class DuplicateKeyFromJavaTest {

    @Test
    void enqueue_keyTakenByWaitingTaskOfReplacingType_returnsItsIdWithTheNewPayload() {
        TaskType<Greeting> greet = TaskType.builder("greet", GreetingCodec.INSTANCE)
                .onDuplicateKey(OnDuplicateKey.REPLACE)
                .build();
        try (PendenzJava<Long> pendenz =
                PendenzJava.builder().inMemory().register(greet, (ctx, p) -> {}).build()) {
            // Arrange
            EnqueueOptions<Long> keyed = EnqueueOptions.<Long>defaults().idempotencyKey("welcome-42");
            Long first = pendenz.enqueue(greet, new Greeting("Ada"), keyed);

            // Act
            Long repeat = pendenz.enqueue(greet, new Greeting("Ada Lovelace"), keyed);

            // Assert
            assertThat(repeat).isEqualTo(first);
            assertThat(pendenz.find(first).orElseThrow().getPayload().get("payload").toString())
                    .contains("Ada Lovelace");
        }
    }
}
