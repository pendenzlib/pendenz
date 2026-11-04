package io.github.pendenzlib

import io.github.pendenzlib.schema.PostgresDdl
import io.github.pendenzlib.schema.ResourceBlockTable
import io.github.pendenzlib.schema.ResourceLimitTable
import io.github.pendenzlib.schema.TaskTable
import io.github.pendenzlib.testing.MutableClock
import io.github.pendenzlib.testing.PostgresDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/** Deferrals, hand-overs and resource blocks against PostgreSQL (ADR 0035), driven by runPending on a set clock. */
class HandlerDecisionEndToEndTest {
    @Serializable
    data class Sync(
        val customer: String,
    )

    private val sync = TaskType.json<Sync>("sync-customer", retryPolicy = NoRetry)
    private val clock = MutableClock(Instant.parse("2026-11-04T09:00:00Z"))
    private val inAnHour = Instant.parse("2026-11-04T10:00:00Z")
    private val ran = CopyOnWriteArrayList<String>()
    private lateinit var table: TaskTable<Long>
    private lateinit var blockTable: ResourceBlockTable

    @BeforeEach
    fun createTables() {
        table = db.createTable("decision")
        blockTable = ResourceBlockTable(db.uniqueName("block"))
        db.sql(PostgresDdl.createResourceBlockTable(blockTable))
    }

    @Nested
    inner class Outcomes {
        @Test
        fun `retry later - on PostgreSQL - RETRY_PENDING at that time, the attempt taken back`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { _, _ -> throw RetryLaterException(inAnHour, "export not ready") }
                val id = pendenz.enqueue(sync, Sync("acme"))

                pendenz.runPending()

                // Assert
                val task = pendenz.find(id)!!
                assertThat(task.status).isEqualTo(TaskStatus.RETRY_PENDING)
                assertThat(task.runAt).isEqualTo(inAnHour)
                assertThat(task.attempts).isZero()
                assertThat(task.lockedBy).isNull()
                assertThat(task.lastError).isEqualTo("deferred until $inAnHour: export not ready")
            }

        @Test
        fun `hand over - on PostgreSQL - QUARANTINED with the reason and no finish time`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { _, _ -> throw HandOverException("lines do not add up") }
                val id = pendenz.enqueue(sync, Sync("acme"))

                pendenz.runPending()

