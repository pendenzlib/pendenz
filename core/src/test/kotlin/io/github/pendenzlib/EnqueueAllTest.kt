package io.github.pendenzlib

import io.github.pendenzlib.schema.IdType
import io.github.pendenzlib.store.InMemoryTaskStore
import io.github.pendenzlib.testing.MutableClock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/** Many tasks in one call (ADR 0046), through the in-memory mode. */
class EnqueueAllTest {
    @Serializable
    data class Order(
        val number: String,
    )

    @Serializable
    data class Refund(
        val order: String,
        val amount: Int,
    )

    private val clock = MutableClock()
    private val store = InMemoryTaskStore(IdType.BIGINT)
    private val events = CopyOnWriteArrayList<String>()
    private val importOrder = TaskType.json<Order>("import-order")
    private val refund =
        TaskType.json<Refund>(
            "refund",
            approval =
                Approval.rule {
                    _,
                    refund,
                    ->
                    refund!!.amount > 1000
                },
        )
    private val unregistered = TaskType.json<Order>("archive-order")

    @Nested
    inner class EnqueueAll {
        @Test
        fun `enqueueAll - two types with their own options - stores each request's values, ids in list order`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                val later = clock.now.plus(Duration.ofHours(1))
                val requests =
                    listOf(
                        EnqueueRequest(importOrder, Order("A-1001"), idempotencyKey = "A-1001"),
                        EnqueueRequest(refund, Refund("A-1001", 40), runAt = later, lane = "tenant-7", priority = 3),
                        EnqueueRequest(importOrder, Order("A-1002"), resource = "erp", correlationId = "import-12"),
                    )

                val ids = pendenz.enqueueAll(requests, createdBy = "import-job")

                // Assert
                val tasks = ids.map { pendenz.find(it)!! }
                assertThat(tasks.map { it.type }).containsExactly("import-order", "refund", "import-order")
                assertThat(tasks.map { it.createdBy }).containsOnly("import-job")
                assertThat(tasks[0].idempotencyKey).isEqualTo("A-1001")
                assertThat(tasks[1].status).isEqualTo(TaskStatus.SCHEDULED)
                assertThat(tasks[1].runAt).isEqualTo(later)
                assertThat(tasks[1].lane).isEqualTo("tenant-7")
                assertThat(tasks[1].priority).isEqualTo(3)
                assertThat(tasks[2].resource).isEqualTo("erp")
                assertThat(tasks[2].correlationId).isEqualTo("import-12")
            }

        @Test
        fun `enqueueAll - key taken by an earlier enqueue - returns the existing id and keeps its payload`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                val existing = pendenz.enqueue(importOrder, Order("A-1001"), idempotencyKey = "A-1001")

                val ids =
                    pendenz.enqueueAll(
                        listOf(
                            EnqueueRequest(importOrder, Order("A-1001 corrected"), idempotencyKey = "A-1001"),
                            EnqueueRequest(importOrder, Order("A-1002"), idempotencyKey = "A-1002"),
                        ),
                    )

