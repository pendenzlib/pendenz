package io.github.pendenzlib

import io.github.pendenzlib.schema.IdType
import io.github.pendenzlib.store.InMemoryTaskStore
import io.github.pendenzlib.store.StoreTransaction
import io.github.pendenzlib.store.TaskStore
import io.github.pendenzlib.testing.MutableClock
import io.github.pendenzlib.testing.guest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/** Retention rules per task type (ADR 0011), through the in-memory mode and a controlled clock. */
class RetentionTest {
    @Serializable
    data class Greeting(
        val name: String,
    )

    private val clock = MutableClock()
    private val store = InMemoryTaskStore(IdType.BIGINT)
    private val sevenDays = Duration.ofDays(7)

    @Nested
    inner class Delete {
        @Test
        fun `runMaintenance - succeeded task older than the retention period - is deleted`() =
            runBlocking<Unit> {
                // Arrange
                val type = TaskType.json<Greeting>("greet", retention = Retention.delete(after = sevenDays))
                val pendenz = pendenz { register(type) { _, _ -> } }
                val id = pendenz.enqueue(type, Greeting("Ada"))
                pendenz.runPending()
                clock.advance(sevenDays.plusMinutes(1))

                val removed = pendenz.runMaintenance()

                // Assert
                assertThat(removed).isEqualTo(1)
                assertThat(pendenz.find(id)).isNull()
            }

        @Test
        fun `runMaintenance - succeeded task younger than the retention period - stays`() =
            runBlocking<Unit> {
                // Arrange
                val type = TaskType.json<Greeting>("greet", retention = Retention.delete(after = sevenDays))
                val pendenz = pendenz { register(type) { _, _ -> } }
                val id = pendenz.enqueue(type, Greeting("Ada"))
                pendenz.runPending()
                clock.advance(sevenDays.minusMinutes(1))

                val removed = pendenz.runMaintenance()

                // Assert
                assertThat(removed).isZero()
                assertThat(pendenz.find(id)).isNotNull()
            }

        @Test
        fun `runMaintenance - failed task older than the retention period - stays for a human`() =
            runBlocking<Unit> {
                // Arrange
                val type =
                    TaskType.json<Greeting>("greet", retryPolicy = NoRetry, retention = Retention.delete(sevenDays))
                val pendenz = pendenz { register(type) { _, _ -> error("broken") } }
                val id = pendenz.enqueue(type, Greeting("Ada"))
                pendenz.runPending()
                clock.advance(Duration.ofDays(30))

                val removed = pendenz.runMaintenance()

                // Assert
                assertThat(removed).isZero()
                assertThat(pendenz.find(id)!!.status).isEqualTo(TaskStatus.FAILED)
            }

        @Test
        fun `runMaintenance - cancelled task older than the retention period - is deleted`() =
            runBlocking<Unit> {
                // Arrange
                val type = TaskType.json<Greeting>("greet", retention = Retention.delete(sevenDays))
                val pendenz = pendenz { register(type) { _, _ -> } }
                val id = pendenz.enqueue(type, Greeting("Ada"))
                pendenz.cancel(id)
                clock.advance(sevenDays.plusMinutes(1))

                val removed = pendenz.runMaintenance()

                // Assert
                assertThat(removed).isEqualTo(1)
                assertThat(pendenz.find(id)).isNull()
            }

        @Test
        fun `runMaintenance - quarantined and rejected tasks older than the retention period - stay for a human`() =
            runBlocking<Unit> {
                // Arrange
                val type = TaskType.json<Greeting>("greet", retention = Retention.delete(sevenDays))
                val rude =
                    TaskType.json<Greeting>("rude", retryPolicy = NoRetry, retention = Retention.delete(sevenDays))
                val refused = TaskResult.json<Greeting, Greeting> { _, _ -> Verdict.reject("rude") }
                val pendenz =
                    pendenz {
                        register(type) { _, _ -> }
                        register(rude, refused) { _, g -> g }
                    }
                val held = pendenz.enqueue(type, Greeting("Ada"))
                pendenz.quarantine(held, "ticket #123")
                val rejected = pendenz.enqueue(rude, Greeting("Bob"))
                pendenz.runPending()
                clock.advance(Duration.ofDays(30))

                val removed = pendenz.runMaintenance()

                // Assert
                assertThat(removed).isZero()
                assertThat(pendenz.find(held)!!.status).isEqualTo(TaskStatus.QUARANTINED)
                assertThat(pendenz.find(rejected)!!.status).isEqualTo(TaskStatus.REJECTED)
            }

        @Test
        fun `runMaintenance - type kept forever - nothing is removed`() =
            runBlocking<Unit> {
                // Arrange
                val type = TaskType.json<Greeting>("greet")
                val pendenz = pendenz { register(type) { _, _ -> } }
                pendenz.enqueue(type, Greeting("Ada"))
                pendenz.runPending()
                clock.advance(Duration.ofDays(365))

                val removed = pendenz.runMaintenance()

                assertThat(removed).isZero()
            }

        @Test
        fun `runMaintenance - more expired rows than the batch size - removes them all in several batches`() =
            runBlocking<Unit> {
                // Arrange
                val type = TaskType.json<Greeting>("greet", retention = Retention.delete(Duration.ZERO))
                val pendenz = pendenz { register(type) { _, _ -> } }
                repeat(5) { pendenz.enqueue(type, Greeting(guest(it))) }
                pendenz.runPending()
                clock.advance(Duration.ofSeconds(1))

                val removed = pendenz.runMaintenance(WorkerOptions(retentionBatchSize = 2))

                // Assert
                assertThat(removed).isEqualTo(5)
                assertThat(store.tasks()).isEmpty()
            }
    }

