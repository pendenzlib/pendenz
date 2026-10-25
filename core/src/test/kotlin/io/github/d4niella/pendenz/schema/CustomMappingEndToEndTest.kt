package io.github.d4niella.pendenz.schema

import io.github.d4niella.pendenz.CycleTasks
import io.github.d4niella.pendenz.ExponentialBackoff
import io.github.d4niella.pendenz.NoRetry
import io.github.d4niella.pendenz.Pendenz
import io.github.d4niella.pendenz.TaskResource
import io.github.d4niella.pendenz.TaskStatus
import io.github.d4niella.pendenz.TaskType
import io.github.d4niella.pendenz.testing.PostgresDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * Principle 2 taken at its word: a schema of the application's own, tables
 * and every column renamed, in mixed case and with reserved words. The
 * library quotes every name and uses it exactly as given (ADR 0028 for
 * the derived names).
 */
class CustomMappingEndToEndTest {
    @Serializable
    data class Invoice(
        val number: String,
    )

    private lateinit var schema: String
    private lateinit var table: TaskTable<Long>
    private lateinit var cycles: CycleTable<Long>
    private lateinit var limits: ResourceLimitTable

    @BeforeEach
    fun createSchema() {
        schema = "Billing_${db.uniqueSuffix()}"
        table = TaskTable(name = "$schema.Invoice Tasks", columns = renamed)
        cycles =
            CycleTable(
                name = "$schema.Runs",
                columns = CycleColumns("Nr", "Kind", "State", "Note", "Created", "Creator", "Done", "Rev"),
            )
        limits = ResourceLimitTable(name = "$schema.Limits", columns = ResourceLimitColumns("Target", "Most"))
        db.sql("""CREATE SCHEMA "$schema"""")
    }

    @Test
    fun `run with a retry - renamed tables and columns in their own schema - succeeds on the second attempt`() =
        runBlocking<Unit> {
            // Arrange
            val attempts = AtomicInteger()
            val immediately = ExponentialBackoff(initialDelay = Duration.ZERO, jitter = 0.0)
            val send = TaskType.json<Invoice>("send", retryPolicy = immediately)
            createTables(send)
            val pendenz =
                queue {
                    register(send) { _, _ -> check(attempts.incrementAndGet() > 1) { "mail server down" } }
                }
            val id = pendenz.enqueue(send, Invoice("2026-0101"), createdBy = "clerk")

            // Act
            pendenz.runPending()

            // Assert
            val record = pendenz.find(id)!!
            assertThat(record.status).isEqualTo(TaskStatus.SUCCEEDED)
            assertThat(record.attempts).isEqualTo(2)
        }

    @Test
    fun `cycle under a limit, then operator SQL - renamed mapping - every generated statement finds its columns`() =
        runBlocking<Unit> {
            // Arrange
            val send =
                TaskType.json<Invoice>(
                    "send",
                    retryPolicy = NoRetry,
                    resource = TaskResource.fixed("mail", maxRunning = 1),
                )
            createTables(send)
            db.sql(PostgresOperatorDdl.views(table))
            db.sql(PostgresOperatorDdl.functions(table, cycles))
            db.sql(PostgresDdl.cycleProgressView(table, cycles))
            val pendenz = queue { register(send) { _, _ -> error("rejected by the mail server") } }
            val cycle = pendenz.startCycle(send, CycleTasks.of(listOf(Invoice("a"), Invoice("b"))), createdBy = "ops")

            // Act
            pendenz.runPending()

            // Assert
            val t = "\"$schema\".\"Invoice Tasks\""
            val quarantine = "\"$schema\".\"Invoice Tasks_quarantine\""
            val first = db.long("""SELECT min("Nr") FROM $t""")
            val version = db.long("""SELECT "Rev" FROM $t WHERE "Nr" = $first""")
            assertThat(pendenz.cycleProgress(cycle)).containsEntry(TaskStatus.FAILED, 2)
            assertThat(db.count("""SELECT count(*) FROM "$schema"."Runs_progress"""")).isEqualTo(1)
            assertThat(db.count("""SELECT count(*) FROM "$schema"."Invoice Tasks_needs_human"""")).isEqualTo(2)
            assertThat(db.long("""SELECT $quarantine($first, $version, 'held')::int""")).isEqualTo(1)
        }

    @Test
    fun `notify trigger - tables of one name in two schemas - both install and announce on their own channels`() {
        // Arrange
        val other = "${schema}_other"
        db.sql("""CREATE SCHEMA "$other"""")
        val here = TaskTable(name = "$schema.task")
        val there = TaskTable(name = "$other.task")
        listOf(here, there).forEach { db.sql(PostgresDdl.createTable(it)) }

        // Act
        listOf(here, there).forEach { db.sql(PostgresDdl.notifyTrigger(it)) }

        // Assert
        assertThat(PostgresDdl.notifyChannel(here)).isNotEqualTo(PostgresDdl.notifyChannel(there))
        val insert =
            """INSERT INTO "$other".task (task_type, status, run_at, payload) VALUES ('t', 'PENDING', now(), '{}')"""
        assertThat(db.sql(insert)).isEqualTo(1)
    }

    private fun createTables(vararg types: TaskType<*>) {
        db.sql(PostgresDdl.createTable(table, *types))
        db.sql(PostgresDdl.createCycleTable(cycles))
        db.sql(PostgresDdl.createResourceLimitTable(limits))
    }

    private fun queue(configure: Pendenz.Builder<Long>.() -> Unit): Pendenz<Long> =
        Pendenz.create(table) {
            dataSource(db.dataSource)
            enableCycles(cycles)
            enableResourceLimits(limits)
            configure()
        }

    companion object {
        /** Every concept under a name of its own: mixed case, a space-free reserved word, nothing like the defaults. */
        private val renamed =
            TaskColumns(
                id = "Nr",
                type = "Kind",
                status = "State",
                runAt = "Due",
                attempts = "Tries",
                lastError = "Error",
                lockedBy = "Holder",
                lockedUntil = "HeldUntil",
                version = "Rev",
                createdAt = "Created",
                idempotencyKey = "Key",
                createdBy = "Creator",
                payloadVersion = "Shape",
                finishedAt = "Done",
                lane = "Group",
                priority = "order",
                resource = "Target",
                approvedBy = "Approver",
                approvedAt = "Approved",
                parentId = "Parent",
                correlationId = "Matter",
                result = "Outcome",
                provenance = "Source",
                preview = "Summary",
            )

        @JvmField
        @RegisterExtension
        val db = PostgresDatabase()
    }
}
