package io.github.d4niella.pendenz.javaapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.d4niella.pendenz.CycleResource;
import io.github.d4niella.pendenz.CycleStatus;
import io.github.d4niella.pendenz.CycleTasks;
import io.github.d4niella.pendenz.TaskQuery;
import io.github.d4niella.pendenz.TaskStatus;
import io.github.d4niella.pendenz.TaskType;
import io.github.d4niella.pendenz.javaapi.GreetingCodec.Greeting;
import io.github.d4niella.pendenz.TaskRecord;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/** A cycle from Java: start from a list, run, pause, resume, finish. */
class CyclesFromJavaTest {

    static final TaskType<Greeting> GREET = new TaskType<>("greet", GreetingCodec.INSTANCE);

    @Test
    void startCycle_listOfPayloads_runsThemAndMaintenanceFinishesTheCycle() {
        // Arrange
        List<String> greeted = new CopyOnWriteArrayList<>();
        try (PendenzJava<Long> pendenz = PendenzJava.builder()
                .inMemory()
                .register(GREET, (ctx, p) -> greeted.add(p.name()))
                .build()) {
            List<Greeting> guests = List.of(new Greeting("Ada"), new Greeting("Bob"));
            Long id = pendenz.startCycle(GREET, CycleTasks.of(guests), CycleOptions.<Long>none().description("welcome mails"));

            // Act
            pendenz.runPending();
            pendenz.runMaintenance();

            // Assert
            assertThat(greeted).containsExactlyInAnyOrder("Ada", "Bob");
            assertThat(pendenz.cycleProgress(id)).containsEntry(TaskStatus.SUCCEEDED, 2);
            assertThat(pendenz.findCycle(id).orElseThrow().getStatus()).isEqualTo(CycleStatus.FINISHED);
        }
    }

    @Test
    void startCycle_withIdResourceAndCorrelation_cycleAndTasksCarryThem() {
        try (PendenzJava<Long> pendenz = PendenzJava.builder().inMemory().register(GREET, (ctx, p) -> {}).build()) {
            CycleOptions<Long> options = CycleOptions.<Long>none()
                    .id(4711L)
                    .resource(CycleResource.fixed("mail-server"))
                    .correlationId("autumn-mailing");

            // Act
            Long id = pendenz.startCycle(GREET, CycleTasks.of(List.of(new Greeting("Dan"))), options);

            // Assert
            assertThat(id).isEqualTo(4711L);
            TaskRecord<Long> task = pendenz.tasks(new TaskQuery(null, Set.of(), Set.of(), "autumn-mailing")).get(0);
            assertThat(task.getResource()).isEqualTo("mail-server");
        }
    }

    @Test
    void cancelCycle_activeCycle_cancelsItsWaitingTasks() {
        try (PendenzJava<Long> pendenz = PendenzJava.builder().inMemory().register(GREET, (ctx, p) -> {}).build()) {
            Long id = pendenz.startCycle(GREET, CycleTasks.of(List.of(new Greeting("Eve"))));

            // Act
            boolean cancelled = pendenz.cancelCycle(id);

            // Assert
            assertThat(cancelled).isTrue();
            assertThat(pendenz.cycleProgress(id)).containsEntry(TaskStatus.CANCELLED, 1);
        }
    }

    @Test
    void pauseCycle_activeCycle_itsTasksWaitUntilResumed() {
        try (PendenzJava<Long> pendenz = PendenzJava.builder().inMemory().register(GREET, (ctx, p) -> {}).build()) {
            Long id = pendenz.startCycle(GREET, CycleTasks.of(List.of(new Greeting("Cleo"))));

            // Act
            boolean paused = pendenz.pauseCycle(id);

            // Assert
            assertThat(paused).isTrue();
            assertThat(pendenz.runPending()).isZero();
            assertThat(pendenz.resumeCycle(id)).isTrue();
            assertThat(pendenz.runPending()).isEqualTo(1);
        }
    }
}
