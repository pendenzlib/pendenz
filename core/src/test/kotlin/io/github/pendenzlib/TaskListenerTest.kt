package io.github.pendenzlib

import io.github.pendenzlib.schema.IdType
import io.github.pendenzlib.store.InMemoryTaskStore
import io.github.pendenzlib.store.StoreTransaction
import io.github.pendenzlib.store.TaskStore
import io.github.pendenzlib.testing.MutableClock
import io.github.pendenzlib.testing.claim
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/** The hooks for metrics and logs, through the in-memory mode. */
class TaskListenerTest {
    @Serializable
    data class Greeting(
        val name: String,
    )

    /** Records every event as one line, so a test can assert the whole sequence. */
    private class Recording : TaskListener<Long> {
        val events = CopyOnWriteArrayList<String>()

        override fun onEnqueued(
            id: Long,
            type: String,
        ) {
            events += "enqueued $type #$id"
        }

        override fun onClaimed(task: TaskContext<Long>) {
            events += "claimed #${task.id} attempt ${task.attempt}"
        }

        override fun onSucceeded(
            task: TaskContext<Long>,
            duration: Duration,
        ) {
            events += "succeeded #${task.id} after $duration"
        }

        override fun onRetryScheduled(
            task: TaskContext<Long>,
            error: Throwable,
            nextRunAt: Instant,
        ) {
            events += "retry #${task.id} at $nextRunAt because ${error.message}"
        }

        override fun onFailed(
            task: TaskContext<Long>,
            error: Throwable,
        ) {
            events += "failed #${task.id} because ${error.message}"
        }

        override fun onReleased(task: TaskContext<Long>) {
            events += "released #${task.id}"
        }

        override fun onOutcomeDropped(task: TaskContext<Long>) {
            events += "dropped #${task.id}"
        }

        override fun onRetentionApplied(
            type: String,
            count: Int,
        ) {
            events += "retention $type removed $count"
        }

        override fun onAwaitingApproval(
            id: Long,
            type: String,
        ) {
            events += "awaiting $type #$id"
        }

        override fun onOperatorMove(move: OperatorMove<Long>) {
            events += "${move.action} #${move.id} ${move.from} -> ${move.to} by ${move.actor}: ${move.reason}"
        }

        override fun onCycleStarted(cycle: CycleEvent<Long>) {
            events +=
                "cycle started ${cycle.type} #${cycle.id}: ${cycle.tasks} tasks, ${cycle.awaitingApproval} waiting"
        }

        override fun onCycleApproved(cycle: CycleEvent<Long>) {
            events += "cycle approved #${cycle.id} by ${cycle.actor}: ${cycle.tasks} tasks"
        }

        override fun onCycleMoved(cycle: CycleEvent<Long>) {
            events +=
                "${cycle.action} #${cycle.id} ${cycle.from} -> ${cycle.to} by ${cycle.actor}: ${cycle.tasks} tasks"
        }
    }

    /** The in-memory store, recording when a transaction of its own has committed. */
    private inner class CommitRecording : TaskStore<Long> by store {
        override suspend fun <T> transaction(block: (StoreTransaction) -> T): T =
            store.transaction(block).also { recording.events += "committed" }
    }

    /** The in-memory store as one that joined a transaction it does not commit: it keeps what runs after it. */
    private inner class JoinedTransaction : TaskStore<Long> by store {
        val afterCommit = ArrayList<() -> Unit>()

        override fun afterCommit(action: () -> Unit) {
            afterCommit += action
        }
    }

    private val clock = MutableClock()
    private val store = InMemoryTaskStore(IdType.BIGINT)
    private val recording = Recording()

