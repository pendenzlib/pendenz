package io.github.d4niella.pendenz

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
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/** Results, provenance and preview against PostgreSQL (ADR 0020): jsonb columns that SQL can query. */
class ResultEndToEndTest {
    @Serializable
    data class Ticket(
        val id: Int,
        val text: String,
    )

    @Serializable
    data class Score(
        val value: Int,
    )

    private val scoreTicket = TaskType.json<Ticket>("score-ticket", retryPolicy = NoRetry)
    private val inRange =
        TaskResult.json<Ticket, Score> { _, score ->
            if (score.value in 0..100) Verdict.accept() else Verdict.reject("score ${score.value} out of range")
        }

    private lateinit var table: TaskTable<Long>
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val workers = mutableListOf<Job>()

    @BeforeEach
    fun createTable() {
        table = db.createTable("ticket")
    }

    @AfterEach
    fun stopWorkers() =
        runBlocking<Unit> {
            workers.forEach { it.cancelAndJoin() }
        }

    @Nested
    inner class Results {
        @Test
        fun `worker - accepted result - SUCCEEDED and the result is JSON that SQL can take apart`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { _, ticket -> Score(ticket.text.length) }
                startWorker(pendenz)

                val id = pendenz.enqueue(scoreTicket, Ticket(1, "hello"))

                // Assert
                val record = awaitStatus(pendenz, id, TaskStatus.SUCCEEDED)
                assertThat(score(record.result)).isEqualTo(Score(5))
                assertThat(
                    db.count("""SELECT (result->>'value')::bigint FROM "${table.name}" WHERE id = $id"""),
                ).isEqualTo(5)
            }

        @Test
        fun `worker - refused result - REJECTED with the result kept, the reason in last_error and no retry`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { _, _ -> Score(500) }
                startWorker(pendenz)

                val id = pendenz.enqueue(scoreTicket, Ticket(1, "x"))

                // Assert
                val record = awaitStatus(pendenz, id, TaskStatus.REJECTED)
                assertThat(score(record.result)).isEqualTo(Score(500))
                assertThat(record.lastError).isEqualTo("score 500 out of range")
                assertThat(record.attempts).isEqualTo(1)
                assertThat(record.lockedBy).isNull()
                assertThat(record.finishedAt).isNotNull()
            }

        @Test
        fun `restart - REJECTED task after the handler was fixed - runs again and SUCCEEDED on the second attempt`() =
            runBlocking<Unit> {
                // Arrange
                val fixed = AtomicBoolean(false)
                val pendenz = pendenz { _, _ -> Score(if (fixed.get()) 42 else 500) }
                startWorker(pendenz)
                val id = pendenz.enqueue(scoreTicket, Ticket(1, "x"))
                awaitStatus(pendenz, id, TaskStatus.REJECTED)
                fixed.set(true)

                val restarted = pendenz.restart(id, actor = "ada")

                // Assert
                assertThat(restarted).isTrue()
                val record = awaitStatus(pendenz, id, TaskStatus.SUCCEEDED)
                assertThat(score(record.result)).isEqualTo(Score(42))
                assertThat(record.attempts).isEqualTo(2)
            }
    }

    @Nested
    inner class Provenance {
        @Test
        fun `worker - handler attaches provenance - SQL can filter on it in the jsonb column`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz =
                    pendenz { ctx, _ ->
                        ctx.provenance("model", "claude-sonnet-5-5")
                        ctx.provenance("rules", "2026-10")
                        Score(1)
                    }
                startWorker(pendenz)

                val id = pendenz.enqueue(scoreTicket, Ticket(1, "x"))

                // Assert
                val record = awaitStatus(pendenz, id, TaskStatus.SUCCEEDED)
                assertThat(record.provenance)
                    .containsExactlyEntriesOf(mapOf("model" to "claude-sonnet-5-5", "rules" to "2026-10"))
                assertThat(
                    db.count(
                        """SELECT count(*) FROM "${table.name}" WHERE provenance->>'model' = 'claude-sonnet-5-5'""",
                    ),
                ).isEqualTo(1)
            }
    }

    @Nested
    inner class Preview {
        @Test
        fun `enqueue - type with a preview - the preview column holds it before the task runs`() =
            runBlocking<Unit> {
                // Arrange
                val previewed = TaskType.json<Ticket>("preview-ticket", preview = { "ticket #${it.id}: ${it.text}" })
                val pendenz =
                    Pendenz.create(table) {
                        dataSource(db.dataSource)
                        register(previewed) { _, _ -> }
                    }

                val id = pendenz.enqueue(previewed, Ticket(7, "printer on fire"))

                // Assert
                assertThat(pendenz.find(id)!!.preview).isEqualTo("ticket #7: printer on fire")
                assertThat(
                    db.count("""SELECT count(*) FROM "${table.name}" WHERE preview = 'ticket #7: printer on fire'"""),
                ).isEqualTo(1)
            }
    }

    /** The row holds the result as jsonb, which PostgreSQL re-serialises in its own spacing, so decode it. */
    private fun score(result: String?): Score = Json.decodeFromString(Score.serializer(), result!!)

    private fun pendenz(handler: ResultHandler<Long, Ticket, Score>): Pendenz<Long> =
        Pendenz.create(table) {
            dataSource(db.dataSource)
            register(scoreTicket, inRange, handler)
        }

    private fun startWorker(pendenz: Pendenz<Long>): Job {
        val options = WorkerOptions(parallelism = 1, pollInterval = Duration.ofMillis(50))
        return pendenz.startWorker(scope, options).also { workers += it }
    }

    companion object {
        @JvmField
        @RegisterExtension
        val db = PostgresDatabase()
    }
}
