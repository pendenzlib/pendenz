package io.github.pendenzlib

import io.github.pendenzlib.schema.PostgresDdl
import io.github.pendenzlib.schema.TaskTable
import io.github.pendenzlib.testing.PostgresDatabase
import io.github.pendenzlib.testing.awaitStatus
import io.github.pendenzlib.testing.fastBackoff
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
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The write of an outcome that fails against PostgreSQL (ADR 0048): a
 * follow-up the application's schema refuses, a trigger that raises, a
 * text the table does not take. A foreign key from the follow-up's
 * `invoice_id` to the invoice table stands for the application's schema.
 */
class OutcomeWriteEndToEndTest {
    @Serializable
    data class CloseInvoice(
        val invoiceId: Long,
    )

    data class NotifyCustomer(
        val invoiceId: Long,
    )

    @Serializable
    data class Reminder(
        val customer: String,
    )

    private val notifyCustomer =
        TaskType.columns<NotifyCustomer>(
            "notify-customer",
            listOf(PayloadColumn("invoice_id", SqlType.BIGINT)),
            write = { mapOf("invoice_id" to it.invoiceId) },
            read = { NotifyCustomer(it.getLong("invoice_id")) },
        )
    private val sendReminder = TaskType.json<Reminder>("send-reminder")

    private lateinit var table: TaskTable<Long>
    private lateinit var invoices: String
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val workers = mutableListOf<Job>()
    private val events = CopyOnWriteArrayList<String>()
    private val runs = AtomicInteger()

    @BeforeEach
    fun createTables() {
        val suffix = db.uniqueSuffix()
        table = TaskTable(name = "task_$suffix")
        invoices = "invoice_$suffix"
        db.sql(PostgresDdl.createTable(table, notifyCustomer, sendReminder))
        db.sql("""CREATE TABLE "$invoices" (id bigint PRIMARY KEY)""")
        db.sql("""INSERT INTO "$invoices" VALUES (7)""")
        db.sql(
            """ALTER TABLE "${table.name}" ADD CONSTRAINT notified_invoice_exists """ +
                """FOREIGN KEY (invoice_id) REFERENCES "$invoices" (id)""",
        )
    }

    @AfterEach
    fun stopWorkers() =
        runBlocking<Unit> {
            workers.forEach { it.cancelAndJoin() }
        }

