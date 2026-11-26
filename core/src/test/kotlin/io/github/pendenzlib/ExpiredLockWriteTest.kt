package io.github.pendenzlib

import io.github.pendenzlib.schema.IdType
import io.github.pendenzlib.store.InMemoryTaskStore
import io.github.pendenzlib.store.Outcome
import io.github.pendenzlib.store.StoreTransaction
import io.github.pendenzlib.store.TaskStore
import io.github.pendenzlib.testing.MutableClock
import io.github.pendenzlib.testing.awaitStatus
import io.github.pendenzlib.testing.claim
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The failed attempt of an expired lock that the store does not take
 * (ADR 0049). The store of the test throws what a database would: a
 * refusal, another answer, or nothing that came from the database. A dead
 * worker is played by claiming through the store.
 */
class ExpiredLockWriteTest {
    @Serializable
    data class Invoice(
        val number: String,
    )

    /** An in-memory store that throws for the failed attempt of an expired lock when [failure] says so. */
    private class Refusing(
        private val inner: InMemoryTaskStore<Long>,
    ) : TaskStore<Long> by inner {
        /** What to throw for the task with this id and this error text; null writes the row. */
        @Volatile
        var failure: (Long, String) -> Exception? = { _, _ -> null }
        val writes = AtomicInteger()

        /** When set, whatever a transaction throws is wrapped, as a store of an application may do. */
        @Volatile
        var wrapsFailures: Boolean = false

        @Suppress("TooGenericExceptionCaught")
        override suspend fun <T> transaction(block: (StoreTransaction) -> T): T =
            try {
                inner.transaction(block)
            } catch (e: Exception) {
                throw if (wrapsFailures) IllegalStateException("transaction failed", e) else e
            }

        override fun finish(
            tx: StoreTransaction,
            id: Long,
            expectedVersion: Long,
            outcome: Outcome,
            now: Instant,
        ): Boolean {
            val error = (outcome as? Outcome.Failed)?.error
            if (error != null) {
                writes.incrementAndGet()
                failure(id, error)?.let { throw it }
            }
            return inner.finish(tx, id, expectedVersion, outcome, now)
        }
    }

    private val clock = MutableClock()
    private val inner = InMemoryTaskStore(IdType.BIGINT)
    private val store = Refusing(inner)
    private val closeInvoice = TaskType.json<Invoice>("close-invoice", retryPolicy = NoRetry)
    private val events = CopyOnWriteArrayList<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val workers = mutableListOf<Job>()

    @AfterEach
    fun stopWorkers() =
        runBlocking<Unit> {
            workers.forEach { it.cancelAndJoin() }
        }

    @Nested
    inner class RefusedText {
        @Test
        fun `expired lock - store refuses the message with state 23514 - FAILED with the class name alone`() =
            runBlocking<Unit> {
                // Arrange
                val tooLong = SQLException("text too long", "23514")
                store.failure = { _, text -> tooLong.takeIf { text.contains("lock expired") } }
                val pendenz = pendenz()
                val id = expiredLockOf("2026-0117")

                // Act
                pendenz.runPending()

                // Assert
                val record = pendenz.find(id)!!
                assertThat(record.status).isEqualTo(TaskStatus.FAILED)
                assertThat(record.lastError).isEqualTo("io.github.pendenzlib.LockExpiredException")
                assertThat(events).containsExactly("failed LockExpiredException")
            }

        @Test
        fun `expired lock - store refuses the message and wraps what its transaction throws - FAILED all the same`() =
            runBlocking<Unit> {
                // Arrange
                val tooLong = SQLException("text too long", "23514")
                store.failure = { _, text -> tooLong.takeIf { text.contains("lock expired") } }
                store.wrapsFailures = true
                val pendenz = pendenz()
                val id = expiredLockOf("2026-0128")

                // Act
                pendenz.runPending()

                // Assert
                val record = pendenz.find(id)!!
                assertThat(record.status).isEqualTo(TaskStatus.FAILED)
                assertThat(record.lastError).isEqualTo("io.github.pendenzlib.LockExpiredException")
            }

        @Test
        fun `expired lock - store refuses the class name too - stays RUNNING, listener hears the last exception`() =
            runBlocking<Unit> {
                // Arrange
                store.failure = { _, _ -> SQLException("no text is taken", "23514") }
                val pendenz = pendenz()
                val id = expiredLockOf("2026-0118")

                // Act
                pendenz.runPending()

                // Assert
                assertThat(pendenz.find(id)!!.status).isEqualTo(TaskStatus.RUNNING)
                assertThat(events).containsExactly("not recorded: no text is taken")
                assertThat(store.writes).hasValue(3)
            }
    }

    @Nested
    inner class SetAside {
        @Test
        fun `expired lock - store answers with a deadlock, 40P01 - stays RUNNING without a second write`() =
            runBlocking<Unit> {
                // Arrange
                store.failure = { _, _ -> SQLException("deadlock detected", "40P01") }
                val pendenz = pendenz()
                val id = expiredLockOf("2026-0119")

                // Act
                pendenz.runPending()

                // Assert
                assertThat(pendenz.find(id)!!.status).isEqualTo(TaskStatus.RUNNING)
                assertThat(events).containsExactly("not recorded: deadlock detected")
                assertThat(store.writes).hasValue(2)
            }

        @Test
        fun `expired lock - one row cannot be recorded, a second one can - the second is FAILED with its message`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                val refusing = expiredLockOf("2026-0120")
                val recordable = expiredLockOf("2026-0121")
                store.failure = { id, _ -> SQLException("deadlock detected", "40P01").takeIf { id == refusing } }

                // Act
                pendenz.runPending()

                // Assert
                assertThat(pendenz.find(refusing)!!.status).isEqualTo(TaskStatus.RUNNING)
                val recorded = pendenz.find(recordable)!!
                assertThat(recorded.status).isEqualTo(TaskStatus.FAILED)
                assertThat(recorded.lastError).contains("lock expired", "dead-worker")
            }

