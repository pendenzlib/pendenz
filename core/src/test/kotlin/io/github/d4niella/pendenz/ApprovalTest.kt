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
        fun `approveCycle - by the cycle's creator - throws NotPermittedException`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                val cycle = pendenz.startCycle(refund, CycleTasks.of(listOf(Refund("c", 10))), createdBy = "ada")

                val attempt = ThrowingCallable { runBlocking { pendenz.approveCycle(cycle, actor = "ada") } }

                assertThatThrownBy(attempt).isInstanceOf(NotPermittedException::class.java)
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
