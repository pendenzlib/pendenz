package io.github.pendenzlib.javaapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pendenzlib.CycleResource;
import io.github.pendenzlib.CycleTasks;
import io.github.pendenzlib.TaskQuery;
import io.github.pendenzlib.TaskRecord;
import io.github.pendenzlib.TaskType;
import io.github.pendenzlib.javaapi.GreetingCodec.Greeting;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** A cycle started from a blocking handler, with the calls Java has (ADR 0047). */
class HandlerCycleFromJavaTest {

    static final TaskType<Greeting> START_MAILING = new TaskType<>("start-mailing", GreetingCodec.INSTANCE);
    static final TaskType<Greeting> GREET = new TaskType<>("greet", GreetingCodec.INSTANCE);
    static final List<Greeting> GUESTS = List.of(new Greeting("Ada"), new Greeting("Bob"));

    @Test
    void startCycle_onTheContextWithTypeAndTasksOnly_cycleTasksNameTheStartingTaskAsParent() {
        try (PendenzJava<Long> pendenz = PendenzJava.builder()
                .inMemory()
                .register(START_MAILING, (ctx, p) -> ctx.startCycle(GREET, CycleTasks.of(GUESTS)))
                .register(GREET, (ctx, p) -> {})
                .build()) {
            Long starting = pendenz.enqueue(START_MAILING, new Greeting("autumn"));

            // Act
            pendenz.runPending();

            // Assert
            List<TaskRecord<Long>> greetings = pendenz.tasks(new TaskQuery(null, Set.of("greet")));
            assertThat(greetings).hasSize(2).extracting(TaskRecord::getParentId).containsOnly(starting);
        }
    }

    @Test
    void startCycle_onTheContextWithDescriptionPriorityIdAndResource_cycleAndTasksCarryThem() {
        try (PendenzJava<Long> pendenz = PendenzJava.builder()
                .inMemory()
                .register(START_MAILING, (ctx, p) -> ctx.startCycle(
                        GREET, CycleTasks.of(GUESTS), "autumn mailing", 5, 4711L, CycleResource.fixed("mail-server")))
                .register(GREET, (ctx, p) -> {})
                .build()) {
            pendenz.enqueue(START_MAILING, new Greeting("autumn"));

            // Act
            pendenz.runPending();

            // Assert
            assertThat(pendenz.findCycle(4711L).orElseThrow().getDescription()).isEqualTo("autumn mailing");
            List<TaskRecord<Long>> greetings = pendenz.tasks(new TaskQuery(null, Set.of("greet")));
            assertThat(greetings).extracting(TaskRecord::getPriority).containsOnly(5);
            assertThat(greetings).extracting(TaskRecord::getResource).containsOnly("mail-server");
        }
    }
}
