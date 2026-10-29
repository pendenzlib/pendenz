package io.github.pendenzlib

import io.github.pendenzlib.schema.IdType
import io.github.pendenzlib.store.InMemoryTaskStore
import io.github.pendenzlib.testing.MutableClock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/** Recurring tasks (ADR 0033), through the in-memory mode and a clock the test moves. */
class RecurringTaskTest {
    @Serializable
    data class Digest(
        val audience: String,
    )

    @Serializable
    data class Mail(
        val to: String,
    )

    private val clock = MutableClock(Instant.parse("2026-10-19T09:20:00Z"))
    private val store = InMemoryTaskStore(IdType.BIGINT)
    private val sentAt = CopyOnWriteArrayList<Instant>()
    private val events = CopyOnWriteArrayList<String>()
    private val digest = TaskType.json<Digest>("send-digest", retryPolicy = NoRetry)
    private val mail = TaskType.json<Mail>("send-mail")
    private val hourly = Schedule.every(Duration.ofHours(1))

    @Nested
    inner class FirstOccurrence {
        @Test
        fun `runPending - schedule without occurrence - the next fire time is SCHEDULED, created by the schedule`() =
            runBlocking<Unit> {
                val pendenz = pendenz()

                pendenz.runPending()

                // Assert
                val occurrence = store.tasks().single()
                assertThat(occurrence.status).isEqualTo(TaskStatus.SCHEDULED)
                assertThat(occurrence.runAt).isEqualTo(Instant.parse("2026-10-19T10:00:00Z"))
                assertThat(occurrence.createdBy).isEqualTo("schedule:hourly-digest")
                assertThat(occurrence.idempotencyKey).isEqualTo("schedule:hourly-digest@2026-10-19T10:00:00Z")
                assertThat(occurrence.payload).containsEntry("payload", """{"audience":"team"}""")
                assertThat(events).containsExactly("enqueued send-digest #${occurrence.id}")
            }

        @Test
        fun `runPending - called again before the fire time - still one occurrence`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                pendenz.runPending()
                clock.advance(Duration.ofMinutes(10))

                pendenz.runPending()

                assertThat(store.tasks()).hasSize(1)
            }

        @Test
        fun `runMaintenance - schedule without occurrence - inserts the next fire time`() =
            runBlocking<Unit> {
                val pendenz = pendenz()

                pendenz.runMaintenance()

                assertThat(store.tasks().single().runAt).isEqualTo(Instant.parse("2026-10-19T10:00:00Z"))
            }

