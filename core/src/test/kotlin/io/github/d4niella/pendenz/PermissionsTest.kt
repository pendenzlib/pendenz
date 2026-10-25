package io.github.d4niella.pendenz

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

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

    /** Every action against a stranger, who may do nothing: refused before anything changes. */
    @ParameterizedTest
    @EnumSource(Action::class)
    fun `every action - actor without it - throws NotPermittedException naming actor and action, nothing changes`(
        action: Action,
    ) = runBlocking<Unit> {
        // Arrange: a pending task the clerk enqueued and an active cycle billing started
        val pendenz = pendenz(table)
        val task = pendenz.enqueue(greet, Greeting("Ada"), createdBy = "clerk")
        val cycle = pendenz.startCycle(greet, CycleTasks.of(listOf(Greeting("Bob"))), createdBy = "billing")
        val stranger = "stranger"

        val thrown =
            catchThrowable {
                runBlocking {
                    when (action) {
                        Action.ENQUEUE -> {
                            pendenz.enqueue(greet, Greeting("Cleo"), createdBy = stranger)
                        }

                        Action.APPROVE -> {
                            pendenz.approve(task, stranger)
                        }

                        Action.DECLINE -> {
                            pendenz.decline(task, stranger, "no")
                        }

                        Action.RESTART -> {
                            pendenz.restart(task, stranger)
                        }

                        Action.COMPLETE -> {
                            pendenz.complete(task, stranger)
                        }

                        Action.QUARANTINE -> {
                            pendenz.quarantine(task, "held", stranger)
                        }

                        Action.CANCEL -> {
                            pendenz.cancel(task, stranger)
                        }

                        Action.START_CYCLE -> {
                            pendenz.startCycle(
                                greet,
                                CycleTasks.of(listOf(Greeting("Dan"))),
                                createdBy = stranger,
                            )
                        }

                        Action.APPROVE_CYCLE -> {
                            pendenz.approveCycle(cycle, stranger)
                        }

                        Action.PAUSE_CYCLE -> {
                            pendenz.pauseCycle(cycle, stranger)
                        }

                        Action.RESUME_CYCLE -> {
                            pendenz.resumeCycle(cycle, stranger)
                        }

                        Action.CANCEL_CYCLE -> {
                            pendenz.cancelCycle(cycle, stranger)
                        }
                    }
                }
            }

        // Assert
        assertThat(thrown).isInstanceOf(NotPermittedException::class.java).hasMessageContaining(stranger)
        assertThat((thrown as NotPermittedException).action).isEqualTo(action)
        assertThat(pendenz.find(task)!!.status).isEqualTo(TaskStatus.PENDING)
        assertThat(pendenz.findCycle(cycle)!!.status).isEqualTo(CycleStatus.ACTIVE)
        assertThat(pendenz.tasks()).hasSize(2)
    }

    @Test
    fun `restart - actor allowed for another task type only - throws NotPermittedException`() =
        runBlocking<Unit> {
            // Arrange: the clerk may restart greetings, not reminders
            val remind = TaskType.json<Greeting>("remind", retryPolicy = NoRetry)
            val permissions =
                Permissions.table {
                    actor("clerk").may(Action.ENQUEUE, "remind").may(Action.RESTART, "greet")
                }
            val pendenz =
                Pendenz.create {
                    inMemory()
                    permissions(permissions)
                    register(remind) { _, _ -> error("mail server down") }
                }
            val id = pendenz.enqueue(remind, Greeting("Eve"), createdBy = "clerk")
            pendenz.runPending()

            val attempt = ThrowingCallable { runBlocking { pendenz.restart(id, actor = "clerk") } }

            assertThatThrownBy(attempt).isInstanceOf(NotPermittedException::class.java)
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
