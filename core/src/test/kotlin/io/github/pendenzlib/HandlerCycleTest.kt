package io.github.pendenzlib

import io.github.pendenzlib.schema.IdType
import io.github.pendenzlib.store.InMemoryTaskStore
import io.github.pendenzlib.testing.MutableClock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/** A cycle started from a handler, in the outcome's transaction (ADR 0047), through the in-memory mode. */
class HandlerCycleTest {
    @Serializable
    data class InvoiceRun(
        val month: String,
    )

    @Serializable
    data class Invoice(
        val number: Int,
    )

    private val clock = MutableClock()
    private val store = InMemoryTaskStore(IdType.BIGINT)
    private val events = CopyOnWriteArrayList<String>()
    private val contexts = CopyOnWriteArrayList<TaskContext<Long>>()
    private val startRun = TaskType.json<InvoiceRun>("start-invoice-run", retryPolicy = NoRetry)
    private val sendInvoice = TaskType.json<Invoice>("send-invoice")
    private val october = CycleTasks.of(listOf(Invoice(41), Invoice(42), Invoice(43)))

    @Nested
    inner class Start {
        @Test
        fun `context startCycle - handler succeeds - the cycle exists with one task per payload in its lane`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { ctx, run -> ctx.startCycle(sendInvoice, october, run.month, id = 900L) }
                pendenz.enqueue(startRun, InvoiceRun("October"))

                pendenz.runPending()

                // Assert
                val cycle = pendenz.findCycle(900L)!!
                assertThat(cycle.type).isEqualTo("send-invoice")
                assertThat(cycle.description).isEqualTo("October")
                assertThat(invoiceTasks().map { it.lane }).containsOnly("cycle:900")
                assertThat(invoiceTasks().map { it.payload["number"] }).hasSize(3)
            }

        @Test
        fun `context startCycle - handler succeeds - the listener hears one cycle start and no enqueue per task`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { ctx, _ -> ctx.startCycle(sendInvoice, october, id = 901L) }
                pendenz.enqueue(startRun, InvoiceRun("October"))
                events.clear()

                pendenz.runPending()

