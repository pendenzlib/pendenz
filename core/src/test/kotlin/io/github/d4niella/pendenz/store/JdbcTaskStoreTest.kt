package io.github.d4niella.pendenz.store

import io.github.d4niella.pendenz.DataSourceConnectionProvider
import io.github.d4niella.pendenz.PayloadColumn
import io.github.d4niella.pendenz.SqlType
import io.github.d4niella.pendenz.TaskStatus
import io.github.d4niella.pendenz.schema.CycleTable
import io.github.d4niella.pendenz.schema.IdType
import io.github.d4niella.pendenz.schema.PostgresDdl
import io.github.d4niella.pendenz.schema.ResourceLimitTable
import io.github.d4niella.pendenz.schema.TaskTable
import io.github.d4niella.pendenz.testing.PostgresDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Instant

class JdbcTaskStoreTest : TaskStoreContractTest() {
    private lateinit var table: TaskTable<Long>
    private val jsonColumn = listOf(PayloadColumn("payload", SqlType.JSONB))

    override fun newStore(): TaskStore<Long> {
        table = db.createTable()
        val limits = ResourceLimitTable(name = "${table.name}_limit")
        val cycles = CycleTable(IdType.BIGINT, "${table.name}_cycle")
        db.sql(PostgresDdl.createResourceLimitTable(limits))
        db.sql(PostgresDdl.createCycleTable(cycles))
        return JdbcTaskStore(
            table,
            DataSourceConnectionProvider(db.dataSource),
            jsonColumn,
            limitTable = limits,
            cycleTable = cycles,
        )
    }

    override fun createArchiveTable(): String {
        val archive = "${table.name}_archive"
        db.sql("""CREATE TABLE "$archive" (LIKE "${table.name}" INCLUDING ALL EXCLUDING INDEXES)""")
        return archive
    }

    override fun archivedCount(archiveTable: String): Long = db.count("""SELECT count(*) FROM "$archiveTable"""")

    @Test
    fun `construction - payload column named like a queue column - throws IllegalArgumentException naming it`() {
        // Arrange
        val clashing = listOf(PayloadColumn("priority", SqlType.INTEGER))
        val provider = DataSourceConnectionProvider(db.dataSource)

        val attempt = ThrowingCallable { JdbcTaskStore(TaskTable(IdType.BIGINT, "refund"), provider, clashing) }

        assertThatThrownBy(attempt).isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("priority")
    }

    @Test
    fun `insert - payload with a column the store does not have - throws IllegalArgumentException naming it`() =
        runBlocking<Unit> {
            // Arrange
            val store = newStore()
            val now = Instant.parse("2026-10-25T09:00:00Z")
            val columns = mapOf("payload" to "{}", "amount" to 42)
            val task = NewTask<Long>(null, "refund", columns, now, TaskStatus.PENDING, now)

            val thrown = catchThrowable { runBlocking { store.transaction { store.insert(it, task) } } }

            assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("amount")
        }

    @Test
    fun `find - provenance written by hand with a number and an object - reads them as their JSON text`() =
        runBlocking<Unit> {
            // Arrange
            val store = newStore()
            val now = Instant.parse("2026-10-25T09:00:00Z")
            val id =
                store.transaction {
                    store.insert(
                        it,
                        NewTask(null, "summarise", mapOf("payload" to "{}"), now, TaskStatus.SUCCEEDED, now),
                    )
                }
            db.sql(
                """UPDATE "${table.name}" SET provenance = '{"model": "small", "tokens": 1200, "cost": {"eur": 2}}'
                   WHERE id = $id""",
            )

            val record = store.transaction { store.find(it, id) }!!

            assertThat(record.provenance).containsExactlyInAnyOrderEntriesOf(
                mapOf("model" to "small", "tokens" to "1200", "cost" to """{"eur":2}"""),
            )
        }

    @Test
    fun `lockClaims - same table spelled with and without its schema - the second waits for the first`() =
        runBlocking<Unit> {
            // Arrange: one deployment names the table bare, the other with its schema
            val bare = newStore()
            val limits = ResourceLimitTable(name = "${table.name}_limit")
            val provider = DataSourceConnectionProvider(db.dataSource)
            val qualified =
                JdbcTaskStore(
                    TaskTable(IdType.BIGINT, "public.${table.name}"),
                    provider,
                    jsonColumn,
                    limitTable = limits,
                )
            db.dataSource.connection.use { first ->
                first.autoCommit = false
                bare.resourceLimits!!.lockClaims(bare.adopt(first))

                // Act: on its own thread, so a lock that is not shared shows as a claim that does not wait
                val second =
                    async(Dispatchers.IO) { qualified.transaction { qualified.resourceLimits!!.lockClaims(it) } }

                // Assert: a fixed wait, to show the second does not get the lock while the first holds it
                assertThat(withTimeoutOrNull(500) { second.await() }).isNull()
                first.rollback()
                assertThat(withTimeoutOrNull(5_000) { second.await() }).isEqualTo(Unit)
            }
        }

    @Test
    fun `promoteDue - a due row locked by another transaction - promotes the others without waiting`() =
        runBlocking<Unit> {
            // Arrange: an operator's transaction holds one of two due scheduled rows
            val store = newStore()
            val due = Instant.parse("2026-10-25T09:00:00Z")
            val scheduled = { NewTask<Long>(null, "greet", mapOf("payload" to "{}"), due, TaskStatus.SCHEDULED, due) }
            val held = store.transaction { store.insert(it, scheduled()) }
            val free = store.transaction { store.insert(it, scheduled()) }
            db.dataSource.connection.use { operator ->
                operator.autoCommit = false
                operator.createStatement().use {
                    it.execute(
                        """SELECT 1 FROM "${table.name}" WHERE id = $held FOR UPDATE""",
                    )
                }

                // Act: on its own thread, so a promotion that waits for the lock fails the test instead of hanging
                val promoting = async(Dispatchers.IO) { store.transaction { store.promoteDue(it, due.plusSeconds(1)) } }
                val promoted = withTimeoutOrNull(5_000) { promoting.await() }
                operator.rollback()

                // Assert
                assertThat(promoted).describedAs("promoteDue waited for the locked row").isEqualTo(1)
                assertThat(store.transaction { store.find(it, free) }!!.status).isEqualTo(TaskStatus.PENDING)
                assertThat(store.transaction { store.find(it, held) }!!.status).isEqualTo(TaskStatus.SCHEDULED)
                promoting.await()
            }
        }

    companion object {
        @JvmField
        @RegisterExtension
        val db = PostgresDatabase()
    }
}
