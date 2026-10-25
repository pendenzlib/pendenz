package io.github.d4niella.pendenz

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource

class DataSourceConnectionProviderTest {
    /** A connection whose rollback fails, as a connection the server dropped would; every other call does nothing. */
    private val brokenRollback =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, _ ->
            when (method.name) {
                "rollback" -> throw SQLException("connection lost")
                "getAutoCommit" -> true
                else -> null
            }
        } as Connection

    private val dataSource =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(DataSource::class.java)) { _, method, _ ->
            if (method.name == "getConnection") brokenRollback else null
        } as DataSource

    @Test
    fun `inTransaction - block and rollback both fail - the block's failure arrives, the rollback's attached`() =
        runBlocking<Unit> {
            val provider = DataSourceConnectionProvider(dataSource)

            val thrown = catchThrowable { runBlocking { provider.inTransaction { error("invoice not found") } } }

            // Assert
            assertThat(thrown).isInstanceOf(IllegalStateException::class.java).hasMessage("invoice not found")
            // With assertions on, coroutines copy the exception on its way out and keep the original as its cause
            val suppressed = generateSequence(thrown) { it.cause }.flatMap { it.suppressed.asSequence() }.toList()
            assertThat(suppressed.map { it.message }).containsExactly("connection lost")
        }
}