    @Nested
    inner class Archive {
        @Test
        fun `runMaintenance - succeeded task older than the retention period - is moved to the archive`() =
            runBlocking<Unit> {
                // Arrange
                val type = TaskType.json<Greeting>("greet", retention = Retention.archive(sevenDays, "greet_archive"))
                val pendenz = pendenz { register(type) { _, _ -> } }
                val id = pendenz.enqueue(type, Greeting("Ada"))
                pendenz.runPending()
                clock.advance(sevenDays.plusMinutes(1))

                val moved = pendenz.runMaintenance()

                // Assert
                assertThat(moved).isEqualTo(1)
                assertThat(pendenz.find(id)).isNull()
                assertThat(store.archived().map { it.id }).containsExactly(id)
                assertThat(store.archived().single().status).isEqualTo(TaskStatus.SUCCEEDED)
            }
    }

    @Nested
    inner class ByStatus {
        private val oneDay = Duration.ofDays(1)
        private val ninetyDays = Duration.ofDays(90)

        /** Successes go after a day; cancellations, with who cancelled, are archived after ninety. */
        private val invoice =
            TaskType.json<Greeting>(
                "send-invoice",
                retention =
                    Retention.byStatus(
                        succeeded = Retention.delete(oneDay),
                        cancelled = Retention.archive(ninetyDays, "invoice_archive"),
                    ),
            )

        @Test
        fun `runMaintenance - succeeded and cancelled task two days old - the success goes, the cancellation stays`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { register(invoice) { _, _ -> } }
                val sent = pendenz.enqueue(invoice, Greeting("Ada"))
                pendenz.runPending()
                val cancelled = pendenz.enqueue(invoice, Greeting("Bob"))
                pendenz.cancel(cancelled, actor = "ops@example.com")
                clock.advance(Duration.ofDays(2))

                val removed = pendenz.runMaintenance()

                // Assert
                assertThat(removed).isEqualTo(1)
                assertThat(pendenz.find(sent)).isNull()
                assertThat(pendenz.find(cancelled)!!.status).isEqualTo(TaskStatus.CANCELLED)
            }

        @Test
        fun `runMaintenance - cancelled task past its own period - is moved to the archive`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { register(invoice) { _, _ -> } }
                val cancelled = pendenz.enqueue(invoice, Greeting("Bob"))
                pendenz.cancel(cancelled, actor = "ops@example.com")
                clock.advance(ninetyDays.plusMinutes(1))

                val moved = pendenz.runMaintenance()

