package io.github.pendenzlib

import io.github.pendenzlib.schema.IdType
import io.github.pendenzlib.schema.TaskTable
import io.github.pendenzlib.testing.PostgresDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/** Many tasks in one call (ADR 0046), through PostgreSQL and the JDBC batch. */
class EnqueueAllEndToEndTest {
    @Serializable
    data class Order(
        val number: String,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val workers = mutableListOf<Job>()
    private val importOrder = TaskType.json<Order>("import-order")
    private val confirmOrder = TaskType.json<Order>("confirm-order")

    @AfterEach
    fun stopWorkers() =
        runBlocking<Unit> {
            workers.forEach { it.cancelAndJoin() }
        }

    @Nested
    inner class EnqueueAll {
        @Test
        fun `enqueueAll - one worker running one task at a time - runs every task once, in list order`() =
            runBlocking<Unit> {
                // Arrange
                val imported = CopyOnWriteArrayList<String>()
                val pendenz =
                    pendenz(db.createTable()) { register(importOrder) { _, order -> imported += order.number } }
                workers +=
                    pendenz.startWorker(scope, WorkerOptions(parallelism = 1, pollInterval = Duration.ofMillis(50)))
                val numbers = (1001..1050).map { "A-$it" }

                // Act
                pendenz.enqueueAll(numbers.map { EnqueueRequest(importOrder, Order(it), idempotencyKey = it) })

                // Assert
                await atMost Duration.ofSeconds(10) untilAsserted { assertThat(imported).hasSize(numbers.size) }
                assertThat(imported).containsExactlyElementsOf(numbers)
            }

        @Test
        fun `enqueueAll - caller's transaction rolled back - leaves no task behind`() =
            runBlocking<Unit> {
                // Arrange
                val table = db.createTable()
                val pendenz = pendenz(table) { register(importOrder) { _, _ -> } }

                // Act
                db.dataSource.connection.use { connection ->
                    connection.autoCommit = false
                    pendenz.enqueueAll(
                        connection,
                        listOf(
                            EnqueueRequest(importOrder, Order("A-1001")),
                            EnqueueRequest(importOrder, Order("A-1002")),
                        ),
                    )
                    connection.rollback()
                }

                // Assert
                assertThat(db.count("""SELECT count(*) FROM "${table.name}"""")).isZero()
            }

        @Test
        fun `enqueueAll - uuid table, keys taken in the list - returns the id each request's task is stored under`() =
            runBlocking<Unit> {
                // Arrange
                val table = db.createTable(IdType.UUID)
                val pendenz = pendenz(table) { register(importOrder) { _, _ -> } }
                val numbers = listOf("A-1001", "A-1002", "A-1001")

                // Act
                val ids =
                    pendenz.enqueueAll(
                        numbers.map { EnqueueRequest(importOrder, Order(it), idempotencyKey = it) },
                    )

                // Assert
                assertThat(ids[2]).isEqualTo(ids[0])
                ids.forEachIndexed { i, id ->
                    assertThat(
                        pendenz.find(id)!!.idempotencyKey,
                    ).describedAs("key of request %d", i).isEqualTo(numbers[i])
                }
            }
    }

    @Nested
    inner class Chaining {
        @Test
        fun `context enqueue - follow-ups repeating a key - stores one task per key, under the parent`() =
            runBlocking<Unit> {
                // Arrange
                val table = db.createTable()
                val pendenz =
                    pendenz(table) {
                        register(importOrder) { ctx, _ ->
                            listOf("A-1001", "A-1002", "A-1001").forEach {
                                ctx.enqueue(confirmOrder, Order(it), idempotencyKey = it)
                            }
                        }
                        register(confirmOrder) { _, _ -> }
                    }
                val parent = pendenz.enqueue(importOrder, Order("batch-7"))

                // Act
                workers += pendenz.startWorker(scope, WorkerOptions(pollInterval = Duration.ofMillis(50)))

                // Assert
                await atMost Duration.ofSeconds(10) untilAsserted {
                    val followUps = runBlocking { pendenz.tasks(TaskQuery(types = setOf("confirm-order"))) }
                    assertThat(followUps.map { it.idempotencyKey }).containsExactlyInAnyOrder("A-1001", "A-1002")
                    assertThat(followUps.map { it.parentId }).containsOnly(parent)
                }
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

    companion object {
        @JvmField
        @RegisterExtension
        val db = PostgresDatabase()
    }
}
