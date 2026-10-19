package io.github.d4niella.pendenz.store

import io.github.d4niella.pendenz.DataSourceConnectionProvider
import io.github.d4niella.pendenz.PayloadColumn
import io.github.d4niella.pendenz.SqlType
import io.github.d4niella.pendenz.schema.PostgresDdl
import io.github.d4niella.pendenz.schema.ResourceLimitTable
import io.github.d4niella.pendenz.schema.TaskTable
import io.github.d4niella.pendenz.testing.PostgresDatabase
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

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

    companion object {
        @JvmField
        @RegisterExtension
        val db = PostgresDatabase()
    }
}
