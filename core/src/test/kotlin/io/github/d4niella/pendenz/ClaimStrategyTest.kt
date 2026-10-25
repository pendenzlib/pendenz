package io.github.d4niella.pendenz

import io.github.d4niella.pendenz.schema.IdType
import io.github.d4niella.pendenz.store.InMemoryTaskStore
import io.github.d4niella.pendenz.testing.MutableClock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Fairness across lanes and priority as tiers (ADR 0015), through the in-memory mode. */
class ClaimStrategyTest {
    @Serializable
    data class Item(
        val name: String,
    )

    private val clock = MutableClock()
    private val store = InMemoryTaskStore(IdType.BIGINT)
    private val ran = CopyOnWriteArrayList<String>()
    private val work = TaskType.json<Item>("work")

    @Nested
    inner class Fair {
        @Test
        fun `runPending - a cycle of fifty tasks then one single task - the single task runs second`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                repeat(50) { pendenz.enqueue(work, Item("cycle-$it"), lane = "cycle-1") }
                pendenz.enqueue(work, Item("single"))

                pendenz.runPending(WorkerOptions(parallelism = 1, claimStrategy = FairClaim()))

                assertThat(ran.indexOf("single")).isEqualTo(1)
            }

        @Test
        fun `runPending - three lanes and one task per poll - visits the lanes in turn across polls`() =
            runBlocking<Unit> {
                // Arrange
                val strategy = FairClaim()
                val pendenz = pendenz()
                listOf("a", "b", "c").forEach { lane ->
                    repeat(2) { pendenz.enqueue(work, Item("$lane$it"), lane = lane) }
                }

                pendenz.runPending(WorkerOptions(parallelism = 1, claimStrategy = strategy))

                assertThat(ran).containsExactly("a0", "b0", "c0", "a1", "b1", "c1")
            }

        @Test
        fun `runPending - lanes with different priorities - the higher tier runs out before the lower starts`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                repeat(2) { pendenz.enqueue(work, Item("normal-$it"), lane = "cycle-1") }
                pendenz.enqueue(work, Item("single"))
                repeat(2) { pendenz.enqueue(work, Item("urgent-$it"), lane = "payroll", priority = 5) }

                pendenz.runPending(WorkerOptions(parallelism = 1, claimStrategy = FairClaim()))

                assertThat(ran).startsWith("urgent-0", "urgent-1")
                assertThat(ran).hasSize(5)
            }

        @Test
        fun `runPending - several free permits - fills them round-robin from the lanes in one poll`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                repeat(3) { pendenz.enqueue(work, Item("a$it"), lane = "a") }
                pendenz.enqueue(work, Item("b0"), lane = "b")

                pendenz.runPending(WorkerOptions(parallelism = 4, claimStrategy = FairClaim()))

                assertThat(ran).containsExactly("a0", "b0", "a1", "a2")
            }
    }

    @Nested
    inner class Batches {
        @Test
        fun `claim - type with a batch size of three and two lanes - takes three per lane per visit`() =
            runBlocking<Unit> {
                // Arrange
                val bulk = TaskType.json<Item>("bulk", claimBatchSize = 3)
                val pendenz = bulkPendenz(bulk)
                listOf("a", "b").forEach { lane -> repeat(4) { pendenz.enqueue(bulk, Item("$lane$it"), lane = lane) } }

                val claimed = claim(FairClaim(), mapOf("bulk" to 3), limit = 8)

                assertThat(claimed).containsExactly("a0", "a1", "a2", "b0", "b1", "b2", "a3", "b3")
            }

        @Test
        fun `claim - batch larger than the free permits - takes only what fits, the next poll moves on`() =
            runBlocking<Unit> {
                // Arrange
                val bulk = TaskType.json<Item>("bulk", claimBatchSize = 10)
                val pendenz = bulkPendenz(bulk)
                repeat(3) { pendenz.enqueue(bulk, Item("a$it"), lane = "a") }
                repeat(3) { pendenz.enqueue(bulk, Item("b$it"), lane = "b") }
                val strategy = FairClaim()

                // Act
                val first = claim(strategy, mapOf("bulk" to 10), limit = 2)
                val second = claim(strategy, mapOf("bulk" to 10), limit = 2)

                // Assert: two from one lane, then two from the other
                assertThat(first).containsExactly("a0", "a1")
                assertThat(second).containsExactly("b0", "b1")
            }

        private fun bulkPendenz(bulk: TaskType<Item>): Pendenz<Long> =
            Pendenz
                .create {
                    store(store)
                    clock(clock)
                    register(bulk) { _, item -> ran += item.name }
                }.also { ran.clear() }

        /** Claims through the strategy and returns the item names, decoded from the JSON payload column. */
        private suspend fun claim(
            strategy: ClaimStrategy,
            types: Map<String, Int>,
            limit: Int,
        ): List<String> =
            store
                .transaction { tx ->
                    strategy.claim(store, tx, ClaimRequest("w1", types, limit, clock.now, clock.now.plusSeconds(60)))
                }.map { Json.decodeFromString(Item.serializer(), it.payload["payload"] as String).name }
    }

    @Nested
    inner class Fifo {
        @Test
        fun `runPending - a cycle of fifty tasks then one single task - the single task runs last`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                repeat(50) { pendenz.enqueue(work, Item("cycle-$it"), lane = "cycle-1") }
                pendenz.enqueue(work, Item("single"))

                pendenz.runPending(WorkerOptions(parallelism = 1, claimStrategy = FifoClaim))

                assertThat(ran.indexOf("single")).isEqualTo(50)
            }

        @Test
        fun `runPending - a later task with higher priority - still runs first`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                pendenz.enqueue(work, Item("normal"))
                pendenz.enqueue(work, Item("urgent"), priority = 1)

                pendenz.runPending(WorkerOptions(parallelism = 1, claimStrategy = FifoClaim))

                assertThat(ran).containsExactly("urgent", "normal")
            }
    }

    private fun pendenz(): Pendenz<Long> =
        Pendenz
            .create {
                inMemory()
                clock(clock)
                register(work) { _, item -> ran += item.name }
            }.also { ran.clear() }
}
