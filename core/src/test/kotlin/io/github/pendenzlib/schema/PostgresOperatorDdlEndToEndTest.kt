package io.github.pendenzlib.schema

import io.github.pendenzlib.Approval
import io.github.pendenzlib.CycleStatus
import io.github.pendenzlib.CycleTasks
import io.github.pendenzlib.NoRetry
import io.github.pendenzlib.Pendenz
import io.github.pendenzlib.TaskStatus
import io.github.pendenzlib.TaskType
import io.github.pendenzlib.testing.PostgresDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Duration
import java.time.Instant

/** The generated operator views and functions against PostgreSQL, driven through runPending and SQL. */
class PostgresOperatorDdlEndToEndTest {
    @Serializable
    data class Greeting(
        val name: String,
    )

    private val greet = TaskType.json<Greeting>("greet", retryPolicy = NoRetry)
    private val vetted = TaskType.json<Greeting>("vetted", approval = Approval.ALWAYS)
    private val previewed =
        TaskType.json<Greeting>("previewed", approval = Approval.ALWAYS, preview = {
            "Greet ${it.name}"
        })
    private lateinit var table: TaskTable<Long>
    private lateinit var cycleTable: CycleTable<Long>
    private lateinit var pendenz: Pendenz<Long>

    @BeforeEach
    fun createSchema() {
        val suffix = db.uniqueSuffix()
        table = TaskTable(name = "task_$suffix")
        cycleTable = CycleTable(name = "cycle_$suffix")
        db.sql(PostgresDdl.createTable(table, greet, vetted, previewed))
        db.sql(PostgresDdl.createCycleTable(cycleTable))
        db.sql(PostgresOperatorDdl.views(table, stuckAfter = Duration.ofMinutes(2)))
        db.sql(PostgresOperatorDdl.functions(table, cycleTable))
        pendenz =
            Pendenz.create(table) {
                dataSource(db.dataSource)
                enableCycles(cycleTable)
                register(greet) { _, greeting -> check(greeting.name != "Mallory") { "refused: ${greeting.name}" } }
                register(vetted) { _, _ -> }
                register(previewed) { _, _ -> }
            }
    }

    private val t get() = "\"${table.name}\""

    private fun call(
        function: String,
        arguments: String,
    ): Long = db.long("""SELECT "$function"($arguments)::int""")

