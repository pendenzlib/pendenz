package io.github.pendenzlib.javaapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pendenzlib.TaskType;
import io.github.pendenzlib.javaapi.GreetingCodec.Greeting;
import org.junit.jupiter.api.Test;

class EnqueueOptionsFromJavaTest {

    static final TaskType<Greeting> GREET = new TaskType<>("greet", GreetingCodec.INSTANCE);

    @Test
    void options_sharedBaseRefinedForOneCall_theBaseStaysAsItWas() {
        try (PendenzJava<Long> pendenz = PendenzJava.builder().inMemory().register(GREET, (ctx, p) -> {}).build()) {
            EnqueueOptions<Long> base = EnqueueOptions.<Long>defaults().createdBy("clerk");
            pendenz.enqueue(GREET, new Greeting("Ada"), base.lane("tenant-a"));

            // Act
            Long id = pendenz.enqueue(GREET, new Greeting("Bob"), base);

            // Assert
            assertThat(pendenz.find(id).orElseThrow().getLane()).isNull();
            assertThat(pendenz.find(id).orElseThrow().getCreatedBy()).isEqualTo("clerk");
        }
    }
}
