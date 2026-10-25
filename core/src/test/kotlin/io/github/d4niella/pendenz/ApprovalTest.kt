package io.github.d4niella.pendenz

import io.github.d4niella.pendenz.schema.IdType
import io.github.d4niella.pendenz.store.InMemoryTaskStore
import io.github.d4niella.pendenz.testing.MutableClock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/** Approval before a task runs (ADR 0018), through the in-memory mode. */
class ApprovalTest {
    @Serializable
    data class Refund(
        val customer: String,
        val amount: Int,
    )

    private val clock = MutableClock()
    private val store = InMemoryTaskStore(IdType.BIGINT)
    private val refunded = CopyOnWriteArrayList<Refund>()
    private val events = CopyOnWriteArrayList<String>()
    private val refund = TaskType.json<Refund>("refund", approval = Approval.ALWAYS)
    private val bigRefund =
        TaskType.json<Refund>(
            "big-refund",
            approval = Approval.rule { _, payload -> payload == null || payload.amount > 1000 },
        )

    @Nested
    inner class Enqueue {
        @Test
        fun `enqueue - type requires approval - the task is AWAITING_APPROVAL and is not run`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()

                // Act
                val id = pendenz.enqueue(refund, Refund("acme", 50), createdBy = "ada")
                pendenz.runPending()

                // Assert
                assertThat(pendenz.find(id)!!.status).isEqualTo(TaskStatus.AWAITING_APPROVAL)
                assertThat(refunded).isEmpty()
                assertThat(events).contains("awaiting refund #$id")
            }

        @Test
        fun `enqueue - rule over the payload - only the large refund waits`() =
            runBlocking<Unit> {
                val pendenz = pendenz()

                val small = pendenz.enqueue(bigRefund, Refund("acme", 10))
                val large = pendenz.enqueue(bigRefund, Refund("acme", 5000))

                // Assert
                assertThat(pendenz.find(small)!!.status).isEqualTo(TaskStatus.PENDING)
                assertThat(pendenz.find(large)!!.status).isEqualTo(TaskStatus.AWAITING_APPROVAL)
            }

        @Test
        fun `enqueue - another instance of the registered name without the rule - refused, nothing stored`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                val lookalike = TaskType.json<Refund>("refund")

                val attempt = ThrowingCallable { runBlocking { pendenz.enqueue(lookalike, Refund("acme", 9000)) } }

