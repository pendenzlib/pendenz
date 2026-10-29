package io.github.d4niella.pendenz

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource

/**
 * Hands the library a connection inside a transaction.
 *
 * The default implementation opens a connection from a [DataSource], runs the
 * block with auto-commit off and commits or rolls back. Applications whose
 * transactions are managed elsewhere, for example by Spring, implement this
 * interface to return the connection bound to the current transaction.
 *
 * For every operation that takes no explicit connection, the queue calls
 * [inTransaction] on the thread of the calling coroutine, without switching
 * dispatchers first. A provider may therefore look up a transaction bound
 * to that thread, as Spring binds its transactions; whether it then moves
 * the blocking work elsewhere is its own decision. See ADR 0023.
 */
public interface ConnectionProvider {
    /** Runs [block] with a connection inside a transaction and returns its result. */
    public suspend fun <T> inTransaction(block: (Connection) -> T): T

    /**
     * Like [inTransaction] for a [block] that only reads, so a provider may
     * join a read-only transaction it would refuse a write in. The same as
     * [inTransaction] unless a provider says otherwise.
     */
    public suspend fun <T> inReadTransaction(block: (Connection) -> T): T = inTransaction(block)

    /**
     * Runs [action] once the transaction [inTransaction] has just returned
     * from is committed, and never when it rolls back. The queue calls it
     * right after [inTransaction] returns, on the same thread. The default
     * runs it at once, which is right for a provider that commits before
     * [inTransaction] returns; one that joins a transaction it does not
     * commit defers it to that commit (ADR 0032).
     */
    public fun afterCommit(action: () -> Unit) {
        action()
    }
}

/**
 * Puts auto-commit back. The connection is closed right after, so a failure here must not hide what
 * happened in the transaction; a pool resets the flag on return anyway.
 */
private fun restore(
    connection: Connection,
    autoCommit: Boolean,
) {
    try {
        connection.autoCommit = autoCommit
    } catch (e: SQLException) {
        LoggerFactory.getLogger(DataSourceConnectionProvider::class.java).warn("could not restore auto-commit", e)
    }
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
                    // The block's failure is the one to report; a failed rollback travels along with it
                    try {
                        connection.rollback()
                    } catch (rollback: SQLException) {
                        e.addSuppressed(rollback)
                    }
                    throw e
                } finally {
                    restore(connection, previousAutoCommit)
                }
            }
        }
}
