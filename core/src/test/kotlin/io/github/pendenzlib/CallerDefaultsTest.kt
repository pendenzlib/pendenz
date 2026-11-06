package io.github.pendenzlib

import io.github.pendenzlib.schema.IdType
import io.github.pendenzlib.schema.TaskTable
import io.github.pendenzlib.store.InMemoryTaskStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Identity and correlation from the caller's environment (ADR 0036), through the in-memory mode. */
class CallerDefaultsTest {
    @Serializable
    data class Order(
        val number: String,
    )

    private val store = InMemoryTaskStore(IdType.BIGINT)
    private val place = TaskType.json<Order>("place-order", retryPolicy = NoRetry)
    private val confirm = TaskType.json<Order>("confirm-order", retryPolicy = NoRetry)
    private val moves = CopyOnWriteArrayList<String>()

    /** What a request filter would have put on the thread: the signed-in user and the trace id. */
    private val request =
        object : CallerDefaults {
            override fun actor() = "ada@example.com"

            override fun correlationId() = "trace-4bf92f35"
        }

    @Nested
    inner class Enqueue {
        @Test
        fun `enqueue - creator and correlation omitted - both taken from the defaults`() =
            runBlocking<Unit> {
                val pendenz = pendenz(request)

                val id = pendenz.enqueue(place, Order("A-1001"))

                // Assert
                val task = pendenz.find(id)!!
                assertThat(task.createdBy).isEqualTo("ada@example.com")
                assertThat(task.correlationId).isEqualTo("trace-4bf92f35")
            }

        @Test
        fun `enqueue - creator and correlation given - the given values are kept`() =
            runBlocking<Unit> {
                val pendenz = pendenz(request)

                val id = pendenz.enqueue(place, Order("A-1001"), createdBy = "import-job", correlationId = "batch-7")

                // Assert
                val task = pendenz.find(id)!!
                assertThat(task.createdBy).isEqualTo("import-job")
                assertThat(task.correlationId).isEqualTo("batch-7")
            }

        @Test
        fun `enqueue - defaults return null - creator and correlation stay null`() =
            runBlocking<Unit> {
                val pendenz = pendenz(object : CallerDefaults {})

                val id = pendenz.enqueue(place, Order("A-1001"))

                // Assert
                val task = pendenz.find(id)!!
                assertThat(task.createdBy).isNull()
                assertThat(task.correlationId).isNull()
            }

        @Test
        fun `enqueue - creator omitted, the default may enqueue - the permission check sees the default`() =
            runBlocking<Unit> {
                // Arrange
                val permissions = Permissions.table { actor("ada@example.com").may(Action.ENQUEUE, "place-order") }
                val pendenz = pendenz(request, permissions)

                val id = pendenz.enqueue(place, Order("A-1001"))

                assertThat(pendenz.find(id)).isNotNull()
            }

        @Test
        fun `enqueue - the defaults throw - the exception reaches the caller, no row is stored`() =
            runBlocking<Unit> {
                // Arrange
                val noUser =
                    object : CallerDefaults {
                        override fun actor(): String = error("no user signed in")
                    }
                val pendenz = pendenz(noUser)

                val attempt = ThrowingCallable { runBlocking { pendenz.enqueue(place, Order("A-1001")) } }

                // Assert
                assertThatThrownBy(
                    attempt,
                ).isInstanceOf(IllegalStateException::class.java).hasMessage("no user signed in")
                assertThat(pendenz.tasks()).isEmpty()
            }

        @Test
        fun `enqueue - follow-up from a handler - keeps its parent's creator and correlation, not the defaults`() =
            runBlocking<Unit> {
                // Arrange
                val later =
                    object : CallerDefaults {
                        override fun actor() = "worker-thread-user"

                        override fun correlationId() = "worker-thread-trace"
                    }
                val pendenz =
                    pendenz(later) { context, order -> context.enqueue(confirm, order) }
                pendenz.enqueue(place, Order("A-1001"), createdBy = "ada@example.com", correlationId = "trace-4bf92f35")

                pendenz.runPending()

                // Assert
                val followUp = pendenz.tasks(TaskQuery(types = setOf("confirm-order"))).single()
                assertThat(followUp.createdBy).isEqualTo("ada@example.com")
                assertThat(followUp.correlationId).isEqualTo("trace-4bf92f35")
            }
    }

    @Nested
    inner class StartCycle {
        @Test
        fun `startCycle - creator and correlation omitted - the cycle and its tasks carry the defaults`() =
            runBlocking<Unit> {
                val pendenz = pendenz(request)

                val cycle = pendenz.startCycle(place, CycleTasks.of(listOf(Order("A-1001"), Order("A-1002"))))

                // Assert
                assertThat(pendenz.findCycle(cycle)!!.createdBy).isEqualTo("ada@example.com")
                assertThat(pendenz.tasks()).allSatisfy {
                    assertThat(it.createdBy).isEqualTo("ada@example.com")
                    assertThat(it.correlationId).isEqualTo("trace-4bf92f35")
                }
            }
    }

    @Nested
    inner class OperatorMoves {
        @Test
        fun `cancel - actor omitted - the move names the default actor`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz(request)
                val id = pendenz.enqueue(place, Order("A-1001"), createdBy = "shop")

                pendenz.cancel(id)

                assertThat(moves).containsExactly("CANCEL by ada@example.com")
            }

        @Test
        fun `cancel - actor given - the move names the given actor`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz(request)
                val id = pendenz.enqueue(place, Order("A-1001"), createdBy = "shop")

                pendenz.cancel(id, actor = "ops-oncall")

                assertThat(moves).containsExactly("CANCEL by ops-oncall")
            }

        @Test
        fun `pauseCycle - actor omitted - the cycle move names the default actor`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz(request)
                val cycle = pendenz.startCycle(place, CycleTasks.of(listOf(Order("A-1001"))))

                pendenz.pauseCycle(cycle)

                assertThat(moves).containsExactly("PAUSE_CYCLE by ada@example.com")
            }
    }

    @Test
    fun `callerDefaults - set twice on the builder - throws IllegalArgumentException`() {
        val builder = Pendenz.builder(TaskTable()).inMemory().callerDefaults(request)

        val attempt = ThrowingCallable { builder.callerDefaults(request) }

        assertThatThrownBy(attempt).isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("twice")
    }

    private fun pendenz(
        defaults: CallerDefaults,
        permissions: Permissions = Permissions.ALLOW_ALL,
        handler: TaskHandler<Long, Order> = TaskHandler { _, _ -> },
    ): Pendenz<Long> =
        Pendenz.create {
            store(store)
            callerDefaults(defaults)
            permissions(permissions)
            listener(MoveRecorder())
            register(place, handler)
            register(confirm) { _, _ -> }
        }

    private inner class MoveRecorder : TaskListener<Long> {
        override fun onOperatorMove(move: OperatorMove<Long>) {
            moves += "${move.action} by ${move.actor}"
        }

        override fun onCycleMoved(cycle: CycleEvent<Long>) {
            moves += "${cycle.action} by ${cycle.actor}"
        }
    }
}