    @Nested
    inner class RefusedWrite {
        @Test
        fun `outcome write - follow-up violates a foreign key - FAILED after one run, constraint named, not the key`() =
            runBlocking<Unit> {
                // Arrange
                val closeInvoice = TaskType.json<CloseInvoice>("close-invoice")
                val pendenz = pendenz(closeInvoice)
                val id = pendenz.enqueue(closeInvoice, CloseInvoice(4711))

                // Act
                startWorker(pendenz)

                // Assert
                val task = awaitStatus(pendenz, id, TaskStatus.FAILED)
                assertThat(task.attempts).isEqualTo(1)
                assertThat(task.lastError)
                    .contains("outcome SUCCEEDED could not be recorded")
                    .contains("notified_invoice_exists")
                    .doesNotContain("4711")
                assertThat(runs).hasValue(1)
                assertThat(count("task_type = 'notify-customer'")).isZero()
                assertThat(events).containsExactly("failed OutcomeNotRecordedException refused")
            }

        @Test
        fun `outcome write - refused under a policy of the application that retries - the handler runs again`() =
            runBlocking<Unit> {
                // Arrange
                val twice = RetryPolicy { failure -> failure.failedAt.plusMillis(20).takeIf { failure.attempt < 2 } }
                val closeInvoice = TaskType.json<CloseInvoice>("close-invoice", retryPolicy = twice)
                val pendenz = pendenz(closeInvoice)
                val id = pendenz.enqueue(closeInvoice, CloseInvoice(4711))

                // Act
                startWorker(pendenz)

                // Assert
                awaitStatus(pendenz, id, TaskStatus.FAILED)
                assertThat(runs).hasValue(2)
                assertThat(events)
                    .containsExactly(
                        "retry OutcomeNotRecordedException refused",
                        "failed OutcomeNotRecordedException refused",
                    )
            }

        @Test
        fun `outcome write - trigger raises with check_violation - FAILED after one run as a refused write`() =
            runBlocking<Unit> {
                // Arrange
                refuseFollowUps("USING ERRCODE = 'check_violation'")
                val closeInvoice = TaskType.json<CloseInvoice>("close-invoice", retryPolicy = fastBackoff(2))
                val pendenz = pendenz(closeInvoice)
                val id = pendenz.enqueue(closeInvoice, CloseInvoice(7))

                // Act
                startWorker(pendenz)

                // Assert
                awaitStatus(pendenz, id, TaskStatus.FAILED)
                assertThat(runs).hasValue(1)
                assertThat(events).containsExactly("failed OutcomeNotRecordedException refused")
            }

        @Test
        fun `outcome write - twenty refused beside twenty reminders, long lock timeout - all end within seconds`() =
            runBlocking<Unit> {
                // Arrange: at half the lock timeout each, twenty refused tasks would hold the four permits for 25 s
                val closeInvoice = TaskType.json<CloseInvoice>("close-invoice")
                val pendenz = pendenz(closeInvoice)
                repeat(20) { pendenz.enqueue(closeInvoice, CloseInvoice(4000L + it)) }
                repeat(20) { pendenz.enqueue(sendReminder, Reminder("customer-$it")) }

                // Act
                startWorker(pendenz, lockTimeout = Duration.ofSeconds(10))

                // Assert
                await atMost Duration.ofSeconds(10) untilAsserted {
                    assertThat(count("task_type = 'send-reminder' AND status = 'SUCCEEDED'")).isEqualTo(20)
                    assertThat(count("task_type = 'close-invoice' AND status = 'FAILED'")).isEqualTo(20)
                }
                assertThat(count("last_error LIKE '%lock expired%'")).isZero()
            }

        @Test
        fun `outcome write - refused, error description registered - it receives the driver's exception as cause`() =
            runBlocking<Unit> {
                // Arrange
                val described = AtomicReference<Throwable>()
                val closeInvoice = TaskType.json<CloseInvoice>("close-invoice")
                val pendenz =
                    pendenz(closeInvoice) {
                        errorDescription { _, error ->
                            described.set(error)
                            "follow-up refused"
                        }
                    }
                val id = pendenz.enqueue(closeInvoice, CloseInvoice(4711))

                // Act
                startWorker(pendenz)

                // Assert
                assertThat(awaitStatus(pendenz, id, TaskStatus.FAILED).lastError).isEqualTo("follow-up refused")
                assertThat(described.get()).isInstanceOf(OutcomeNotRecordedException::class.java)
                val causes = generateSequence(described.get().cause) { it.cause }.toList()
                assertThat(causes.filterIsInstance<SQLException>().map { it.sqlState }).contains("23503")
            }
    }

    @Nested
    inner class RefusedWriteRetry {
        @Test
        fun `refusedWriteRetry - long on one type, constraint dropped meanwhile - SUCCEEDED after one run`() =
            runBlocking<Unit> {
                // Arrange: the type without a value has failed by the time the constraint goes
                val quick = TaskType.json<CloseInvoice>("close-invoice")
                val patient =
                    TaskType.json<CloseInvoice>(
                        "close-audited-invoice",
                        refusedWriteRetry = Duration.ofSeconds(5),
                    )
                val patientRuns = AtomicInteger()
                val pendenz =
                    pendenz(quick) {
                        register(patient) { ctx, invoice ->
                            patientRuns.incrementAndGet()
                            ctx.enqueue(notifyCustomer, NotifyCustomer(invoice.invoiceId))
                        }
                    }
                val failing = pendenz.enqueue(quick, CloseInvoice(4711))
                val waiting = pendenz.enqueue(patient, CloseInvoice(4712))
                startWorker(pendenz, lockTimeout = Duration.ofSeconds(10))
                awaitStatus(pendenz, failing, TaskStatus.FAILED)

                // Act
                db.sql("""ALTER TABLE "${table.name}" DROP CONSTRAINT notified_invoice_exists""")

                // Assert
                awaitStatus(pendenz, waiting, TaskStatus.SUCCEEDED)
                assertThat(patientRuns).hasValue(1)
                assertThat(count("task_type = 'notify-customer' AND invoice_id = 4712")).isEqualTo(1)
            }
    }