        @Test
        fun `runPending - schedule has ended - inserts nothing`() =
            runBlocking<Unit> {
                val pendenz = pendenz(schedule = { null })

                pendenz.runPending()

                assertThat(store.tasks()).isEmpty()
            }
    }

    @Nested
    inner class Series {
        @Test
        fun `runPending - fire time reached - the occurrence runs once and leaves the next one`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                pendenz.runPending()
                clock.now = Instant.parse("2026-10-19T10:00:00Z")

                val ran = pendenz.runPending()

                // Assert
                assertThat(ran).isEqualTo(1)
                assertThat(sentAt).containsExactly(Instant.parse("2026-10-19T10:00:00Z"))
                assertThat(store.tasks().map { it.status to it.runAt }).containsExactly(
                    TaskStatus.SUCCEEDED to Instant.parse("2026-10-19T10:00:00Z"),
                    TaskStatus.SCHEDULED to Instant.parse("2026-10-19T11:00:00Z"),
                )
            }

        @Test
        fun `occurrence - handler fails with no retry left - FAILED, and the next one is scheduled anyway`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { _, _ -> error("mail server down") }
                pendenz.runPending()
                clock.now = Instant.parse("2026-10-19T10:00:00Z")

                pendenz.runPending()

                assertThat(store.tasks().map { it.status to it.runAt }).containsExactly(
                    TaskStatus.FAILED to Instant.parse("2026-10-19T10:00:00Z"),
                    TaskStatus.SCHEDULED to Instant.parse("2026-10-19T11:00:00Z"),
                )
            }

        @Test
        fun `occurrence - handler fails with retries left - RETRY_PENDING, and no next occurrence yet`() =
            runBlocking<Unit> {
                // Arrange
                val retried = TaskType.json<Digest>("send-digest", retryPolicy = ExponentialBackoff(jitter = 0.0))
                val pendenz = pendenz(type = retried) { _, _ -> error("mail server down") }
                pendenz.runPending()
                clock.now = Instant.parse("2026-10-19T10:00:00Z")

                pendenz.runPending()

                assertThat(store.tasks().map { it.status }).containsExactly(TaskStatus.RETRY_PENDING)
            }

        @Test
        fun `occurrence - handler enqueues a follow-up of another type - the next occurrence is scheduled too`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { ctx, d -> ctx.enqueue(mail, Mail(d.audience)) }
                pendenz.runPending()
                clock.now = Instant.parse("2026-10-19T10:00:00Z")

                pendenz.runPending()

                // Assert: the follow-up carries the schedule as creator, but only occurrences hold the series
                val next = store.tasks().single { it.type == "send-digest" && it.status == TaskStatus.SCHEDULED }
                assertThat(next.runAt).isEqualTo(Instant.parse("2026-10-19T11:00:00Z"))
                val followUp = store.tasks().single { it.type == "send-mail" }
                assertThat(followUp.createdBy).isEqualTo("schedule:hourly-digest")
            }

        @Test
        fun `runPending - several fire times missed - the overdue occurrence runs once, then the next after now`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                pendenz.runPending()
                clock.now = Instant.parse("2026-10-19T13:30:00Z")

                pendenz.runPending()

                // Assert
                assertThat(sentAt).containsExactly(Instant.parse("2026-10-19T10:00:00Z"))
                assertThat(store.tasks().map { it.status to it.runAt }).containsExactly(
                    TaskStatus.SUCCEEDED to Instant.parse("2026-10-19T10:00:00Z"),
                    TaskStatus.SCHEDULED to Instant.parse("2026-10-19T14:00:00Z"),
                )
            }

        @Test
        fun `occurrence - type requires approval - AWAITING_APPROVAL holds the series past its fire times`() =
            runBlocking<Unit> {
                // Arrange
                val approved = TaskType.json<Digest>("send-digest", approval = Approval.ALWAYS)
                val pendenz = pendenz(type = approved)
                pendenz.runPending()
                clock.now = Instant.parse("2026-10-19T12:30:00Z")

                pendenz.runPending()

                // Assert
                val occurrence = store.tasks().single()
                assertThat(occurrence.status).isEqualTo(TaskStatus.AWAITING_APPROVAL)
                assertThat(occurrence.runAt).isEqualTo(Instant.parse("2026-10-19T10:00:00Z"))
                assertThat(sentAt).isEmpty()
            }
    }

    @Nested
    inner class OperatorInterventions {
        @Test
        fun `check - occurrence cancelled before its fire time - inserts nothing while that fire time is ahead`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                pendenz.runPending()
                pendenz.cancel(store.tasks().single().id, actor = "ops")
                clock.now = Instant.parse("2026-10-19T09:40:00Z")

                pendenz.runPending()

                assertThat(store.tasks().map { it.status }).containsExactly(TaskStatus.CANCELLED)
            }

        @Test
        fun `check - occurrence cancelled and its fire time passed - the next one, the cancelled one never ran`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                pendenz.runPending()
                pendenz.cancel(store.tasks().single().id, actor = "ops")
                clock.now = Instant.parse("2026-10-19T10:05:00Z")

                pendenz.runPending()

                // Assert
                assertThat(sentAt).isEmpty()
                assertThat(store.tasks().map { it.status to it.runAt }).containsExactly(
                    TaskStatus.CANCELLED to Instant.parse("2026-10-19T10:00:00Z"),
                    TaskStatus.SCHEDULED to Instant.parse("2026-10-19T11:00:00Z"),
                )
            }

        @Test
        fun `check - occurrence quarantined - the next one is scheduled`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                pendenz.runPending()
                pendenz.quarantine(store.tasks().single().id, "report template broken", actor = "ops")
                clock.now = Instant.parse("2026-10-19T10:05:00Z")

                pendenz.runMaintenance()

                assertThat(store.tasks().map { it.status to it.runAt }).containsExactly(
                    TaskStatus.QUARANTINED to Instant.parse("2026-10-19T10:00:00Z"),
                    TaskStatus.SCHEDULED to Instant.parse("2026-10-19T11:00:00Z"),
                )
            }
    }

    @Nested
    inner class Registration {
        @Test
        fun `schedule - type not registered - IllegalArgumentException at build`() {
            assertThatThrownBy {
                Pendenz.create {
                    store(store)
                    schedule("hourly-digest", digest, Digest("team"), hourly)
                }
            }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("not registered")
        }

        @Test
        fun `schedule - same name twice - IllegalArgumentException`() {
            assertThatThrownBy {
                Pendenz.create {
                    store(store)
                    register(digest) { _, _ -> }
                    schedule("hourly-digest", digest, Digest("team"), hourly)
                    schedule("hourly-digest", digest, Digest("board"), hourly)
                }
            }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("twice")
        }

        @Test
        fun `schedule - name with a space - IllegalArgumentException`() {
            assertThatThrownBy {
                Pendenz.create {
                    store(store)
                    register(digest) { _, _ -> }
                    schedule("hourly digest", digest, Digest("team"), hourly)
                }
            }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("'hourly digest'")
        }

        @Test
        fun `schedule - two schedules of one type - each keeps its own series`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz =
                    Pendenz.create {
                        store(store)
                        clock(clock)
                        register(digest) { _, _ -> }
                        schedule("team-digest", digest, Digest("team"), hourly)
                        schedule("board-digest", digest, Digest("board"), Schedule.every(Duration.ofHours(2)))
                    }

                pendenz.runPending()

                assertThat(store.tasks().map { it.createdBy to it.runAt }).containsExactlyInAnyOrder(
                    "schedule:team-digest" to Instant.parse("2026-10-19T10:00:00Z"),
                    "schedule:board-digest" to Instant.parse("2026-10-19T10:00:00Z"),
                )
            }

        @Test
        fun `enqueue - createdBy with the schedule prefix - IllegalArgumentException`() {
            val pendenz = pendenz()

            assertThatThrownBy {
                runBlocking { pendenz.enqueue(digest, Digest("team"), createdBy = "schedule:hourly-digest") }
            }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("reserve")
        }

        @Test
        fun `enqueue - idempotency key with the schedule prefix - IllegalArgumentException`() {
            val pendenz = pendenz()

            assertThatThrownBy {
                runBlocking { pendenz.enqueue(digest, Digest("team"), idempotencyKey = "schedule:hourly-digest@noon") }
            }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("reserve")
        }
    }

    private fun pendenz(
        type: TaskType<Digest> = digest,
        schedule: Schedule = hourly,
        handler: TaskHandler<Long, Digest> = TaskHandler { ctx, _ -> sentAt += ctx.dueAt },
    ): Pendenz<Long> =
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
            register(type, handler)
            register(mail) { _, _ -> }
            schedule("hourly-digest", type, Digest("team"), schedule)
        }
}