                assertThat(
                    events.filterNot { it.startsWith("succeeded") },
                ).containsExactly("cycle 901 started, 3 tasks")
            }

        @Test
        fun `context startCycle - twice in one handler - two cycles with lanes of their own`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz =
                    pendenz { ctx, _ ->
                        ctx.startCycle(sendInvoice, CycleTasks.of(listOf(Invoice(41))), id = 902L)
                        ctx.startCycle(sendInvoice, CycleTasks.of(listOf(Invoice(42))), id = 903L)
                    }
                pendenz.enqueue(startRun, InvoiceRun("October"))

                pendenz.runPending()

                assertThat(invoiceTasks().map { it.lane }).containsExactlyInAnyOrder("cycle:902", "cycle:903")
            }

        @Test
        fun `context startCycle - the starting task is itself in a cycle - the new cycle has a lane of its own`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { ctx, _ -> ctx.startCycle(sendInvoice, october, id = 904L) }
                val outer = pendenz.startCycle(startRun, CycleTasks.of(listOf(InvoiceRun("October"))))

                pendenz.runPending()

                // Assert
                assertThat(invoiceTasks().map { it.lane }).containsOnly("cycle:904")
                assertThat(pendenz.cycleProgress(outer)).containsExactlyEntriesOf(mapOf(TaskStatus.SUCCEEDED to 1))
            }
    }

    @Nested
    inner class Inheritance {
        @Test
        fun `context startCycle - task with creator, correlation and priority - cycle and tasks inherit them`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { ctx, _ -> ctx.startCycle(sendInvoice, october, id = 910L) }
                pendenz.enqueue(
                    startRun,
                    InvoiceRun("October"),
                    createdBy = "billing",
                    priority = 3,
                    correlationId = "run-october",
                )

                pendenz.runPending()

                // Assert
                assertThat(pendenz.findCycle(910L)!!.createdBy).isEqualTo("billing")
                assertThat(invoiceTasks()).allSatisfy { task ->
                    assertThat(task.createdBy).isEqualTo("billing")
                    assertThat(task.correlationId).isEqualTo("run-october")
                    assertThat(task.priority).isEqualTo(3)
                }
            }

        @Test
        fun `context startCycle - handler succeeds - the cycle's tasks name the starting task as their parent`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { ctx, _ -> ctx.startCycle(sendInvoice, october) }
                val starting = pendenz.enqueue(startRun, InvoiceRun("October"))

                pendenz.runPending()

                assertThat(invoiceTasks().map { it.parentId }).containsOnly(starting)
            }

        @Test
        fun `context startCycle - priority and resource given - the tasks carry them instead of the task's`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz =
                    pendenz { ctx, _ ->
                        ctx.startCycle(
                            sendInvoice,
                            october,
                            priority = 7,
                            resource = CycleResource.fixed("mail-server"),
                        )
                    }
                pendenz.enqueue(startRun, InvoiceRun("October"), priority = 3)

                pendenz.runPending()

                // Assert
                assertThat(invoiceTasks().map { it.priority }).containsOnly(7)
                assertThat(invoiceTasks().map { it.resource }).containsOnly("mail-server")
            }

        @Test
        fun `context startCycle - occurrence of a schedule - the cycle names the schedule as its creator`() =
            runBlocking<Unit> {
                // Arrange
                clock.now = Instant.parse("2026-10-19T09:20:00Z")
                val pendenz =
                    Pendenz.create {
                        store(store)
                        clock(clock)
                        register(startRun) { ctx, _ -> ctx.startCycle(sendInvoice, october, id = 911L) }
                        register(sendInvoice) { _, _ -> }
                        schedule("weekly-invoices", startRun, InvoiceRun("weekly"), Schedule.every(Duration.ofHours(1)))
                    }
                pendenz.runPending()
                clock.now = Instant.parse("2026-10-19T10:00:00Z")

                pendenz.runPending()

                // Assert
                assertThat(pendenz.findCycle(911L)!!.createdBy).isEqualTo("schedule:weekly-invoices")
                assertThat(invoiceTasks().map { it.createdBy }).containsOnly("schedule:weekly-invoices")
            }

        @Test
        fun `context startCycle - type whose approval rule asks for the creator - the tasks wait for approval`() =
            runBlocking<Unit> {
                // Arrange
                val payOut =
                    TaskType.json<Invoice>("pay-out", approval = Approval.rule { creator, _ -> creator == "billing" })
                val pendenz =
                    Pendenz.create {
                        store(store)
                        clock(clock)
                        register(startRun) { ctx, _ -> ctx.startCycle(payOut, october) }
                        register(payOut) { _, _ -> }
                    }
                pendenz.enqueue(startRun, InvoiceRun("October"), createdBy = "billing")

                pendenz.runPending()

                assertThat(store.tasks().filter { it.type == "pay-out" }.map { it.status })
                    .hasSize(3)
                    .containsOnly(TaskStatus.AWAITING_APPROVAL)
            }
    }

    @Nested
    inner class NotSucceeded {
        @Test
        fun `context startCycle - handler throws afterwards - neither the cycle nor its tasks exist`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz =
                    pendenz { ctx, _ ->
                        ctx.startCycle(sendInvoice, october, id = 920L)
                        error("ledger closed")
                    }
                val starting = pendenz.enqueue(startRun, InvoiceRun("October"))

                pendenz.runPending()

                // Assert
                assertThat(pendenz.find(starting)!!.status).isEqualTo(TaskStatus.FAILED)
                assertThat(pendenz.findCycle(920L)).isNull()
                assertThat(invoiceTasks()).isEmpty()
            }

        @Test
        fun `context startCycle - first attempt throws, the retry succeeds - one cycle`() =
            runBlocking<Unit> {
                // Arrange
                val retried = TaskType.json<InvoiceRun>("start-with-retry", retryPolicy = RetryPolicy { it.failedAt })
                val pendenz =
                    Pendenz.create {
                        store(store)
                        clock(clock)
                        register(retried) { ctx, _ ->
                            ctx.startCycle(sendInvoice, october)
                            check(ctx.attempt > 1) { "ledger busy" }
                        }
                        register(sendInvoice) { _, _ -> }
                    }
                pendenz.enqueue(retried, InvoiceRun("October"))

                pendenz.runPending()

                assertThat(invoiceTasks().map { it.lane }.distinct()).hasSize(1)
                assertThat(invoiceTasks()).hasSize(3)
            }

        @Test
        fun `context startCycle - result refused by the validator - starts nothing`() =
            runBlocking<Unit> {
                // Arrange
                val refused = TaskResult.json<InvoiceRun, Invoice> { _, _ -> Verdict.reject("wrong month") }
                val pendenz =
                    Pendenz.create {
                        store(store)
                        clock(clock)
                        register(startRun, refused) { ctx, _ ->
                            ctx.startCycle(sendInvoice, october)
                            Invoice(0)
                        }
                        register(sendInvoice) { _, _ -> }
                    }
                val starting = pendenz.enqueue(startRun, InvoiceRun("October"))

                pendenz.runPending()

                // Assert
                assertThat(pendenz.find(starting)!!.status).isEqualTo(TaskStatus.REJECTED)
                assertThat(invoiceTasks()).isEmpty()
            }
    }

    @Nested
    inner class Refusals {
        @Test
        fun `context startCycle - query on the in-memory store - the attempt fails with the refusal, nothing starts`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz =
                    pendenz { ctx, _ -> ctx.startCycle(sendInvoice, CycleTasks.query("SELECT number FROM invoice")) }
                val starting = pendenz.enqueue(startRun, InvoiceRun("October"))

                pendenz.runPending()

                // Assert
                val failed = pendenz.find(starting)!!
                assertThat(failed.status).isEqualTo(TaskStatus.FAILED)
                assertThat(failed.lastError).contains("cannot run a query")
            }

        @Test
        fun `context startCycle - type that is not registered - the attempt fails naming the type`() =
            runBlocking<Unit> {
                // Arrange
                val unknown = TaskType.json<Invoice>("archive-invoice")
                val pendenz = pendenz { ctx, _ -> ctx.startCycle(unknown, october) }
                val starting = pendenz.enqueue(startRun, InvoiceRun("October"))

                pendenz.runPending()

                assertThat(pendenz.find(starting)!!.lastError).contains("task type 'archive-invoice' is not registered")
            }

        @Test
        fun `context startCycle - query with a resource per row and a resource for the cycle - the attempt fails`() =
            runBlocking<Unit> {
                // Arrange
                val perRow =
                    CycleTasks.query<Invoice>(
                        "SELECT number FROM invoice",
                        resource = "'customer:' || q.number",
                    )
                val pendenz =
                    pendenz {
                        ctx,
                        _,
                        ->
                        ctx.startCycle(sendInvoice, perRow, resource = CycleResource.fixed("mail-server"))
                    }
                val starting = pendenz.enqueue(startRun, InvoiceRun("October"))

                pendenz.runPending()

                assertThat(pendenz.find(starting)!!.lastError).contains("a resource for the whole cycle cannot apply")
            }

        @Test
        fun `context startCycle - on the context a listener got at the claim - throws IllegalStateException`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { _, _ -> }
                pendenz.enqueue(startRun, InvoiceRun("October"))
                pendenz.runPending()
                val atTheClaim = contexts.single()

                assertThatThrownBy { atTheClaim.startCycle(sendInvoice, october) }
                    .isInstanceOf(IllegalStateException::class.java)
                    .hasMessageContaining("running handler")
            }
    }

    private fun invoiceTasks(): List<TaskRecord<Long>> = store.tasks().filter { it.type == "send-invoice" }

    private fun pendenz(handler: TaskHandler<Long, InvoiceRun>): Pendenz<Long> =
        Pendenz.create {
            store(store)
            clock(clock)
            listener(
                object : TaskListener<Long> {
                    override fun onEnqueued(
                        id: Long,
                        type: String,
                    ) {
                        events += "enqueued $type #$id"
                    }

                    override fun onCycleStarted(cycle: CycleEvent<Long>) {
                        events += "cycle ${cycle.id} started, ${cycle.tasks} tasks"
                    }

                    override fun onSucceeded(
                        task: TaskContext<Long>,
                        duration: Duration,
                    ) {
                        events += "succeeded ${task.type}"
                    }

                    override fun onClaimed(task: TaskContext<Long>) {
                        if (task.type == startRun.name) contexts += task
                    }
                },
            )
            register(startRun, handler)
            register(sendInvoice) { _, _ -> }
        }
}
