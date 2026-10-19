package io.github.d4niella.pendenz

import io.github.d4niella.pendenz.schema.IdType
import io.github.d4niella.pendenz.store.ClaimGroup
import io.github.d4niella.pendenz.store.InMemoryTaskStore
import io.github.d4niella.pendenz.testing.MutableClock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** Concurrency limits per resource (ADR 0017), through the in-memory store's claim. */
class ResourceLimitTest {
    @Serializable
    data class Mail(
        val customer: String,
    )

    private val clock = MutableClock()
    private val store = InMemoryTaskStore(IdType.BIGINT)
    private val mail = TaskType.json<Mail>("mail", resource = TaskResource.fixed("mail-server", maxRunning = 2))
    private val perCustomer =
        TaskType.json<Mail>(
            "notify",
            resource = TaskResource.fromPayload({ "customer:${it.customer}" }, pattern = "customer:%", maxRunning = 1),
        )

    @Nested
    inner class Resources {
        @Test
        fun `enqueue - type with a fixed resource - the task carries it and the handler sees it`() =
            runBlocking<Unit> {
                // Arrange
                val seen = mutableListOf<String?>()
                val pendenz = pendenz { register(mail) { ctx, _ -> seen += ctx.resource } }

                // Act
                val id = pendenz.enqueue(mail, Mail("acme"))
                pendenz.runPending()

                // Assert
                assertThat(pendenz.find(id)!!.resource).isEqualTo("mail-server")
                assertThat(seen).containsExactly("mail-server")
            }

        @Test
        fun `enqueue - resource derived from the payload, overridden by the caller - the caller wins`() =
            runBlocking<Unit> {
                val pendenz = pendenz { register(perCustomer) { _, _ -> } }

                val derived = pendenz.enqueue(perCustomer, Mail("acme"))
                val overridden = pendenz.enqueue(perCustomer, Mail("acme"), resource = "customer:globex")

                // Assert
                assertThat(pendenz.find(derived)!!.resource).isEqualTo("customer:acme")
                assertThat(pendenz.find(overridden)!!.resource).isEqualTo("customer:globex")
            }
    }

    @Nested
    inner class Limits {
        @Test
        fun `runPending - type declares a default limit - it is seeded on the first run`() =
            runBlocking<Unit> {
                val pendenz = pendenz { register(mail) { _, _ -> } }

                pendenz.runPending()

                assertThat(pendenz.resourceLimits()).containsExactly(ResourceLimit("mail-server", 2))
            }

        @Test
        fun `limitResource - an operator's value set before the seed - survives the seed`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { register(mail) { _, _ -> } }
                pendenz.limitResource("mail-server", 5)

                pendenz.runPending()

                assertThat(pendenz.resourceLimits()).containsExactly(ResourceLimit("mail-server", 5))
            }

        @Test
        fun `claim - four tasks of a resource limited to two - two are claimed, the rest wait`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { register(mail) { _, _ -> } }
                repeat(4) { pendenz.enqueue(mail, Mail("n$it")) }
                pendenz.limitResource("mail-server", 2)

                val claimed = store.transaction { store.claim(it, "w1", setOf("mail"), 10, clock.now) }

                // Assert
                assertThat(claimed).hasSize(2)
                assertThat(store.tasks().count { it.status == TaskStatus.PENDING }).isEqualTo(2)
            }

        @Test
        fun `claimFromGroup - a lane whose resource is full - returns nothing and the lane waits`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { register(mail) { _, _ -> } }
                repeat(3) { pendenz.enqueue(mail, Mail("n$it"), lane = "batch") }
                pendenz.limitResource("mail-server", 1)
                store.transaction { store.claimFromGroup(it, "w1", ClaimGroup(0, "mail", "batch"), 1, clock.now) }

                val next =
                    store.transaction {
                        store.claimFromGroup(
                            it,
                            "w1",
                            ClaimGroup(0, "mail", "batch"),
                            1,
                            clock.now,
                        )
                    }

                assertThat(next).isEmpty()
            }

        @Test
        fun `claim - LIKE pattern per customer - one task per customer at a time, the longest match wins`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { register(perCustomer) { _, _ -> } }
                repeat(2) { pendenz.enqueue(perCustomer, Mail("acme")) }
                repeat(2) { pendenz.enqueue(perCustomer, Mail("globex")) }
                pendenz.limitResource("customer:%", 1)
                pendenz.limitResource("customer:globex", 2)

                val claimed = store.transaction { store.claim(it, "w1", setOf("notify"), 10, clock.now) }

                // Assert
                assertThat(
                    claimed.map { it.resource },
                ).containsExactlyInAnyOrder("customer:acme", "customer:globex", "customer:globex")
            }

        @Test
        fun `claim - resource without a limit row - is unlimited`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { register(mail) { _, _ -> } }
                repeat(3) { pendenz.enqueue(mail, Mail("n$it")) }
                pendenz.removeResourceLimit("mail-server")

                val claimed = store.transaction { store.claim(it, "w1", setOf("mail"), 10, clock.now) }

                assertThat(claimed).hasSize(3)
            }

        @Test
        fun `claim - a limited task finished - frees its slot for the next`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { register(mail) { _, _ -> } }
                repeat(2) { pendenz.enqueue(mail, Mail("n$it")) }
                pendenz.limitResource("mail-server", 1)
                val first = store.transaction { store.claim(it, "w1", setOf("mail"), 10, clock.now) }.single()
                store.transaction {
                    store.finish(
                        it,
                        first.id,
                        first.version,
                        io.github.d4niella.pendenz.store.Outcome
                            .Succeeded(),
                        clock.now,
                    )
                }

                val second = store.transaction { store.claim(it, "w1", setOf("mail"), 10, clock.now) }

                assertThat(second).hasSize(1)
            }
    }

    private fun pendenz(configure: Pendenz.Builder<Long>.() -> Unit): Pendenz<Long> =
        Pendenz.create {
            store(store)
            clock(clock)
            configure()
        }
}
