package io.github.d4niella.pendenz

import io.github.d4niella.pendenz.schema.IdType
import io.github.d4niella.pendenz.store.InMemoryTaskStore
import io.github.d4niella.pendenz.store.StoreTransaction
import io.github.d4niella.pendenz.store.TaskStore
import io.github.d4niella.pendenz.testing.MutableClock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
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
                repeat(5) { pendenz.enqueue(type, Greeting("n$it")) }
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
    inner class Failures {
        /** A store whose retention fails for the type "broken", as a missing archive table would. */
        private val failing =
            object : TaskStore<Long> by store {
                override fun deleteFinished(
                    tx: StoreTransaction,
                    type: String,
                    finishedBefore: Instant,
                    limit: Int,
                ): Int {
                    check(type != "broken") { "archive table missing" }
                    return store.deleteFinished(tx, type, finishedBefore, limit)
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
