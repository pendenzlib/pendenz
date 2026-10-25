package io.github.d4niella.pendenz.schema

import io.github.d4niella.pendenz.TaskQuery
import io.github.d4niella.pendenz.TaskStatus
import io.github.d4niella.pendenz.store.CycleSql
import io.github.d4niella.pendenz.store.PostgresDialect
import io.github.d4niella.pendenz.store.TaskSql
import io.github.d4niella.pendenz.testing.PostgresDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * The statements a worker runs on every poll or maintenance pass use the
 * indexes the generated DDL creates (ADR 0028). Read from their plans, with
 * sequential scans disabled, so a small test table says what a large one
 * would get; the SQL text itself is not looked at.
 */
class IndexUseEndToEndTest {
    private lateinit var table: TaskTable<Long>
    private lateinit var sql: TaskSql
    private lateinit var cycleSql: CycleSql
    private val now = Instant.now()

    @BeforeEach
    fun createFilledTable() {
        table = db.createTable("indexed")
        sql = PostgresDialect.statements(table, payloadColumnsOf(emptyList()), null)
        cycleSql = PostgresDialect.cycleStatements(table, CycleTable(IdType.BIGINT, name = "${table.name}_cycle"))
        // A mix of states, lanes and creators, analysed, so the planner has statistics to weigh the indexes by
        db.sql(
            """
            INSERT INTO "${table.name}" (task_type, status, run_at, payload, lane, created_by, finished_at, locked_until)
            SELECT 'greet', (ARRAY['PENDING', 'SCHEDULED', 'RUNNING', 'SUCCEEDED'])[1 + i % 4],
                   now() + (i % 7 - 3) * interval '1 hour', '{}'::jsonb,
                   CASE WHEN i % 3 = 0 THEN 'cycle:' || i % 10 END, 'agent-' || i % 50,
                   CASE WHEN i % 4 = 3 THEN now() - i * interval '1 minute' END,
                   CASE WHEN i % 4 = 2 THEN now() + interval '1 minute' END
            FROM generate_series(1, 5000) AS i
            """.trimIndent(),
        )
        db.sql("""ANALYZE "${table.name}"""")
    }

    @Test
    fun `claimFromLane - group with a lane - claim index with lane and priority as conditions, no sort`() {
        val plan =
            plan(sql.claimFromLane, PENDING, now, "greet", "cycle:3", 0, 1, RUNNING, "w1", now.plusSeconds(60))

        // Assert
        assertThat(plan.indexes()).contains(index("_claim_idx"))
        assertThat(plan.indexConditions()).anySatisfy { assertThat(it).contains("lane", "priority") }
        assertThat(plan.nodeTypes()).doesNotContain("Seq Scan", "Sort")
    }

    @Test
    fun `claimWithoutLane - group of single tasks - claim index, no sort`() {
        val plan = plan(sql.claimWithoutLane, PENDING, now, "greet", 0, 1, RUNNING, "w1", now.plusSeconds(60))

        // Assert
        assertThat(plan.indexes()).contains(index("_claim_idx"))
        assertThat(plan.nodeTypes()).doesNotContain("Seq Scan", "Sort")
    }

    @Test
    fun `promoteDue - every poll - the due index`() {
        val plan = plan(sql.promoteDue, PENDING, "SCHEDULED", "RETRY_PENDING", now)

        assertThat(plan.indexes()).contains(index("_due_idx"))
    }

    @Test
    fun `expiredLocks - every poll - the index of running tasks`() {
        val plan = plan(sql.expiredLocks, RUNNING, now, arrayOf("greet"), 100)

        // Assert
        assertThat(plan.indexes()).contains(index("_running_idx"))
        assertThat(plan.nodeTypes()).doesNotContain("Seq Scan")
    }

    @Test
    fun `countLane - cycle progress - the lane index`() {
        val plan = plan(cycleSql.countLane, "cycle:3")

        assertThat(plan.indexes()).contains(index("_lane_idx"))
    }

    @Test
    fun `deleteFinished - retention pass - the retention index`() {
        val cutOff = now.minusSeconds(3600)

        val plan = plan(sql.deleteFinished, "greet", "SUCCEEDED", "CANCELLED", cutOff, cutOff, 1000)

        assertThat(plan.indexes()).contains(index("_retention_idx"))
    }

    @Test
    fun `search - tasks of one creator - the creator index in order, no sort`() {
        val plan = plan(sql.search(TaskQuery(createdBy = "agent-7")), "agent-7", 20)

        // Assert
        assertThat(plan.indexes()).contains(index("_creator_idx"))
        assertThat(plan.nodeTypes()).doesNotContain("Seq Scan", "Sort")
    }

    private fun index(suffix: String) = table.name + suffix

    /** The JSON plan of [statement] with [values] bound in order, sequential scans disabled. */
    private fun plan(
        statement: String,
        vararg values: Any,
    ): String =
        db.dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute("SET enable_seqscan = off") }
            connection.prepareStatement("EXPLAIN (FORMAT JSON) $statement").use { explain ->
                values.forEachIndexed { i, value ->
                    when (value) {
                        is Instant -> explain.setObject(i + 1, OffsetDateTime.ofInstant(value, ZoneOffset.UTC))
                        is Array<*> -> explain.setArray(i + 1, connection.createArrayOf("text", value))
                        else -> explain.setObject(i + 1, value)
                    }
                }
                explain.executeQuery().use { rs ->
                    rs.next()
                    rs.getString(1)
                }
            }
        }

    private fun String.values(key: String): List<String> =
        Regex("\"$key\": \"((?:[^\"\\\\]|\\\\.)*)\"").findAll(this).map { it.groupValues[1] }.toList()

    private fun String.indexes() = values("Index Name")

    private fun String.indexConditions() = values("Index Cond")

    private fun String.nodeTypes() = values("Node Type")

    companion object {
        private val PENDING = TaskStatus.PENDING.name
        private val RUNNING = TaskStatus.RUNNING.name

        @JvmField
        @RegisterExtension
        val db = PostgresDatabase()
    }
}
