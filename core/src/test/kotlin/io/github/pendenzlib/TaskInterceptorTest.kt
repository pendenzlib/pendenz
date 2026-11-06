package io.github.pendenzlib

import io.github.pendenzlib.schema.IdType
import io.github.pendenzlib.store.InMemoryTaskStore
import io.github.pendenzlib.testing.MutableClock
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/** Interceptors around the handler (ADR 0036), through the in-memory mode. */
class TaskInterceptorTest {
    @Serializable
    data class Invoice(
        val number: String,
    )

    private val clock = MutableClock(Instant.parse("2026-11-06T09:00:00Z"))
    private val store = InMemoryTaskStore(IdType.BIGINT)
    private val calls = CopyOnWriteArrayList<String>()
    private val send = TaskType.json<Invoice>("send-invoice", retryPolicy = NoRetry)
    private val inAnHour = Instant.parse("2026-11-06T10:00:00Z")

    @Nested
    inner class Around {
        @Test
        fun `interceptors - two registered - run in registration order around the handler, outermost first`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz =
                    pendenz(
                        handler = { _, invoice -> calls += "handler ${invoice.number}" },
                        recording("tracing"),
                        recording("logging"),
                    )
                pendenz.enqueue(send, Invoice("2026-0042"))

                pendenz.runPending()

                assertThat(calls).containsExactly(
                    "tracing before",
                    "logging before",
                    "handler 2026-0042",
                    "logging after",
                    "tracing after",
                )
            }

        @Test
        fun `interceptor - context changed with withContext - the handler runs in that context`() =
            runBlocking<Unit> {
                // Arrange
                val naming =
                    TaskInterceptor<Long> {
                        context,
                        proceed,
                        ->
                        withContext(CoroutineName("task-${context.id}")) { proceed() }
                    }
                val pendenz =
                    pendenz(handler = { _, _ -> calls += currentCoroutineContext()[CoroutineName]!!.name }, naming)
                val id = pendenz.enqueue(send, Invoice("2026-0042"))

                pendenz.runPending()

                assertThat(calls).containsExactly("task-$id")
            }

