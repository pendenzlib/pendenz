package io.github.pendenzlib.javaapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pendenzlib.PayloadCodec;
import io.github.pendenzlib.TaskRecord;
import io.github.pendenzlib.TaskResult;
import io.github.pendenzlib.TaskStatus;
import io.github.pendenzlib.TaskType;
import io.github.pendenzlib.Verdict;
import io.github.pendenzlib.javaapi.GreetingCodec.Greeting;
import org.junit.jupiter.api.Test;

/** A result handler with a validator, written in Java. */
class ResultsFromJavaTest {

    static final TaskType<Greeting> GREET = new TaskType<>("greet", GreetingCodec.INSTANCE);

    /** The result is a plain string; a hand-written codec quotes it as JSON. */
    static final PayloadCodec<String> TEXT = new PayloadCodec<>() {
        @Override
        public String encode(String payload) {
            return "\"" + payload + "\"";
        }

        @Override
        public String decode(String text) {
            return text.substring(1, text.length() - 1);
        }
    };

    static final TaskResult<Greeting, String> POLITE = new TaskResult<>(TEXT, (greeting, reply) ->
            reply.startsWith("Dear") ? Verdict.accept() : Verdict.reject("not polite: " + reply));

    @Test
    void resultHandler_politeReply_succeedsWithTheResultStored() {
        try (PendenzJava<Long> pendenz = PendenzJava.builder()
                .inMemory()
                .register(GREET, POLITE, (ctx, g) -> "Dear " + g.name())
                .build()) {
            Long id = pendenz.enqueue(GREET, new Greeting("Ada"));

            pendenz.runPending();

            // Assert
            assertThat(pendenz.find(id).orElseThrow().getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
            assertThat(pendenz.find(id).orElseThrow().getResult()).isEqualTo("\"Dear Ada\"");
        }
    }

    @Test
    void resultHandler_rudeReply_rejectedWithTheReason() {
        try (PendenzJava<Long> pendenz = PendenzJava.builder()
                .inMemory()
                .register(GREET, POLITE, (ctx, g) -> "Hey " + g.name())
                .build()) {
            Long id = pendenz.enqueue(GREET, new Greeting("Bob"));

            pendenz.runPending();

            // Assert
            assertThat(pendenz.find(id).orElseThrow().getStatus()).isEqualTo(TaskStatus.REJECTED);
            assertThat(pendenz.find(id).orElseThrow().getLastError()).isEqualTo("not polite: Hey Bob");
        }
    }

    @Test
    void resultHandler_returnsNull_failsTheAttemptNamingTheType() {
        try (PendenzJava<Long> pendenz = PendenzJava.builder()
                .inMemory()
                .register(GREET, POLITE, (ctx, g) -> null)
                .build()) {
            Long id = pendenz.enqueue(GREET, new Greeting("Cleo"));

            pendenz.runPending();

            // Assert
            TaskRecord<Long> task = pendenz.find(id).orElseThrow();
            assertThat(task.getStatus()).isEqualTo(TaskStatus.RETRY_PENDING);
            assertThat(task.getLastError()).contains("'greet'", "returned null");
        }
    }
}