                // Assert
                assertThat(moved).isEqualTo(1)
                assertThat(store.archived().map { it.id }).containsExactly(cancelled)
            }

        @Test
        fun `runMaintenance - one status kept - its rows stay however old`() =
            runBlocking<Unit> {
                // Arrange
                val audited =
                    TaskType.json<Greeting>(
                        "send-invoice",
                        retention =
                            Retention.byStatus(
                                succeeded = Retention.delete(oneDay),
                                cancelled = Retention.keep(),
                            ),
                    )
                val pendenz = pendenz { register(audited) { _, _ -> } }
                val cancelled = pendenz.enqueue(audited, Greeting("Bob"))
                pendenz.cancel(cancelled, actor = "ops@example.com")
                clock.advance(Duration.ofDays(3650))

                pendenz.runMaintenance()

                assertThat(pendenz.find(cancelled)).isNotNull()
            }

        @Test
        fun `runMaintenance - store without the overloads with statuses - rows stay, other types are swept`() =
            runBlocking<Unit> {
                // Arrange: a store of the application's that implements only the methods without statuses
                val oldStore =
                    object : TaskStore<Long> by store {
                        override fun deleteFinished(
                            tx: StoreTransaction,
                            type: String,
                            statuses: Set<TaskStatus>,
                            finishedBefore: Instant,
                            limit: Int,
                        ): Int = super.deleteFinished(tx, type, statuses, finishedBefore, limit)

                        override fun archiveFinished(
                            tx: StoreTransaction,
                            type: String,
                            statuses: Set<TaskStatus>,
                            finishedBefore: Instant,
                            limit: Int,
                            archiveTable: String,
                        ): Int = super.archiveFinished(tx, type, statuses, finishedBefore, limit, archiveTable)
                    }
                val greet = TaskType.json<Greeting>("greet", retention = Retention.delete(oneDay))
                val pendenz =
                    Pendenz.create {
                        store(oldStore)
                        clock(clock)
                        register(invoice) { _, _ -> }
                        register(greet) { _, _ -> }
                    }
                val sent = pendenz.enqueue(invoice, Greeting("Ada"))
                val greeted = pendenz.enqueue(greet, Greeting("Bob"))
                pendenz.runPending()
                clock.advance(Duration.ofDays(2))

                pendenz.runMaintenance()

                // Assert
                assertThat(pendenz.find(sent)).isNotNull()
                assertThat(pendenz.find(greeted)).isNull()
            }

        @Test
        fun `byStatus - a part that is itself byStatus - throws IllegalArgumentException`() {
            val nested = Retention.byStatus(Retention.keep(), Retention.keep())

            assertThatThrownBy { Retention.byStatus(succeeded = nested, cancelled = Retention.keep()) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Nested
    inner class Failures {
        /** A store whose retention fails for the type "broken", as a missing archive table would. */
        private val failing =
            object : TaskStore<Long> by store {
                override fun deleteFinished(
                    tx: StoreTransaction,
                    type: String,
                    statuses: Set<TaskStatus>,
                    finishedBefore: Instant,
                    limit: Int,
                ): Int {
                    check(type != "broken") { "archive table missing" }
                    return store.deleteFinished(tx, type, statuses, finishedBefore, limit)
                }
            }

        @Test
        fun `runMaintenance - retention of one type fails - the other types are still swept`() =
            runBlocking<Unit> {
                // Arrange: the broken type comes first in the registry
                val broken = TaskType.json<Greeting>("broken", retention = Retention.delete(after = sevenDays))
                val greet = TaskType.json<Greeting>("greet", retention = Retention.delete(after = sevenDays))
                val pendenz =
                    Pendenz.create {
                        store(failing)
                        clock(clock)
                        register(broken) { _, _ -> }
                        register(greet) { _, _ -> }
                    }
                pendenz.enqueue(broken, Greeting("Ada"))
                val swept = pendenz.enqueue(greet, Greeting("Bob"))
                pendenz.runPending()
                clock.advance(sevenDays.plusMinutes(1))

                pendenz.runMaintenance()

                assertThat(pendenz.find(swept)).isNull()
            }
    }

    private fun pendenz(configure: Pendenz.Builder<Long>.() -> Unit): Pendenz<Long> =
        Pendenz.create {
            store(store)
            clock(clock)
            configure()
        }
}
