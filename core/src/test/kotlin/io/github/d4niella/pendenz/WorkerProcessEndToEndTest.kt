package io.github.d4niella.pendenz

import io.github.d4niella.pendenz.schema.TaskTable
import io.github.d4niella.pendenz.testing.PostgresDatabase
import io.github.d4niella.pendenz.testing.WorkerProcess
import io.github.d4niella.pendenz.testing.WorkerProcess.Handover
import io.github.d4niella.pendenz.testing.awaitStatus
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

/**
 * Two JVMs on one table. The heartbeat keeps a living worker's task away
 * from everyone else (ADR 0002); the lock timeout hands a dead worker's
 * task to the next one, which runs it to its end exactly once (ADR 0005).
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
        logTable = "completion_${System.nanoTime().toString(36)}"
        db.sql("""CREATE TABLE "$logTable" (worker_id text NOT NULL)""")
    }

    @AfterEach
    fun stopEverything() =
        runBlocking<Unit> {
            other?.destroyForcibly()?.waitFor()
            workers.forEach { it.cancelAndJoin() }
        }

    @Test
    fun `other JVM runs the task and heartbeats - this worker leaves it alone well past the lock timeout`() =
        runBlocking<Unit> {
            // Arrange
            val pendenz = pendenz()
            val id = pendenz.enqueue(WorkerProcess.HANDOVER, Handover("Ada"))
            other = WorkerProcess.start(db, table, logTable, workerId = OTHER)
            awaitLockedBy(pendenz, id, OTHER)

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
    fun `other JVM killed mid-task - this worker takes it over after the lock timeout and completes it exactly once`() =
        runBlocking<Unit> {
            // Arrange
            val pendenz = pendenz()
            val id = pendenz.enqueue(WorkerProcess.HANDOVER, Handover("Bob"))
            val victim = WorkerProcess.start(db, table, logTable, workerId = OTHER).also { other = it }
            awaitLockedBy(pendenz, id, OTHER)
            victim.destroyForcibly().waitFor()

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

    /** The other JVM needs a few seconds to start; the row shows when it has claimed the task. */
    private fun awaitLockedBy(
        pendenz: Pendenz<Long>,
        id: Long,
        workerId: String,
    ) {
        await atMost Duration.ofSeconds(10) untilAsserted {
            val record = runBlocking { pendenz.find(id)!! }
            assertThat(record.status).isEqualTo(TaskStatus.RUNNING)
            assertThat(record.lockedBy).isEqualTo(workerId)
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