    @Nested
    inner class Lifecycle {
        @Test
        fun `listener - task succeeds - sees enqueued, claimed and succeeded with the duration`() =
            runBlocking<Unit> {
                // Arrange
                val type = TaskType.json<Greeting>("greet")
                val pendenz = pendenz { register(type) { _, _ -> clock.advance(Duration.ofSeconds(2)) } }

                // Act
                val id = pendenz.enqueue(type, Greeting("Ada"))
                pendenz.runPending()

                // Assert
                assertThat(recording.events)
                    .containsExactly("enqueued greet #$id", "claimed #$id attempt 1", "succeeded #$id after PT2S")
            }

        @Test
        fun `listener - handler throws with attempts left - sees retry with the next run time and the cause`() =
            runBlocking<Unit> {
                // Arrange
                val backoff = ExponentialBackoff(maxAttempts = 2, initialDelay = Duration.ofMinutes(1), jitter = 0.0)
                val type = TaskType.json<Greeting>("greet", retryPolicy = backoff)
                val pendenz = pendenz { register(type) { _, _ -> error("mail server down") } }
                val id = pendenz.enqueue(type, Greeting("Bob"))

                pendenz.runPending()

                // Assert
                val nextRunAt = clock.now.plus(Duration.ofMinutes(1))
                assertThat(recording.events).contains("retry #$id at $nextRunAt because mail server down")
            }

        @Test
        fun `listener - attempts exhausted - sees failed with the cause`() =
            runBlocking<Unit> {
                // Arrange
                val type = TaskType.json<Greeting>("greet", retryPolicy = NoRetry)
                val pendenz = pendenz { register(type) { _, _ -> error("mail server down") } }
                val id = pendenz.enqueue(type, Greeting("Cleo"))

                pendenz.runPending()

                assertThat(recording.events).contains("failed #$id because mail server down")
            }

        @Test
        fun `listener - lock of a dead worker expired - sees a retry caused by the expired lock`() =
            runBlocking<Unit> {
                // Arrange
                val type = TaskType.json<Greeting>("greet", retryPolicy = ExponentialBackoff(jitter = 0.0))
                val pendenz = pendenz { register(type) { _, _ -> } }
                val id = pendenz.enqueue(type, Greeting("Dan"))
                store.transaction { store.claim(it, "dead-worker", setOf("greet"), 1, clock.now) }
                clock.advance(Duration.ofMinutes(5))

                pendenz.runPending()

                // Assert
                assertThat(recording.events).anySatisfy {
                    assertThat(it).startsWith("retry #$id at ${clock.now.plusSeconds(1)} because lock expired")
                }
            }

        @Test
        fun `listener - retention sweep removes rows - sees the type and the count`() =
            runBlocking<Unit> {
                // Arrange
                val type = TaskType.json<Greeting>("greet", retention = Retention.delete(Duration.ZERO))
                val pendenz = pendenz { register(type) { _, _ -> } }
                pendenz.enqueue(type, Greeting("Eve"))
                pendenz.enqueue(type, Greeting("Fay"))
                pendenz.runPending()
                clock.advance(Duration.ofSeconds(1))

                pendenz.runMaintenance()

                assertThat(recording.events).contains("retention greet removed 2")
            }
    }

    @Nested
    inner class Delivery {
        @Test
        fun `listener - enqueue in a transaction of the queue - hears of the task after the commit`() =
            runBlocking<Unit> {
                // Arrange
                val type = TaskType.json<Greeting>("greet")
                val pendenz = pendenz(CommitRecording()) { register(type) { _, _ -> } }

                val id = pendenz.enqueue(type, Greeting("Ada"))

                assertThat(recording.events).containsExactly("committed", "enqueued greet #$id")
            }

        @Test
        fun `listener - store joined a transaction it does not commit - hears nothing until that commit`() =
            runBlocking<Unit> {
                // Arrange
                val type = TaskType.json<Greeting>("greet")
                val joined = JoinedTransaction()
                val pendenz = pendenz(joined) { register(type) { _, _ -> } }
                val id = pendenz.enqueue(type, Greeting("Ada"))
                val beforeCommit = recording.events.toList()

                joined.afterCommit.forEach { it() }

                // Assert
                assertThat(beforeCommit).isEmpty()
                assertThat(recording.events).containsExactly("enqueued greet #$id")
            }

        @Test
        fun `listener - cycle start fails at the third payload - hears of no task and no cycle`() =
            runBlocking<Unit> {
                // Arrange: the preview, computed at enqueue, refuses one payload after two were inserted
                val type =
                    TaskType.json<Greeting>("greet", preview = {
                        require(it.name != "Cleo") { "no preview for Cleo" }
                        "greets ${it.name}"
                    })
                val pendenz = pendenz { register(type) { _, _ -> } }
                val payloads = CycleTasks.of(listOf(Greeting("Ada"), Greeting("Bob"), Greeting("Cleo")))

                val attempt = catchThrowable { runBlocking { pendenz.startCycle(type, payloads) } }

                // Assert
                assertThat(attempt).isInstanceOf(IllegalArgumentException::class.java)
                assertThat(recording.events).isEmpty()
            }
    }