                // Assert
                assertThat(ids[0]).isEqualTo(existing)
                assertThat(ids[1]).isNotEqualTo(existing)
                assertThat(pendenz.find(existing)!!.payload["payload"].toString()).doesNotContain("corrected")
            }

        @Test
        fun `enqueueAll - creator and correlation omitted - both taken from the caller defaults`() =
            runBlocking<Unit> {
                // Arrange
                val defaults =
                    object : CallerDefaults {
                        override fun actor() = "ada@example.com"

                        override fun correlationId() = "trace-4bf92f35"
                    }
                val pendenz = pendenz(defaults = defaults)

                val ids = pendenz.enqueueAll(listOf(EnqueueRequest(importOrder, Order("A-1001"))))

                // Assert
                val task = pendenz.find(ids.single())!!
                assertThat(task.createdBy).isEqualTo("ada@example.com")
                assertThat(task.correlationId).isEqualTo("trace-4bf92f35")
            }

        @Test
        fun `enqueueAll - empty list - returns an empty list and stores nothing`() =
            runBlocking<Unit> {
                val pendenz = pendenz()

                val ids = pendenz.enqueueAll(emptyList())

                // Assert
                assertThat(ids).isEmpty()
                assertThat(store.tasks()).isEmpty()
            }
    }

    @Nested
    inner class Checks {
        @Test
        fun `enqueueAll - creator may not enqueue one of the types - throws NotPermittedException, stores nothing`() =
            runBlocking<Unit> {
                // Arrange
                val permissions = Permissions.table { actor("import-job").may(Action.ENQUEUE, "import-order") }
                val pendenz = pendenz(permissions = permissions)
                val requests =
                    listOf(
                        EnqueueRequest(importOrder, Order("A-1001")),
                        EnqueueRequest(refund, Refund("A-1001", 40)),
                    )

                val attempt =
                    ThrowingCallable { runBlocking { pendenz.enqueueAll(requests, createdBy = "import-job") } }

                // Assert
                assertThatThrownBy(
                    attempt,
                ).isInstanceOf(NotPermittedException::class.java).hasMessageContaining("refund")
                assertThat(store.tasks()).isEmpty()
            }

        @Test
        fun `enqueueAll - last request of an unregistered type - throws IllegalArgumentException, stores nothing`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                val requests =
                    listOf(
                        EnqueueRequest(importOrder, Order("A-1001")),
                        EnqueueRequest(unregistered, Order("A-1001")),
                    )

                val attempt = ThrowingCallable { runBlocking { pendenz.enqueueAll(requests) } }

                // Assert
                assertThatThrownBy(attempt)
                    .isInstanceOf(IllegalArgumentException::class.java)
                    .hasMessageContaining("archive-order")
                assertThat(store.tasks()).isEmpty()
            }

        @Test
        fun `enqueueAll - request in a cycle's lane - throws IllegalArgumentException, stores nothing`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                val requests =
                    listOf(
                        EnqueueRequest(importOrder, Order("A-1001")),
                        EnqueueRequest(importOrder, Order("A-1002"), lane = "cycle:7"),
                    )

                val attempt = ThrowingCallable { runBlocking { pendenz.enqueueAll(requests) } }

                // Assert
                assertThatThrownBy(
                    attempt,
                ).isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("cycle")
                assertThat(store.tasks()).isEmpty()
            }
    }

    @Nested
    inner class Listener {
        @Test
        fun `enqueueAll - one key taken, one refund over the limit - hears of new rows and the approval only`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                val existing = pendenz.enqueue(importOrder, Order("A-1001"), idempotencyKey = "A-1001")
                events.clear()

                val ids =
                    pendenz.enqueueAll(
                        listOf(
                            EnqueueRequest(importOrder, Order("A-1001"), idempotencyKey = "A-1001"),
                            EnqueueRequest(refund, Refund("A-1001", 4000)),
                        ),
                    )

                // Assert
                assertThat(ids[0]).isEqualTo(existing)
                assertThat(events).containsExactly("enqueued ${ids[1]} refund", "awaiting approval ${ids[1]} refund")
            }
    }

    private fun pendenz(
        permissions: Permissions = Permissions.ALLOW_ALL,
        defaults: CallerDefaults = CallerDefaults.NONE,
    ): Pendenz<Long> =
        Pendenz.create {
            store(store)
            clock(clock)
            permissions(permissions)
            callerDefaults(defaults)
            listener(
                object : TaskListener<Long> {
                    override fun onEnqueued(
                        id: Long,
                        type: String,
                    ) {
                        events += "enqueued $id $type"
                    }

                    override fun onAwaitingApproval(
                        id: Long,
                        type: String,
                    ) {
                        events += "awaiting approval $id $type"
                    }
                },
            )
            register(importOrder) { _, _ -> }
            register(refund) { _, _ -> }
        }
}
