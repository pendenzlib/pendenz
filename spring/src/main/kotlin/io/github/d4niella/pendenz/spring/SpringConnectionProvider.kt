package io.github.d4niella.pendenz.spring

import io.github.d4niella.pendenz.ConnectionProvider
import io.github.d4niella.pendenz.DataSourceConnectionProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection
import javax.sql.DataSource

/**
 * A [ConnectionProvider] for applications whose transactions Spring
 * manages. See ADR 0023.
 *
 * - Called inside a Spring transaction, it runs the block on the calling
 *   thread with that transaction's connection. It neither commits nor
 *   rolls back nor marks the transaction rollback-only: an exception
 *   propagates, and the caller decides what becomes of the transaction.
 * - Inside a read-only transaction it joins for reading, such as `find`
 *   and `tasks`, and throws [IllegalStateException] for anything that
 *   writes. Inside a transaction that holds no connection of this data
 *   source it throws as well, instead of working beside the caller's
 *   transaction.
 * - Outside a transaction, as on a worker, it runs a JDBC transaction of
 *   its own on [dispatcher], bypassing the transaction manager, as the
 *   core's [DataSourceConnectionProvider] does.
 * - Listener events of an operation that joined a Spring transaction are
 *   delivered after that transaction commits, and dropped when it rolls
 *   back (ADR 0032).
 *
 * The constructor checks that [transactionManager] manages [dataSource].
 *
 * On PostgreSQL a failed statement aborts the whole transaction, so a
 * database error during an enqueue still ends the caller's transaction.
 * Permissions and payloads are checked before the first statement.
 *
 * @throws IllegalStateException when [transactionManager] is no
 *   `DataSourceTransactionManager`, `JdbcTransactionManager` or
 *   `JpaTransactionManager` over [dataSource], so that joining its
 *   transactions could fail silently. JTA is refused.
 */
public class SpringConnectionProvider
    @JvmOverloads
    constructor(
        dataSource: DataSource,
        transactionManager: PlatformTransactionManager,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : ConnectionProvider {
        /** The key the transaction manager binds its connection under. */
        private val transactional: DataSource = TransactionPair.check(dataSource, transactionManager)
        private val own = DataSourceConnectionProvider(withoutTransactionAwareness(dataSource), dispatcher)

        override suspend fun <T> inTransaction(block: (Connection) -> T): T =
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                check(!TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
                    "the current transaction is read-only; Pendenz cannot write a task in it"
                }
                joined(block)
            } else {
                own.inTransaction(block)
            }

        override suspend fun <T> inReadTransaction(block: (Connection) -> T): T =
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                joined(block)
            } else {
                own.inTransaction(block)
            }

        override fun afterCommit(action: () -> Unit) {
            // Only a joined transaction is still open here; an own one has committed already
            if (TransactionSynchronizationManager.isActualTransactionActive() &&
                TransactionSynchronizationManager.isSynchronizationActive()
            ) {
                TransactionSynchronizationManager.registerSynchronization(
                    object : TransactionSynchronization {
                        override fun afterCommit() = action()
                    },
                )
            } else {
                action()
            }
        }

        private fun <T> joined(block: (Connection) -> T): T {
            check(TransactionSynchronizationManager.hasResource(transactional)) {
                "a transaction is active on this thread but holds no connection of Pendenz' data source; " +
                    "it belongs to another transaction manager or data source, and Pendenz does not write beside it"
            }
            val connection = DataSourceUtils.getConnection(transactional)
            try {
                return block(connection)
            } finally {
                DataSourceUtils.releaseConnection(connection, transactional)
            }
        }

        private companion object {
            /** Own transactions commit themselves; a transaction-aware proxy would hand out a joined connection. */
            fun withoutTransactionAwareness(dataSource: DataSource): DataSource =
                (dataSource as? TransactionAwareDataSourceProxy)?.targetDataSource ?: dataSource
        }
    }
