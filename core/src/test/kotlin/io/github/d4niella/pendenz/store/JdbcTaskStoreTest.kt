package io.github.d4niella.pendenz.store

import io.github.d4niella.pendenz.DataSourceConnectionProvider
import io.github.d4niella.pendenz.PayloadColumn
import io.github.d4niella.pendenz.SqlType
import io.github.d4niella.pendenz.TaskStatus
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
        db.sql(PostgresDdl.createResourceLimitTable(limits))
        return JdbcTaskStore(table, DataSourceConnectionProvider(db.dataSource), jsonColumn, limitTable = limits)
    }

    override fun createArchiveTable(): String {
        val archive = "${table.name}_archive"
        db.sql("""CREATE TABLE "$archive" (LIKE "${table.name}" INCLUDING ALL EXCLUDING INDEXES)""")
        return archive
    }

    override fun archivedCount(archiveTable: String): Long = db.count("""SELECT count(*) FROM "$archiveTable"""")

    @Test
    fun `operation - transaction handle from another store - throws IllegalArgumentException`() {
        // Arrange
        val store = newStore()
        val foreign = object : StoreTransaction {}

        val attempt = ThrowingCallable { store.find(foreign, 1L) }

        assertThatThrownBy(attempt).isInstanceOf(IllegalArgumentException::class.java)
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