    @Nested
    inner class Repeats {
        @Test
        fun `listener - repeat under an idempotency key - hears of the stored task once`() =
            runBlocking<Unit> {
                // Arrange
                val type = TaskType.json<Greeting>("greet")
                val pendenz = pendenz { register(type) { _, _ -> } }
                val id = pendenz.enqueue(type, Greeting("Ada"), idempotencyKey = "welcome-ada")

                pendenz.enqueue(type, Greeting("Ada"), idempotencyKey = "welcome-ada")

                assertThat(recording.events).containsExactly("enqueued greet #$id")
            }

        @Test
        fun `listener - repeat of an approved task under its key - asks for no second approval`() =
            runBlocking<Unit> {
                // Arrange
                val refund = TaskType.json<Greeting>("refund", approval = Approval.ALWAYS)
                val pendenz = pendenz { register(refund) { _, _ -> } }
                val id = pendenz.enqueue(refund, Greeting("Ada"), idempotencyKey = "order-7", createdBy = "agent")
                pendenz.approve(id, actor = "clerk")

                pendenz.enqueue(refund, Greeting("Ada"), idempotencyKey = "order-7", createdBy = "agent")

                assertThat(recording.events).containsExactly("enqueued refund #$id", "awaiting refund #$id")
            }
    }

    @Nested
    inner class OperatorMoves {
        @Test
        fun `listener - operator quarantines a pending task - hears the move with source, target, actor and reason`() =
            runBlocking<Unit> {
                // Arrange
                val type = TaskType.json<Greeting>("greet")
                val pendenz = pendenz { register(type) { _, _ -> } }
                val id = pendenz.enqueue(type, Greeting("Ada"))

                pendenz.quarantine(id, "address unknown", actor = "ops")

                assertThat(
                    recording.events.last(),
                ).isEqualTo("QUARANTINE #$id PENDING -> QUARANTINED by ops: address unknown")
            }

        @ParameterizedTest
        @EnumSource(Action::class, names = ["RESTART", "COMPLETE", "CANCEL"])
        fun `listener - operator action on a quarantined task - hears one move of that action`(action: Action) =
            runBlocking<Unit> {
                // Arrange
                val type = TaskType.json<Greeting>("greet")
                val pendenz = pendenz { register(type) { _, _ -> } }
                val id = pendenz.enqueue(type, Greeting("Ada"))
                pendenz.quarantine(id, "address unknown", actor = "ops")
                recording.events.clear()

                when (action) {
                    Action.RESTART -> pendenz.restart(id, actor = "ops")
                    Action.COMPLETE -> pendenz.complete(id, actor = "ops")
                    else -> pendenz.cancel(id, actor = "ops")
                }

                // Assert
                val target = pendenz.find(id)!!.status
                assertThat(recording.events).containsExactly("$action #$id QUARANTINED -> $target by ops: null")
            }

        @Test
        fun `listener - operator move from a status it does not apply to - hears nothing`() =
            runBlocking<Unit> {
                // Arrange
                val type = TaskType.json<Greeting>("greet")
                val pendenz = pendenz { register(type) { _, _ -> } }
                val id = pendenz.enqueue(type, Greeting("Ada"))
                recording.events.clear()

                pendenz.complete(id, actor = "ops")

                assertThat(recording.events).isEmpty()
            }
    }

