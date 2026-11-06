package io.github.pendenzlib.javaapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pendenzlib.CallerDefaults;
import io.github.pendenzlib.NoRetry;
import io.github.pendenzlib.TaskRecord;
import io.github.pendenzlib.TaskStatus;
import io.github.pendenzlib.TaskType;
import io.github.pendenzlib.javaapi.GreetingCodec.Greeting;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/** Blocking interceptors and caller defaults written in Java (ADR 0036). */
class InterceptorsFromJavaTest {

    static final TaskType<Greeting> GREET = new TaskType<>("greet", GreetingCodec.INSTANCE, NoRetry.INSTANCE);

    /** Stands for a framework's thread-local, such as the MDC or a security context. */
    static final ThreadLocal<String> TENANT = new ThreadLocal<>();

    final List<String> calls = new CopyOnWriteArrayList<>();

    @Test
    void interceptor_setsAThreadLocal_theBlockingHandlerReadsItOnItsThread() {
        // Arrange
        BlockingTaskInterceptor<Long> tenant = (context, proceed) -> {
            TENANT.set("tenant-of-" + context.getCreatedBy());
            try {
                proceed.proceed();
            } finally {
                TENANT.remove();
            }
        };
        try (PendenzJava<Long> pendenz = PendenzJava.builder()
                .inMemory()
                .interceptor(tenant)
                .register(GREET, (ctx, greeting) -> calls.add(greeting.name() + " for " + TENANT.get()))
                .build()) {
            pendenz.enqueue(GREET, new Greeting("Ada"), EnqueueOptions.<Long>defaults().createdBy("acme"));

            // Act
            pendenz.runPending();

            // Assert
            assertThat(calls).containsExactly("Ada for tenant-of-acme");
        }
    }

    @Test
    void interceptors_twoRegistered_runInRegistrationOrderAroundTheHandler() {
        try (PendenzJava<Long> pendenz = PendenzJava.builder()
                .inMemory()
                .interceptor(recording("tracing"))
                .interceptor(recording("logging"))
                .register(GREET, (ctx, greeting) -> calls.add("handler " + greeting.name()))
                .build()) {
            pendenz.enqueue(GREET, new Greeting("Ada"));

            // Act
            pendenz.runPending();

            // Assert
            assertThat(calls)
                    .containsExactly(
                            "tracing before", "logging before", "handler Ada", "logging after", "tracing after");
        }
    }

    @Test
    void interceptor_aroundAResultHandler_theResultIsStored() {
        try (PendenzJava<Long> pendenz = PendenzJava.builder()
                .inMemory()
                .interceptor(recording("logging"))
                .register(GREET, ResultsFromJavaTest.POLITE, (ctx, greeting) -> "Dear " + greeting.name())
                .build()) {
            Long id = pendenz.enqueue(GREET, new Greeting("Ada"));

            // Act
            pendenz.runPending();

            // Assert
            TaskRecord<Long> task = pendenz.find(id).orElseThrow();
            assertThat(task.getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
            assertThat(task.getResult()).contains("Dear Ada");
            assertThat(calls).containsExactly("logging before", "logging after");
        }
    }

    @Test
    void interceptor_returnsWithoutCallingProceed_failedAttemptAndTheHandlerDoesNotRun() {
        try (PendenzJava<Long> pendenz = PendenzJava.builder()
                .inMemory()
                .interceptor((context, proceed) -> {})
                .register(GREET, (ctx, greeting) -> calls.add("handler"))
                .build()) {
            Long id = pendenz.enqueue(GREET, new Greeting("Ada"));

            // Act
            pendenz.runPending();

            // Assert
            TaskRecord<Long> task = pendenz.find(id).orElseThrow();
            assertThat(task.getStatus()).isEqualTo(TaskStatus.FAILED);
            assertThat(task.getLastError()).contains("returned without calling proceed");
            assertThat(calls).isEmpty();
        }
    }

    @Test
    void callerDefaults_implementedInJava_fillTheCreatorOfEnqueue() {
        try (PendenzJava<Long> pendenz = PendenzJava.builder()
                .inMemory()
                .callerDefaults(new SignedInUser())
                .register(GREET, (ctx, greeting) -> {})
                .build()) {
            TENANT.set("ada@example.com");
            Long id;
            try {
                id = pendenz.enqueue(GREET, new Greeting("Ada"));
            } finally {
                TENANT.remove();
            }

            TaskRecord<Long> task = pendenz.find(id).orElseThrow();

            assertThat(task.getCreatedBy()).isEqualTo("ada@example.com");
        }
    }

    @Test
    void callerDefaults_enqueueAsync_readOnTheCallingThreadBeforeTheWrite() {
        try (PendenzJava<Long> pendenz = PendenzJava.builder()
                .inMemory()
                .callerDefaults(new SignedInUser())
                .register(GREET, (ctx, greeting) -> {})
                .build()) {
            TENANT.set("ada@example.com");
            Long id;
            try {
                id = pendenz.enqueueAsync(GREET, new Greeting("Ada")).join();
            } finally {
                TENANT.remove();
            }

            TaskRecord<Long> task = pendenz.find(id).orElseThrow();

            assertThat(task.getCreatedBy()).isEqualTo("ada@example.com");
        }
    }

    /** Reads the user a request filter put on the thread; only the actor, the correlation id keeps its default. */
    static final class SignedInUser implements CallerDefaults {
        @Override
        public String actor() {
            return TENANT.get();
        }
    }

    private BlockingTaskInterceptor<Long> recording(String name) {
        return (context, proceed) -> {
            calls.add(name + " before");
            proceed.proceed();
            calls.add(name + " after");
        };
    }
}
