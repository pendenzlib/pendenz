package io.github.d4niella.pendenz

import io.github.d4niella.pendenz.schema.TaskTable
import io.github.d4niella.pendenz.store.TaskRecord
import io.github.d4niella.pendenz.testing.PostgresDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class QueueEndToEndTest {
    @Serializable
    data class Greeting(
        val name: String,
    )

    private lateinit var table: TaskTable
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

    @Test
    fun `an enqueued task is claimed, run with its payload and marked succeeded`() =
        runBlocking<Unit> {
            val seen = CopyOnWriteArrayList<Pair<TaskContext, Greeting>>()
            val greet = TaskType.json<Greeting>("greet")
            val pendenz = pendenz { register(greet) { ctx, payload -> seen += ctx to payload } }

            val id = pendenz.enqueue(greet, Greeting("Ada"))
            startWorker(pendenz)

            awaitStatus(pendenz, id, TaskStatus.SUCCEEDED)
            assertThat(seen).hasSize(1)
            assertThat(seen.single().second).isEqualTo(Greeting("Ada"))
            assertThat(seen.single().first.attempt).isEqualTo(1)
            val record = pendenz.find(id)!!
            assertThat(record.attempts).isEqualTo(1)
            assertThat(record.lockedBy).isNull()
            assertThat(record.lastError).isNull()
        }

    @Test
    fun `a failing handler is retried with backoff until it succeeds`() =
        runBlocking<Unit> {
            val calls = AtomicInteger()
            val flaky = TaskType.json<Greeting>("flaky", retryPolicy = fastBackoff(maxAttempts = 5))
            val pendenz =
                pendenz {
                    register(flaky) { _, _ ->
                        if (calls.incrementAndGet() < 3) error("not yet")
                    }
                }

            val id = pendenz.enqueue(flaky, Greeting("Bob"))
            startWorker(pendenz)

            awaitStatus(pendenz, id, TaskStatus.SUCCEEDED)
            assertThat(calls.get()).isEqualTo(3)
            assertThat(pendenz.find(id)!!.attempts).isEqualTo(3)
        }

    @Test
    fun `attempts exhausted leaves the task failed with the last error, and a plain SQL restart revives it`() =
        runBlocking<Unit> {
            val broken = AtomicInteger(1)
            val type = TaskType.json<Greeting>("fragile", retryPolicy = fastBackoff(maxAttempts = 2))
            val pendenz =
                pendenz {
                    register(type) { _, _ ->
                        if (broken.get() == 1) error("downstream is down")
                    }
                }

            val id = pendenz.enqueue(type, Greeting("Cleo"))
            startWorker(pendenz)

            awaitStatus(pendenz, id, TaskStatus.FAILED)
            pendenz.find(id)!!.let {
                assertThat(it.attempts).isEqualTo(2)
                assertThat(it.lastError).contains("downstream is down")
                assertThat(it.lockedBy).isNull()
            }

            // The operator fixes the downstream and restarts the task with SQL alone.
            broken.set(0)
            val c = table.columns
            val restarted =
                db.sql(
                    """UPDATE "${table.name}" SET "${c.status}" = 'PENDING', "${c.version}" = "${c.version}" + 1
                       WHERE "${c.id}" = $id AND "${c.status}" = 'FAILED'""",
                )
            assertThat(restarted).isEqualTo(1)

            awaitStatus(pendenz, id, TaskStatus.SUCCEEDED)
            assertThat(pendenz.find(id)!!.attempts).isEqualTo(3)
        }

    @Test
    fun `a task scheduled for later waits as scheduled and runs when due`() =
        runBlocking<Unit> {
            val type = TaskType.json<Greeting>("later")
            val ran = AtomicInteger()
            val pendenz = pendenz { register(type) { _, _ -> ran.incrementAndGet() } }
            startWorker(pendenz)

            val id = pendenz.enqueue(type, Greeting("Dan"), runAt = Instant.now().plusMillis(700))

            assertThat(pendenz.find(id)!!.status).isEqualTo(TaskStatus.SCHEDULED)
            delay(300)
            assertThat(ran.get()).isZero()
            awaitStatus(pendenz, id, TaskStatus.SUCCEEDED)
            assertThat(ran.get()).isEqualTo(1)
        }

    @Test
    fun `a SQL intervention while running wins over the worker's outcome`() =
        runBlocking<Unit> {
            val type = TaskType.json<Greeting>("slow")
            val pendenz = pendenz { register(type) { _, _ -> delay(1500) } }
            val id = pendenz.enqueue(type, Greeting("Eve"))
            startWorker(pendenz)

            awaitStatus(pendenz, id, TaskStatus.RUNNING)
            val c = table.columns
            db.sql(
                """UPDATE "${table.name}" SET "${c.status}" = 'CANCELLED', "${c.version}" = "${c.version}" + 1
                   WHERE "${c.id}" = $id""",
            )

            delay(2000)
            assertThat(pendenz.find(id)!!.status).isEqualTo(TaskStatus.CANCELLED)
        }

    @Test
    fun `enqueuing inside the caller's transaction follows its rollback`() =
        runBlocking<Unit> {
            val type = TaskType.json<Greeting>("tx")
            val pendenz = pendenz { register(type) { _, _ -> } }

            val id =
                db.dataSource.connection.use { connection ->
                    connection.autoCommit = false
                    val id = pendenz.enqueue(connection, type, Greeting("Fay"))
                    connection.rollback()
                    id
                }

            assertThat(pendenz.find(id)).isNull()
        }

    @Test
    fun `unregistered task types cannot be enqueued`() =
        runBlocking<Unit> {
            val registered = TaskType.json<Greeting>("known")
            val stranger = TaskType.json<Greeting>("unknown")
            val pendenz = pendenz { register(registered) { _, _ -> } }

            assertThatThrownBy { runBlocking { pendenz.enqueue(stranger, Greeting("Gus")) } }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("unknown")
        }

    @Test
    fun `two workers share the table without running a task twice`() =
        runBlocking<Unit> {
            val type = TaskType.json<Greeting>("shared")
            val runs = CopyOnWriteArrayList<Long>()
            val pendenz = pendenz { register(type) { ctx, _ -> runs += ctx.id } }
            val ids = (1..20).map { pendenz.enqueue(type, Greeting("n$it")) }

            startWorker(pendenz, WorkerOptions(parallelism = 2, pollInterval = Duration.ofMillis(20)))
            startWorker(pendenz, WorkerOptions(parallelism = 2, pollInterval = Duration.ofMillis(20)))

            await atMost Duration.ofSeconds(10) untilAsserted {
                assertThat(runs).hasSize(20)
            }
            assertThat(runs).containsExactlyInAnyOrderElementsOf(ids)
        }

    private fun pendenz(configure: Pendenz.Builder.() -> Unit): Pendenz =
        Pendenz.create {
            dataSource(db.dataSource)
            table(table)
            configure()
        }

    private fun startWorker(
        pendenz: Pendenz,
        options: WorkerOptions = WorkerOptions(pollInterval = Duration.ofMillis(50)),
    ) {
        workers += pendenz.startWorker(scope, options)
    }

    private fun fastBackoff(maxAttempts: Int) =
        ExponentialBackoff(maxAttempts = maxAttempts, initialDelay = Duration.ofMillis(20), jitter = 0.0)

    private fun awaitStatus(
        pendenz: Pendenz,
        id: Long,
        status: TaskStatus,
    ): TaskRecord {
        lateinit var record: TaskRecord
        await atMost Duration.ofSeconds(10) untilAsserted {
            record = runBlocking { pendenz.find(id)!! }
            assertThat(record.status).isEqualTo(status)
        }
        return record
    }

    companion object {
        @JvmField
        @RegisterExtension
        val db = PostgresDatabase()
    }
}
