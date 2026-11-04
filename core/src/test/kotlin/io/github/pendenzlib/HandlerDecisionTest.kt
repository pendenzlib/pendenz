package io.github.pendenzlib

import io.github.pendenzlib.schema.IdType
import io.github.pendenzlib.store.InMemoryTaskStore
import io.github.pendenzlib.testing.MutableClock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/** Decisions a handler reports by throwing (ADR 0035), through the in-memory mode and a clock the test moves. */
class HandlerDecisionTest {
    @Serializable
    data class Sync(
        val customer: String,
    )

    private val clock = MutableClock(Instant.parse("2026-11-04T09:00:00Z"))
    private val store = InMemoryTaskStore(IdType.BIGINT)
    private val events = CopyOnWriteArrayList<String>()
    private val policyAsked = CopyOnWriteArrayList<Int>()
    private val countingPolicy =
        RetryPolicy { attempt, failedAt, _ ->
            policyAsked += attempt
            failedAt.plusSeconds(10)
        }
    private val sync = TaskType.json<Sync>("sync-customer", retryPolicy = countingPolicy)
    private val notify = TaskType.json<Sync>("notify-customer")
    private val inAnHour = Instant.parse("2026-11-04T10:00:00Z")

    @Nested
    inner class Deferral {
        @Test
        fun `retry later - time in an hour - RETRY_PENDING at that time, the attempt taken back, reason kept`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { _, _ -> throw RetryLaterException(inAnHour, "export not ready before 10:00") }
                val id = pendenz.enqueue(sync, Sync("acme"))

                pendenz.runPending()

                // Assert
                val task = pendenz.find(id)!!
                assertThat(task.status).isEqualTo(TaskStatus.RETRY_PENDING)
                assertThat(task.runAt).isEqualTo(inAnHour)
                assertThat(task.attempts).isZero()
                assertThat(task.lastError).isEqualTo("deferred until $inAnHour: export not ready before 10:00")
                assertThat(policyAsked).isEmpty()
            }

        @Test
        fun `retry later - deferred, then due - runs again and succeeds, one attempt counted`() =
            runBlocking<Unit> {
                // Arrange
                var deferred = false
                val pendenz =
                    pendenz { _, _ ->
                        if (!deferred) {
                            deferred = true
                            throw RetryLaterException(inAnHour, "export not ready")
                        }
                    }
                val id = pendenz.enqueue(sync, Sync("acme"))
                pendenz.runPending()
                clock.now = inAnHour

                pendenz.runPending()

                // Assert
                val task = pendenz.find(id)!!
                assertThat(task.status).isEqualTo(TaskStatus.SUCCEEDED)
                assertThat(task.attempts).isOne()
            }

        @Test
        fun `retry later - time not after now - a failed attempt for the retry policy`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { _, _ -> throw RetryLaterException(clock.now, "Retry-After: 0") }
                val id = pendenz.enqueue(sync, Sync("acme"))

                pendenz.runPending()

