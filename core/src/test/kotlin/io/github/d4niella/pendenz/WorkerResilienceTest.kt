package io.github.d4niella.pendenz

import io.github.d4niella.pendenz.schema.IdType
import io.github.d4niella.pendenz.store.ClaimGroup
import io.github.d4niella.pendenz.store.ClaimTerms
import io.github.d4niella.pendenz.store.InMemoryTaskStore
import io.github.d4niella.pendenz.store.Outcome
import io.github.d4niella.pendenz.store.StoreTransaction
import io.github.d4niella.pendenz.store.TaskStore
import io.github.d4niella.pendenz.testing.awaitStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
        val failNextFinish = AtomicBoolean(false)

        override fun finish(
            tx: StoreTransaction,
            id: Long,
            expectedVersion: Long,
            outcome: Outcome,
            now: Instant,
        ): Boolean {
            if (failNextFinish.compareAndSet(true, false)) error("connection reset")
            return inner.finish(tx, id, expectedVersion, outcome, now)
        }

        /** When set, a claim announces itself on [claimStarted] and then blocks until [claimMayFinish]. */
        @Volatile
        var stallClaims: Boolean = false
        val claimStarted = CountDownLatch(1)
        val claimMayFinish = CountDownLatch(1)

        override fun heartbeat(
            tx: StoreTransaction,
            id: Long,
            version: Long,
            lockedUntil: Instant,
        ): Boolean {
            if (failNextHeartbeat.compareAndSet(true, false)) error("pool exhausted")
            return inner.heartbeat(tx, id, version, lockedUntil)
        }

        /** When set, a retention sweep announces itself on [sweepStarted] and then blocks until [sweepMayFinish]. */
        @Volatile
        var stallSweeps: Boolean = false
        val sweepStarted = CountDownLatch(1)
        val sweepMayFinish = CountDownLatch(1)
        val sweeps = AtomicInteger()

        override fun deleteFinished(
            tx: StoreTransaction,
            type: String,
            finishedBefore: Instant,
            limit: Int,
        ): Int {
            sweeps.incrementAndGet()
            if (stallSweeps) {
                sweepStarted.countDown()
                // Longer than any wait of a test, so a claim blocked behind the sweep shows as a failure
                check(sweepMayFinish.await(30, TimeUnit.SECONDS)) { "the stalled sweep was never let go" }
            }
            return inner.deleteFinished(tx, type, finishedBefore, limit)
        }

        /** The first step of every poll under the default [FairClaim], so a stall here is a stall of the claim. */
        override fun dueGroups(
            tx: StoreTransaction,
            types: Collection<String>,
            terms: ClaimTerms,
        ): List<ClaimGroup> {
            if (stallClaims) {
                claimStarted.countDown()
                check(claimMayFinish.await(10, TimeUnit.SECONDS)) { "the stalled claim was never let go" }
            }
            return inner.dueGroups(tx, types, terms)
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

        @Test
        fun `lock - second worker has a shorter lock timeout - leaves the task to its owner, which runs it once`() =
            runBlocking<Unit> {
                // Arrange: the owner beats every 400 ms, far beyond the other worker's 100 ms lock timeout
                val runs = AtomicInteger()
                val handler =
                    TaskHandler<Long, Greeting> { _, _ ->
                        runs.incrementAndGet()
                        delay(600)
                    }
                val owner = pendenz(handler)
                val id = owner.enqueue(greet, Greeting("Ada"))
                startWorker(owner, heartbeat = Duration.ofMillis(400), lockTimeout = Duration.ofSeconds(2))
                awaitStatus(owner, id, TaskStatus.RUNNING)

                // Act
                startWorker(pendenz(handler), heartbeat = Duration.ofMillis(20), lockTimeout = Duration.ofMillis(100))

                // Assert
                val record = awaitStatus(owner, id, TaskStatus.SUCCEEDED)
                assertThat(record.attempts).isEqualTo(1)
                assertThat(runs.get()).isEqualTo(1)
            }
    }

    @Nested
    inner class ExecutionTimeout {
        @Test
        fun `worker - handler suspends past the execution timeout - cancelled, the attempt fails with the timeout`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { _, _ -> delay(5_000) }
                val id = pendenz.enqueue(greet, Greeting("Ada"))

                startWorker(pendenz, executionTimeout = Duration.ofMillis(100))

                // Assert
                assertThat(awaitStatus(pendenz, id, TaskStatus.FAILED).lastError).contains("Timed out")
            }

        @Test
        fun `worker - blocking handler that runs past the execution timeout - its result is recorded when it ends`() =
            runBlocking<Unit> {
                // Arrange: Thread.sleep cannot be interrupted by a coroutine timeout, the work gets done
                val pendenz = pendenz { _, _ -> Thread.sleep(300) }
                val id = pendenz.enqueue(greet, Greeting("Bob"))

                startWorker(pendenz, executionTimeout = Duration.ofMillis(50))

                awaitStatus(pendenz, id, TaskStatus.SUCCEEDED)
            }
    }

    @Nested
    inner class Recording {
        @Test
        fun `runPending - writing the outcome fails once - written on the next try, the handler ran once`() =
            runBlocking<Unit> {
                // Arrange
                val runs = AtomicInteger()
                val pendenz = pendenz { _, _ -> runs.incrementAndGet() }
                val id = pendenz.enqueue(greet, Greeting("Ada"))
                store.failNextFinish.set(true)

                pendenz.runPending()

                // Assert
                val record = pendenz.find(id)!!
                assertThat(record.status).isEqualTo(TaskStatus.SUCCEEDED)
                assertThat(record.attempts).isEqualTo(1)
                assertThat(runs.get()).isEqualTo(1)
                assertThat(store.failNextFinish.get()).isFalse()
            }
    }

    @Nested
    inner class HandlerErrors {
        @Test
        fun `runPending - handler calls TODO() - task ends FAILED with the error instead of throwing`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { _, _ -> TODO("greeting in Klingon") }
                val id = pendenz.enqueue(greet, Greeting("Ada"))

                pendenz.runPending()

                // Assert
                val record = pendenz.find(id)!!
                assertThat(record.status).isEqualTo(TaskStatus.FAILED)
                assertThat(record.lastError).contains("NotImplementedError", "greeting in Klingon")
                assertThat(record.lockedBy).isNull()
            }

        @Test
        fun `runPending - handler overflows its stack - the retry policy schedules the next attempt`() =
            runBlocking<Unit> {
                // Arrange
                val retried = TaskType.json<Greeting>("retried", retryPolicy = ExponentialBackoff(jitter = 0.0))
                val pendenz =
                    Pendenz.create {
                        store(store)
                        register(retried) { _, _ -> throw StackOverflowError() }
                    }
                val id = pendenz.enqueue(retried, Greeting("Bob"))

                pendenz.runPending()

                // Assert
                val record = pendenz.find(id)!!
                assertThat(record.status).isEqualTo(TaskStatus.RETRY_PENDING)
                assertThat(record.attempts).isEqualTo(1)
                assertThat(record.lastError).contains("StackOverflowError")
            }

        @Test
        fun `worker - handler throws an AssertionError - task ends FAILED and the worker runs the next task`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { _, payload -> if (payload.name == "Cleo") throw AssertionError("not Cleo") }
                val broken = pendenz.enqueue(greet, Greeting("Cleo"))
                val fine = pendenz.enqueue(greet, Greeting("Dan"))

                startWorker(pendenz)

                // Assert
                assertThat(awaitStatus(pendenz, broken, TaskStatus.FAILED).lastError).contains("AssertionError")
                awaitStatus(pendenz, fine, TaskStatus.SUCCEEDED)
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
    inner class Maintenance {
        private val fleeting =
            TaskType.json<Greeting>(
                "greet",
                retryPolicy = NoRetry,
                retention = Retention.delete(Duration.ZERO),
            )

        @Test
        fun `worker - retention sweep that takes long - tasks are claimed and run meanwhile`() =
            runBlocking<Unit> {
                // Arrange
                store.stallSweeps = true
                val pendenz = retaining()
                startWorker(pendenz, maintenanceInterval = Duration.ofMillis(50))
                try {
                    assertThat(store.sweepStarted.await(10, TimeUnit.SECONDS)).isTrue()

                    // Act
                    val id = pendenz.enqueue(fleeting, Greeting("Ada"))

                    // Assert: the sweep still blocks, the task ran anyway
                    awaitStatus(pendenz, id, TaskStatus.SUCCEEDED)
                } finally {
                    store.sweepMayFinish.countDown()
                }
            }

        @Test
        fun `worker - maintenance interval zero - never sweeps`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = retaining()
                val id = pendenz.enqueue(fleeting, Greeting("Bob"))

                // Act
                startWorker(pendenz, maintenanceInterval = Duration.ZERO)

                // Assert
                awaitStatus(pendenz, id, TaskStatus.SUCCEEDED)
                delay(200) // fixed wait on purpose: a sweep must not happen meanwhile
                assertThat(store.sweeps.get()).isZero()
                assertThat(pendenz.find(id)).isNotNull()
            }

        private fun retaining(): Pendenz<Long> =
            Pendenz.create {
                store(store)
                register(fleeting) { _, _ -> }
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

    @Nested
    inner class ForeignCancellation {
        @Test
        fun `worker - WakeUp that times out with its own withTimeout - keeps polling and runs the task`() =
            runBlocking<Unit> {
                // Arrange: a WakeUp written the natural way, throwing TimeoutCancellationException when nothing came
                val pendenz = pendenz { _, _ -> }
                val timingOut =
                    io.github.d4niella.pendenz
                        .WakeUp { _, atMost -> withTimeout(atMost.toMillis()) { awaitCancellation() } }
                startWorker(pendenz, wakeUp = timingOut)
                delay(200) // fixed wait on purpose: the WakeUp must have timed out a few times before the task comes

                val id = pendenz.enqueue(greet, Greeting("Gil"))

                awaitStatus(pendenz, id, TaskStatus.SUCCEEDED)
            }
    }

    private fun pendenz(handler: TaskHandler<Long, Greeting>): Pendenz<Long> =
        Pendenz.create {
            store(store)
            register(greet, handler)
        }

    @Suppress("LongParameterList") // one parameter per worker option a test varies, the rest stay at short defaults
    private fun startWorker(
        pendenz: Pendenz<Long>,
        heartbeat: Duration = Duration.ofMillis(50),
        lockTimeout: Duration = Duration.ofSeconds(5),
        shutdownTimeout: Duration = Duration.ofSeconds(5),
        wakeUp: io.github.d4niella.pendenz.WakeUp = io.github.d4niella.pendenz.WakeUp.POLLING,
        maintenanceInterval: Duration = WorkerOptions.DEFAULT_MAINTENANCE_INTERVAL,
        executionTimeout: Duration = WorkerOptions.DEFAULT_EXECUTION_TIMEOUT,
    ): Job {
        val options =
            WorkerOptions(
                parallelism = 2,
                pollInterval = Duration.ofMillis(50),
                heartbeatInterval = heartbeat,
                lockTimeout = lockTimeout,
                shutdownTimeout = shutdownTimeout,
                wakeUp = wakeUp,
                maintenanceInterval = maintenanceInterval,
                executionTimeout = executionTimeout,
            )
        return pendenz.startWorker(scope, options).also { workers += it }
    }
}
