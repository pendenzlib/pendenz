package io.github.d4niella.pendenz

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** Permissions checked in the API (ADR 0018), through the in-memory mode. */
class PermissionsTest {
    @Serializable
    data class Greeting(
        val name: String,
    )

    private val greet = TaskType.json<Greeting>("greet", retryPolicy = NoRetry)
    private val table =
        Permissions.table {
            actor("clerk").may(Action.ENQUEUE, "greet").may(Action.RESTART, "greet")
            actor("manager").may(Action.APPROVE).may(Action.CANCEL)
            actor("billing").may(Action.START_CYCLE, "greet")
        }

    @Nested
    inner class StartCycle {
        @Test
        fun `startCycle - actor may start cycles but not enqueue - the cycle's tasks are stored`() =
            runBlocking<Unit> {
                val pendenz = pendenz(table)

                val cycle =
                    pendenz.startCycle(
                        greet,
                        CycleTasks.of(listOf(Greeting("Ada"), Greeting("Bob"))),
                        createdBy = "billing",
                    )

                assertThat(pendenz.cycleProgress(cycle)).containsExactlyEntriesOf(mapOf(TaskStatus.PENDING to 2))
            }

        @Test
        fun `startCycle - actor without the action - throws NotPermittedException`() =
            runBlocking<Unit> {
                val pendenz = pendenz(table)

                val attempt =
                    ThrowingCallable {
                        runBlocking {
                            pendenz.startCycle(greet, CycleTasks.of(listOf(Greeting("Ada"))), createdBy = "clerk")
                        }
                    }

                assertThatThrownBy(attempt).isInstanceOf(NotPermittedException::class.java)
            }
    }

    @Nested
    inner class Enqueue {
        @Test
        fun `enqueue - actor listed for the type - is stored`() =
            runBlocking<Unit> {
                val pendenz = pendenz(table)

                val id = pendenz.enqueue(greet, Greeting("Ada"), createdBy = "clerk")

                assertThat(pendenz.find(id)).isNotNull()
            }

        @Test
        fun `enqueue - actor not listed - throws NotPermittedException naming actor, action and type`() =
            runBlocking<Unit> {
                val pendenz = pendenz(table)

                val attempt =
                    ThrowingCallable { runBlocking { pendenz.enqueue(greet, Greeting("Ada"), createdBy = "intern") } }

                assertThatThrownBy(attempt)
                    .isInstanceOf(NotPermittedException::class.java)
                    .hasMessageContaining("intern")
                    .hasMessageContaining("ENQUEUE")
                    .hasMessageContaining("greet")
            }

        @Test
        fun `enqueue - anonymous under a permission table - is denied`() =
            runBlocking<Unit> {
                val pendenz = pendenz(table)

                val attempt = ThrowingCallable { runBlocking { pendenz.enqueue(greet, Greeting("Ada")) } }

                assertThatThrownBy(
                    attempt,
                ).isInstanceOf(NotPermittedException::class.java).hasMessageContaining("anonymous")
            }

        @Test
        fun `enqueue - default permissions - anonymous is allowed`() =
            runBlocking<Unit> {
                val pendenz = pendenz(Permissions.ALLOW_ALL)

                val id = pendenz.enqueue(greet, Greeting("Ada"))

                assertThat(pendenz.find(id)).isNotNull()
            }
    }

    @Nested
    inner class OperatorOperations {
        @Test
        fun `restart - FAILED task by an actor allowed to - PENDING again`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz(table, handler = { _, _ -> error("printer jam") })
                val id = pendenz.enqueue(greet, Greeting("Ada"), createdBy = "clerk")
                pendenz.runPending()

                val restarted = pendenz.restart(id, actor = "clerk")

                // Assert
                assertThat(restarted).isTrue()
                assertThat(pendenz.find(id)!!.status).isEqualTo(TaskStatus.PENDING)
            }

        @Test
        fun `restart - PENDING task - returns false, nothing waits for a human`() =
            runBlocking<Unit> {
                val pendenz = pendenz(Permissions.ALLOW_ALL)
                val id = pendenz.enqueue(greet, Greeting("Ada"))

                val restarted = pendenz.restart(id)

                assertThat(restarted).isFalse()
            }

        @Test
        fun `complete - FAILED task - SUCCEEDED without running`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz(Permissions.ALLOW_ALL, handler = { _, _ -> error("printer jam") })
                val id = pendenz.enqueue(greet, Greeting("Ada"))
                pendenz.runPending()

                val completed = pendenz.complete(id)

                // Assert
                assertThat(completed).isTrue()
                assertThat(pendenz.find(id)!!.status).isEqualTo(TaskStatus.SUCCEEDED)
            }

        @Test
        fun `quarantine - PENDING task with a reason - QUARANTINED and not run`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz(Permissions.ALLOW_ALL)
                val id = pendenz.enqueue(greet, Greeting("Ada"))

                val held = pendenz.quarantine(id, reason = "ticket #123")

                // Assert
                assertThat(held).isTrue()
                assertThat(pendenz.find(id)!!.lastError).isEqualTo("ticket #123")
                assertThat(pendenz.runPending()).isZero()
            }

        @Test
        fun `cancel - actor with the action on every type - CANCELLED`() =
            runBlocking<Unit> {
                val pendenz = pendenz(table)
                val id = pendenz.enqueue(greet, Greeting("Ada"), createdBy = "clerk")

                val cancelled = pendenz.cancel(id, actor = "manager")

                assertThat(cancelled).isTrue()
                assertThat(pendenz.find(id)!!.status).isEqualTo(TaskStatus.CANCELLED)
            }

        @Test
        fun `cancel - actor without the action - throws NotPermittedException`() =
            runBlocking<Unit> {
                val pendenz = pendenz(table)
                val id = pendenz.enqueue(greet, Greeting("Ada"), createdBy = "clerk")

                val attempt = ThrowingCallable { runBlocking { pendenz.cancel(id, actor = "clerk") } }

                assertThatThrownBy(attempt).isInstanceOf(NotPermittedException::class.java)
            }
    }

    private fun pendenz(
        permissions: Permissions,
        handler: TaskHandler<Long, Greeting> = TaskHandler { _, _ -> },
    ): Pendenz<Long> =
        Pendenz.create {
            inMemory()
            permissions(permissions)
            register(greet, handler)
        }
}
