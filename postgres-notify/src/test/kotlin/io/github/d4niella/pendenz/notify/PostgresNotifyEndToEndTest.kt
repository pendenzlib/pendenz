package io.github.d4niella.pendenz.notify

import io.github.d4niella.pendenz.Pendenz
import io.github.d4niella.pendenz.TaskStatus
import io.github.d4niella.pendenz.TaskType
import io.github.d4niella.pendenz.WorkerOptions
import io.github.d4niella.pendenz.schema.PostgresDdl
import io.github.d4niella.pendenz.schema.TaskTable
import io.github.d4niella.pendenz.schema.notifyChannelFor
import io.github.d4niella.pendenz.testing.PostgresDatabase
import io.github.d4niella.pendenz.testing.awaitStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Duration
import kotlin.system.measureTimeMillis

class PostgresNotifyEndToEndTest {
    @Serializable
    data class Greeting(
        val name: String,
    )

    private lateinit var table: TaskTable<Long>
    private lateinit var notify: PostgresNotify
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val workers = mutableListOf<Job>()

    @BeforeEach
    fun createTableWithTrigger() {
        table = db.createTable()
        db.sql(PostgresDdl.notifyTrigger(table))
        notify = PostgresNotify.start(db.dataSource, table)
    }

    @AfterEach
    fun stop() =
        runBlocking<Unit> {
            workers.forEach { it.cancelAndJoin() }
            notify.close()
        }

    @Nested
    inner class Await {
        @Test
        fun `await - NOTIFY for a type the waiter handles - returns well before the timeout`() =
            runBlocking<Unit> {
                // Arrange
                val waiting = scope.async { measureTimeMillis { notify.await(setOf("greet"), Duration.ofSeconds(10)) } }
                awaitListening()

                db.sql("NOTIFY ${notifyChannelFor(table)}, 'greet'")

                assertThat(waiting.await()).isLessThan(5_000)
            }

        @Test
        fun `await - NOTIFY for another type - keeps waiting until the timeout`() =
            runBlocking<Unit> {
                // Arrange
                val waiting = scope.async { measureTimeMillis { notify.await(setOf("greet"), Duration.ofMillis(500)) } }
                awaitListening()

                db.sql("NOTIFY ${notifyChannelFor(table)}, 'other'")

                // Assert: the full timeout elapses, which shows the notification was ignored
                assertThat(waiting.await()).isGreaterThanOrEqualTo(500)
            }

        @Test
        fun `await - NOTIFY without a payload - wakes every waiter`() =
            runBlocking<Unit> {
                // Arrange
                val waiting = scope.async { measureTimeMillis { notify.await(setOf("greet"), Duration.ofSeconds(10)) } }
                awaitListening()

                db.sql("NOTIFY ${notifyChannelFor(table)}")

                assertThat(waiting.await()).isLessThan(5_000)
            }
    }

    @Nested
    inner class WithWorker {
        @Test
        fun `worker - trigger installed and a long poll interval - runs an enqueued task within seconds`() =
            runBlocking<Unit> {
                // Arrange
                val type = TaskType.json<Greeting>("greet")
                val pendenz = pendenz { register(type) { _, _ -> } }
                val options = WorkerOptions(pollInterval = Duration.ofMinutes(5), wakeUp = notify)
                workers += pendenz.startWorker(scope, options)
                awaitListening()

                val id = pendenz.enqueue(type, Greeting("Ada"))

                // Assert: ten seconds is far below the five minute poll interval
                awaitStatus(pendenz, id, TaskStatus.SUCCEEDED)
            }

        @Test
        fun `worker - task inserted with SQL - the trigger wakes the worker just the same`() =
            runBlocking<Unit> {
                // Arrange
                val type = TaskType.json<Greeting>("greet")
                val pendenz = pendenz { register(type) { _, _ -> } }
                val options = WorkerOptions(pollInterval = Duration.ofMinutes(5), wakeUp = notify)
                workers += pendenz.startWorker(scope, options)
                awaitListening()

                // Act
                db.sql(
                    """INSERT INTO "${table.name}" (task_type, status, run_at, payload)
                       VALUES ('greet', 'PENDING', now(), '{"name":"Bob"}')""",
                )

                // Assert
                await atMost Duration.ofSeconds(10) untilAsserted {
                    val done = db.count("""SELECT count(*) FROM "${table.name}" WHERE status = 'SUCCEEDED'""")
                    assertThat(done).isEqualTo(1)
                }
            }

        @Test
        fun `worker - listener closed - falls back to polling and still runs the task`() =
            runBlocking<Unit> {
                // Arrange
                val type = TaskType.json<Greeting>("greet")
                val pendenz = pendenz { register(type) { _, _ -> } }
                val options = WorkerOptions(pollInterval = Duration.ofMillis(200), wakeUp = notify)
                workers += pendenz.startWorker(scope, options)
                notify.close()

                val id = pendenz.enqueue(type, Greeting("Cleo"))

                awaitStatus(pendenz, id, TaskStatus.SUCCEEDED)
            }
    }

    private fun pendenz(configure: Pendenz.Builder<Long>.() -> Unit): Pendenz<Long> =
        Pendenz.create(table) {
            dataSource(db.dataSource)
            configure()
        }

    /** The listener thread needs a moment to open its connection; pg_listening_channels shows when it has. */
    private fun awaitListening() {
        await atMost Duration.ofSeconds(10) untilAsserted {
            val listeners =
                db.count(
                    "SELECT count(*) FROM pg_stat_activity WHERE query LIKE 'LISTEN %' AND state = 'idle'",
                )
            assertThat(listeners).isGreaterThanOrEqualTo(1)
        }
    }

    companion object {
        @JvmField
        @RegisterExtension
        val db = PostgresDatabase()
    }
}
