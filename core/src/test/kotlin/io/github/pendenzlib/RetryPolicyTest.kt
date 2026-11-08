package io.github.pendenzlib

import io.github.pendenzlib.schema.IdType
import io.github.pendenzlib.store.InMemoryTaskStore
import io.github.pendenzlib.testing.MutableClock
import io.github.pendenzlib.testing.claim
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/** What a retry policy sees of the task, and what a policy that throws does (ADR 0038). */
class RetryPolicyTest {
    @Serializable
    data class Call(
        val partner: String,
    )

    private val clock = MutableClock(Instant.parse("2026-11-08T09:00:00Z"))
    private val store = InMemoryTaskStore(IdType.BIGINT)

    @Nested
    inner class Context {
        @Test
        fun `retry policy - handler failed - sees resource, lane, creator and attempt of the task`() =
            runBlocking<Unit> {
                // Arrange
                val seen = CopyOnWriteArrayList<FailedAttempt>()
                val watching =
                    RetryPolicy { failure ->
                        seen += failure
                        null
                    }
                val callPartner = TaskType.json<Call>("call-partner", retryPolicy = watching)
                val pendenz = pendenz { register(callPartner) { _, _ -> error("partner down") } }
                val id =
                    pendenz.enqueue(
                        callPartner,
                        Call("acme"),
                        createdBy = "agent:helpdesk",
                        lane = "night-run",
                        resource = "partner-api:acme",
                    )

                pendenz.runPending()

                // Assert
                val failure = seen.single()
                assertThat(failure.task.id).isEqualTo(id)
                assertThat(failure.task.resource).isEqualTo("partner-api:acme")
                assertThat(failure.task.lane).isEqualTo("night-run")
                assertThat(failure.task.createdBy).isEqualTo("agent:helpdesk")
                assertThat(failure.attempt).isEqualTo(1)
                assertThat(failure.failedAt).isEqualTo(clock.now)
                assertThat(failure.cause).hasMessage("partner down")
            }

        @Test
        fun `retry policy - waits per resource - each task retried after its own resource's delay`() =
            runBlocking<Unit> {
                // Arrange
                val byResource =
                    RetryPolicy { failure ->
                        val delay = if (failure.task.resource == "partner-api:slow") 30L else 1L
                        failure.failedAt.plus(Duration.ofMinutes(delay))
                    }
                val callPartner = TaskType.json<Call>("call-partner", retryPolicy = byResource)
                val pendenz = pendenz { register(callPartner) { _, _ -> error("partner down") } }
                val slow = pendenz.enqueue(callPartner, Call("slow"), resource = "partner-api:slow")
                val quick = pendenz.enqueue(callPartner, Call("quick"), resource = "partner-api:quick")

                pendenz.runPending()

                // Assert
                assertThat(pendenz.find(slow)!!.runAt).isEqualTo(clock.now.plus(Duration.ofMinutes(30)))
                assertThat(pendenz.find(quick)!!.runAt).isEqualTo(clock.now.plus(Duration.ofMinutes(1)))
            }

        @Test
        fun `retry policy - enqueues a follow-up from the context - gives up, FAILED, no follow-up stored`() =
            runBlocking<Unit> {
                // Arrange
                val apology = TaskType.json<Call>("send-apology")
                val enqueuing =
                    RetryPolicy { failure ->
                        @Suppress("UNCHECKED_CAST") // the queue's ids are Long
                        (failure.task as TaskContext<Long>).enqueue(apology, Call("acme"))
                        failure.failedAt.plusSeconds(1)
                    }
                val callPartner = TaskType.json<Call>("call-partner", retryPolicy = enqueuing)
                val pendenz =
                    pendenz {
                        register(callPartner) { _, _ -> error("partner down") }
                        register(apology) { _, _ -> }
                    }
                val id = pendenz.enqueue(callPartner, Call("acme"))

                pendenz.runPending()

                // Assert
                assertThat(pendenz.find(id)!!.status).isEqualTo(TaskStatus.FAILED)
                assertThat(pendenz.tasks(TaskQuery(types = setOf("send-apology")))).isEmpty()
            }
    }

    @Nested
    inner class FaultyPolicy {
        private val throwing = RetryPolicy { _ -> throw IllegalStateException("policy bug") }

        @Test
        fun `retry policy - throws after a handler failure - FAILED with the handler's error as last error`() =
            runBlocking<Unit> {
                // Arrange
                val callPartner = TaskType.json<Call>("call-partner", retryPolicy = throwing)
                val pendenz = pendenz { register(callPartner) { _, _ -> error("partner down") } }
                val id = pendenz.enqueue(callPartner, Call("acme"))

                pendenz.runPending()

                // Assert
                val task = pendenz.find(id)!!
                assertThat(task.status).isEqualTo(TaskStatus.FAILED)
                assertThat(task.lastError).contains("partner down").doesNotContain("policy bug")
            }

        @Test
        fun `retry policy - throws for an expired lock - FAILED, and the same poll still runs other tasks`() =
            runBlocking<Unit> {
                // Arrange
                val callPartner = TaskType.json<Call>("call-partner", retryPolicy = throwing)
                val greet = TaskType.json<Call>("greet")
                val greeted = CopyOnWriteArrayList<String>()
                val pendenz =
                    pendenz {
                        register(callPartner) { _, _ -> }
                        register(greet) { _, payload -> greeted += payload.partner }
                    }
                val lost = pendenz.enqueue(callPartner, Call("acme"))
                store.transaction { store.claim(it, "dead-worker", setOf("call-partner"), 1, clock.now) }
                clock.advance(Duration.ofMinutes(2))
                pendenz.enqueue(greet, Call("globex"))

                pendenz.runPending()

                // Assert
                assertThat(pendenz.find(lost)!!.status).isEqualTo(TaskStatus.FAILED)
                assertThat(pendenz.find(lost)!!.lastError).contains("LockExpiredException")
                assertThat(greeted).containsExactly("globex")
            }
    }

    private fun pendenz(configure: Pendenz.Builder<Long>.() -> Unit): Pendenz<Long> =
        Pendenz.create {
            store(store)
            clock(clock)
            configure()
        }
}