                // Assert
                val task = pendenz.find(id)!!
                assertThat(task.status).isEqualTo(TaskStatus.QUARANTINED)
                assertThat(task.lastError).isEqualTo("lines do not add up")
                assertThat(task.finishedAt).isNull()
            }
    }

    @Nested
    inner class Blocks {
        @Test
        fun `resource unavailable - other task of the resource - not claimed before blocked_until, then runs`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = quotaUntil(inAnHour)
                pendenz.enqueue(sync, Sync("acme"), resource = "partner-api")
                pendenz.enqueue(sync, Sync("globex"), resource = "partner-api")
                pendenz.runPending()
                assertThat(ran).containsExactly("acme")
                clock.now = inAnHour

                pendenz.runPending()

                // Assert
                assertThat(ran).containsExactlyInAnyOrder("acme", "acme", "globex")
                assertThat(db.count("SELECT count(*) FROM ${table.name} WHERE status = 'SUCCEEDED'")).isEqualTo(2)
            }

        @Test
        fun `block - pattern row inserted by an operator with SQL - every matching resource waits`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { _, s -> ran += s.customer }
                db.sql(
                    "INSERT INTO ${blockTable.name} (name, blocked_until, reason) " +
                        "VALUES ('customer:%', '$inAnHour', 'maintenance window')",
                )
                pendenz.enqueue(sync, Sync("acme"), resource = "customer:acme")
                pendenz.enqueue(sync, Sync("globex"), resource = "customer:globex")
                pendenz.enqueue(sync, Sync("ledger"), resource = "ledger")

                pendenz.runPending()

                assertThat(ran).containsExactly("ledger")
            }

        @Test
        fun `block - later report, then an earlier one - blocked_until keeps the later time`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { _, _ -> }
                pendenz.blockResource("partner-api", Instant.parse("2026-11-04T12:00:00Z"), "daily quota")

                pendenz.blockResource("partner-api", inAnHour, "hourly quota")

                // Assert
                val block = pendenz.resourceBlocks().single()
                assertThat(block.blockedUntil).isEqualTo(Instant.parse("2026-11-04T12:00:00Z"))
                assertThat(block.reason).isEqualTo("daily quota")
            }

        @Test
        fun `block - earlier report, then a later one - blocked_until moves to the later time`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { _, _ -> }
                pendenz.blockResource("partner-api", inAnHour, "hourly quota")

                pendenz.blockResource("partner-api", Instant.parse("2026-11-04T12:00:00Z"), "daily quota")

                assertThat(pendenz.resourceBlocks().single().blockedUntil)
                    .isEqualTo(Instant.parse("2026-11-04T12:00:00Z"))
            }

        @Test
        fun `block - operator deletes the row with SQL - the resource's task runs at the next poll`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { _, s -> ran += s.customer }
                pendenz.blockResource("partner-api", inAnHour, "quota used up")
                pendenz.enqueue(sync, Sync("acme"), resource = "partner-api")
                pendenz.runPending()
                db.sql("DELETE FROM ${blockTable.name} WHERE name = 'partner-api'")

                pendenz.runPending()

                assertThat(ran).containsExactly("acme")
            }

        @Test
        fun `runMaintenance - block whose time has passed - its row is deleted`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { _, _ -> }
                pendenz.blockResource("partner-api", inAnHour, "quota used up")
                clock.now = inAnHour

                pendenz.runMaintenance()

                assertThat(db.count("SELECT count(*) FROM ${blockTable.name}")).isZero()
            }

        @Test
        fun `resource unavailable - task cancelled by an operator meanwhile - outcome dropped, no block written`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz =
                    pendenz { ctx, _ ->
                        db.sql(
                            "UPDATE ${table.name} SET status = 'CANCELLED', version = version + 1 WHERE id = ${ctx.id}",
                        )
                        throw ResourceUnavailableException(inAnHour, "quota used up")
                    }
                pendenz.enqueue(sync, Sync("acme"), resource = "partner-api")

                pendenz.runPending()

                assertThat(db.count("SELECT count(*) FROM ${blockTable.name}")).isZero()
            }

        @Test
        fun `resource unavailable - blocks not enabled - the task is deferred, the next one of the resource runs`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = quotaUntil(inAnHour, blocks = false)
                val first = pendenz.enqueue(sync, Sync("acme"), resource = "partner-api")
                pendenz.runPending()
                pendenz.enqueue(sync, Sync("globex"), resource = "partner-api")

                pendenz.runPending()

                // Assert
                assertThat(pendenz.find(first)!!.status).isEqualTo(TaskStatus.RETRY_PENDING)
                assertThat(ran).containsExactly("acme", "globex")
            }

        @Test
        fun `block - resource limits enabled too - the blocked resource waits, a limited one still runs`() =
            runBlocking<Unit> {
                // Arrange
                val limitTable = ResourceLimitTable(db.uniqueName("limit"))
                db.sql(PostgresDdl.createResourceLimitTable(limitTable))
                val pendenz = pendenz(limitTable) { _, s -> ran += s.customer }
                pendenz.setResourceLimit("ledger", 1)
                pendenz.blockResource("partner-api", inAnHour, "quota used up")
                pendenz.enqueue(sync, Sync("acme"), resource = "partner-api")
                pendenz.enqueue(sync, Sync("globex"), resource = "ledger")

                pendenz.runPending()

                assertThat(ran).containsExactly("globex")
            }
    }

    /** A partner API whose quota is used up until [until]: every call before then reports it. */
    private fun quotaUntil(
        until: Instant,
        blocks: Boolean = true,
    ): Pendenz<Long> =
        pendenz(blocks = blocks) { _, s ->
            ran += s.customer
            if (clock.now.isBefore(until)) throw ResourceUnavailableException(until, "quota used up")
        }

    private fun pendenz(
        limitTable: ResourceLimitTable? = null,
        blocks: Boolean = true,
        handler: TaskHandler<Long, Sync>,
    ): Pendenz<Long> =
        Pendenz.create(table) {
            dataSource(db.dataSource)
            clock(clock)
            if (blocks) enableResourceBlocks(blockTable)
            if (limitTable != null) enableResourceLimits(limitTable)
            register(sync, handler)
        }

    companion object {
        @JvmField
        @RegisterExtension
        val db = PostgresDatabase()
    }
}