    @Nested
    inner class OtherFailures {
        @Test
        fun `outcome write - trigger raises without an error code - repeated, then the policy retries`() =
            runBlocking<Unit> {
                // Arrange
                refuseFollowUps("")
                val closeInvoice = TaskType.json<CloseInvoice>("close-invoice", retryPolicy = fastBackoff(2))
                val pendenz = pendenz(closeInvoice)
                val id = pendenz.enqueue(closeInvoice, CloseInvoice(7))

                // Act
                startWorker(pendenz)

                // Assert
                val task = awaitStatus(pendenz, id, TaskStatus.FAILED)
                assertThat(
                    task.lastError,
                ).contains("outcome SUCCEEDED could not be recorded").doesNotContain("lock expired")
                assertThat(runs).hasValue(2)
                assertThat(events)
                    .containsExactly(
                        "retry OutcomeNotRecordedException other",
                        "failed OutcomeNotRecordedException other",
                    )
            }

        @Test
        fun `outcome write - second write refused too - the task ends by its expired lock`() =
            runBlocking<Unit> {
                // Arrange: the table takes no error text but the one of an expired lock
                db.sql(
                    """ALTER TABLE "${table.name}" ADD CONSTRAINT only_expired_locks """ +
                        "CHECK (last_error IS NULL OR last_error LIKE '%lock expired%')",
                )
                val closeInvoice = TaskType.json<CloseInvoice>("close-invoice", retryPolicy = NoRetry)
                val pendenz = pendenz(closeInvoice)
                val id = pendenz.enqueue(closeInvoice, CloseInvoice(4711))

                // Act
                startWorker(pendenz)

                // Assert
                assertThat(awaitStatus(pendenz, id, TaskStatus.FAILED).lastError).contains("lock expired")
                assertThat(events).containsExactly("failed LockExpiredException")
            }
    }

    @Nested
    inner class AttemptAlreadyFailed {
        @Test
        fun `outcome write - handler threw and the table refuses its text - policy's statuses, class name stored`() =
            runBlocking<Unit> {
                // Arrange
                db.sql("""ALTER TABLE "${table.name}" ADD CONSTRAINT short_error CHECK (length(last_error) <= 200)""")
                val closeInvoice = TaskType.json<CloseInvoice>("close-invoice", retryPolicy = fastBackoff(2))
                val pendenz =
                    Pendenz.create(table) {
                        dataSource(db.dataSource)
                        listener(Recording())
                        register(closeInvoice) { _, _ -> error("ledger refused the booking: " + "9".repeat(300)) }
                    }
                val id = pendenz.enqueue(closeInvoice, CloseInvoice(7))

                // Act
                startWorker(pendenz)

                // Assert
                val task = awaitStatus(pendenz, id, TaskStatus.FAILED)
                assertThat(task.attempts).isEqualTo(2)
                assertThat(task.lastError).isEqualTo("java.lang.IllegalStateException")
                assertThat(events).containsExactly("retry IllegalStateException", "failed IllegalStateException")
            }

        @Test
        fun `outcome write - handler threw, next occurrence fails by a trigger without a code - handler's text kept`() =
            runBlocking<Unit> {
                // Arrange: the first occurrence exists before the trigger refuses every further one
                val reconcile = TaskType.json<Reminder>("reconcile-ledger", retryPolicy = NoRetry)
                val pendenz =
                    Pendenz.create(table) {
                        dataSource(db.dataSource)
                        listener(Recording())
                        register(reconcile) { _, _ -> error("ledger is closed for the month") }
                        schedule("reconcile", reconcile, Reminder("Globex"), Schedule.every(Duration.ofMillis(200)))
                    }
                pendenz.runMaintenance()
                raiseOnInsert("NEW.created_by = 'schedule:reconcile'", "")

                // Act
                startWorker(pendenz)

                // Assert
                await atMost Duration.ofSeconds(10) untilAsserted {
                    val occurrence = runBlocking { pendenz.tasks(TaskQuery(createdBy = "schedule:reconcile")) }.single()
                    assertThat(occurrence.status).isEqualTo(TaskStatus.FAILED)
                    assertThat(occurrence.lastError).contains("ledger is closed for the month")
                }
                assertThat(events).containsExactly("failed IllegalStateException")
            }
    }

    @Nested
    inner class Schedules {
        @Test
        fun `outcome write - occurrences of a schedule refused, no sweep - the next occurrence runs, FAILED too`() =
            runBlocking<Unit> {
                // Arrange: the worker runs no maintenance, so only the outcome's own write can add an occurrence
                val closeMonth = TaskType.json<CloseInvoice>("close-month")
                val pendenz =
                    Pendenz.create(table) {
                        dataSource(db.dataSource)
                        register(notifyCustomer) { _, _ -> }
                        register(
                            closeMonth,
                        ) { ctx, month -> ctx.enqueue(notifyCustomer, NotifyCustomer(month.invoiceId)) }
                        schedule("close-month", closeMonth, CloseInvoice(4711), Schedule.every(Duration.ofSeconds(1)))
                    }

                // Act
                startWorker(pendenz)

                // Assert
                await atMost Duration.ofSeconds(10) untilAsserted {
                    val statuses =
                        runBlocking {
                            pendenz.tasks(
                                TaskQuery(createdBy = "schedule:close-month"),
                            )
                        }.map { it.status }
                    assertThat(statuses.count { it == TaskStatus.FAILED }).isGreaterThanOrEqualTo(2)
                }
            }
    }