                // Assert
                assertThatThrownBy(
                    attempt,
                ).isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("refund")
                assertThat(store.tasks()).isEmpty()
            }

        @Test
        fun `startCycle - another instance of the registered name - refused, no cycle and no task stored`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                val lookalike = TaskType.json<Refund>("refund")

                val attempt =
                    ThrowingCallable {
                        runBlocking { pendenz.startCycle(lookalike, CycleTasks.of(listOf(Refund("acme", 9000)))) }
                    }

                // Assert
                assertThatThrownBy(attempt).isInstanceOf(IllegalArgumentException::class.java)
                assertThat(store.tasks()).isEmpty()
            }
    }

    @Nested
    inner class Approve {
        @Test
        fun `approve - by someone else - records the approver and the task runs`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                val id = pendenz.enqueue(refund, Refund("acme", 50), createdBy = "ada")

                val approved = pendenz.approve(id, actor = "bob")

                // Assert
                assertThat(approved).isTrue()
                val record = pendenz.find(id)!!
                assertThat(record.status).isEqualTo(TaskStatus.PENDING)
                assertThat(record.approvedBy).isEqualTo("bob")
                assertThat(record.approvedAt).isEqualTo(clock.now)
                assertThat(pendenz.runPending()).isEqualTo(1)
                assertThat(events).contains("approved refund #$id by bob")
            }

        @Test
        fun `approve - by the creator - throws NotPermittedException and the task keeps waiting`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                val id = pendenz.enqueue(refund, Refund("acme", 50), createdBy = "ada")

                val attempt = ThrowingCallable { runBlocking { pendenz.approve(id, actor = "ada") } }

                // Assert
                assertThatThrownBy(
                    attempt,
                ).isInstanceOf(NotPermittedException::class.java).hasMessageContaining("creator")
                assertThat(pendenz.find(id)!!.status).isEqualTo(TaskStatus.AWAITING_APPROVAL)
            }

        @Test
        fun `approve - run time still ahead - the task becomes SCHEDULED, not PENDING`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                val id = pendenz.enqueue(refund, Refund("acme", 50), runAt = clock.now.plus(Duration.ofHours(1)))

                pendenz.approve(id, actor = "bob")

                assertThat(pendenz.find(id)!!.status).isEqualTo(TaskStatus.SCHEDULED)
            }

        @Test
        fun `restart - waiting task - returns false, so a restart cannot stand in for an approval`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                val id = pendenz.enqueue(refund, Refund("acme", 50), createdBy = "ada")

                val restarted = pendenz.restart(id, actor = "ada")

                // Assert
                assertThat(restarted).isFalse()
                assertThat(pendenz.find(id)!!.status).isEqualTo(TaskStatus.AWAITING_APPROVAL)
            }

        @Test
        fun `quarantine - waiting task - returns false, quarantine would forget that approval is due`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                val id = pendenz.enqueue(refund, Refund("acme", 50), createdBy = "ada")

                val held = pendenz.quarantine(id, reason = "later")

                // Assert
                assertThat(held).isFalse()
                assertThat(pendenz.find(id)!!.status).isEqualTo(TaskStatus.AWAITING_APPROVAL)
            }

        @Test
        fun `approve - task not waiting - returns false`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                val id = pendenz.enqueue(bigRefund, Refund("acme", 10))

                val approved = pendenz.approve(id, actor = "bob")

                assertThat(approved).isFalse()
            }

        @Test
        fun `decline - waiting task - CANCELLED with the reason and never runs`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                val id = pendenz.enqueue(refund, Refund("acme", 50), createdBy = "ada")

                val declined = pendenz.decline(id, actor = "bob", reason = "duplicate claim")

                // Assert
                assertThat(declined).isTrue()
                val record = pendenz.find(id)!!
                assertThat(record.status).isEqualTo(TaskStatus.CANCELLED)
                assertThat(record.lastError).isEqualTo("duplicate claim")
                assertThat(record.finishedAt).isEqualTo(clock.now)
                assertThat(events).contains("declined refund #$id by bob: duplicate claim")
            }
    }

    @Nested
    inner class Cycles {
        @Test
        fun `approveCycle - cycle of a type requiring approval - every waiting task runs after one call`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                val cycle = pendenz.startCycle(refund, CycleTasks.of(List(3) { Refund("c$it", 10) }), createdBy = "ada")
                assertThat(pendenz.cycleProgress(cycle)).containsExactlyEntriesOf(
                    mapOf(TaskStatus.AWAITING_APPROVAL to 3),
                )

                val approved = pendenz.approveCycle(cycle, actor = "bob")

                // Assert
                assertThat(approved).isEqualTo(3)
                assertThat(pendenz.runPending()).isEqualTo(3)
            }

        @Test
        fun `approveCycle - PAUSED cycle - approves nothing, the tasks keep waiting`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                val cycle = pendenz.startCycle(refund, CycleTasks.of(List(2) { Refund("c$it", 10) }), createdBy = "ada")
                pendenz.pauseCycle(cycle)

                val approved = pendenz.approveCycle(cycle, actor = "bob")

                // Assert
                assertThat(approved).isZero()
                assertThat(pendenz.cycleProgress(cycle)).containsExactlyEntriesOf(
                    mapOf(TaskStatus.AWAITING_APPROVAL to 2),
                )
            }

        @Test
        fun `approveCycle - a follow-up of another type waiting in the lane - is left for its own approval`() =
            runBlocking<Unit> {
                // Arrange: the small refund runs and enqueues a follow-up that always needs approval
                val pendenz =
                    Pendenz.create {
                        store(store)
                        clock(clock)
                        register(refund) { _, r -> refunded += r }
                        register(bigRefund) { ctx, r -> ctx.enqueue(refund, r) }
                    }
                val payloads = listOf(Refund("c1", 10), Refund("c2", 5000))
                val cycle = pendenz.startCycle(bigRefund, CycleTasks.of(payloads), createdBy = "ada")
                pendenz.runPending()

                val approved = pendenz.approveCycle(cycle, actor = "bob")

                // Assert: the large refund is approved, the follow-up still waits
                assertThat(approved).isEqualTo(1)
                assertThat(pendenz.cycleProgress(cycle)).containsExactlyInAnyOrderEntriesOf(
                    mapOf(TaskStatus.SUCCEEDED to 1, TaskStatus.PENDING to 1, TaskStatus.AWAITING_APPROVAL to 1),
                )
            }

        @Test
        fun `approveCycle - by the cycle's creator - throws NotPermittedException`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                val cycle = pendenz.startCycle(refund, CycleTasks.of(listOf(Refund("c", 10))), createdBy = "ada")

                val attempt = ThrowingCallable { runBlocking { pendenz.approveCycle(cycle, actor = "ada") } }

                assertThatThrownBy(attempt).isInstanceOf(NotPermittedException::class.java)
            }

        @Test
        fun `enqueue - into another creator's cycle lane - refused, so nobody approves an own task with the cycle`() =
            runBlocking<Unit> {
                // Arrange: ada started the cycle; bob would approve it and wants his own refund to ride along
                val pendenz = pendenz()
                val cycle = pendenz.startCycle(refund, CycleTasks.of(listOf(Refund("c", 10))), createdBy = "ada")

                val attempt =
                    ThrowingCallable {
                        runBlocking {
                            pendenz.enqueue(
                                refund,
                                Refund("bob", 50_000),
                                createdBy = "bob",
                                lane = CycleLane.of(cycle),
                            )
                        }
                    }

                // Assert
                assertThatThrownBy(attempt).isInstanceOf(IllegalArgumentException::class.java)
                assertThat(store.tasks().map { it.createdBy }).containsExactly("ada")
            }
    }

    private fun pendenz(): Pendenz<Long> =
        Pendenz.create {
            store(store)
            clock(clock)
            listener(
                object : TaskListener<Long> {
                    override fun onAwaitingApproval(
                        id: Long,
                        type: String,
                    ) {
                        events += "awaiting $type #$id"
                    }

                    override fun onApproved(
                        id: Long,
                        type: String,
                        actor: String,
                    ) {
                        events += "approved $type #$id by $actor"
                    }

                    override fun onDeclined(
                        id: Long,
                        type: String,
                        actor: String,
                        reason: String,
                    ) {
                        events += "declined $type #$id by $actor: $reason"
                    }
                },
            )
            register(refund) { _, r -> refunded += r }
            register(bigRefund) { _, r -> refunded += r }
        }
}