        @Test
        fun `interceptor - reads the task context - sees type, attempt and creator of the task`() =
            runBlocking<Unit> {
                // Arrange
                val reading =
                    TaskInterceptor<Long> { context, proceed ->
                        calls += "${context.type} attempt ${context.attempt} by ${context.createdBy}"
                        proceed()
                    }
                val pendenz = pendenz(handler = { _, _ -> }, reading)
                pendenz.enqueue(send, Invoice("2026-0042"), createdBy = "billing-run")

                pendenz.runPending()

                assertThat(calls).containsExactly("send-invoice attempt 1 by billing-run")
            }
    }

    @Nested
    inner class Outcomes {
        @Test
        fun `interceptor - handler succeeds - the task is SUCCEEDED`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz(handler = { _, _ -> }, recording("logging"))
                val id = pendenz.enqueue(send, Invoice("2026-0042"))

                pendenz.runPending()

                assertThat(pendenz.find(id)!!.status).isEqualTo(TaskStatus.SUCCEEDED)
            }

        @Test
        fun `interceptor - maps the client's exception to retry later - the task is deferred, no attempt counted`() =
            runBlocking<Unit> {
                // Arrange
                val translating =
                    TaskInterceptor<Long> { _, proceed ->
                        try {
                            proceed()
                        } catch (e: IOException) {
                            throw RetryLaterException(inAnHour, "mail server busy").apply { initCause(e) }
                        }
                    }
                val pendenz = pendenz(handler = { _, _ -> throw IOException("421 try again later") }, translating)
                val id = pendenz.enqueue(send, Invoice("2026-0042"))

                pendenz.runPending()

                // Assert
                val task = pendenz.find(id)!!
                assertThat(task.status).isEqualTo(TaskStatus.RETRY_PENDING)
                assertThat(task.runAt).isEqualTo(inAnHour)
                assertThat(task.attempts).isZero()
            }

        @Test
        fun `interceptor - throws before proceed - a failed attempt, the handler does not run`() =
            runBlocking<Unit> {
                // Arrange
                val refusing = TaskInterceptor<Long> { _, _ -> throw IllegalStateException("no tenant for the task") }
                val pendenz = pendenz(handler = { _, _ -> calls += "handler" }, refusing)
                val id = pendenz.enqueue(send, Invoice("2026-0042"))

                pendenz.runPending()

                // Assert
                val task = pendenz.find(id)!!
                assertThat(task.status).isEqualTo(TaskStatus.FAILED)
                assertThat(task.lastError).contains("no tenant for the task")
                assertThat(calls).isEmpty()
            }

        @Test
        fun `interceptor - swallows the handler's exception - the attempt fails with that exception`() =
            runBlocking<Unit> {
                // Arrange
                val swallowing = TaskInterceptor<Long> { _, proceed -> runCatching { proceed() } }
                val pendenz = pendenz(handler = { _, _ -> throw IOException("mailbox full") }, swallowing)
                val id = pendenz.enqueue(send, Invoice("2026-0042"))

                pendenz.runPending()

                // Assert
                val task = pendenz.find(id)!!
                assertThat(task.status).isEqualTo(TaskStatus.FAILED)
                assertThat(task.lastError).contains("IOException", "mailbox full")
            }

        @Test
        fun `interceptor - returns without calling proceed - a failed attempt, the handler does not run`() =
            runBlocking<Unit> {
                // Arrange
                val skipping = TaskInterceptor<Long> { _, _ -> }
                val pendenz = pendenz(handler = { _, _ -> calls += "handler" }, skipping)
                val id = pendenz.enqueue(send, Invoice("2026-0042"))

                pendenz.runPending()

                // Assert
                val task = pendenz.find(id)!!
                assertThat(task.status).isEqualTo(TaskStatus.FAILED)
                assertThat(task.lastError).contains("IllegalStateException", "returned without calling proceed")
                assertThat(calls).isEmpty()
            }

        @Test
        fun `interceptor - calls proceed twice - a failed attempt, the handler runs once`() =
            runBlocking<Unit> {
                // Arrange
                val repeating =
                    TaskInterceptor<Long> { _, proceed ->
                        proceed()
                        runCatching { proceed() }
                    }
                val pendenz = pendenz(handler = { _, _ -> calls += "handler" }, repeating)
                val id = pendenz.enqueue(send, Invoice("2026-0042"))

                pendenz.runPending()

                // Assert
                val task = pendenz.find(id)!!
                assertThat(task.status).isEqualTo(TaskStatus.FAILED)
                assertThat(task.lastError).contains("called proceed more than once")
                assertThat(calls).containsExactly("handler")
            }

        @Test
        fun `interceptor - slower than the execution timeout - a failed attempt by timeout`() =
            runBlocking<Unit> {
                // Arrange
                val slow =
                    TaskInterceptor<Long> { _, proceed ->
                        delay(Duration.ofSeconds(10).toMillis())
                        proceed()
                    }
                val pendenz = pendenz(handler = { _, _ -> calls += "handler" }, slow)
                val id = pendenz.enqueue(send, Invoice("2026-0042"))

                pendenz.runPending(WorkerOptions(executionTimeout = Duration.ofMillis(50)))

                // Assert
                val task = pendenz.find(id)!!
                assertThat(task.status).isEqualTo(TaskStatus.FAILED)
                assertThat(task.lastError).contains("Timeout")
                assertThat(calls).isEmpty()
            }
    }

    private fun recording(name: String): TaskInterceptor<Long> =
        TaskInterceptor { _, proceed ->
            calls += "$name before"
            proceed()
            calls += "$name after"
        }

    private fun pendenz(
        handler: TaskHandler<Long, Invoice>,
        vararg interceptors: TaskInterceptor<Long>,
    ): Pendenz<Long> =
        Pendenz.create {
            store(store)
            clock(clock)
            register(send, handler)
            interceptors.forEach { interceptor(it) }
        }
}
