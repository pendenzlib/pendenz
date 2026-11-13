package io.github.pendenzlib

import io.github.pendenzlib.schema.CycleTable
import io.github.pendenzlib.schema.PostgresDdl
import io.github.pendenzlib.schema.TaskTable
import io.github.pendenzlib.testing.MutableClock
import io.github.pendenzlib.testing.PostgresDatabase
import kotlinx.coroutines.CompletableDeferred
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
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A cycle started from a handler against PostgreSQL (ADR 0047): the query
 * runs in the transaction that records the outcome of the starting task.
 */
class HandlerCycleEndToEndTest {
    data class SendInvoice(
        val invoiceId: Long,
        val customer: String,
    )

    @Serializable
    data class InvoiceRun(
        val description: String,
    )

    private val sendInvoice =
        TaskType.columns<SendInvoice>(
            "send-invoice",
            listOf(PayloadColumn("invoice_id", SqlType.BIGINT), PayloadColumn("customer", SqlType.TEXT)),
            write = { mapOf("invoice_id" to it.invoiceId, "customer" to it.customer) },
            read = { SendInvoice(it.getLong("invoice_id"), it.getString("customer")) },
        )
    private val startRun = TaskType.json<InvoiceRun>("start-invoice-run", retryPolicy = NoRetry)
    private val audit = TaskType.json<InvoiceRun>("audit-invoice-run", retryPolicy = NoRetry)

    private lateinit var table: TaskTable<Long>
    private lateinit var cycleTable: CycleTable<Long>
    private lateinit var invoices: String
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val workers = mutableListOf<Job>()
    private val dropped = CopyOnWriteArrayList<Long>()

    @BeforeEach
    fun createTables() {
        val suffix = db.uniqueSuffix()
        table = TaskTable(name = "task_$suffix")
        cycleTable = CycleTable(name = "cycle_$suffix")
        invoices = "invoice_$suffix"
        db.sql(PostgresDdl.createTable(table, sendInvoice, startRun, audit))
        db.sql(PostgresDdl.createCycleTable(cycleTable))
        db.sql("""CREATE TABLE "$invoices" (id bigint PRIMARY KEY, customer text NOT NULL, sent boolean NOT NULL)""")
        (1..20).forEach { db.sql("""INSERT INTO "$invoices" VALUES ($it, 'customer-$it', ${it > 15})""") }
    }

    @AfterEach
    fun stopWorkers() =
        runBlocking<Unit> {
            workers.forEach { it.cancelAndJoin() }
        }

    private val unsentInvoices get() = """SELECT id, customer FROM "$invoices" WHERE sent = false"""

    @Nested
    inner class Start {
        @Test
        fun `context startCycle - query over the invoice table - one task per unsent invoice in the cycle's lane`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz =
                    pendenz {
                        ctx,
                        run,
                        ->
                        ctx.startCycle(sendInvoice, CycleTasks.query(unsentInvoices), run.description)
                    }
                pendenz.enqueue(startRun, InvoiceRun("October"))

                pendenz.runPending()

                // Assert
                val cycle = db.long("""SELECT id FROM "${cycleTable.name}" WHERE description = 'October'""")
                assertThat(count("lane = 'cycle:$cycle' AND task_type = 'send-invoice'")).isEqualTo(15)
                assertThat(db.long("""SELECT max(invoice_id) FROM "${table.name}" WHERE lane = 'cycle:$cycle'"""))
                    .isEqualTo(15)
            }

        @Test
        fun `context startCycle - query, task with creator and correlation - the tasks inherit them and the parent`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { ctx, _ -> ctx.startCycle(sendInvoice, CycleTasks.query(unsentInvoices)) }
                val starting =
                    pendenz.enqueue(
                        startRun,
                        InvoiceRun("October"),
                        createdBy = "billing",
                        priority = 3,
                        correlationId = "run-october",
                    )

                pendenz.runPending()

