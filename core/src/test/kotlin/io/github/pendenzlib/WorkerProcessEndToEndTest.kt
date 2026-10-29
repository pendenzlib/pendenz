package io.github.pendenzlib

import io.github.pendenzlib.schema.TaskTable
import io.github.pendenzlib.testing.PostgresDatabase
import io.github.pendenzlib.testing.WorkerProcess
import io.github.pendenzlib.testing.WorkerProcess.Handover
import io.github.pendenzlib.testing.awaitStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Duration
import java.time.Instant

/**
 * Two JVMs on one table. The heartbeat keeps a living worker's task away
 * from everyone else (ADR 0002); once a dead worker's lock has expired,
 * the next worker records the lost attempt as failed and runs the retry
 * to its end exactly once (ADR 0026, ADR 0027).
 */
class WorkerProcessEndToEndTest {
    private lateinit var table: TaskTable<Long>
    private lateinit var logTable: String
    private var other: Process? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val workers = mutableListOf<Job>()

    @BeforeEach
    fun createTables() {
        table = db.createTable("handover")
        logTable = "completion_${db.uniqueSuffix()}"
        db.sql("""CREATE TABLE "$logTable" (worker_id text NOT NULL)""")
    }

    @AfterEach
    fun stopEverything() =
        runBlocking<Unit> {
            other?.destroyForcibly()?.waitFor()
            workers.forEach { it.cancelAndJoin() }
        }

    @Test
    fun `worker - other JVM runs the task and heartbeats - leaves it alone well past the lock timeout`() =
        runBlocking<Unit> {
            // Arrange
            val pendenz = pendenz()
            val id = pendenz.enqueue(WorkerProcess.HANDOVER, Handover("Ada"))
            other = WorkerProcess.start(db, table, logTable, workerId = OTHER)
            awaitLockedBy(pendenz, id, OTHER)

            // Act
            startWorker(pendenz)

            // Assert
            delay(WorkerProcess.LOCK_TIMEOUT.toMillis() * 3) // fixed wait on purpose: nothing may happen meanwhile
            val record = pendenz.find(id)!!
            assertThat(record.status).isEqualTo(TaskStatus.RUNNING)
            assertThat(record.lockedBy).isEqualTo(OTHER)
            assertThat(record.attempts).isEqualTo(1)
            assertThat(completionsBy(THIS)).isZero()
        }

    @Test
    fun `worker - other JVM killed mid-task - takes it over after the lock timeout, completes it once`() =
        runBlocking<Unit> {
            // Arrange
            val pendenz = pendenz()
            val id = pendenz.enqueue(WorkerProcess.HANDOVER, Handover("Bob"))
            val victim = WorkerProcess.start(db, table, logTable, workerId = OTHER).also { other = it }
            awaitLockedBy(pendenz, id, OTHER)
            victim.destroyForcibly().waitFor()

            // Act
            startWorker(pendenz)

            // Assert
            val record = awaitStatus(pendenz, id, TaskStatus.SUCCEEDED)
            assertThat(record.attempts).isEqualTo(2)
            assertThat(record.lockedBy).isNull()
            assertThat(completionsBy(THIS)).isEqualTo(1)
            assertThat(db.count("""SELECT count(*) FROM "$logTable"""")).isEqualTo(1)
        }

    private fun pendenz(): Pendenz<Long> =
        Pendenz.create(table) {
            dataSource(db.dataSource)
            workerId(THIS)
            register(WorkerProcess.HANDOVER) { _, _ -> WorkerProcess.logCompletion(db.dataSource, logTable, THIS) }
        }

    private fun startWorker(pendenz: Pendenz<Long>): Job {
        val options =
            WorkerOptions(
                pollInterval = WorkerProcess.POLL,
                heartbeatInterval = WorkerProcess.HEARTBEAT,
                lockTimeout = WorkerProcess.LOCK_TIMEOUT,
            )
        return pendenz.startWorker(scope, options).also { workers += it }
    }

    /**
     * The other JVM needs a few seconds to start; the row shows when it has claimed the task, and a lock
     * renewed after the claim shows that its heartbeat runs. A cold JVM can take longer than the lock
     * timeout for its first heartbeat, so the test's own worker starts only once that has happened.
     */
    private fun awaitLockedBy(
        pendenz: Pendenz<Long>,
        id: Long,
        workerId: String,
    ) {
        var claimedUntil: Instant? = null
        await atMost Duration.ofSeconds(10) untilAsserted {
            val record = runBlocking { pendenz.find(id)!! }
            assertThat(record.status).isEqualTo(TaskStatus.RUNNING)
            assertThat(record.lockedBy).isEqualTo(workerId)
            claimedUntil = record.lockedUntil
        }
        await atMost Duration.ofSeconds(10) untilAsserted {
            val record = runBlocking { pendenz.find(id)!! }
            assertThat(record.lockedBy).isEqualTo(workerId)
            assertThat(record.lockedUntil).isAfter(claimedUntil)
        }
    }

    private fun completionsBy(workerId: String): Long =
        db.count("""SELECT count(*) FROM "$logTable" WHERE worker_id = '$workerId'""")

    companion object {
        private const val THIS = "this-jvm"
        private const val OTHER = "other-jvm"

        @JvmField
        @RegisterExtension
        val db = PostgresDatabase()
    }
}
