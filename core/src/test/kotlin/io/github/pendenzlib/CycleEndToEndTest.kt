package io.github.pendenzlib

import io.github.pendenzlib.schema.CycleTable
import io.github.pendenzlib.schema.PostgresDdl
import io.github.pendenzlib.schema.TaskTable
import io.github.pendenzlib.testing.PostgresDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
import java.util.concurrent.CopyOnWriteArrayList

/** Cycles against PostgreSQL (ADR 0016): INSERT ... SELECT from the application's query, the view, the worker. */
class CycleEndToEndTest {
    data class SendInvoice(
        val invoiceId: Long,
        val customer: String,
    )

    private val sendInvoice =
        TaskType.columns<SendInvoice>(
            "send-invoice",
            listOf(PayloadColumn("invoice_id", SqlType.BIGINT), PayloadColumn("customer", SqlType.TEXT)),
            write = { mapOf("invoice_id" to it.invoiceId, "customer" to it.customer) },
            read = { SendInvoice(it.getLong("invoice_id"), it.getString("customer")) },
        )

    /** A second type on the same table, with a column of its own, so the table is wider than one type's payload. */
    private val sendReminder =
        TaskType.columns<Reminder>(
            "send-reminder",
            listOf(PayloadColumn("invoice_id", SqlType.BIGINT), PayloadColumn("reminder_text", SqlType.TEXT)),
            write = { mapOf("invoice_id" to it.invoiceId, "reminder_text" to it.text) },
            read = { Reminder(it.getLong("invoice_id"), it.getString("reminder_text")) },
        )

    data class Reminder(
        val invoiceId: Long,
        val text: String,
    )

    private lateinit var table: TaskTable<Long>
    private lateinit var cycleTable: CycleTable<Long>
    private lateinit var invoices: String
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val workers = mutableListOf<Job>()

    @BeforeEach
    fun createTables() {
        val suffix = db.uniqueSuffix()
        table = TaskTable(name = "task_$suffix")
        cycleTable = CycleTable(name = "cycle_$suffix")
        invoices = "invoice_$suffix"
        db.sql(PostgresDdl.createTable(table, sendInvoice, sendReminder))
        db.sql(PostgresDdl.createCycleTable(cycleTable))
        db.sql(PostgresDdl.cycleProgressView(table, cycleTable))
        db.sql("""CREATE TABLE "$invoices" (id bigint PRIMARY KEY, customer text NOT NULL, sent boolean NOT NULL)""")
        (1..20).forEach { db.sql("""INSERT INTO "$invoices" VALUES ($it, 'customer-$it', ${it > 15})""") }
    }

    @AfterEach
    fun stopWorkers() =
        runBlocking<Unit> {
            workers.forEach { it.cancelAndJoin() }
        }

    private val unsentInvoices get() = """SELECT id, customer FROM "$invoices" WHERE sent = false ORDER BY id"""

    @Nested
    inner class Start {
        @Test
        fun `startCycle - query over the invoice table - one task per unsent invoice, with its columns, in the lane`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { _, _ -> }

                // Act
                val id = pendenz.startCycle(sendInvoice, CycleTasks.query(unsentInvoices), "October", "billing-run")

                // Assert
                assertThat(pendenz.cycleProgress(id)).containsExactlyEntriesOf(mapOf(TaskStatus.PENDING to 15))
                val customers =
                    db.count(
                        """SELECT count(DISTINCT customer) FROM "${table.name}" WHERE lane = 'cycle:$id'""",
                    )
                assertThat(customers).isEqualTo(15)
                assertThat(
                    db.long("""SELECT min(invoice_id) FROM "${table.name}" WHERE lane = 'cycle:$id'"""),
                ).isEqualTo(1)
                assertThat(
                    db.count("""SELECT count(*) FROM "${table.name}" WHERE created_by = 'billing-run'"""),
                ).isEqualTo(15)
            }

        @Test
        fun `startCycle - another type with other columns on the same table - the query fills this type's columns`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz =
                    Pendenz.create(table) {
                        dataSource(db.dataSource)
                        enableCycles(cycleTable)
                        register(sendInvoice) { _, _ -> }
                        register(sendReminder) { _, _ -> }
                    }

                // Act
                val id = pendenz.startCycle(sendInvoice, CycleTasks.query(unsentInvoices))

