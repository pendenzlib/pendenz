package io.github.d4niella.pendenz

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import javax.sql.DataSource

/**
 * Hands the library a connection inside a transaction.
 *
 * The default implementation opens a connection from a [DataSource], runs the
 * block with auto-commit off and commits or rolls back. Applications whose
 * transactions are managed elsewhere, for example by Spring, implement this
 * interface to return the connection bound to the current transaction.
 */
public interface ConnectionProvider {
    /** Runs [block] with a connection inside a transaction and returns its result. */
    public suspend fun <T> inTransaction(block: (Connection) -> T): T
}

/** Opens a fresh connection per call and manages the transaction itself. */
public class DataSourceConnectionProvider(
    private val dataSource: DataSource,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ConnectionProvider {
    // Anything thrown by the block must roll back the transaction before it propagates.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun <T> inTransaction(block: (Connection) -> T): T =
        withContext(dispatcher) {
            dataSource.connection.use { connection ->
                val previousAutoCommit = connection.autoCommit
                connection.autoCommit = false
                try {
                    val result = block(connection)
                    connection.commit()
                    result
                } catch (e: Throwable) {
                    connection.rollback()
                    throw e
                } finally {
                    connection.autoCommit = previousAutoCommit
                }
            }
        }
}
