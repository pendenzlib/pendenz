package io.github.d4niella.pendenz

import io.github.d4niella.pendenz.schema.IdType
import io.github.d4niella.pendenz.store.InMemoryTaskStore
import io.github.d4niella.pendenz.testing.MutableClock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/** Follow-up tasks in the outcome's transaction (ADR 0019), through the in-memory mode. */
class ChainingTest {
    @Serializable
    data class Invoice(
        val number: Int,
    )

    @Serializable
    data class Reminder(
        val invoice: Int,
    )

    private val clock = MutableClock()
    private val store = InMemoryTaskStore(IdType.BIGINT)
    private val reminded = CopyOnWriteArrayList<Reminder>()
    private val events = CopyOnWriteArrayList<String>()
    private val sendInvoice = TaskType.json<Invoice>("send-invoice", retryPolicy = NoRetry)
    private val remind = TaskType.json<Reminder>("remind")

    @Nested
    inner class FollowUps {
        @Test
        fun `handler enqueues a follow-up - task succeeds - the follow-up exists with parent and inherited fields`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz =
                    pendenz { ctx, invoice ->
                        ctx.enqueue(remind, Reminder(invoice.number), runAt = clock.now.plus(Duration.ofDays(14)))
                    }
                val parent =
                    pendenz.enqueue(
                        sendInvoice,
                        Invoice(42),
                        createdBy = "billing",
                        lane = "october",
                        priority = 3,
                        correlationId = "inv-42",
                    )

                pendenz.runPending()

                // Assert
                val child = store.tasks().single { it.type == "remind" }
                assertThat(child.parentId).isEqualTo(parent)
                assertThat(child.correlationId).isEqualTo("inv-42")
                assertThat(child.createdBy).isEqualTo("billing")
                assertThat(child.lane).isEqualTo("october")
                assertThat(child.priority).isEqualTo(3)
                assertThat(child.status).isEqualTo(TaskStatus.SCHEDULED)
                assertThat(events).contains("enqueued remind #${child.id}")
            }

        @Test
        fun `handler enqueues a follow-up then throws - nothing is enqueued`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz =
                    pendenz { ctx, invoice ->
                        ctx.enqueue(remind, Reminder(invoice.number))
                        error("printer jam")
                    }
                val parent = pendenz.enqueue(sendInvoice, Invoice(42))

                pendenz.runPending()

                // Assert
                assertThat(pendenz.find(parent)!!.status).isEqualTo(TaskStatus.FAILED)
                assertThat(store.tasks().map { it.type }).containsExactly("send-invoice")
            }

        @Test
        fun `handler enqueues a follow-up - the follow-up runs in the next round and sees its parent`() =
            runBlocking<Unit> {
                // Arrange
                val parents = CopyOnWriteArrayList<Long?>()
                val pendenz =
                    Pendenz.create {
                        store(store)
                        clock(clock)
                        register(sendInvoice) { ctx, invoice -> ctx.enqueue(remind, Reminder(invoice.number)) }
                        register(remind) { ctx, r ->
                            reminded += r
                            parents += ctx.parentId
                        }
                    }
                val parent = pendenz.enqueue(sendInvoice, Invoice(7))

                val ran = pendenz.runPending()

                // Assert: runPending keeps going while something is due, so the follow-up ran in the same call
                assertThat(ran).isEqualTo(2)
                assertThat(reminded).containsExactly(Reminder(7))
                assertThat(parents).containsExactly(parent)
            }

        @Test
        fun `follow-up of a type requiring approval - waits for approval like any other task`() =
            runBlocking<Unit> {
                // Arrange
                val vetted = TaskType.json<Reminder>("vetted-remind", approval = Approval.ALWAYS)
                val pendenz =
                    Pendenz.create {
                        store(store)
                        clock(clock)
                        register(sendInvoice) { ctx, invoice -> ctx.enqueue(vetted, Reminder(invoice.number)) }
                        register(vetted) { _, _ -> }
                    }
                pendenz.enqueue(sendInvoice, Invoice(7), createdBy = "billing")

                pendenz.runPending()

                assertThat(
                    store.tasks().single { it.type == "vetted-remind" }.status,
                ).isEqualTo(TaskStatus.AWAITING_APPROVAL)
            }

        @Test
        fun `context enqueue - unregistered type - the handler fails and nothing is enqueued`() =
            runBlocking<Unit> {
                // Arrange
                val stranger = TaskType.json<Reminder>("stranger")
                val pendenz = pendenz { ctx, invoice -> ctx.enqueue(stranger, Reminder(invoice.number)) }
                val parent = pendenz.enqueue(sendInvoice, Invoice(1))

                pendenz.runPending()

                // Assert
                val record = pendenz.find(parent)!!
                assertThat(record.status).isEqualTo(TaskStatus.FAILED)
                assertThat(record.lastError).contains("stranger")
            }
    }

    private fun pendenz(handler: TaskHandler<Long, Invoice>): Pendenz<Long> =
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
                },
            )
            register(sendInvoice, handler)
            register(remind) { _, r -> reminded += r }
        }
}
