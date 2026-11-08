package io.github.pendenzlib

import io.github.pendenzlib.schema.IdType
import io.github.pendenzlib.store.InMemoryTaskStore
import io.github.pendenzlib.store.TaskStore
import io.github.pendenzlib.testing.MutableClock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/** Rate limits per resource (ADR 0040), through the in-memory mode and a clock the test moves. */
class ResourceRateTest {
    @Serializable
    data class Call(
        val partner: String,
        val order: Int = 0,
    )

    private val clock = MutableClock(Instant.parse("2026-11-08T09:00:00Z"))
    private val store = InMemoryTaskStore(IdType.BIGINT)
    private val oneMinute = Duration.ofMinutes(1)
    private val called = CopyOnWriteArrayList<Call>()
    private val callPartner =
        TaskType.json<Call>("call-partner", retryPolicy = NoRetry, resource = TaskResource.fromPayload({ it.partner }))

    @Nested
    inner class Claiming {
        @Test
        fun `runPending - five due tasks, rate of two per minute - two run, three stay PENDING`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                pendenz.setResourceRate("partner-api", 2, oneMinute)
                val ids = (1..5).map { pendenz.enqueue(callPartner, Call("partner-api", it)) }

                val ran = pendenz.runPending()

                // Assert
                assertThat(ran).isEqualTo(2)
                assertThat(ids.map { pendenz.find(it)!!.status })
                    .containsExactly(
                        TaskStatus.SUCCEEDED,
                        TaskStatus.SUCCEEDED,
                        TaskStatus.PENDING,
                        TaskStatus.PENDING,
                        TaskStatus.PENDING,
                    )
            }

        @Test
        fun `runPending - rate used up, then the window passes - the next two run`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                pendenz.setResourceRate("partner-api", 2, oneMinute)
                (1..5).forEach { pendenz.enqueue(callPartner, Call("partner-api", it)) }
                pendenz.runPending()
                clock.advance(Duration.ofSeconds(61))

                val ran = pendenz.runPending()

