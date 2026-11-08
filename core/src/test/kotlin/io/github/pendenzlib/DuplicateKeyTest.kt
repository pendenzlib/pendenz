package io.github.pendenzlib

import io.github.pendenzlib.schema.IdType
import io.github.pendenzlib.store.InMemoryTaskStore
import io.github.pendenzlib.testing.MutableClock
import io.github.pendenzlib.testing.claim
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/** What a repeat enqueue with a taken idempotency key does (ADR 0009, ADR 0044), through the in-memory mode. */
class DuplicateKeyTest {
    @Serializable
    data class Reindex(
        val product: String,
        val fields: List<String>,
    )

    private val clock = MutableClock()
    private val store = InMemoryTaskStore(IdType.BIGINT)
    private val events = CopyOnWriteArrayList<String>()

    private val reindex =
        TaskType.json<Reindex>(
            "reindex-product",
            resource = TaskResource.fixed("search-index"),
            preview = { "reindex ${it.product}: ${it.fields}" },
            onDuplicateKey = OnDuplicateKey.REPLACE,
        )

    @Nested
    inner class Keep {
        @Test
        fun `enqueue - key taken, type keeps - the first payload and run time stay`() =
            runBlocking<Unit> {
                // Arrange
                val kept = TaskType.json<Reindex>("reindex-product")
                val pendenz = pendenz { register(kept) { _, _ -> } }
                val id = pendenz.enqueue(kept, Reindex("P-42", listOf("price")), idempotencyKey = "P-42")

                pendenz.enqueue(kept, Reindex("P-42", listOf("title")), runAt = later(), idempotencyKey = "P-42")

                // Assert
                val task = pendenz.find(id)!!
                assertThat(task.payload["payload"].toString()).contains("price").doesNotContain("title")
                assertThat(task.status).isEqualTo(TaskStatus.PENDING)
            }
    }

    @Nested
    inner class Replace {
        @Test
        fun `enqueue - key taken by a waiting task - returns its id and replaces payload, run time and preview`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { register(reindex) { _, _ -> } }
                val id = pendenz.enqueue(reindex, Reindex("P-42", listOf("price")), idempotencyKey = "P-42")

                val repeat =
                    pendenz.enqueue(
                        reindex,
                        Reindex("P-42", listOf("price", "title")),
                        later(),
                        idempotencyKey = "P-42",
                    )

                // Assert
                val task = pendenz.find(id)!!
                assertThat(repeat).isEqualTo(id)
                assertThat(task.payload["payload"].toString()).contains("title")
                assertThat(task.runAt).isEqualTo(later())
                assertThat(task.status).isEqualTo(TaskStatus.SCHEDULED)
                assertThat(task.preview).isEqualTo("reindex P-42: [price, title]")
                assertThat(task.resource).isEqualTo("search-index")
            }

        @Test
        fun `enqueue - repeated before the run time - the run time moves to the last call's`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { register(reindex) { _, _ -> } }
                val id = pendenz.enqueue(reindex, Reindex("P-42", listOf("price")), later(), idempotencyKey = "P-42")
                clock.advance(Duration.ofSeconds(20))

                pendenz.enqueue(reindex, Reindex("P-42", listOf("price")), later(), idempotencyKey = "P-42")

                // Assert
                assertThat(pendenz.tasks()).hasSize(1)
                assertThat(pendenz.find(id)!!.runAt).isEqualTo(later())
            }

        @Test
        fun `enqueue - key taken by a running task - the running task stays as it was`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { register(reindex) { _, _ -> } }
                val id = pendenz.enqueue(reindex, Reindex("P-42", listOf("price")), idempotencyKey = "P-42")
                store.transaction { store.claim(it, "worker-1", setOf("reindex-product"), 1, clock.now) }

                pendenz.enqueue(reindex, Reindex("P-42", listOf("title")), idempotencyKey = "P-42")

                // Assert
                val task = pendenz.find(id)!!
                assertThat(task.status).isEqualTo(TaskStatus.RUNNING)
                assertThat(task.payload["payload"].toString()).contains("price").doesNotContain("title")
            }

        @Test
        fun `enqueue - replacing - fires no second onEnqueued`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { register(reindex) { _, _ -> } }
                pendenz.enqueue(reindex, Reindex("P-42", listOf("price")), idempotencyKey = "P-42")

                pendenz.enqueue(reindex, Reindex("P-42", listOf("title")), idempotencyKey = "P-42")

                assertThat(events).containsExactly("enqueued reindex-product")
            }

        @Test
        fun `enqueue - approved task replaced - waits for approval again, with the event`() =
            runBlocking<Unit> {
                // Arrange
                val approved =
                    TaskType.json<Reindex>(
                        "reindex-product",
                        approval = Approval.ALWAYS,
                        onDuplicateKey = OnDuplicateKey.REPLACE,
                    )
                val pendenz = pendenz { register(approved) { _, _ -> } }
                val id = pendenz.enqueue(approved, Reindex("P-42", listOf("price")), idempotencyKey = "P-42")
                pendenz.approve(id, "ops@example.com")
                events.clear()

                pendenz.enqueue(approved, Reindex("P-42", listOf("title")), idempotencyKey = "P-42")

                // Assert
                val task = pendenz.find(id)!!
                assertThat(task.status).isEqualTo(TaskStatus.AWAITING_APPROVAL)
                assertThat(task.approvedBy).isNull()
                assertThat(events).containsExactly("awaiting approval reindex-product")
            }

        @Test
        fun `follow-up - key taken by a waiting task - the handler's follow-up replaces it`() =
            runBlocking<Unit> {
                // Arrange
                val import = TaskType.json<Reindex>("import-product")
                val pendenz =
                    pendenz {
                        register(reindex) { _, _ -> }
                        register(import) { context, payload ->
                            context.enqueue(reindex, payload, later(), idempotencyKey = payload.product)
                        }
                    }
                val waiting =
                    pendenz.enqueue(
                        reindex,
                        Reindex("P-42", listOf("price")),
                        later(),
                        idempotencyKey = "P-42",
                    )
                pendenz.enqueue(import, Reindex("P-42", listOf("stock")))

                pendenz.runPending()

                assertThat(pendenz.find(waiting)!!.payload["payload"].toString()).contains("stock")
            }
    }

    private fun later() = clock.now.plusSeconds(30)

    private fun pendenz(configure: Pendenz.Builder<Long>.() -> Unit): Pendenz<Long> =
        Pendenz.create {
            store(store)
            clock(clock)
            listener(
                object : TaskListener<Long> {
                    override fun onEnqueued(
                        id: Long,
                        type: String,
                    ) {
                        events += "enqueued $type"
                    }

                    override fun onAwaitingApproval(
                        id: Long,
                        type: String,
                    ) {
                        events += "awaiting approval $type"
                    }
                },
            )
            configure()
        }
}
