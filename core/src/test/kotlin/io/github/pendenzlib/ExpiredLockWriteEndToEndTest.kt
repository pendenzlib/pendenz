package io.github.pendenzlib

import io.github.pendenzlib.schema.TaskTable
import io.github.pendenzlib.testing.PostgresDatabase
import io.github.pendenzlib.testing.awaitStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
import java.sql.SQLException
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * An expired lock whose failed attempt PostgreSQL does not take (ADR 0049):
 * a `last_error` column too narrow for the message, and a trigger that
 * refuses the row. A dead worker is played by SQL that leaves a row
 * `RUNNING` with a lock that ran out a minute ago. Twenty reminders beside
 * it show whether the worker still claims.
 */
class ExpiredLockWriteEndToEndTest {
    @Serializable
    data class CloseInvoice(
        val invoiceId: Long,
    )

    @Serializable
    data class Reminder(
        val customer: String,
    )

    private val closeInvoice = TaskType.json<CloseInvoice>("close-invoice", retryPolicy = NoRetry)
    private val archiveInvoice = TaskType.json<CloseInvoice>("archive-invoice", retryPolicy = NoRetry)
    private val sendReminder = TaskType.json<Reminder>("send-reminder")

    private lateinit var table: TaskTable<Long>
    private lateinit var pendenz: Pendenz<Long>
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val workers = mutableListOf<Job>()
    private val events = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun createTable() {
        table = db.createTable("expired", closeInvoice, archiveInvoice, sendReminder)
        pendenz =
            Pendenz.create(table) {
                dataSource(db.dataSource)
                listener(Recording())
                register(closeInvoice) { _, _ -> }
                register(archiveInvoice) { _, _ -> }
                register(sendReminder) { _, _ -> }
            }
    }

    @AfterEach
    fun stopWorkers() =
        runBlocking<Unit> {
            workers.forEach { it.cancelAndJoin() }
        }

    @Nested
    inner class RefusedText {
        @Test
        fun `expired lock - last_error too narrow for the message - FAILED with the class name, reminders run`() =
            runBlocking<Unit> {
                // Arrange
                db.sql("""ALTER TABLE "${table.name}" ALTER COLUMN last_error TYPE varchar(60)""")
                val id = pendenz.enqueue(closeInvoice, CloseInvoice(4711))
                expireLocks("id = $id")
                repeat(20) { pendenz.enqueue(sendReminder, Reminder("customer-$it")) }

                // Act
                startWorker()

                // Assert
                val task = awaitStatus(pendenz, id, TaskStatus.FAILED)
                assertThat(task.lastError).isEqualTo("io.github.pendenzlib.LockExpiredException")
                awaitReminders(20)
                // The listener hears of the failed attempt after its commit
                await atMost Duration.ofSeconds(10) untilAsserted {
                    assertThat(events).containsExactly("failed LockExpiredException")
                }
            }
    }

    @Nested
    inner class SetAside {
        @Test
        fun `expired lock - trigger refuses every update of the row - stays RUNNING, reported, reminders run`() =
            runBlocking<Unit> {
                // Arrange
                val id = pendenz.enqueue(closeInvoice, CloseInvoice(4711))
                expireLocks("id = $id")
                refuseUpdates("OLD.id = $id", "USING ERRCODE = 'check_violation'")
                repeat(20) { pendenz.enqueue(sendReminder, Reminder("customer-$it")) }

                // Act
                startWorker()

                // Assert
                awaitReminders(20)
                await atMost Duration.ofSeconds(10) untilAsserted {
                    assertThat(events).contains("not recorded 23514")
                }
                assertThat(pendenz.find(id)!!.status).isEqualTo(TaskStatus.RUNNING)
            }

        @Test
        fun `set aside - trigger dropped - the row is FAILED with the message once its time aside has passed`() =
            runBlocking<Unit> {
                // Arrange
                val id = pendenz.enqueue(closeInvoice, CloseInvoice(4711))
                expireLocks("id = $id")
                refuseUpdates("OLD.id = $id")
                startWorker()
                await atMost Duration.ofSeconds(10) untilAsserted {
                    assertThat(events).contains("not recorded P0001")
                }

                // Act
                db.sql("""DROP TRIGGER refuse ON "${table.name}"""")

                // Assert
                assertThat(awaitStatus(pendenz, id, TaskStatus.FAILED).lastError).contains("lock expired", "billing-1")
            }

        @Test
        fun `expired locks - more refusing rows than one poll reads - a newer expired lock is still recorded`() =
            runBlocking<Unit> {
                // Arrange: a poll reads the hundred oldest expired locks; the archive task's lock ran out last
                repeat(101) { pendenz.enqueue(closeInvoice, CloseInvoice(5000L + it)) }
                expireLocks("task_type = 'close-invoice'")
                refuseUpdates("OLD.task_type = 'close-invoice'")
                val newer = pendenz.enqueue(archiveInvoice, CloseInvoice(4711))
                expireLocks("id = $newer", ago = "1 second")

                // Act
                startWorker(lockTimeout = Duration.ofSeconds(30))

                // Assert
                awaitStatus(pendenz, newer, TaskStatus.FAILED)
                assertThat(count("task_type = 'close-invoice' AND status = 'RUNNING'")).isEqualTo(101)
            }
    }

    /** Leaves the rows as a worker that died a while [ago] left them: RUNNING, first attempt, lock run out. */
    private fun expireLocks(
        where: String,
        ago: String = "1 minute",
    ) {
        db.sql(
            """UPDATE "${table.name}" SET status = 'RUNNING', attempts = 1, locked_by = 'billing-1', """ +
                "locked_until = now() - interval '$ago' WHERE $where",
        )
    }

    /** A trigger that raises on every update of the rows [condition] names, with or without an error code. */
    private fun refuseUpdates(
        condition: String,
        using: String = "",
    ) {
        val function = "refuse_${table.name}"
        db.sql(
            """
            CREATE FUNCTION $function() RETURNS trigger AS $$
            BEGIN
                RAISE EXCEPTION 'this row is closed' $using;
            END $$ LANGUAGE plpgsql
            """.trimIndent(),
        )
        db.sql(
            """CREATE TRIGGER refuse BEFORE UPDATE ON "${table.name}" FOR EACH ROW WHEN ($condition) """ +
                "EXECUTE FUNCTION $function()",
        )
    }

    private fun awaitReminders(expected: Long) {
        await atMost Duration.ofSeconds(10) untilAsserted {
            assertThat(count("task_type = 'send-reminder' AND status = 'SUCCEEDED'")).isEqualTo(expected)
        }
    }

    private fun count(where: String): Long = db.count("""SELECT count(*) FROM "${table.name}" WHERE $where""")

    private fun startWorker(lockTimeout: Duration = Duration.ofSeconds(1)) {
        val options =
            WorkerOptions(
                pollInterval = Duration.ofMillis(50),
                heartbeatInterval = Duration.ofMillis(100),
                lockTimeout = lockTimeout,
                maintenanceInterval = Duration.ZERO,
            )
        workers += pendenz.startWorker(scope, options)
    }

    /** Records failed attempts by the cause's class, and rows that were set aside by the SQL state of the write. */
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
            val states = generateSequence(error) { it.cause }.filterIsInstance<SQLException>().map { it.sqlState }
            events += "not recorded ${states.lastOrNull()}"
        }
    }

    companion object {
        @JvmField
        @RegisterExtension
        val db = PostgresDatabase()
    }
}