                // Assert
                val inherited =
                    "parent_id = $starting AND created_by = 'billing' AND correlation_id = 'run-october'"
                assertThat(count("task_type = 'send-invoice' AND priority = 3 AND $inherited")).isEqualTo(15)
                assertThat(db.string("""SELECT created_by FROM "${cycleTable.name}"""")).isEqualTo("billing")
            }

        @Test
        fun `context startCycle - occurrence of a schedule, query - the cycle's row names the schedule`() =
            runBlocking<Unit> {
                // Arrange
                val clock = MutableClock(Instant.parse("2026-10-19T09:20:00Z"))
                val pendenz =
                    Pendenz.create(table) {
                        dataSource(db.dataSource)
                        clock(clock)
                        enableCycles(cycleTable)
                        register(sendInvoice) { _, _ -> }
                        register(startRun) { ctx, run ->
                            ctx.startCycle(sendInvoice, CycleTasks.query(unsentInvoices), run.description)
                        }
                        schedule("weekly-invoices", startRun, InvoiceRun("weekly"), Schedule.every(Duration.ofHours(1)))
                    }
                pendenz.runPending()
                clock.now = Instant.parse("2026-10-19T10:00:00Z")

                pendenz.runPending()

                // Assert
                val creator = "created_by = 'schedule:weekly-invoices'"
                val cycles = """SELECT count(*) FROM "${cycleTable.name}" WHERE $creator"""
                assertThat(db.count(cycles)).isEqualTo(1)
                assertThat(
                    count("task_type = 'send-invoice' AND created_by = 'schedule:weekly-invoices'"),
                ).isEqualTo(15)
            }

        @Test
        fun `context startCycle - cycles not enabled on the queue - the attempt fails saying so`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz =
                    Pendenz.create(table) {
                        dataSource(db.dataSource)
                        register(sendInvoice) { _, _ -> }
                        register(startRun) { ctx, _ -> ctx.startCycle(sendInvoice, CycleTasks.query(unsentInvoices)) }
                    }
                val starting = pendenz.enqueue(startRun, InvoiceRun("October"))

                pendenz.runPending()

                // Assert
                val failed = pendenz.find(starting)!!
                assertThat(failed.status).isEqualTo(TaskStatus.FAILED)
                assertThat(failed.lastError).contains("cycles are not enabled")
            }
    }

    @Nested
    inner class OutcomeTransaction {
        @Test
        fun `context startCycle - an operator cancelled the task while it ran - the outcome is dropped, no cycle`() =
            runBlocking<Unit> {
                // Arrange
                lateinit var pendenz: Pendenz<Long>
                pendenz =
                    pendenz { ctx, _ ->
                        ctx.startCycle(sendInvoice, CycleTasks.query(unsentInvoices))
                        pendenz.cancel(ctx.id, "operator")
                    }
                val starting = pendenz.enqueue(startRun, InvoiceRun("October"))

                // Act
                startWorker(pendenz)

                // Assert
                await atMost Duration.ofSeconds(10) untilAsserted { assertThat(dropped).containsExactly(starting) }
                assertThat(pendenz.find(starting)!!.status).isEqualTo(TaskStatus.CANCELLED)
                assertThat(db.count("""SELECT count(*) FROM "${cycleTable.name}"""")).isZero()
                assertThat(count("task_type = 'send-invoice'")).isZero()
            }

        @Test
        fun `context startCycle - query that fails - the task is FAILED once its lock expired, no cycle`() =
            runBlocking<Unit> {
                // Arrange
                val broken = """SELECT id, customer FROM "${invoices}_missing""""
                val pendenz = pendenz { ctx, _ -> ctx.startCycle(sendInvoice, CycleTasks.query(broken)) }
                val starting = pendenz.enqueue(startRun, InvoiceRun("October"))

                // Act
                startWorker(pendenz)

                // Assert
                await atMost Duration.ofSeconds(10) untilAsserted {
                    val task = runBlocking { pendenz.find(starting) }!!
                    assertThat(task.status).isEqualTo(TaskStatus.FAILED)
                    assertThat(task.lastError).contains("lock expired")
                }
                assertThat(db.count("""SELECT count(*) FROM "${cycleTable.name}"""")).isZero()
            }

        @Test
        fun `context startCycle - query that runs longer than the lock timeout - one cycle, the task SUCCEEDED once`() =
            runBlocking<Unit> {
                // Arrange
                val slow = """SELECT id, customer FROM "$invoices", pg_sleep(2.5) WHERE sent = false"""
                val pendenz = pendenz { ctx, _ -> ctx.startCycle(sendInvoice, CycleTasks.query(slow)) }
                val starting = pendenz.enqueue(startRun, InvoiceRun("October"))

                // Act
                startWorker(pendenz)

                // Assert
                await atMost Duration.ofSeconds(10) untilAsserted {
                    assertThat(runBlocking { pendenz.find(starting) }!!.status).isEqualTo(TaskStatus.SUCCEEDED)
                }
                assertThat(pendenz.find(starting)!!.attempts).isEqualTo(1)
                assertThat(db.count("""SELECT count(*) FROM "${cycleTable.name}"""")).isEqualTo(1)
                assertThat(count("task_type = 'send-invoice'")).isEqualTo(15)
            }
    }

    @Nested
    inner class Schedules {
        @Test
        fun `schedule - follow-up of another type ends while no occurrence is open - it inserts no occurrence`() =
            runBlocking<Unit> {
                // Arrange: the occurrence of 10:00 ran and enqueued an audit, which inherits the schedule as creator
                val clock = MutableClock(Instant.parse("2026-10-19T09:20:00Z"))
                val auditRunning = CompletableDeferred<Unit>()
                val auditMayEnd = CompletableDeferred<Unit>()
                val pendenz =
                    Pendenz.create(table) {
                        dataSource(db.dataSource)
                        clock(clock)
                        register(sendInvoice) { _, _ -> }
                        register(startRun) { ctx, run -> ctx.enqueue(audit, run) }
                        register(audit) { _, _ ->
                            auditRunning.complete(Unit)
                            auditMayEnd.await()
                        }
                        schedule("weekly-invoices", startRun, InvoiceRun("weekly"), Schedule.every(Duration.ofHours(1)))
                    }
                pendenz.runMaintenance()
                clock.now = Instant.parse("2026-10-19T10:00:00Z")
                // The lock outlasts the jump of the clock below; no maintenance, which would check the schedule itself
                val options =
                    WorkerOptions(
                        pollInterval = Duration.ofMillis(50),
                        lockTimeout = Duration.ofHours(3),
                        maintenanceInterval = Duration.ZERO,
                    )
                workers += pendenz.startWorker(scope, options)
                auditRunning.await()
                val next = pendenz.tasks(TaskQuery(types = setOf("start-invoice-run"))).first()
                pendenz.cancel(next.id, "operator")
                clock.now = Instant.parse("2026-10-19T11:30:00Z")

                // Act
                auditMayEnd.complete(Unit)

                // Assert
                await atMost Duration.ofSeconds(10) untilAsserted {
                    assertThat(count("task_type = 'audit-invoice-run' AND status = 'SUCCEEDED'")).isEqualTo(1)
                }
                assertThat(pendenz.tasks(TaskQuery(types = setOf("start-invoice-run"))).map { it.status })
                    .containsExactly(TaskStatus.CANCELLED, TaskStatus.SUCCEEDED)
            }
    }

    private fun count(where: String): Long = db.count("""SELECT count(*) FROM "${table.name}" WHERE $where""")

    private fun pendenz(handler: TaskHandler<Long, InvoiceRun>): Pendenz<Long> =
        Pendenz.create(table) {
            dataSource(db.dataSource)
            enableCycles(cycleTable)
            listener(
                object : TaskListener<Long> {
                    override fun onOutcomeDropped(task: TaskContext<Long>) {
                        dropped += task.id
                    }
                },
            )
            register(sendInvoice) { _, _ -> }
            register(startRun, handler)
        }

    /** A worker whose lock times out after a second, so that a test sees an expired lock without waiting long. */
    private fun startWorker(pendenz: Pendenz<Long>) {
        val options =
            WorkerOptions(
                pollInterval = Duration.ofMillis(50),
                heartbeatInterval = Duration.ofMillis(100),
                lockTimeout = Duration.ofSeconds(1),
            )
        workers += pendenz.startWorker(scope, options)
    }

    companion object {
        @JvmField
        @RegisterExtension
        val db = PostgresDatabase()
    }
}