                // Assert
                assertThat(pendenz.cycleProgress(id)).containsExactlyEntriesOf(mapOf(TaskStatus.PENDING to 15))
                assertThat(
                    db.count("""SELECT count(*) FROM "${table.name}" WHERE reminder_text IS NULL"""),
                ).isEqualTo(15)
            }

        @Test
        fun `startCycle - query and a type with a fixed resource - every task carries the type's resource`() =
            runBlocking<Unit> {
                // Arrange
                val throttled =
                    TaskType.columns<SendInvoice>(
                        sendInvoice.name,
                        sendInvoice.payload.columns,
                        write = { mapOf("invoice_id" to it.invoiceId, "customer" to it.customer) },
                        read = { SendInvoice(it.getLong("invoice_id"), it.getString("customer")) },
                        resource = TaskResource.fixed("billing-api"),
                    )
                val pendenz =
                    Pendenz.create(table) {
                        dataSource(db.dataSource)
                        enableCycles(cycleTable)
                        register(throttled) { _, _ -> }
                    }

                // Act
                pendenz.startCycle(throttled, CycleTasks.query(unsentInvoices))

                // Assert
                assertThat(
                    db.count("""SELECT count(*) FROM "${table.name}" WHERE resource = 'billing-api'"""),
                ).isEqualTo(15)
            }

        @Test
        fun `startCycle - query whose tasks wait for approval - the listener hears one start, 15 tasks, all waiting`() =
            runBlocking<Unit> {
                // Arrange
                val held =
                    TaskType.columns<SendInvoice>(
                        "send-invoice",
                        sendInvoice.payload.columns,
                        write = { mapOf("invoice_id" to it.invoiceId, "customer" to it.customer) },
                        read = { SendInvoice(it.getLong("invoice_id"), it.getString("customer")) },
                        approval = Approval.ALWAYS,
                    )
                val started = CopyOnWriteArrayList<CycleEvent<Long>>()
                val pendenz =
                    Pendenz.create(table) {
                        dataSource(db.dataSource)
                        enableCycles(cycleTable)
                        register(held) { _, _ -> }
                        listener(
                            object : TaskListener<Long> {
                                override fun onCycleStarted(cycle: CycleEvent<Long>) {
                                    started += cycle
                                }
                            },
                        )
                    }

                // Act
                val id = pendenz.startCycle(held, CycleTasks.query(unsentInvoices), createdBy = "billing-run")

                // Assert
                assertThat(started).singleElement().satisfies({ event ->
                    assertThat(event.id).isEqualTo(id)
                    assertThat(event.tasks).isEqualTo(15)
                    assertThat(event.awaitingApproval).isEqualTo(15)
                    assertThat(event.actor).isEqualTo("billing-run")
                })
            }

        @Test
        fun `startCycle - inside the caller's transaction, rolled back - neither cycle nor tasks exist`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { _, _ -> }

                // Act
                val id =
                    db.dataSource.connection.use { connection ->
                        connection.autoCommit = false
                        val id = pendenz.startCycle(connection, sendInvoice, CycleTasks.query(unsentInvoices))
                        connection.rollback()
                        id
                    }

                // Assert
                assertThat(pendenz.findCycle(id)).isNull()
                assertThat(db.count("""SELECT count(*) FROM "${table.name}"""")).isZero()
            }
    }

    @Nested
    inner class Progress {
        @Test
        fun `worker - cycle of fifteen - runs every task, the view counts them and maintenance finishes the cycle`() =
            runBlocking<Unit> {
                // Arrange
                val sent = CopyOnWriteArrayList<SendInvoice>()
                val pendenz = pendenz { _, invoice -> sent += invoice }
                val id = pendenz.startCycle(sendInvoice, CycleTasks.query(unsentInvoices))

                // Act
                startWorker(pendenz, parallelism = 4)

                // Assert
                await atMost Duration.ofSeconds(10) untilAsserted {
                    assertThat(runBlocking { pendenz.findCycle(id)!!.status }).isEqualTo(CycleStatus.FINISHED)
                }
                assertThat(sent.map { it.invoiceId }).containsExactlyInAnyOrderElementsOf((1L..15L).toList())
                val view = "${cycleTable.name}_progress"
                assertThat(db.long("""SELECT succeeded FROM "$view" WHERE id = $id""")).isEqualTo(15)
                assertThat(db.long("""SELECT tasks FROM "$view" WHERE id = $id""")).isEqualTo(15)
                assertThat(db.long("""SELECT pending FROM "$view" WHERE id = $id""")).isZero()
            }
    }

    @Nested
    inner class CycleRetention {
        @Test
        fun `worker - type with retention - the finished cycle row goes after its tasks`() =
            runBlocking<Unit> {
                // Arrange
                val fleeting =
                    TaskType.columns<SendInvoice>(
                        "send-invoice",
                        listOf(PayloadColumn("invoice_id", SqlType.BIGINT), PayloadColumn("customer", SqlType.TEXT)),
                        write = { mapOf("invoice_id" to it.invoiceId, "customer" to it.customer) },
                        read = { SendInvoice(it.getLong("invoice_id"), it.getString("customer")) },
                        retention = Retention.delete(Duration.ofMillis(200)),
                    )
                val pendenz =
                    Pendenz.create(table) {
                        dataSource(db.dataSource)
                        enableCycles(cycleTable)
                        register(fleeting) { _, _ -> }
                    }
                val id = pendenz.startCycle(fleeting, CycleTasks.query(unsentInvoices))

                // Act
                startWorker(pendenz, parallelism = 4)

                // Assert
                await atMost Duration.ofSeconds(10) untilAsserted {
                    assertThat(runBlocking { pendenz.findCycle(id) }).isNull()
                }
                assertThat(db.count("""SELECT count(*) FROM "${table.name}"""")).isZero()
            }
    }

    @Nested
    inner class PauseResumeCancel {
        @Test
        fun `pauseCycle - worker running one task at a time - the rest wait as QUARANTINED and none runs`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { _, _ -> delay(50) }
                val id = pendenz.startCycle(sendInvoice, CycleTasks.query(unsentInvoices))
                startWorker(pendenz, parallelism = 1)
                await atMost Duration.ofSeconds(10) untilAsserted {
                    assertThat(
                        runBlocking { pendenz.cycleProgress(id) }[TaskStatus.SUCCEEDED] ?: 0,
                    ).isGreaterThanOrEqualTo(1)
                }

                // Act
                val paused = pendenz.pauseCycle(id)

                // Assert
                assertThat(paused).isTrue()
                await atMost Duration.ofSeconds(10) untilAsserted {
                    val progress = runBlocking { pendenz.cycleProgress(id) }
                    assertThat(progress[TaskStatus.RUNNING] ?: 0).isZero()
                    assertThat(progress[TaskStatus.QUARANTINED] ?: 0).isGreaterThanOrEqualTo(1)
                }
                val whilePaused = pendenz.cycleProgress(id)
                delay(300) // fixed wait on purpose: nothing of a paused cycle may run meanwhile
                assertThat(pendenz.cycleProgress(id)).isEqualTo(whilePaused)
            }

        @Test
        fun `resumeCycle - paused while a worker runs - the worker runs every task of the cycle`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { _, _ -> delay(50) }
                val id = pendenz.startCycle(sendInvoice, CycleTasks.query(unsentInvoices))
                startWorker(pendenz, parallelism = 1)
                await atMost Duration.ofSeconds(10) untilAsserted {
                    assertThat(
                        runBlocking { pendenz.cycleProgress(id) }[TaskStatus.SUCCEEDED] ?: 0,
                    ).isGreaterThanOrEqualTo(1)
                }
                pendenz.pauseCycle(id)

                // Act
                val resumed = pendenz.resumeCycle(id)

                // Assert
                assertThat(resumed).isTrue()
                await atMost Duration.ofSeconds(10) untilAsserted {
                    assertThat(runBlocking { pendenz.cycleProgress(id) }).containsExactlyEntriesOf(
                        mapOf(TaskStatus.SUCCEEDED to 15),
                    )
                }
            }

        @Test
        fun `resumeCycle - a task the operator quarantined for another reason - stays QUARANTINED`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { _, _ -> }
                val id = pendenz.startCycle(sendInvoice, CycleTasks.query(unsentInvoices))
                pendenz.pauseCycle(id)
                val held = """UPDATE "${table.name}" SET last_error = 'held by operator', version = version + 1"""
                db.sql("$held WHERE invoice_id = 7")

                // Act
                pendenz.resumeCycle(id)

                // Assert
                assertThat(pendenz.cycleProgress(id)).containsExactlyInAnyOrderEntriesOf(
                    mapOf(TaskStatus.PENDING to 14, TaskStatus.QUARANTINED to 1),
                )
            }

        @Test
        fun `cancelCycle - a task of it is RUNNING - that task is cancelled too and the worker drops its outcome`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { _, _ -> delay(3_000) }
                val id = pendenz.startCycle(sendInvoice, CycleTasks.query(unsentInvoices))
                startWorker(pendenz, parallelism = 1)
                await atMost Duration.ofSeconds(10) untilAsserted {
                    assertThat(runBlocking { pendenz.cycleProgress(id) }[TaskStatus.RUNNING] ?: 0).isEqualTo(1)
                }

                // Act
                val cancelled = pendenz.cancelCycle(id)

                // Assert
                assertThat(cancelled).isTrue()
                assertThat(pendenz.findCycle(id)!!.status).isEqualTo(CycleStatus.CANCELLED)
                assertThat(pendenz.cycleProgress(id)).containsExactlyEntriesOf(mapOf(TaskStatus.CANCELLED to 15))
                // fixed wait on purpose: the running handler ends meanwhile and must not overwrite CANCELLED
                delay(3_500)
                assertThat(pendenz.cycleProgress(id)).containsExactlyEntriesOf(mapOf(TaskStatus.CANCELLED to 15))
            }
    }

    private fun pendenz(handler: TaskHandler<Long, SendInvoice>): Pendenz<Long> =
        Pendenz.create(table) {
            dataSource(db.dataSource)
            enableCycles(cycleTable)
            register(sendInvoice, handler)
        }

    private fun startWorker(
        pendenz: Pendenz<Long>,
        parallelism: Int,
    ) {
        val options =
            WorkerOptions(
                parallelism = parallelism,
                pollInterval = Duration.ofMillis(50),
                heartbeatInterval = Duration.ofMillis(100),
                lockTimeout = Duration.ofSeconds(5),
                maintenanceInterval = Duration.ofMillis(100),
            )
        workers += pendenz.startWorker(scope, options)
    }

    companion object {
        @JvmField
        @RegisterExtension
        val db = PostgresDatabase()
    }
}
