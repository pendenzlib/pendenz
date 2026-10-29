package io.github.pendenzlib

import io.github.pendenzlib.schema.TaskTable
import io.github.pendenzlib.testing.MutableClock
import io.github.pendenzlib.testing.PostgresDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/** Recurring tasks against PostgreSQL (ADR 0033): a polling worker, and two queues sharing one table. */
class RecurringTaskEndToEndTest {
    @Serializable
    data class Probe(
        val target: String,
    )

    private val healthCheck = TaskType.json<Probe>("health-check", retryPolicy = NoRetry)
    private val checked = CopyOnWriteArrayList<Instant>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val workers = mutableListOf<Job>()
    private lateinit var table: TaskTable<Long>

    @BeforeEach
    fun createTable() {
        table = db.createTable("recurring")
    }

    @AfterEach
    fun stopWorkers() =
        runBlocking<Unit> {
            workers.forEach { it.cancelAndJoin() }
        }

    @Test
    fun `worker - schedule every second - occurrences run one after the other, one always ahead`() =
        runBlocking<Unit> {
            val pendenz = pendenz(Clock.systemUTC())

            // Act
            workers += pendenz.startWorker(scope, WorkerOptions(pollInterval = Duration.ofMillis(50)))

            // Assert
            await atMost Duration.ofSeconds(10) untilAsserted {
                val statuses = runBlocking { occurrences(pendenz) }.map { it.status }
                assertThat(statuses.count { it == TaskStatus.SUCCEEDED }).isGreaterThanOrEqualTo(2)
                assertThat(statuses.filterNot { it == TaskStatus.SUCCEEDED }).hasSize(1)
            }
            assertThat(checked).doesNotHaveDuplicates()
        }

    @Test
    fun `runMaintenance - two queues on one table, their clocks a fire time apart - one occurrence`() =
        runBlocking<Unit> {
            // Arrange: one clock just before the hour, the other just after it
            val hourly = Schedule.every(Duration.ofHours(1))
            val early = pendenz(MutableClock(Instant.parse("2026-10-19T09:59:59Z")), hourly)
            val late = pendenz(MutableClock(Instant.parse("2026-10-19T10:00:01Z")), hourly)

            listOf(early, late).map { async(Dispatchers.IO) { it.runMaintenance() } }.awaitAll()

            // Assert
            val occurrence = occurrences(early).single()
            assertThat(occurrence.status).isEqualTo(TaskStatus.SCHEDULED)
            assertThat(occurrence.idempotencyKey).isEqualTo("schedule:health@${occurrence.runAt}")
        }

    private suspend fun occurrences(pendenz: Pendenz<Long>): List<TaskRecord<Long>> =
        pendenz.tasks(TaskQuery(createdBy = "schedule:health"))

    private fun pendenz(
        clock: Clock,
        schedule: Schedule = Schedule.every(Duration.ofSeconds(1)),
    ): Pendenz<Long> =
        Pendenz.create(table) {
            dataSource(db.dataSource)
            clock(clock)
            register(healthCheck) { ctx, _ -> checked += ctx.dueAt }
            schedule("health", healthCheck, Probe("billing-api"), schedule)
        }

    companion object {
        @JvmField
        @RegisterExtension
        val db = PostgresDatabase()
    }
}
