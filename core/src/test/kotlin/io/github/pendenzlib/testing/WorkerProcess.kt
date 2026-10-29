package io.github.pendenzlib.testing

import io.github.pendenzlib.ExponentialBackoff
import io.github.pendenzlib.Pendenz
import io.github.pendenzlib.TaskType
import io.github.pendenzlib.WorkerOptions
import io.github.pendenzlib.schema.TaskTable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.io.File
import java.time.Duration
import javax.sql.DataSource
import kotlin.system.exitProcess

/**
 * A worker in a JVM of its own, for tests that need a second process on
 * the same table: one that can be killed without warning. Its handler
 * for [HANDOVER] never finishes on its own, so whatever completes the
 * task did so in another process; a completion writes the worker's id
 * into the log table, so the test can see who did.
 */
object WorkerProcess {
    @Serializable
    data class Handover(
        val name: String,
    )

    /** A killed worker's attempt counts as failed (ADR 0026); the retry follows one second later, no jitter. */
    val HANDOVER: TaskType<Handover> =
        TaskType.json<Handover>("handover", retryPolicy = ExponentialBackoff(jitter = 0.0))

    /**
     * Short enough for a test, long enough that a living worker sends many heartbeats per timeout, and
     * that one stalled for a second on a busy CI runner still keeps its lock.
     */
    val LOCK_TIMEOUT: Duration = Duration.ofSeconds(2)
    val HEARTBEAT: Duration = Duration.ofMillis(100)
    val POLL: Duration = Duration.ofMillis(100)

    /** Keeps the process from outliving a test that crashed before killing it. */
    private const val LIFETIME_MILLIS = 60_000L
    private const val HANDLER_MILLIS = 60_000L

    /** Starts the worker JVM on the test's own classpath; stdout and stderr go to the test's. */
    fun start(
        db: PostgresDatabase,
        table: TaskTable<Long>,
        logTable: String,
        workerId: String,
    ): Process {
        val java =
            ProcessHandle.current().info().command().orElse(
                File(System.getProperty("java.home"), "bin/java").path,
            )
        val command =
            listOf(
                java,
                "-cp",
                System.getProperty("java.class.path"),
                WorkerProcess::class.java.name,
                db.jdbcUrl,
                db.username,
                db.password,
                table.name,
                logTable,
                workerId,
            )
        return ProcessBuilder(command).inheritIO().start()
    }

    /** Records that [workerId] completed a task. Both processes call this from their handler. */
    fun logCompletion(
        dataSource: DataSource,
        logTable: String,
        workerId: String,
    ) {
        dataSource.connection.use { c ->
            c.prepareStatement("""INSERT INTO "$logTable" (worker_id) VALUES (?)""").use {
                it.setString(1, workerId)
                it.executeUpdate()
            }
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val (jdbcUrl, username, password) = args
        val tableName = args[3]
        val logTable = args[4]
        val workerId = args[5]
        val dataSource = PostgresDatabase.Access(jdbcUrl, username, password).dataSource()
        val pendenz =
            Pendenz.create(TaskTable(name = tableName)) {
                dataSource(dataSource)
                workerId(workerId)
                register(HANDOVER) { _, _ ->
                    delay(HANDLER_MILLIS) // never ends within a test: only a kill ends this handler
                    logCompletion(dataSource, logTable, workerId)
                }
            }
        val options = WorkerOptions(pollInterval = POLL, heartbeatInterval = HEARTBEAT, lockTimeout = LOCK_TIMEOUT)
        runBlocking { withTimeoutOrNull(LIFETIME_MILLIS) { pendenz.runWorker(options) } }
        exitProcess(0)
    }
}
