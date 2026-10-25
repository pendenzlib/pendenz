package io.github.d4niella.pendenz

import io.github.d4niella.pendenz.schema.IdType
import io.github.d4niella.pendenz.schema.TaskTable
import io.github.d4niella.pendenz.testing.PostgresDatabase
import io.github.d4niella.pendenz.testing.awaitStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.sql.SQLException
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class TaskIdEndToEndTest {
    @Serializable
    data class Greeting(
        val name: String,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val workers = mutableListOf<Job>()

    @AfterEach
    fun stopWorkers() =
        runBlocking<Unit> {
            workers.forEach { it.cancelAndJoin() }
        }

    @Nested
    inner class BigintIds {
        @Test
        fun `enqueue - no id given - the database assigns the next identity value`() =
            runBlocking<Unit> {
                // Arrange
                val type = TaskType.json<Greeting>("greet")
                val pendenz = pendenz(db.createTable(IdType.BIGINT)) { register(type) { _, _ -> } }

                // Act
                val first = pendenz.enqueue(type, Greeting("Ada"))
                val second = pendenz.enqueue(type, Greeting("Bob"))

                // Assert
                assertThat(second).isGreaterThan(first)
                assertThat(pendenz.find(first)!!.id).isEqualTo(first)
            }

        @Test
        fun `enqueue - caller supplies the id - stores the task under that id`() =
            runBlocking<Unit> {
                // Arrange
                val type = TaskType.json<Greeting>("greet")
                val pendenz = pendenz(db.createTable(IdType.BIGINT)) { register(type) { _, _ -> } }

                // Act
                val id = pendenz.enqueue(type, Greeting("Cleo"), id = 4711L)

                // Assert
                assertThat(id).isEqualTo(4711L)
                assertThat(pendenz.find(4711L)!!.payload["payload"].toString()).contains("Cleo")
            }

        @Test
        fun `enqueue - caller supplies an id twice - the second enqueue fails with SQLException`() =
            runBlocking<Unit> {
                // Arrange
                val type = TaskType.json<Greeting>("greet")
                val pendenz = pendenz(db.createTable(IdType.BIGINT)) { register(type) { _, _ -> } }
                pendenz.enqueue(type, Greeting("Dan"), id = 1L)

                // Act
                val again = ThrowingCallable { runBlocking { pendenz.enqueue(type, Greeting("Dan"), id = 1L) } }

                // Assert
                assertThatThrownBy(again).isInstanceOf(SQLException::class.java)
            }
    }

    @Nested
    inner class UuidIds {
        @Test
        fun `enqueue - no id given - the client generates a UUID and the handler sees it`() =
            runBlocking<Unit> {
                // Arrange
                val seen = CopyOnWriteArrayList<UUID>()
                val type = TaskType.json<Greeting>("greet")
                val pendenz = pendenz(db.createTable(IdType.UUID)) { register(type) { ctx, _ -> seen += ctx.id } }

                // Act
                val id = pendenz.enqueue(type, Greeting("Eve"))
                startWorker(pendenz)

                // Assert
                awaitStatus(pendenz, id, TaskStatus.SUCCEEDED)
                assertThat(seen).containsExactly(id)
            }

        @Test
        fun `enqueue - caller supplies the UUID - stores the task under that id`() =
            runBlocking<Unit> {
                // Arrange
                val type = TaskType.json<Greeting>("greet")
                val pendenz = pendenz(db.createTable(IdType.UUID)) { register(type) { _, _ -> } }
                val chosen = UUID.fromString("7d3f0a5e-2c1b-4e8a-9f6d-0b1c2d3e4f50")

                // Act
                val id = pendenz.enqueue(type, Greeting("Fay"), id = chosen)

                // Assert
                assertThat(id).isEqualTo(chosen)
                assertThat(pendenz.find(chosen)!!.status).isEqualTo(TaskStatus.PENDING)
            }

        @Test
        fun `SQL insert - no id given - the column default assigns a UUID that the worker runs`() =
            runBlocking<Unit> {
                // Arrange
                val table = db.createTable(IdType.UUID)
                val seen = CopyOnWriteArrayList<UUID>()
                val type = TaskType.json<Greeting>("greet")
                val pendenz = pendenz(table) { register(type) { ctx, _ -> seen += ctx.id } }
                val c = table.columns

                // Act
                db.sql(
                    """INSERT INTO "${table.name}" ("${c.type}", "${c.status}", "payload", "${c.runAt}")
                       VALUES ('greet', 'PENDING', '{"name":"Gus"}', now())""",
                )
                startWorker(pendenz)

                // Assert
                awaitStatus(pendenz, seenId(seen), TaskStatus.SUCCEEDED)
            }

        private fun seenId(seen: List<UUID>): UUID {
            await atMost Duration.ofSeconds(10) untilAsserted { assertThat(seen).hasSize(1) }
            return seen.single()
        }
    }

    private fun <ID : Any> pendenz(
        table: TaskTable<ID>,
        configure: Pendenz.Builder<ID>.() -> Unit,
    ): Pendenz<ID> =
        Pendenz.create(table) {
            dataSource(db.dataSource)
            configure()
        }

    private fun startWorker(pendenz: Pendenz<*>) {
        workers += pendenz.startWorker(scope, WorkerOptions(pollInterval = Duration.ofMillis(50)))
    }

    companion object {
        @JvmField
        @RegisterExtension
        val db = PostgresDatabase()
    }
}