    @Nested
    inner class OperatorInterventions {
        @Test
        fun `outcome write - operator cancels while the write is repeated - stays CANCELLED, outcome dropped`() =
            runBlocking<Unit> {
                // Arrange
                val patient = TaskType.json<CloseInvoice>("close-invoice", refusedWriteRetry = Duration.ofSeconds(5))
                val pendenz = pendenz(patient)
                val id = pendenz.enqueue(patient, CloseInvoice(4711))
                startWorker(pendenz, lockTimeout = Duration.ofSeconds(10))
                await atMost Duration.ofSeconds(10) untilAsserted { assertThat(runs).hasValue(1) }

                // Act
                pendenz.cancel(id)

                // Assert
                await atMost Duration.ofSeconds(10) untilAsserted { assertThat(events).containsExactly("dropped") }
                assertThat(pendenz.find(id)!!.status).isEqualTo(TaskStatus.CANCELLED)
            }
    }

    /** A trigger that refuses every follow-up of type notify-customer, with or without an error code. */
    private fun refuseFollowUps(using: String) = raiseOnInsert("NEW.task_type = 'notify-customer'", using)

    private fun raiseOnInsert(
        condition: String,
        using: String,
    ) {
        val function = "refuse_${table.name}"
        db.sql(
            """
            CREATE FUNCTION $function() RETURNS trigger AS $$
            BEGIN
                RAISE EXCEPTION 'this row is not accepted' $using;
            END $$ LANGUAGE plpgsql
            """.trimIndent(),
        )
        db.sql(
            """CREATE TRIGGER refuse BEFORE INSERT ON "${table.name}" FOR EACH ROW WHEN ($condition) """ +
                "EXECUTE FUNCTION $function()",
        )
    }

    private fun count(where: String): Long = db.count("""SELECT count(*) FROM "${table.name}" WHERE $where""")

    /** A queue whose handler of [closing] notifies the customer of the invoice: the follow-up the schema may refuse. */
    private fun pendenz(
        closing: TaskType<CloseInvoice>,
        configure: Pendenz.Builder<Long>.() -> Unit = {},
    ): Pendenz<Long> =
        Pendenz.create(table) {
            dataSource(db.dataSource)
            listener(Recording())
            register(notifyCustomer) { _, _ -> }
            register(sendReminder) { _, _ -> }
            register(closing) { ctx, invoice ->
                runs.incrementAndGet()
                ctx.enqueue(notifyCustomer, NotifyCustomer(invoice.invoiceId))
            }
            configure()
        }

    private fun startWorker(
        pendenz: Pendenz<Long>,
        lockTimeout: Duration = Duration.ofSeconds(2),
    ) {
        val options =
            WorkerOptions(
                pollInterval = Duration.ofMillis(50),
                heartbeatInterval = Duration.ofMillis(100),
                lockTimeout = lockTimeout,
                maintenanceInterval = Duration.ZERO,
            )
        workers += pendenz.startWorker(scope, options)
    }

    /** Records how every attempt ended: the cause's class and, for an unrecorded outcome, whether it was refused. */
    private inner class Recording : TaskListener<Long> {
        override fun onRetryScheduled(
            task: TaskContext<Long>,
            error: Throwable,
            nextRunAt: Instant,
        ) {
            events += "retry ${describe(error)}"
        }

        override fun onFailed(
            task: TaskContext<Long>,
            error: Throwable,
        ) {
            events += "failed ${describe(error)}"
        }

        override fun onOutcomeDropped(task: TaskContext<Long>) {
            events += "dropped"
        }

        private fun describe(error: Throwable): String {
            val name = error.javaClass.simpleName
            if (error !is OutcomeNotRecordedException) return name
            return "$name ${if (error.refused) "refused" else "other"}"
        }
    }

    companion object {
        @JvmField
        @RegisterExtension
        val db = PostgresDatabase()
    }
}
