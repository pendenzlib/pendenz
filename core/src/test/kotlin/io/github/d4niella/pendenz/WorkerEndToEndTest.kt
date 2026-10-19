package io.github.d4niella.pendenz

import io.github.d4niella.pendenz.schema.TaskTable
import io.github.d4niella.pendenz.testing.PostgresDatabase
import io.github.d4niella.pendenz.testing.awaitStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class WorkerEndToEndTest {
    @Serializable
    data class Greeting(
        val name: String,
    )

    private lateinit var table: TaskTable<Long>
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val workers = mutableListOf<Job>()

    @BeforeEach
    fun createTable() {
        table = db.createTable()
    }

    @AfterEach
    fun stopWorkers() =
        runBlocking<Unit> {
            workers.forEach { it.cancelAndJoin() }
        }

    @Nested
    inner class Shutdown {
        @Test
        fun `worker stop - handler finishes within the shutdown timeout - waits for it and records SUCCEEDED`() =
            runBlocking<Unit> {
                // Arrange
                val started = CompletableDeferred<Unit>()
                val completed = AtomicBoolean()
                val type = TaskType.json<Greeting>("slow")
                val pendenz =
                    pendenz {
                        register(type) { _, _ ->
                            started.complete(Unit)
                            delay(300)
                            completed.set(true)
                        }
                    }
                val id = pendenz.enqueue(type, Greeting("Ada"))
                val worker = startWorker(pendenz, options(shutdownTimeout = Duration.ofSeconds(5)))
                started.await()

                // Act
                worker.cancelAndJoin()

                // Assert
                assertThat(completed).isTrue()
                val record = pendenz.find(id)!!
                assertThat(record.status).isEqualTo(TaskStatus.SUCCEEDED)
                assertThat(record.lockedBy).isNull()
            }

        @Test
        fun `worker stop - handler exceeds the shutdown timeout - cancels it and releases the task as PENDING`() =
            runBlocking<Unit> {
                // Arrange
                val started = CompletableDeferred<Unit>()
                val type = TaskType.json<Greeting>("endless")
                val pendenz =
                    pendenz {
                        register(type) { _, _ ->
                            started.complete(Unit)
                            delay(60_000)
                        }
                    }
                val id = pendenz.enqueue(type, Greeting("Bob"))
                val worker = startWorker(pendenz, options(shutdownTimeout = Duration.ofMillis(200)))
                started.await()

                // Act
                worker.cancelAndJoin()

                // Assert
                val record = pendenz.find(id)!!
                assertThat(record.status).isEqualTo(TaskStatus.PENDING)
                assertThat(record.lockedBy).isNull()
                assertThat(record.lockedAt).isNull()
                assertThat(record.attempts).isEqualTo(1)
            }

        @Test
        fun `worker stop - released task - is picked up by the next worker`() =
            runBlocking<Unit> {
                // Arrange
                val stopping = AtomicBoolean(true)
                val type = TaskType.json<Greeting>("handover")
                val pendenz =
                    pendenz {
                        register(type) { _, _ ->
                            if (stopping.get()) delay(60_000)
                        }
                    }
                val id = pendenz.enqueue(type, Greeting("Cleo"))
                val first = startWorker(pendenz, options(shutdownTimeout = Duration.ofMillis(100)))
                awaitStatus(pendenz, id, TaskStatus.RUNNING)
                first.cancelAndJoin()
                stopping.set(false)

                // Act
                startWorker(pendenz)

                // Assert
                val record = awaitStatus(pendenz, id, TaskStatus.SUCCEEDED)
                assertThat(record.attempts).isEqualTo(2)
            }
    }

    @Nested
    inner class Parallelism {
        @Test
        fun `worker - parallelism 2 and six tasks - never holds more than two tasks RUNNING`() =
            runBlocking<Unit> {
                // Arrange
                val mostRunning = AtomicLong()
                val type = TaskType.json<Greeting>("counted")
                val pendenz =
                    pendenz {
                        register(type) { _, _ ->
                            val running = db.count("""SELECT count(*) FROM "${table.name}" WHERE status = 'RUNNING'""")
                            mostRunning.accumulateAndGet(running, ::maxOf)
                            delay(100)
                        }
                    }
                val ids = (1..6).map { pendenz.enqueue(type, Greeting("n$it")) }

                // Act
                startWorker(pendenz, options(parallelism = 2))

                // Assert
                ids.forEach { awaitStatus(pendenz, it, TaskStatus.SUCCEEDED) }
                assertThat(mostRunning.get()).isBetween(1, 2)
            }
    }

    private fun pendenz(configure: Pendenz.Builder<Long>.() -> Unit): Pendenz<Long> =
        Pendenz.create(table) {
            dataSource(db.dataSource)
            configure()
        }

    private fun startWorker(
        pendenz: Pendenz<Long>,
        options: WorkerOptions = options(),
    ): Job = pendenz.startWorker(scope, options).also { workers += it }

    private fun options(
        parallelism: Int = 1,
        shutdownTimeout: Duration = WorkerOptions.DEFAULT_SHUTDOWN_TIMEOUT,
    ) = WorkerOptions(
        parallelism = parallelism,
        pollInterval = Duration.ofMillis(50),
        shutdownTimeout = shutdownTimeout,
    )

    companion object {
        @JvmField
        @RegisterExtension
        val db = PostgresDatabase()
    }
}
