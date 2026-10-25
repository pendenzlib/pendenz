package io.github.d4niella.pendenz.notify

import io.github.d4niella.pendenz.Pendenz
import io.github.d4niella.pendenz.TaskStatus
import io.github.d4niella.pendenz.TaskType
import io.github.d4niella.pendenz.WorkerOptions
import io.github.d4niella.pendenz.schema.PostgresDdl
import io.github.d4niella.pendenz.schema.TaskTable
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
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource
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

                // Act
                db.sql("NOTIFY ${PostgresDdl.notifyChannel(table)}, 'greet'")

                // Assert
                assertThat(waiting.await()).isLessThan(5_000)
            }

        @Test
        fun `await - NOTIFY for another type - keeps waiting until the timeout`() =
            runBlocking<Unit> {
                // Arrange
                val waiting = scope.async { measureTimeMillis { notify.await(setOf("greet"), Duration.ofMillis(500)) } }
                awaitListening()

                // Act
                db.sql("NOTIFY ${PostgresDdl.notifyChannel(table)}, 'other'")

                // Assert: the full timeout elapses, which shows the notification was ignored
                assertThat(waiting.await()).isGreaterThanOrEqualTo(500)
            }

        @Test
        fun `await - table name of sixty characters - the trigger's announcement reaches the waiter`() =
            runBlocking<Unit> {
                // Arrange: trigger and listener derive the same shortened channel
                val unique = db.uniqueSuffix()
                val long =
                    TaskTable(
                        name =
                            "customer_onboarding_document_verification_".padEnd(60 - unique.length, 'x') + unique,
                    )
                db.sql(PostgresDdl.createTable(long))
                db.sql(PostgresDdl.notifyTrigger(long))
                val listening = PostgresNotify.start(db.dataSource, long)
                try {
                    val waiting =
                        scope.async {
                            measureTimeMillis {
                                listening.await(
                                    setOf("greet"),
                                    Duration.ofSeconds(10),
                                )
                            }
                        }
                    awaitListening(channel = PostgresDdl.notifyChannel(long))

                    // Act
                    db.sql(
                        """INSERT INTO "${long.name}" (task_type, status, run_at, payload)
                           VALUES ('greet', 'PENDING', now(), '{}')""",
                    )

                    // Assert
                    assertThat(waiting.await()).isLessThan(5_000)
                } finally {
                    listening.close()
                }
            }

        @Test
        fun `await - NOTIFY without a payload - wakes every waiter`() =
            runBlocking<Unit> {
                // Arrange
                val waiting = scope.async { measureTimeMillis { notify.await(setOf("greet"), Duration.ofSeconds(10)) } }
                awaitListening()

                // Act
                db.sql("NOTIFY ${PostgresDdl.notifyChannel(table)}")

                // Assert
                assertThat(waiting.await()).isLessThan(5_000)
            }
    }

    @Nested
    inner class Connection {
        @Test
        fun `start - data source hands out connections with auto-commit off - LISTEN still takes effect`() =
            runBlocking<Unit> {
                // Arrange
                val inTransaction =
                    object : DataSource by db.dataSource {
                        override fun getConnection(): java.sql.Connection =
                            db.dataSource.connection.also {
                                it.autoCommit =
                                    false
                            }
                    }
                val late = PostgresNotify.start(inTransaction, table)
                try {
                    val waiting =
                        scope.async {
                            measureTimeMillis {
                                late.await(
                                    setOf("greet"),
                                    Duration.ofSeconds(10),
                                )
                            }
                        }
                    awaitListening(2)

                    // Act
                    db.sql("NOTIFY ${PostgresDdl.notifyChannel(table)}, 'greet'")

                    // Assert
                    assertThat(waiting.await()).isLessThan(5_000)
                } finally {
                    late.close()
                }
            }

        @ParameterizedTest
        @ValueSource(strings = ["", " ", "pendenz\"task"])
        fun `start - blank channel or one with a double quote - throws IllegalArgumentException`(channel: String) {
            // Act
            val attempt = ThrowingCallable { PostgresNotify.start(db.dataSource, channel).close() }

            // Assert
            assertThatThrownBy(attempt).isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `close - while the listener is still opening its connection - that connection is closed too`() {
            // Arrange: a data source that opens its connection only when the test lets it
            val opening = CountDownLatch(1)
            val mayOpen = Semaphore(0)
            val opened = AtomicReference<java.sql.Connection>()
            val slow =
                object : DataSource by db.dataSource {
                    override fun getConnection(): java.sql.Connection {
                        opening.countDown()
                        mayOpen.acquireUninterruptibly()
                        return db.dataSource.connection.also { opened.set(it) }
                    }
                }
            val late = PostgresNotify.start(slow, table)
            assertThat(opening.await(10, TimeUnit.SECONDS)).isTrue()

            // Act
            late.close()
            mayOpen.release()

            // Assert
            await atMost Duration.ofSeconds(10) untilAsserted {
                assertThat(opened.get()).isNotNull()
                assertThat(opened.get().isClosed).isTrue()
            }
        }
    }

    @Nested
    inner class Reconnect {
        @Test
        fun `listener - backend terminated by an operator - listens again on a new connection`() {
            // Arrange
            awaitListening()
            val first = listenerPid()

            // Act
            db.long("SELECT pg_terminate_backend($first)::int")

            // Assert: the backoff starts at one second
            await atMost Duration.ofSeconds(10) untilAsserted {
                assertThat(listenerPid()).isNotIn(null, first)
            }
        }

        @Test
        fun `listener - connection stops answering - replaced by a new connection that listens`() {
            // Arrange: the first connection looks alive to the driver but fails every liveness check
            val opened = AtomicInteger()
            val firstGoesSilent = silentFirstConnection(opened)

            // Act
            val replaced = PostgresNotify.start(firstGoesSilent, table, livenessInterval = Duration.ofMillis(200))

            // Assert
            try {
                await atMost Duration.ofSeconds(10) untilAsserted { assertThat(opened.get()).isEqualTo(2) }
            } finally {
                replaced.close()
            }
        }

        @Test
        fun `listener - connection replaced - wakes every waiter once, without a NOTIFY`() =
            runBlocking<Unit> {
                // Act
                // Arrange
                val replaced =
                    PostgresNotify.start(silentFirstConnection(AtomicInteger()), table, Duration.ofMillis(200))
                try {
                    // Act
                    val waiting =
                        scope.async {
                            measureTimeMillis {
                                replaced.await(
                                    setOf("greet"),
                                    Duration.ofSeconds(10),
                                )
                            }
                        }

                    // Assert: replacing the connection takes the one second backoff, far less than the timeout
                    assertThat(waiting.await()).isLessThan(5_000)
                } finally {
                    replaced.close()
                }
            }

        /** A data source whose first connection never passes `isValid`; [opened] counts the connections. */
        private fun silentFirstConnection(opened: AtomicInteger): DataSource =
            object : DataSource by db.dataSource {
                override fun getConnection(): java.sql.Connection {
                    val real = db.dataSource.connection
                    return if (opened.incrementAndGet() == 1) Silent(real) else real
                }
            }

        /** The pid of the session listening on this test's channel, null when there is none. */
        private fun listenerPid(): Long? =
            db
                .count(
                    "SELECT coalesce(max(pid), 0) FROM pg_stat_activity " +
                        "WHERE query = 'LISTEN \"${PostgresDdl.notifyChannel(table)}\"' AND state = 'idle'",
                ).takeIf { it != 0L }
    }

    /** A connection cut off without a reset: it never answers a round trip. */
    private class Silent(
        real: java.sql.Connection,
    ) : java.sql.Connection by real {
        override fun isValid(timeout: Int): Boolean = false
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

                // Act
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

                // Act
                val id = pendenz.enqueue(type, Greeting("Cleo"))

                // Assert
                awaitStatus(pendenz, id, TaskStatus.SUCCEEDED)
            }
    }

    private fun pendenz(configure: Pendenz.Builder<Long>.() -> Unit): Pendenz<Long> =
        Pendenz.create(table) {
            dataSource(db.dataSource)
            configure()
        }

    /**
     * The listener thread needs a moment to open its connection; pg_stat_activity shows when it has.
     * Only sessions on this test's [channel] count: the server is shared with every other test.
     */
    private fun awaitListening(
        atLeast: Long = 1,
        channel: String = PostgresDdl.notifyChannel(table),
    ) {
        await atMost Duration.ofSeconds(10) untilAsserted {
            val listeners =
                db.count(
                    """SELECT count(*) FROM pg_stat_activity WHERE query = 'LISTEN "$channel"' AND state = 'idle'""",
                )
            assertThat(listeners).isGreaterThanOrEqualTo(atLeast)
        }
    }

    companion object {
        @JvmField
        @RegisterExtension
        val db = PostgresDatabase()
    }
}
