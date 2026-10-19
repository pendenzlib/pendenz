package io.github.d4niella.pendenz

import io.github.d4niella.pendenz.schema.IdType
import io.github.d4niella.pendenz.store.CycleStore
import io.github.d4niella.pendenz.store.InMemoryTaskStore
import io.github.d4niella.pendenz.testing.MutableClock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Cycles (ADR 0016) through the in-memory mode: start from payloads, pause, resume, cancel, finish. */
class CycleTest {
    @Serializable
    data class Invoice(
        val number: Int,
    )

    private val clock = MutableClock()
    private val store = InMemoryTaskStore(IdType.BIGINT)
    private val sent = CopyOnWriteArrayList<Int>()
    private val sendInvoice = TaskType.json<Invoice>("send-invoice")
    private val invoices = (1..5).map { Invoice(it) }

    @Nested
    inner class Start {
        @Test
        fun `startCycle - five payloads - one task each in the cycle's lane with its priority and creator`() =
            runBlocking<Unit> {
                val pendenz = pendenz()

                val id =
                    pendenz.startCycle(
                        sendInvoice,
                        CycleTasks.of(invoices),
                        "October",
                        "billing-run",
                        priority = 2,
                    )

                // Assert
                val cycle = pendenz.findCycle(id)!!
                assertThat(cycle.status).isEqualTo(CycleStatus.ACTIVE)
                assertThat(cycle.description).isEqualTo("October")
                assertThat(cycle.createdBy).isEqualTo("billing-run")
                assertThat(pendenz.cycleProgress(id)).containsExactlyEntriesOf(mapOf(TaskStatus.PENDING to 5))
                val task = store.tasks().first()
                assertThat(task.lane).isEqualTo("cycle:$id")
                assertThat(task.priority).isEqualTo(2)
                assertThat(task.createdBy).isEqualTo("billing-run")
            }

        @Test
        fun `startCycle - query source without a database - throws UnsupportedOperationException`() =
            runBlocking<Unit> {
                val pendenz = pendenz()

                val attempt =
                    ThrowingCallable {
                        runBlocking { pendenz.startCycle(sendInvoice, CycleTasks.query("SELECT 1")) }
                    }

                assertThatThrownBy(attempt).isInstanceOf(UnsupportedOperationException::class.java)
            }

        @Test
        fun `startCycle - cycles not enabled on a JDBC store - throws IllegalStateException`() {
            // Arrange: a builder with a store that has no cycle table, built without a database
            val noCycles =
                object : io.github.d4niella.pendenz.store.TaskStore<Long> by store {
                    override val cycles: CycleStore<Long>? = null
                }
            val pendenz =
                Pendenz.create {
                    store(noCycles)
                    register(sendInvoice) { _, _ -> }
                }

            val attempt = ThrowingCallable { runBlocking { pendenz.startCycle(sendInvoice, CycleTasks.of(invoices)) } }

            assertThatThrownBy(attempt).isInstanceOf(IllegalStateException::class.java).hasMessageContaining("cycles")
        }
    }

    @Nested
    inner class Run {
        @Test
        fun `runPending - cycle started - runs every task and maintenance marks the cycle FINISHED`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                val id = pendenz.startCycle(sendInvoice, CycleTasks.of(invoices))

                // Act
                pendenz.runPending()
                pendenz.runMaintenance()

                // Assert
                assertThat(sent).containsExactlyInAnyOrder(1, 2, 3, 4, 5)
                assertThat(pendenz.cycleProgress(id)).containsExactlyEntriesOf(mapOf(TaskStatus.SUCCEEDED to 5))
                val cycle = pendenz.findCycle(id)!!
                assertThat(cycle.status).isEqualTo(CycleStatus.FINISHED)
                assertThat(cycle.finishedAt).isEqualTo(clock.now)
            }

        @Test
        fun `runMaintenance - FINISHED cycle older than the type's retention - the cycle row is deleted too`() =
            runBlocking<Unit> {
                // Arrange
                val fleeting =
                    TaskType.json<Invoice>(
                        "send-invoice",
                        retention = Retention.delete(java.time.Duration.ofDays(7)),
                    )
                val pendenz =
                    Pendenz.create {
                        store(store)
                        clock(clock)
                        register(fleeting) { _, _ -> }
                    }
                val id = pendenz.startCycle(fleeting, CycleTasks.of(invoices))
                pendenz.runPending()
                pendenz.runMaintenance()
                assertThat(pendenz.findCycle(id)!!.status).isEqualTo(CycleStatus.FINISHED)
                clock.advance(java.time.Duration.ofDays(8))

                pendenz.runMaintenance()

                // Assert
                assertThat(store.tasks()).isEmpty()
                assertThat(pendenz.findCycle(id)).isNull()
            }

        @Test
        fun `runMaintenance - type kept forever - the FINISHED cycle row stays`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                val id = pendenz.startCycle(sendInvoice, CycleTasks.of(invoices))
                pendenz.runPending()
                pendenz.runMaintenance()
                clock.advance(java.time.Duration.ofDays(400))

                pendenz.runMaintenance()