                // Assert
                assertThat(ran).isEqualTo(2)
                assertThat(called.map { it.order }).containsExactly(1, 2, 3, 4)
            }

        @Test
        fun `runPending - window partly passed - only starts inside the window count`() =
            runBlocking<Unit> {
                // Arrange: one start at 09:00, one at 09:00:40; at 09:01:10 the first left the window
                val pendenz = pendenz()
                pendenz.setResourceRate("partner-api", 2, oneMinute)
                pendenz.enqueue(callPartner, Call("partner-api", 1))
                pendenz.runPending()
                clock.advance(Duration.ofSeconds(40))
                pendenz.enqueue(callPartner, Call("partner-api", 2))
                pendenz.runPending()
                (3..4).forEach { pendenz.enqueue(callPartner, Call("partner-api", it)) }
                clock.advance(Duration.ofSeconds(30))

                val ran = pendenz.runPending()

                // Assert
                assertThat(ran).isEqualTo(1)
                assertThat(called.map { it.order }).containsExactly(1, 2, 3)
            }

        @Test
        fun `runPending - LIKE pattern, one per minute - every partner gets its own start`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                pendenz.setResourceRate("partner:%", 1, oneMinute)
                listOf("partner:acme", "partner:acme", "partner:globex", "partner:globex")
                    .forEach { pendenz.enqueue(callPartner, Call(it)) }

                pendenz.runPending()

                assertThat(called.map { it.partner }).containsExactlyInAnyOrder("partner:acme", "partner:globex")
            }

        @Test
        fun `runPending - pattern and a longer exact name - the longer name's rate applies to its resource`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                pendenz.setResourceRate("partner:%", 1, oneMinute)
                pendenz.setResourceRate("partner:acme", 3, oneMinute)
                repeat(4) { pendenz.enqueue(callPartner, Call("partner:acme", it)) }

                val ran = pendenz.runPending()

                assertThat(ran).isEqualTo(3)
            }

        @Test
        fun `runPending - resource without a rate row - unlimited`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                pendenz.setResourceRate("partner-api", 1, oneMinute)
                repeat(3) { pendenz.enqueue(callPartner, Call("mail-server", it)) }

                val ran = pendenz.runPending()

                assertThat(ran).isEqualTo(3)
            }

        @Test
        fun `runPending - rate of two, first attempt failed and retried - the retry used the second start`() =
            runBlocking<Unit> {
                // Arrange
                val retryAtOnce = RetryPolicy { failure -> if (failure.attempt < 2) failure.failedAt else null }
                val flaky =
                    TaskType.json<Call>(
                        "flaky",
                        retryPolicy = retryAtOnce,
                        resource = TaskResource.fixed("partner-api"),
                    )
                var failNext = true
                val pendenz =
                    Pendenz.create {
                        store(store)
                        clock(clock)
                        register(flaky) { _, payload ->
                            called += payload
                            if (failNext) {
                                failNext = false
                                error("partner down")
                            }
                        }
                    }
                pendenz.setResourceRate("partner-api", 2, oneMinute)
                pendenz.enqueue(flaky, Call("partner-api", 1))
                pendenz.runPending()
                val later = pendenz.enqueue(flaky, Call("partner-api", 2))

                val ran = pendenz.runPending()

                // Assert
                assertThat(ran).isZero()
                assertThat(pendenz.find(later)!!.status).isEqualTo(TaskStatus.PENDING)
                assertThat(called.map { it.order }).containsExactly(1, 1)
            }

        @Test
        fun `runPending - concurrency limit of one beside a rate of five - one at a time, and five per minute`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                pendenz.setResourceLimit("partner-api", 1)
                pendenz.setResourceRate("partner-api", 5, oneMinute)
                repeat(7) { pendenz.enqueue(callPartner, Call("partner-api", it)) }

                val ran = pendenz.runPending()

                assertThat(ran).isEqualTo(5)
            }
    }

    @Nested
    inner class Operating {
        @Test
        fun `setResourceRate - set twice, then removed - listed with the second value, then not at all`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                pendenz.setResourceRate("partner-api", 100, oneMinute)
                pendenz.setResourceRate("partner-api", 50, Duration.ofSeconds(30))
                val listed = pendenz.resourceRates()

                val removed = pendenz.removeResourceRate("partner-api")

                // Assert
                assertThat(listed).containsExactly(ResourceRate("partner-api", 50, Duration.ofSeconds(30)))
                assertThat(removed).isTrue()
                assertThat(pendenz.resourceRates()).isEmpty()
            }

        @Test
        fun `removeResourceRate - rate used up - returns true and the waiting tasks run`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                pendenz.setResourceRate("partner-api", 1, oneMinute)
                repeat(3) { pendenz.enqueue(callPartner, Call("partner-api", it)) }
                pendenz.runPending()
                pendenz.removeResourceRate("partner-api")

                val ran = pendenz.runPending()

                assertThat(ran).isEqualTo(2)
            }

        @Test
        fun `runPending - type declares a rate - seeded on the first run, an operator's row kept`() =
            runBlocking<Unit> {
                // Arrange
                val seeded =
                    TaskType.json<Call>(
                        "seeded",
                        resource = TaskResource.fixed("partner-api", maxStarts = 10, per = oneMinute),
                    )
                val other =
                    TaskType.json<Call>(
                        "other",
                        resource = TaskResource.fixed("mail-server", maxStarts = 3, per = oneMinute),
                    )
                val pendenz =
                    Pendenz.create {
                        store(store)
                        clock(clock)
                        register(seeded) { _, _ -> }
                        register(other) { _, _ -> }
                    }
                pendenz.setResourceRate("mail-server", 30, oneMinute)

                pendenz.runPending()

                assertThat(pendenz.resourceRates())
                    .containsExactly(
                        ResourceRate("mail-server", 30, oneMinute),
                        ResourceRate("partner-api", 10, oneMinute),
                    )
            }
    }

    @Nested
    inner class Configuration {
        @Test
        fun `build - type declares a rate, store without rates - throws IllegalArgumentException naming the type`() {
            val withoutRates =
                object : TaskStore<Long> by store {
                    override val resourceRates = null
                }
            val quota = TaskResource.fixed("partner-api", maxStarts = 1, per = oneMinute)
            val rated = TaskType.json<Call>("rated", resource = quota)

            val attempt = ThrowingCallable { Pendenz.create { store(withoutRates).register(rated) { _, _ -> } } }

            assertThatThrownBy(attempt).isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("rated")
        }

        @Test
        fun `fixed - maxStarts without per - throws IllegalArgumentException naming both`() {
            val attempt = ThrowingCallable { TaskResource.fixed("partner-api", maxStarts = 1) }

            assertThatThrownBy(attempt)
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("maxStarts")
                .hasMessageContaining("per")
        }

        @Test
        fun `fromPayload - rate without a pattern - throws IllegalArgumentException`() {
            val attempt =
                ThrowingCallable { TaskResource.fromPayload<Call>({ it.partner }, maxStarts = 1, per = oneMinute) }

            assertThatThrownBy(attempt).isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `resource rate - zero starts or a window below a millisecond - throws IllegalArgumentException`() {
            val zeroStarts = ThrowingCallable { ResourceRate("partner-api", 0, oneMinute) }
            val tinyWindow = ThrowingCallable { ResourceRate("partner-api", 1, Duration.ofNanos(1)) }

            // Assert
            assertThatThrownBy(zeroStarts).isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy(tinyWindow).isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    private fun pendenz(): Pendenz<Long> =
        Pendenz.create {
            store(store)
            clock(clock)
            register(callPartner) { _, payload -> called += payload }
        }
}
