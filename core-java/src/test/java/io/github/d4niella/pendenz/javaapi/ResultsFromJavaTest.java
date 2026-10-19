package io.github.d4niella.pendenz.javaapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.d4niella.pendenz.PayloadCodec;
import io.github.d4niella.pendenz.TaskResult;
import io.github.d4niella.pendenz.TaskStatus;
import io.github.d4niella.pendenz.TaskType;
import io.github.d4niella.pendenz.Verdict;
import io.github.d4niella.pendenz.javaapi.GreetingCodec.Greeting;
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
}