                assertThat(pendenz.findCycle(id)).isNotNull()
            }

        @Test
        fun `runMaintenance - a follow-up of another type still pending - the cycle stays ACTIVE and counts it`() =
            runBlocking<Unit> {
                // Arrange
                val remind = TaskType.json<Invoice>("remind")
                val pendenz =
                    Pendenz.create {
                        store(store)
                        clock(clock)
                        register(sendInvoice) { ctx, invoice ->
                            ctx.enqueue(remind, invoice, runAt = clock.now.plus(java.time.Duration.ofDays(14)))
                        }
                        register(remind) { _, _ -> }
                    }
                val id = pendenz.startCycle(sendInvoice, CycleTasks.of(invoices))
                pendenz.runPending()

                pendenz.runMaintenance()

                // Assert
                assertThat(pendenz.cycleProgress(id))
                    .containsExactlyInAnyOrderEntriesOf(mapOf(TaskStatus.SUCCEEDED to 5, TaskStatus.SCHEDULED to 5))
                assertThat(pendenz.findCycle(id)!!.status).isEqualTo(CycleStatus.ACTIVE)
            }

        @Test
        fun `runMaintenance - a task of the cycle FAILED - the cycle stays ACTIVE for a human`() =
            runBlocking<Unit> {
                // Arrange
                val fragile = TaskType.json<Invoice>("send-invoice", retryPolicy = NoRetry)
                val pendenz =
                    Pendenz.create {
                        store(store)
                        clock(clock)
                        register(fragile) { _, invoice -> check(invoice.number != 3) { "printer jam" } }
                    }
                val id = pendenz.startCycle(fragile, CycleTasks.of(invoices))
                pendenz.runPending()

                pendenz.runMaintenance()

                // Assert
                assertThat(pendenz.cycleProgress(id)).containsEntry(TaskStatus.FAILED, 1)
                assertThat(pendenz.findCycle(id)!!.status).isEqualTo(CycleStatus.ACTIVE)
            }
    }

    @Nested
    inner class PauseAndResume {
        @Test
        fun `pauseCycle - ACTIVE cycle - waiting tasks are QUARANTINED with the reason and do not run`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                val id = pendenz.startCycle(sendInvoice, CycleTasks.of(invoices))

                val paused = pendenz.pauseCycle(id)

                // Assert
                assertThat(paused).isTrue()
                assertThat(pendenz.findCycle(id)!!.status).isEqualTo(CycleStatus.PAUSED)
                assertThat(pendenz.cycleProgress(id)).containsExactlyEntriesOf(mapOf(TaskStatus.QUARANTINED to 5))
                assertThat(store.tasks().map { it.lastError }).containsOnly(CycleStore.PAUSED_REASON)
                assertThat(pendenz.runPending()).isZero()
            }

        @Test
        fun `resumeCycle - PAUSED cycle - its tasks wait again and run`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                val id = pendenz.startCycle(sendInvoice, CycleTasks.of(invoices))
                pendenz.pauseCycle(id)

                val resumed = pendenz.resumeCycle(id)

                // Assert
                assertThat(resumed).isTrue()
                assertThat(pendenz.findCycle(id)!!.status).isEqualTo(CycleStatus.ACTIVE)
                assertThat(pendenz.runPending()).isEqualTo(5)
            }

        @Test
        fun `pauseCycle - cycle not ACTIVE - returns false and changes nothing`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                val id = pendenz.startCycle(sendInvoice, CycleTasks.of(invoices))
                pendenz.pauseCycle(id)

                val pausedAgain = pendenz.pauseCycle(id)

                assertThat(pausedAgain).isFalse()
            }
    }

    @Nested
    inner class Cancel {
        @Test
        fun `cancelCycle - ACTIVE cycle with waiting tasks - cycle and tasks are CANCELLED with a finish time`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                val id = pendenz.startCycle(sendInvoice, CycleTasks.of(invoices))

                val cancelled = pendenz.cancelCycle(id)

                // Assert
                assertThat(cancelled).isTrue()
                val cycle = pendenz.findCycle(id)!!
                assertThat(cycle.status).isEqualTo(CycleStatus.CANCELLED)
                assertThat(cycle.finishedAt).isEqualTo(clock.now)
                assertThat(pendenz.cycleProgress(id)).containsExactlyEntriesOf(mapOf(TaskStatus.CANCELLED to 5))
                assertThat(store.tasks().map { it.finishedAt }).containsOnly(clock.now)
            }

        @Test
        fun `cancelCycle - PAUSED cycle - is cancelled too`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                val id = pendenz.startCycle(sendInvoice, CycleTasks.of(invoices))
                pendenz.pauseCycle(id)

                val cancelled = pendenz.cancelCycle(id)

                assertThat(cancelled).isTrue()
                assertThat(pendenz.findCycle(id)!!.status).isEqualTo(CycleStatus.CANCELLED)
            }

        @Test
        fun `cancelCycle - FINISHED cycle - returns false`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                val id = pendenz.startCycle(sendInvoice, CycleTasks.of(invoices))
                pendenz.runPending()
                pendenz.runMaintenance()

                val cancelled = pendenz.cancelCycle(id)

                assertThat(cancelled).isFalse()
            }
    }

    private fun pendenz(): Pendenz<Long> =
        Pendenz.create {
            store(store)
            clock(clock)
            register(sendInvoice) { _, invoice -> sent += invoice.number }
        }
}
