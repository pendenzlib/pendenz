package io.github.d4niella.pendenz.javaapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.d4niella.pendenz.TaskQuery;
import io.github.d4niella.pendenz.TaskStatus;
import io.github.d4niella.pendenz.TaskType;
import io.github.d4niella.pendenz.javaapi.GreetingCodec.Greeting;
import io.github.d4niella.pendenz.TaskRecord;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Querying tasks from Java, with the overloads a Java caller sees. */
class TaskQueryFromJavaTest {

    static final TaskType<Greeting> GREET = new TaskType<>("greet", GreetingCodec.INSTANCE);

    @Test
    void tasks_filteredByCreatorAndStatus_returnsOnlyThatCreatorsPendingTasks() {
        try (PendenzJava<Long> pendenz = PendenzJava.builder()
                .inMemory()
                .register(GREET, (ctx, p) -> {})
                .build()) {
            // Arrange
            Long own = pendenz.enqueue(GREET, new Greeting("Ada"), EnqueueOptions.<Long>defaults().createdBy("ticket-agent"));
            pendenz.enqueue(GREET, new Greeting("Grace"), EnqueueOptions.<Long>defaults().createdBy("billing-clerk"));
            TaskQuery query = new TaskQuery("ticket-agent", Set.of(), Set.of(TaskStatus.PENDING));

            // Act
            List<TaskRecord<Long>> found = pendenz.tasks(query);

            // Assert
            assertThat(found).extracting(TaskRecord::getId).containsExactly(own);
        }
    }

    @Test
    void tasks_secondPageAfterTheFirst_returnsTheOlderTask() {
        try (PendenzJava<Long> pendenz = PendenzJava.builder()
                .inMemory()
                .register(GREET, (ctx, p) -> {})
                .build()) {
            // Arrange
            Long older = pendenz.enqueue(GREET, new Greeting("Ada"));
            pendenz.enqueue(GREET, new Greeting("Bob"));
            TaskQuery onePerPage = new TaskQuery(null, Set.of(), Set.of(), null, 1);
            List<TaskRecord<Long>> first = pendenz.tasks(onePerPage);

            // Act
            List<TaskRecord<Long>> second = pendenz.tasks(onePerPage.after(first.get(0)));

            // Assert
            assertThat(second).extracting(TaskRecord::getId).containsExactly(older);
        }
    }
}
