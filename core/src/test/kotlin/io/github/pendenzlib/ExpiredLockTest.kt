package io.github.pendenzlib

import io.github.pendenzlib.schema.IdType
import io.github.pendenzlib.store.InMemoryTaskStore
import io.github.pendenzlib.testing.MutableClock
import io.github.pendenzlib.testing.claim
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A worker that dies with a task leaves an expired lock behind. The next
 * poll records the lost attempt as failed and lets the retry policy decide
 * (ADR 0026). A dead worker is played by claiming through the store.
 */
class ExpiredLockTest {
    @Serializable
    data class Greeting(
        val name: String,
    )

    private val clock = MutableClock()
    private val store = InMemoryTaskStore(IdType.BIGINT)
    private val noJitter = ExponentialBackoff(maxAttempts = 3, jitter = 0.0)

    @Test
    fun `expired lock - attempts left - task waits RETRY_PENDING for the backoff, naming the dead worker`() =
        runBlocking<Unit> {
            // Arrange
            val greet = TaskType.json<Greeting>("greet", retryPolicy = noJitter)
            val pendenz = pendenz { register(greet) { _, _ -> } }
            val id = pendenz.enqueue(greet, Greeting("Ada"))
            claimByDeadWorker()
            clock.advance(Duration.ofMinutes(2))

            val ran = pendenz.runPending()

            // Assert
            val record = pendenz.find(id)!!
            assertThat(ran).isZero()
            assertThat(record.status).isEqualTo(TaskStatus.RETRY_PENDING)
            assertThat(record.runAt).isEqualTo(clock.now.plusSeconds(1))
            assertThat(record.lastError).contains("LockExpiredException", "dead-worker")
            assertThat(record.lockedBy).isNull()
        }

    @Test
    fun `expired lock - last attempt - task ends FAILED`() =
        runBlocking<Unit> {
            // Arrange
            val greet = TaskType.json<Greeting>("greet", retryPolicy = NoRetry)
            val pendenz = pendenz { register(greet) { _, _ -> } }
            val id = pendenz.enqueue(greet, Greeting("Bob"))
            claimByDeadWorker()
            clock.advance(Duration.ofMinutes(2))

            pendenz.runPending()

            assertThat(pendenz.find(id)!!.status).isEqualTo(TaskStatus.FAILED)
        }

    @Test
    fun `expired lock - poison task that kills every worker - reaches FAILED after maxAttempts`() =
        runBlocking<Unit> {
            // Arrange: each round a worker claims the task and dies; the next poll finds the lock expired
            val greet = TaskType.json<Greeting>("greet", retryPolicy = noJitter)
            val pendenz = pendenz { register(greet) { _, _ -> } }
            val id = pendenz.enqueue(greet, Greeting("Cleo"))
            repeat(2) {
                claimByDeadWorker()
                clock.advance(Duration.ofMinutes(2))
                pendenz.runPending()
                clock.advance(Duration.ofMinutes(10))
                store.transaction { store.promoteDue(it, clock.now) }
            }
            claimByDeadWorker()
            clock.advance(Duration.ofMinutes(2))

            // Act
            pendenz.runPending()

            // Assert
            val record = pendenz.find(id)!!
            assertThat(record.status).isEqualTo(TaskStatus.FAILED)
            assertThat(record.attempts).isEqualTo(3)
        }

    @Test
    fun `expired lock - retry policy asked - sees a LockExpiredException with holder, deadline and attempt`() =
        runBlocking<Unit> {
            // Arrange
            val seen = CopyOnWriteArrayList<Pair<Int, Throwable>>()
            val watching =
                RetryPolicy { attempt, _, cause ->
                    seen += attempt to cause
                    null
                }
            val greet = TaskType.json<Greeting>("greet", retryPolicy = watching)
            val pendenz = pendenz { register(greet) { _, _ -> } }
            pendenz.enqueue(greet, Greeting("Dan"))
            val deadline = claimByDeadWorker()
            clock.advance(Duration.ofMinutes(2))

            pendenz.runPending()

            // Assert
            val (attempt, cause) = seen.single()
            assertThat(attempt).isEqualTo(1)
            assertThat(cause).isInstanceOf(LockExpiredException::class.java)
            assertThat((cause as LockExpiredException).lockedBy).isEqualTo("dead-worker")
            assertThat(cause.lockedUntil).isEqualTo(deadline)
        }

    @Test
    fun `expired lock - type not registered on this queue - stays RUNNING for a worker that knows it`() =
        runBlocking<Unit> {
            // Arrange
            val invoice = TaskType.json<Greeting>("invoice")
            val greet = TaskType.json<Greeting>("greet")
            val writer = pendenz { register(invoice) { _, _ -> } }
            val id = writer.enqueue(invoice, Greeting("Eve"))
            claimByDeadWorker(type = "invoice")
            clock.advance(Duration.ofMinutes(2))
            val greeter = pendenz { register(greet) { _, _ -> } }

            greeter.runPending()

            // Assert
            val record = greeter.find(id)!!
            assertThat(record.status).isEqualTo(TaskStatus.RUNNING)
            assertThat(record.lockedBy).isEqualTo("dead-worker")
        }

    /** Claims the next due task as a worker that dies right after; returns the lock deadline it left behind. */
    private suspend fun claimByDeadWorker(type: String = "greet"): Instant {
        val claimed = store.transaction { store.claim(it, "dead-worker", setOf(type), 1, clock.now) }
        assertThat(claimed).describedAs("a due task of type %s to claim", type).hasSize(1)
        return clock.now.plus(WorkerOptions.DEFAULT_LOCK_TIMEOUT)
    }

    private fun pendenz(configure: Pendenz.Builder<Long>.() -> Unit): Pendenz<Long> =
        Pendenz.create {
            store(store)
            clock(clock)
            configure()
        }
}