    @Nested
    inner class Views {
        @Test
        fun `failures view - two tasks failed with the same error - one row counting both`() =
            runBlocking<Unit> {
                // Arrange
                repeat(2) { pendenz.enqueue(greet, Greeting("Mallory")) }
                pendenz.enqueue(greet, Greeting("Ada"))

                // Act
                pendenz.runPending()

                // Assert
                assertThat(db.count("""SELECT count(*) FROM "${table.name}_failures"""")).isEqualTo(1)
                assertThat(db.long("""SELECT tasks FROM "${table.name}_failures"""")).isEqualTo(2)
            }

        @Test
        fun `stuck view - lock expired ten minutes ago - is listed, a live lock is not`() =
            runBlocking<Unit> {
                // Arrange
                val stale = pendenz.enqueue(greet, Greeting("Ada"))
                val fresh = pendenz.enqueue(greet, Greeting("Bob"))
                val running = "UPDATE $t SET status = 'RUNNING', locked_by = 'w'"
                db.sql("$running, locked_until = now() - interval '10 minutes' WHERE id = $stale")
                db.sql("$running, locked_until = now() + interval '1 minute' WHERE id = $fresh")

                // Act
                val stuck = db.count("""SELECT count(*) FROM "${table.name}_stuck"""")

                // Assert
                assertThat(stuck).isEqualTo(1)
                assertThat(db.long("""SELECT id FROM "${table.name}_stuck"""")).isEqualTo(stale)
            }

        @Test
        fun `needs_human view - task awaiting approval with a preview - shows the preview`() =
            runBlocking<Unit> {
                // Arrange
                val id = pendenz.enqueue(previewed, Greeting("Ada"), createdBy = "ada")

                // Act
                val preview = db.string("""SELECT preview FROM "${table.name}_needs_human" WHERE id = $id""")

                // Assert
                assertThat(preview).isEqualTo("Greet Ada")
            }

        @Test
        fun `needs_human view - a failed and a quarantined task beside a scheduled one - lists the two`() =
            runBlocking<Unit> {
                // Arrange
                val later = Instant.now().plusSeconds(3600)
                pendenz.enqueue(greet, Greeting("Mallory"))
                val held = pendenz.enqueue(greet, Greeting("Ada"), runAt = later)
                db.sql("UPDATE $t SET status = 'QUARANTINED', last_error = 'held' WHERE id = $held")
                pendenz.enqueue(greet, Greeting("Bob"), runAt = later)
                pendenz.runPending()

                // Act
                val waiting = db.count("""SELECT count(*) FROM "${table.name}_needs_human"""")

                // Assert
                assertThat(waiting).isEqualTo(2)
            }
    }

    @Nested
    inner class Functions {
        @Test
        fun `restart - FAILED task with the version the caller saw - returns true and the task is PENDING`() =
            runBlocking<Unit> {
                // Arrange
                val id = pendenz.enqueue(greet, Greeting("Mallory"))
                pendenz.runPending()
                val version = pendenz.find(id)!!.version

                // Act
                val restarted = call("${table.name}_restart", "$id, $version")

                // Assert
                assertThat(restarted).isEqualTo(1)
                val record = pendenz.find(id)!!
                assertThat(record.status).isEqualTo(TaskStatus.PENDING)
                assertThat(record.version).isEqualTo(version + 1)
                assertThat(record.finishedAt).isNull()
            }

        @Test
        fun `restart - stale version - returns false and changes nothing`() =
            runBlocking<Unit> {
                // Arrange
                val id = pendenz.enqueue(greet, Greeting("Mallory"))
                pendenz.runPending()
                val version = pendenz.find(id)!!.version

                // Act
                val restarted = call("${table.name}_restart", "$id, ${version - 1}")

                // Assert
                assertThat(restarted).isZero()
                assertThat(pendenz.find(id)!!.status).isEqualTo(TaskStatus.FAILED)
            }

        @Test
        fun `restart - PENDING task - returns false, a human cannot restart what is not waiting for one`() =
            runBlocking<Unit> {
                // Arrange
                val id = pendenz.enqueue(greet, Greeting("Ada"))

                // Act
                val restarted = call("${table.name}_restart", "$id, 0")

                // Assert
                assertThat(restarted).isZero()
            }

        @Test
        fun `complete - FAILED task - SUCCEEDED with a finish time`() =
            runBlocking<Unit> {
                // Arrange
                val id = pendenz.enqueue(greet, Greeting("Mallory"))
                pendenz.runPending()
                val version = pendenz.find(id)!!.version

                // Act
                val completed = call("${table.name}_complete", "$id, $version")

                // Assert
                assertThat(completed).isEqualTo(1)
                val record = pendenz.find(id)!!
                assertThat(record.status).isEqualTo(TaskStatus.SUCCEEDED)
                assertThat(record.finishedAt).isNotNull()
            }

        @Test
        fun `quarantine - PENDING task with a reason - QUARANTINED carrying the reason`() =
            runBlocking<Unit> {
                // Arrange
                val id = pendenz.enqueue(greet, Greeting("Ada"))

                // Act
                val held = call("${table.name}_quarantine", "$id, 0, 'ticket #123'")

                // Assert
                assertThat(held).isEqualTo(1)
                val record = pendenz.find(id)!!
                assertThat(record.status).isEqualTo(TaskStatus.QUARANTINED)
                assertThat(record.lastError).isEqualTo("ticket #123")
            }

        @Test
        fun `cancel - PENDING task - CANCELLED with a finish time`() =
            runBlocking<Unit> {
                // Arrange
                val id = pendenz.enqueue(greet, Greeting("Ada"))

                // Act
                val cancelled = call("${table.name}_cancel", "$id, 0")

                // Assert
                assertThat(cancelled).isEqualTo(1)
                val record = pendenz.find(id)!!
                assertThat(record.status).isEqualTo(TaskStatus.CANCELLED)
                assertThat(record.finishedAt).isNotNull()
            }

        @Test
        fun `approve - by the creator - returns false and the task keeps waiting`() =
            runBlocking<Unit> {
                // Arrange
                val id = pendenz.enqueue(vetted, Greeting("Ada"), createdBy = "ada")

                // Act
                val approved = call("${table.name}_approve", "$id, 0, 'ada'")

                // Assert
                assertThat(approved).isZero()
                assertThat(pendenz.find(id)!!.status).isEqualTo(TaskStatus.AWAITING_APPROVAL)
            }

        @Test
        fun `approve - by someone else - the task is PENDING with the approver`() =
            runBlocking<Unit> {
                // Arrange
                val id = pendenz.enqueue(vetted, Greeting("Ada"), createdBy = "ada")

                // Act
                val approved = call("${table.name}_approve", "$id, 0, 'bob'")

                // Assert
                assertThat(approved).isEqualTo(1)
                val record = pendenz.find(id)!!
                assertThat(record.status).isEqualTo(TaskStatus.PENDING)
                assertThat(record.approvedBy).isEqualTo("bob")
                assertThat(record.approvedAt).isNotNull()
            }

        @Test
        fun `approve - NULL approver - returns false and the task keeps waiting, nobody approved it`() =
            runBlocking<Unit> {
                // Arrange
                val id = pendenz.enqueue(vetted, Greeting("Ada"), createdBy = "ada")

                // Act
                val approved = call("${table.name}_approve", "$id, 0, NULL")

                // Assert
                assertThat(approved).isZero()
                val record = pendenz.find(id)!!
                assertThat(record.status).isEqualTo(TaskStatus.AWAITING_APPROVAL)
                assertThat(record.approvedBy).isNull()
            }

        @Test
        fun `decline and quarantine - NULL actor or reason - return false and change nothing`() =
            runBlocking<Unit> {
                // Arrange
                val waiting = pendenz.enqueue(vetted, Greeting("Ada"), createdBy = "ada")
                val pending = pendenz.enqueue(greet, Greeting("Bob"))
                val calls =
                    listOf(
                        "_decline" to "$waiting, 0, NULL, 'not this month'",
                        "_decline" to "$waiting, 0, 'bob', NULL",
                        "_quarantine" to "$pending, 0, NULL",
                    )

                // Act
                val changed = calls.map { (function, arguments) -> call(table.name + function, arguments) }

                // Assert
                calls.zip(changed).forEach { (call, rows) ->
                    assertThat(rows).describedAs("%s(%s)", call.first, call.second).isZero()
                }
                assertThat(pendenz.find(waiting)!!.status).isEqualTo(TaskStatus.AWAITING_APPROVAL)
                assertThat(pendenz.find(pending)!!.status).isEqualTo(TaskStatus.PENDING)
            }

        @Test
        fun `restart - waiting task - returns false, the approval cannot be bypassed in SQL`() =
            runBlocking<Unit> {
                // Arrange
                val id = pendenz.enqueue(vetted, Greeting("Ada"), createdBy = "ada")

                // Act
                val restarted = call("${table.name}_restart", "$id, 0")

                // Assert
                assertThat(restarted).isZero()
                assertThat(pendenz.find(id)!!.status).isEqualTo(TaskStatus.AWAITING_APPROVAL)
            }

        @Test
        fun `quarantine - waiting task - returns false, the approval cannot be bypassed in SQL`() =
            runBlocking<Unit> {
                // Arrange
                val id = pendenz.enqueue(vetted, Greeting("Ada"), createdBy = "ada")

                // Act
                val held = call("${table.name}_quarantine", "$id, 0, 'later'")

                // Assert
                assertThat(held).isZero()
                assertThat(pendenz.find(id)!!.status).isEqualTo(TaskStatus.AWAITING_APPROVAL)
            }

        @Test
        fun `decline - waiting task - CANCELLED with the reason`() =
            runBlocking<Unit> {
                // Arrange
                val id = pendenz.enqueue(vetted, Greeting("Ada"), createdBy = "ada")

                // Act
                val declined = call("${table.name}_decline", "$id, 0, 'bob', 'not this month'")

                // Assert
                assertThat(declined).isEqualTo(1)
                val record = pendenz.find(id)!!
                assertThat(record.status).isEqualTo(TaskStatus.CANCELLED)
                assertThat(record.lastError).isEqualTo("not this month")
            }

        @Test
        fun `cycle cancel - stale version - returns false, cycle and tasks unchanged`() =
            runBlocking<Unit> {
                // Arrange
                val cycle = pendenz.startCycle(greet, CycleTasks.of(listOf(Greeting("Ada"), Greeting("Bob"))))

                // Act
                val cancelled = call("${cycleTable.name}_cancel", "$cycle, 7")

                // Assert
                assertThat(cancelled).isZero()
                assertThat(pendenz.findCycle(cycle)!!.status).isEqualTo(CycleStatus.ACTIVE)
                assertThat(pendenz.cycleProgress(cycle)).containsExactlyEntriesOf(mapOf(TaskStatus.PENDING to 2))
            }

        @Test
        fun `cycle cancel - ACTIVE cycle with pending tasks - cycle and tasks CANCELLED`() =
            runBlocking<Unit> {
                // Arrange
                val cycle = pendenz.startCycle(greet, CycleTasks.of(listOf(Greeting("Ada"), Greeting("Bob"))))

                // Act
                val cancelled = call("${cycleTable.name}_cancel", "$cycle, 0")

                // Assert
                assertThat(cancelled).isEqualTo(1)
                assertThat(pendenz.findCycle(cycle)!!.status).isEqualTo(CycleStatus.CANCELLED)
                assertThat(pendenz.cycleProgress(cycle)).containsExactlyEntriesOf(mapOf(TaskStatus.CANCELLED to 2))
            }
    }

    /** Each operator function beside the API call it mirrors (ADR 0031). */
    @Nested
    inner class SameRowAsTheApi {
        private suspend fun arrange(move: Move): Long =
            when (move) {
                Move.RESTART, Move.COMPLETE, Move.QUARANTINE -> {
                    pendenz.enqueue(greet, Greeting("Mallory")).also { pendenz.runPending() }
                }

                Move.CANCEL -> {
                    pendenz.enqueue(greet, Greeting("Ada"))
                }

                Move.APPROVE, Move.DECLINE -> {
                    pendenz.enqueue(vetted, Greeting("Ada"), createdBy = "ada")
                }
            }

        private suspend fun throughApi(
            move: Move,
            id: Long,
        ): Boolean =
            when (move) {
                Move.RESTART -> pendenz.restart(id)
                Move.COMPLETE -> pendenz.complete(id)
                Move.QUARANTINE -> pendenz.quarantine(id, "held for ticket #123")
                Move.CANCEL -> pendenz.cancel(id)
                Move.APPROVE -> pendenz.approve(id, "bob")
                Move.DECLINE -> pendenz.decline(id, "bob", "not this month")
            }

        private fun throughSql(
            move: Move,
            id: Long,
            version: Long,
        ): Long =
            when (move) {
                Move.RESTART -> call("${table.name}_restart", "$id, $version")
                Move.COMPLETE -> call("${table.name}_complete", "$id, $version")
                Move.QUARANTINE -> call("${table.name}_quarantine", "$id, $version, 'held for ticket #123'")
                Move.CANCEL -> call("${table.name}_cancel", "$id, $version")
                Move.APPROVE -> call("${table.name}_approve", "$id, $version, 'bob'")
                Move.DECLINE -> call("${table.name}_decline", "$id, $version, 'bob', 'not this month'")
            }

        /** What a move writes, with times reduced to whether they are set or ahead. */
        private suspend fun written(id: Long): List<Any?> {
            val task = pendenz.find(id)!!
            val now = Instant.now()
            return listOf(
                task.status,
                task.lastError,
                task.lockedBy,
                task.lockedUntil,
                task.finishedAt != null,
                task.runAt.isAfter(now),
                task.attempts,
                task.approvedBy,
                task.approvedAt != null,
            )
        }

        @ParameterizedTest
        @EnumSource(Move::class)
        fun `operator function - task the API moved alike - leaves the same row`(move: Move) =
            runBlocking<Unit> {
                // Arrange
                val reference = arrange(move)
                val id = arrange(move)
                check(throughApi(move, reference))
                val version = pendenz.find(id)!!.version

                // Act
                val changed = throughSql(move, id, version)

                // Assert
                assertThat(changed).isEqualTo(1)
                assertThat(written(id)).isEqualTo(written(reference))
            }
    }

    enum class Move { RESTART, COMPLETE, QUARANTINE, CANCEL, APPROVE, DECLINE }

    companion object {
        @JvmField
        @RegisterExtension
        val db = PostgresDatabase()
    }
}
