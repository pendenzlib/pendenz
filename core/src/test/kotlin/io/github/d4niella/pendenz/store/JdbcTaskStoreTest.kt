package io.github.d4niella.pendenz.store

import io.github.d4niella.pendenz.DataSourceConnectionProvider
import io.github.d4niella.pendenz.testing.PostgresDatabase
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class JdbcTaskStoreTest : TaskStoreContractTest() {
    override fun newStore(): TaskStore<Long> =
        JdbcTaskStore(db.createTable(), DataSourceConnectionProvider(db.dataSource))

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
