package io.github.d4niella.pendenz

import io.github.d4niella.pendenz.schema.IdType
import io.github.d4niella.pendenz.store.ClaimGroup
import io.github.d4niella.pendenz.store.InMemoryTaskStore
import io.github.d4niella.pendenz.store.StoreTransaction
import io.github.d4niella.pendenz.store.TaskStore
import io.github.d4niella.pendenz.testing.awaitStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** The worker and the runner under adverse conditions, with a store that can misbehave on request. */
class WorkerResilienceTest {
    @Serializable
    data class Greeting(
        val name: String,
    )

    /** An in-memory store whose heartbeat and claim can be made to fail or stall for a test. */
    private class Misbehaving(
        private val inner: InMemoryTaskStore<Long>,
    ) : TaskStore<Long> by inner {
        val failNextHeartbeat = AtomicBoolean(false)

        /** When set, a claim announces itself on [claimStarted] and then blocks until [claimMayFinish]. */
        @Volatile
        var stallClaims: Boolean = false
        val claimStarted = CountDownLatch(1)
        val claimMayFinish = CountDownLatch(1)

        override fun heartbeat(
            tx: StoreTransaction,
            id: Long,
            version: Long,
            now: Instant,
        ): Boolean {
            if (failNextHeartbeat.compareAndSet(true, false)) error("pool exhausted")
            return inner.heartbeat(tx, id, version, now)
        }

        /** The first step of every poll under the default [FairClaim], so a stall here is a stall of the claim. */
        override fun dueGroups(
            tx: StoreTransaction,
            types: Collection<String>,
            now: Instant,
        ): List<ClaimGroup> {
            if (stallClaims) {
                claimStarted.countDown()
                check(claimMayFinish.await(10, TimeUnit.SECONDS)) { "the stalled claim was never let go" }
            }
            return inner.dueGroups(tx, types, now)
        }
    }

    private val inner = InMemoryTaskStore(IdType.BIGINT)
    private val store = Misbehaving(inner)
    private val greet = TaskType.json<Greeting>("greet", retryPolicy = NoRetry)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val workers = mutableListOf<Job>()

    @AfterEach
    fun stopWorkers() =
        runBlocking<Unit> {
            workers.forEach { it.cancelAndJoin() }
        }

