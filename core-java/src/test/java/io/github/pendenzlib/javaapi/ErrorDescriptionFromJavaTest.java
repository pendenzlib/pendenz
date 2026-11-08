package io.github.pendenzlib.javaapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pendenzlib.ErrorDescription;
import io.github.pendenzlib.NoRetry;
import io.github.pendenzlib.TaskType;
import io.github.pendenzlib.javaapi.GreetingCodec.Greeting;
import org.junit.jupiter.api.Test;

/** An error description written in Java decides what a failed attempt leaves in last_error (ADR 0043). */
class ErrorDescriptionFromJavaTest {

    @Test
    void errorDescription_lambdaNamingTypeAndClass_isStoredAsLastError() {
        TaskType<Greeting> greet = new TaskType<>("greet", GreetingCodec.INSTANCE, NoRetry.INSTANCE);
        ErrorDescription typeAndClass =
                (context, error) -> context.getType() + ": " + error.getClass().getSimpleName();
        try (PendenzJava<Long> pendenz = PendenzJava.builder()
                .inMemory()
                .errorDescription(typeAndClass)
                .register(greet, (ctx, p) -> {
                    throw new IllegalStateException("smtp password rejected for " + p.name());
                })
                .build()) {
            // Arrange
            Long id = pendenz.enqueue(greet, new Greeting("Ada"));

            // Act
            pendenz.runPending();

            // Assert
            assertThat(pendenz.find(id).orElseThrow().getLastError()).isEqualTo("greet: IllegalStateException");
        }
    }
}
