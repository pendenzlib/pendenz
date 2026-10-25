package io.github.d4niella.pendenz.javaapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.d4niella.pendenz.TaskQuery;
import io.github.d4niella.pendenz.TaskType;
import io.github.d4niella.pendenz.javaapi.GreetingCodec.Greeting;
import io.github.d4niella.pendenz.TaskRecord;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** A follow-up enqueued from a blocking handler, with the short call Java has (ADR 0019). */
class FollowUpsFromJavaTest {

    static final TaskType<Greeting> WELCOME = new TaskType<>("welcome", GreetingCodec.INSTANCE);
    static final TaskType<Greeting> REMIND = new TaskType<>("remind", GreetingCodec.INSTANCE);

    @Test
    void enqueue_onTheContextWithTypeAndPayloadOnly_followUpInheritsTheParentsLane() {
        try (PendenzJava<Long> pendenz = PendenzJava.builder()
                .inMemory()
                .register(WELCOME, (ctx, p) -> ctx.enqueue(REMIND, new Greeting(p.name())))
                .register(REMIND, (ctx, p) -> {})
                .build()) {
            pendenz.enqueue(WELCOME, new Greeting("Ada"), EnqueueOptions.<Long>none().lane("tenant-a"));

            // Act
            pendenz.runPending();

            // Assert
            List<TaskRecord<Long>> reminders = pendenz.tasks(new TaskQuery(null, Set.of("remind")));
            assertThat(reminders).singleElement().extracting(TaskRecord::getLane).isEqualTo("tenant-a");
        }
    }
}