    @Nested
    inner class Heartbeat {
        @Test
        fun `heartbeat - one refresh fails - the next refreshes go on and the task finishes under its lock`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { _, _ -> delay(300) }
                val id = pendenz.enqueue(greet, Greeting("Ada"))
                store.failNextHeartbeat.set(true)

                // Act
                startWorker(pendenz, heartbeat = Duration.ofMillis(20), lockTimeout = Duration.ofMillis(150))

                // Assert: with a dead heartbeat the 150 ms lock timeout would have released the task mid-run
                val record = awaitStatus(pendenz, id, TaskStatus.SUCCEEDED)
                assertThat(record.attempts).isEqualTo(1)
                assertThat(store.failNextHeartbeat.get()).isFalse()
            }
    }

    @Nested
    inner class Cancellation {
        @Test
        fun `worker stop - handler that ignores cancellation finishes after the timeout - its result is recorded`() =
            runBlocking<Unit> {
                // Arrange: Thread.sleep on the worker thread never checks for cancellation, as blocking code does
                val pendenz = pendenz { _, _ -> Thread.sleep(400) }
                val id = pendenz.enqueue(greet, Greeting("Bob"))
                val worker = startWorker(pendenz, shutdownTimeout = Duration.ofMillis(50))
                awaitStatus(pendenz, id, TaskStatus.RUNNING)

                worker.cancelAndJoin()

                // Assert: the work was done, so it is SUCCEEDED, not given back as PENDING
                assertThat(pendenz.find(id)!!.status).isEqualTo(TaskStatus.SUCCEEDED)
            }

        @Test
        fun `worker stop - cooperative handler cancelled after the timeout - the task is given back as PENDING`() =
            runBlocking<Unit> {
                // Arrange: withContext checks for cancellation on the way out, so this handler cooperates
                val pendenz = pendenz { _, _ -> withContext(Dispatchers.IO) { Thread.sleep(400) } }
                val id = pendenz.enqueue(greet, Greeting("Bob"))
                val worker = startWorker(pendenz, shutdownTimeout = Duration.ofMillis(50))
                awaitStatus(pendenz, id, TaskStatus.RUNNING)

                worker.cancelAndJoin()

                assertThat(pendenz.find(id)!!.status).isEqualTo(TaskStatus.PENDING)
            }

        @Test
        fun `handler throws a foreign CancellationException - treated as a failure, not as a worker stop`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { _, _ -> throw CancellationException("someone else's scope") }
                val id = pendenz.enqueue(greet, Greeting("Cleo"))

                pendenz.runPending()

                // Assert
                val record = pendenz.find(id)!!
                assertThat(record.status).isEqualTo(TaskStatus.FAILED)
                assertThat(record.lockedBy).isNull()
            }

        @Test
        fun `worker stop - cancelled while a claim is in flight - the claimed task is given back as PENDING`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { _, _ -> }
                val id = pendenz.enqueue(greet, Greeting("Dan"))
                store.stallClaims = true
                val worker = startWorker(pendenz)
                withContext(Dispatchers.IO) { store.claimStarted.await() }

                // Act: cancelled while the claim is in flight, then the claim is let through
                worker.cancel()
                store.claimMayFinish.countDown()
                worker.join()

                // Assert
                val record = pendenz.find(id)!!
                assertThat(record.status).isEqualTo(TaskStatus.PENDING)
                assertThat(record.lockedBy).isNull()
            }

        @Test
        fun `runPending - task cancelled by an operator while running - returns normally, task stays CANCELLED`() =
            runBlocking<Unit> {
                // Arrange
                lateinit var pendenz: Pendenz<Long>
                pendenz =
                    pendenz { ctx, _ ->
                        pendenz.cancel(ctx.id)
                        delay(300)
                    }
                val id = pendenz.enqueue(greet, Greeting("Eve"))

                val ran = pendenz.runPending(WorkerOptions(heartbeatInterval = Duration.ofMillis(20)))

                // Assert
                assertThat(ran).isEqualTo(1)
                assertThat(pendenz.find(id)!!.status).isEqualTo(TaskStatus.CANCELLED)
            }
    }

    @Nested
    inner class RunPending {
        @Test
        fun `runPending - four pending tasks - claims one at a time, so none waits RUNNING without a heartbeat`() =
            runBlocking<Unit> {
                // Arrange
                val mostRunning = AtomicInteger()
                val pendenz =
                    pendenz { _, _ ->
                        val running = inner.tasks().count { it.status == TaskStatus.RUNNING }
                        mostRunning.accumulateAndGet(running, ::maxOf)
                    }
                repeat(4) { pendenz.enqueue(greet, Greeting("n$it")) }

                val ran = pendenz.runPending(WorkerOptions(parallelism = 4))

                // Assert
                assertThat(ran).isEqualTo(4)
                assertThat(mostRunning.get()).isEqualTo(1)
            }
    }

    @Nested
    inner class WakeUp {
        @Test
        fun `worker - WakeUp that throws - keeps polling and runs the task`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { _, _ -> }
                val broken =
                    io.github.d4niella.pendenz
                        .WakeUp { _, _ -> error("listener down") }
                startWorker(pendenz, wakeUp = broken)
                delay(100) // fixed wait on purpose: the worker must have hit the broken wake-up at least once

                val id = pendenz.enqueue(greet, Greeting("Fay"))

                awaitStatus(pendenz, id, TaskStatus.SUCCEEDED)
            }
    }

    private fun pendenz(handler: TaskHandler<Long, Greeting>): Pendenz<Long> =
        Pendenz.create {
            store(store)
            register(greet, handler)
        }

    private fun startWorker(
        pendenz: Pendenz<Long>,
        heartbeat: Duration = Duration.ofMillis(50),
        lockTimeout: Duration = Duration.ofSeconds(5),
        shutdownTimeout: Duration = Duration.ofSeconds(5),
        wakeUp: io.github.d4niella.pendenz.WakeUp = io.github.d4niella.pendenz.WakeUp.POLLING,
    ): Job {
        val options =
            WorkerOptions(
                parallelism = 2,
                pollInterval = Duration.ofMillis(50),
                heartbeatInterval = heartbeat,
                lockTimeout = lockTimeout,
                shutdownTimeout = shutdownTimeout,
                wakeUp = wakeUp,
            )
        return pendenz.startWorker(scope, options).also { workers += it }
    }
}