    @Nested
    inner class Cycles {
        private val refund = TaskType.json<Greeting>("refund", approval = Approval.ALWAYS)
        private val payloads = CycleTasks.of(listOf(Greeting("Ada"), Greeting("Bob"), Greeting("Cleo")))

        @Test
        fun `listener - cycle of three payloads held for approval - hears one start and no single enqueue`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { register(refund) { _, _ -> } }

                val id = pendenz.startCycle(refund, payloads, createdBy = "billing-run")

                assertThat(recording.events).containsExactly("cycle started refund #$id: 3 tasks, 3 waiting")
            }

        @Test
        fun `listener - cycle approved - hears one approval with the number of tasks`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { register(refund) { _, _ -> } }
                val id = pendenz.startCycle(refund, payloads, createdBy = "billing-run")

                pendenz.approveCycle(id, actor = "clerk")

                assertThat(recording.events.last()).isEqualTo("cycle approved #$id by clerk: 3 tasks")
            }

        @Test
        fun `listener - cycle paused - hears one move from ACTIVE to PAUSED with the tasks set aside`() =
            runBlocking<Unit> {
                // Arrange
                val greet = TaskType.json<Greeting>("greet")
                val pendenz = pendenz { register(greet) { _, _ -> } }
                val id = pendenz.startCycle(greet, payloads)

                pendenz.pauseCycle(id, actor = "ops")

                assertThat(recording.events.last()).isEqualTo("PAUSE_CYCLE #$id ACTIVE -> PAUSED by ops: 3 tasks")
            }
    }

    @Nested
    inner class Interruptions {
        @Test
        fun `listener - task cancelled while its handler runs - sees the outcome dropped`() =
            runBlocking<Unit> {
                // Arrange: the handler's own run is cancelled by an operator before it ends
                val type = TaskType.json<Greeting>("greet")
                lateinit var pendenz: Pendenz<Long>
                pendenz = pendenz { register(type) { ctx, _ -> pendenz.cancel(ctx.id) } }
                val id = pendenz.enqueue(type, Greeting("Ada"))

                pendenz.runPending()

                assertThat(recording.events).contains("dropped #$id")
            }

        @Test
        fun `listener - worker stopped while a handler runs - sees the task released`() =
            runBlocking<Unit> {
                // Arrange
                val type = TaskType.json<Greeting>("greet")
                val started = CompletableDeferred<Unit>()
                val pendenz =
                    pendenz {
                        register(type) { _, _ ->
                            started.complete(Unit)
                            awaitCancellation()
                        }
                    }
                val id = pendenz.enqueue(type, Greeting("Bob"))
                val worker =
                    pendenz.startWorker(
                        this,
                        WorkerOptions(pollInterval = Duration.ofMillis(10), shutdownTimeout = Duration.ZERO),
                    )
                started.await()

                // Act
                worker.cancelAndJoin()

                // Assert
                assertThat(recording.events).contains("released #$id")
            }
    }

    @Nested
    inner class Robustness {
        @Test
        fun `listener - throws in a hook - the task still succeeds and other listeners are still called`() =
            runBlocking<Unit> {
                // Arrange
                val broken =
                    object : TaskListener<Long> {
                        override fun onClaimed(task: TaskContext<Long>) = error("metrics backend down")
                    }
                val type = TaskType.json<Greeting>("greet")
                val pendenz =
                    Pendenz.create {
                        store(store)
                        clock(clock)
                        listener(broken)
                        listener(recording)
                        register(type) { _, _ -> }
                    }
                val id = pendenz.enqueue(type, Greeting("Gus"))

                pendenz.runPending()

                // Assert
                assertThat(pendenz.find(id)!!.status).isEqualTo(TaskStatus.SUCCEEDED)
                assertThat(recording.events).contains("claimed #$id attempt 1")
            }
    }

    private fun pendenz(
        taskStore: TaskStore<Long> = store,
        configure: Pendenz.Builder<Long>.() -> Unit,
    ): Pendenz<Long> =
        Pendenz.create {
            store(taskStore)
            clock(clock)
            listener(recording)
            configure()
        }
}