                // Assert
                val task = pendenz.find(id)!!
                assertThat(policyAsked).containsExactly(1)
                assertThat(task.attempts).isOne()
                assertThat(task.lastError).contains("RetryLaterException", "Retry-After: 0")
            }

        @Test
        fun `retry later - wrapped by a client in another exception - found in the cause chain, deferred`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz =
                    pendenz { _, _ ->
                        throw IOException("partner call failed", RetryLaterException(inAnHour, "429 Too Many Requests"))
                    }
                val id = pendenz.enqueue(sync, Sync("acme"))

                pendenz.runPending()

                // Assert
                val task = pendenz.find(id)!!
                assertThat(task.status).isEqualTo(TaskStatus.RETRY_PENDING)
                assertThat(task.attempts).isZero()
                assertThat(policyAsked).isEmpty()
            }

        @Test
        fun `retry later - handler enqueued a follow-up first - the follow-up is dropped`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz =
                    pendenz { ctx, sync ->
                        ctx.enqueue(notify, Sync(sync.customer))
                        throw RetryLaterException(inAnHour, "export not ready")
                    }
                pendenz.enqueue(sync, Sync("acme"))

                pendenz.runPending()

                assertThat(store.tasks().map { it.type }).containsExactly("sync-customer")
            }

        @Test
        fun `retry later - listener - hears onDeferred with run time and reason, no retry and no failure`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { _, _ -> throw RetryLaterException(inAnHour, "export not ready") }
                pendenz.enqueue(sync, Sync("acme"))

                pendenz.runPending()

                assertThat(events).containsExactly("deferred sync-customer until $inAnHour: export not ready")
            }

        @Test
        fun `retry later - occurrence of a schedule - no next occurrence while it is deferred`() =
            runBlocking<Unit> {
                // Arrange: the occurrence is due at 09:00; the clock stands there
                clock.now = Instant.parse("2026-11-04T08:59:00Z")
                val pendenz =
                    pendenz(schedule = Schedule.every(Duration.ofHours(1))) { _, _ ->
                        throw RetryLaterException(Instant.parse("2026-11-04T09:30:00Z"), "export not ready")
                    }
                pendenz.runPending()
                clock.now = Instant.parse("2026-11-04T09:00:00Z")

                pendenz.runPending()

                assertThat(store.tasks().map { it.status }).containsExactly(TaskStatus.RETRY_PENDING)
            }
    }

    @Nested
    inner class HandOver {
        @Test
        fun `hand over - handler throws - QUARANTINED with the reason, the retry policy not asked`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { _, _ -> throw HandOverException("total 120.00 does not match lines 112.00") }
                val id = pendenz.enqueue(sync, Sync("acme"))

                pendenz.runPending()

                // Assert
                val task = pendenz.find(id)!!
                assertThat(task.status).isEqualTo(TaskStatus.QUARANTINED)
                assertThat(task.lastError).isEqualTo("total 120.00 does not match lines 112.00")
                assertThat(task.finishedAt).isNull()
                assertThat(policyAsked).isEmpty()
            }

        @Test
        fun `hand over - listener - hears onHandedOver with the reason`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { _, _ -> throw HandOverException("lines do not add up") }
                pendenz.enqueue(sync, Sync("acme"))

                pendenz.runPending()

                assertThat(events).containsExactly("handed over sync-customer: lines do not add up")
            }

        @Test
        fun `hand over - restarted by an operator - runs again`() =
            runBlocking<Unit> {
                // Arrange
                var handedOver = false
                val pendenz =
                    pendenz { _, _ ->
                        if (!handedOver) {
                            handedOver = true
                            throw HandOverException("lines do not add up")
                        }
                    }
                val id = pendenz.enqueue(sync, Sync("acme"))
                pendenz.runPending()
                pendenz.restart(id, actor = "ops")

                pendenz.runPending()

                assertThat(pendenz.find(id)!!.status).isEqualTo(TaskStatus.SUCCEEDED)
            }

        @Test
        fun `hand over - occurrence of a schedule - the next occurrence is scheduled`() =
            runBlocking<Unit> {
                // Arrange
                clock.now = Instant.parse("2026-11-04T08:59:00Z")
                val pendenz =
                    pendenz(schedule = Schedule.every(Duration.ofHours(1))) { _, _ ->
                        throw HandOverException("lines do not add up")
                    }
                pendenz.runPending()
                clock.now = Instant.parse("2026-11-04T09:00:00Z")

                pendenz.runPending()

                assertThat(store.tasks().map { it.status to it.runAt }).containsExactly(
                    TaskStatus.QUARANTINED to Instant.parse("2026-11-04T09:00:00Z"),
                    TaskStatus.SCHEDULED to Instant.parse("2026-11-04T10:00:00Z"),
                )
            }
    }

    @Nested
    inner class ResourceBlocks {
        @Test
        fun `resource unavailable - other tasks of the resource - not claimed until the block ends, then they run`() =
            runBlocking<Unit> {
                // Arrange: the first sync finds the quota used up; the second must not try before 10:00
                val ran = CopyOnWriteArrayList<String>()
                val pendenz =
                    pendenz { _, sync ->
                        ran += sync.customer
                        if (clock.now.isBefore(inAnHour)) throw ResourceUnavailableException(inAnHour, "quota used up")
                    }
                pendenz.enqueue(sync, Sync("acme"), resource = "partner-api")
                pendenz.enqueue(sync, Sync("globex"), resource = "partner-api")
                pendenz.runPending()
                assertThat(ran).containsExactly("acme")
                clock.now = inAnHour

                pendenz.runPending()

                // Assert: globex has waited since 09:00, acme was deferred to 10:00
                assertThat(ran).containsExactly("acme", "globex", "acme")
                assertThat(store.tasks().map { it.status }).containsOnly(TaskStatus.SUCCEEDED)
            }

        @Test
        fun `resource unavailable - names another resource - that one is blocked, the task's own is not`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz =
                    pendenz { _, sync ->
                        if (sync.customer ==
                            "acme"
                        ) {
                            throw ResourceUnavailableException(inAnHour, "quota", "partner-api")
                        }
                    }
                pendenz.enqueue(sync, Sync("acme"), resource = "customer:acme")
                pendenz.runPending()
                val other = pendenz.enqueue(sync, Sync("globex"), resource = "customer:acme")
                val partner = pendenz.enqueue(sync, Sync("initech"), resource = "partner-api")

                pendenz.runPending()

                // Assert
                assertThat(pendenz.resourceBlocks().map { it.name }).containsExactly("partner-api")
                assertThat(pendenz.find(other)!!.status).isEqualTo(TaskStatus.SUCCEEDED)
                assertThat(pendenz.find(partner)!!.status).isEqualTo(TaskStatus.PENDING)
            }

        @Test
        fun `resource unavailable - task without a resource, none named - deferred only, no block`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { _, _ -> throw ResourceUnavailableException(inAnHour, "quota used up") }
                val id = pendenz.enqueue(sync, Sync("acme"))

                pendenz.runPending()

                // Assert
                assertThat(pendenz.find(id)!!.status).isEqualTo(TaskStatus.RETRY_PENDING)
                assertThat(pendenz.resourceBlocks()).isEmpty()
            }

        @Test
        fun `resource unavailable - listener - hears onResourceBlocked and onDeferred`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { _, _ -> throw ResourceUnavailableException(inAnHour, "quota used up") }
                pendenz.enqueue(sync, Sync("acme"), resource = "partner-api")

                pendenz.runPending()

                assertThat(events).containsExactly(
                    "blocked partner-api until $inAnHour: quota used up",
                    "deferred sync-customer until $inAnHour: quota used up",
                )
            }

        @Test
        fun `blockResource - LIKE pattern - every matching resource waits, others run`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { _, _ -> }
                pendenz.blockResource("customer:%", inAnHour, "maintenance window")
                val blocked = pendenz.enqueue(sync, Sync("acme"), resource = "customer:acme")
                val free = pendenz.enqueue(sync, Sync("ledger"), resource = "ledger")

                pendenz.runPending()

                // Assert
                assertThat(pendenz.find(blocked)!!.status).isEqualTo(TaskStatus.PENDING)
                assertThat(pendenz.find(free)!!.status).isEqualTo(TaskStatus.SUCCEEDED)
            }

        @Test
        fun `blockResource - later block, then an earlier one - the later time and its reason stay`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { _, _ -> }
                pendenz.blockResource("partner-api", Instant.parse("2026-11-04T12:00:00Z"), "daily quota")

                pendenz.blockResource("partner-api", inAnHour, "hourly quota")

                // Assert
                val block = pendenz.resourceBlocks().single()
                assertThat(block.blockedUntil).isEqualTo(Instant.parse("2026-11-04T12:00:00Z"))
                assertThat(block.reason).isEqualTo("daily quota")
            }

        @Test
        fun `unblockResource - block lifted before its time - the resource's tasks run at the next poll`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { _, _ -> }
                pendenz.blockResource("partner-api", inAnHour, "quota used up")
                val id = pendenz.enqueue(sync, Sync("acme"), resource = "partner-api")
                pendenz.runPending()
                pendenz.unblockResource("partner-api")

                pendenz.runPending()

                assertThat(pendenz.find(id)!!.status).isEqualTo(TaskStatus.SUCCEEDED)
            }

        @Test
        fun `runMaintenance - block whose time has passed - deleted, the others stay`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { _, _ -> }
                pendenz.blockResource("partner-api", Instant.parse("2026-11-04T09:30:00Z"), "hourly quota")
                pendenz.blockResource("ledger", Instant.parse("2026-11-04T12:00:00Z"), "closing")
                clock.now = Instant.parse("2026-11-04T09:30:00Z")

                pendenz.runMaintenance()

                assertThat(pendenz.resourceBlocks().map { it.name }).containsExactly("ledger")
            }
    }

    private fun pendenz(
        schedule: Schedule? = null,
        handler: TaskHandler<Long, Sync>,
    ): Pendenz<Long> =
        Pendenz.create {
            store(store)
            clock(clock)
            listener(Recorder())
            register(sync, handler)
            register(notify) { _, _ -> }
            if (schedule != null) schedule("hourly-sync", sync, Sync("all"), schedule)
        }

    private inner class Recorder : TaskListener<Long> {
        override fun onDeferred(
            task: TaskContext<Long>,
            runAt: Instant,
            reason: String,
        ) {
            events += "deferred ${task.type} until $runAt: $reason"
        }

        override fun onHandedOver(
            task: TaskContext<Long>,
            reason: String,
        ) {
            events += "handed over ${task.type}: $reason"
        }

        override fun onResourceBlocked(
            resource: String,
            until: Instant,
            reason: String,
        ) {
            events += "blocked $resource until $until: $reason"
        }

        override fun onRetryScheduled(
            task: TaskContext<Long>,
            error: Throwable,
            nextRunAt: Instant,
        ) {
            events += "retry ${task.type}"
        }

        override fun onFailed(
            task: TaskContext<Long>,
            error: Throwable,
        ) {
            events += "failed ${task.type}"
        }
    }
}