        @Test
        fun `runPending - a row whose expired lock cannot be recorded - returns after running the due task`() =
            runBlocking<Unit> {
                // Arrange
                store.failure = { _, _ -> SQLException("deadlock detected", "40P01") }
                val pendenz = pendenz()
                expiredLockOf("2026-0122")
                val due = pendenz.enqueue(closeInvoice, Invoice("2026-0123"))

                // Act
                val ran = pendenz.runPending()

                // Assert
                assertThat(ran).isOne()
                assertThat(pendenz.find(due)!!.status).isEqualTo(TaskStatus.SUCCEEDED)
            }

        @Test
        fun `set aside - lock timeout not passed - the worker does not write the row again`() =
            runBlocking<Unit> {
                // Arrange
                store.failure = { _, _ -> SQLException("deadlock detected", "40P01") }
                expiredLockOf("2026-0124")

                // Act
                startWorker(pendenz())

                // Assert
                await atMost Duration.ofSeconds(10) untilAsserted { assertThat(events).hasSize(1) }
                // Shows that no further poll writes the row: at 20 ms per poll, fifteen polls pass meanwhile
                delay(300)
                assertThat(events).hasSize(1)
                assertThat(store.writes).hasValue(2)
            }

        @Test
        fun `set aside - one lock timeout passed, store still answers with an error - reported a second time`() =
            runBlocking<Unit> {
                // Arrange
                store.failure = { _, _ -> SQLException("deadlock detected", "40P01") }
                expiredLockOf("2026-0125")
                startWorker(pendenz())
                await atMost Duration.ofSeconds(10) untilAsserted { assertThat(events).hasSize(1) }

                // Act
                clock.advance(LOCK_TIMEOUT)

                // Assert
                await atMost Duration.ofSeconds(10) untilAsserted { assertThat(events).hasSize(2) }
            }

        @Test
        fun `set aside - one lock timeout passed, store takes the row now - FAILED with its message`() =
            runBlocking<Unit> {
                // Arrange
                store.failure = { _, _ -> SQLException("deadlock detected", "40P01") }
                val pendenz = pendenz()
                val id = expiredLockOf("2026-0126")
                startWorker(pendenz)
                await atMost Duration.ofSeconds(10) untilAsserted { assertThat(events).hasSize(1) }
                store.failure = { _, _ -> null }

                // Act
                clock.advance(LOCK_TIMEOUT)

                // Assert
                assertThat(awaitStatus(pendenz, id, TaskStatus.FAILED).lastError).contains("lock expired")
            }
    }

    @Nested
    inner class NoAnswer {
        @ParameterizedTest
        @ValueSource(strings = ["", "08006"])
        fun `expired lock - store throws without an answer of the database - retried every poll, never reported`(
            sqlState: String,
        ) = runBlocking<Unit> {
            // Arrange: no SQL state at all, and the state of a broken connection
            val thrown: Exception =
                if (sqlState.isEmpty()) IllegalStateException("connection reset") else SQLException("gone", sqlState)
            store.failure = { _, _ -> thrown }
            val pendenz = pendenz()
            val id = expiredLockOf("2026-0127")
            startWorker(pendenz)
            await atMost Duration.ofSeconds(10) untilAsserted { assertThat(store.writes).hasValueGreaterThan(2) }

            // Act
            store.failure = { _, _ -> null }

            // Assert
            assertThat(awaitStatus(pendenz, id, TaskStatus.FAILED).lastError).contains("lock expired")
            // The listener hears of the failed attempt after its commit
            await atMost Duration.ofSeconds(10) untilAsserted {
                assertThat(events)
                    .describedAs("SQL state '%s'", sqlState)
                    .containsExactly("failed LockExpiredException")
            }
        }
    }

    /** Enqueues an invoice, lets a worker claim it and die, and moves the clock past its lock; returns the id. */
    private suspend fun expiredLockOf(number: String): Long {
        val id = pendenz().enqueue(closeInvoice, Invoice(number))
        val claimed = store.transaction { store.claim(it, "dead-worker", setOf(closeInvoice.name), 1, clock.now) }
        assertThat(claimed.map { it.id }).describedAs("the claim of the dead worker").containsExactly(id)
        clock.advance(Duration.ofMinutes(2))
        return id
    }

    private fun pendenz(): Pendenz<Long> =
        Pendenz.create {
            store(store)
            clock(clock)
            listener(Recording())
            register(closeInvoice) { _, _ -> }
        }

    private fun startWorker(pendenz: Pendenz<Long>) {
        val options =
            WorkerOptions(
                pollInterval = Duration.ofMillis(20),
                heartbeatInterval = Duration.ofSeconds(1),
                lockTimeout = LOCK_TIMEOUT,
                maintenanceInterval = Duration.ZERO,
            )
        workers += pendenz.startWorker(scope, options)
    }

    private inner class Recording : TaskListener<Long> {
        override fun onFailed(
            task: TaskContext<Long>,
            error: Throwable,
        ) {
            events += "failed ${error.javaClass.simpleName}"
        }

        override fun onExpiredLockNotRecorded(
            task: TaskContext<Long>,
            error: Throwable,
        ) {
            events += "not recorded: ${error.message}"
        }
    }

    private companion object {
        val LOCK_TIMEOUT: Duration = Duration.ofSeconds(60)
    }
}
