package io.github.d4niella.pendenz.javaapi

import io.github.d4niella.pendenz.ConnectionProvider
import java.sql.Connection
import java.util.function.Function

/**
 * A [ConnectionProvider] Java code can implement: runs [inTransaction]'s
 * block with a connection inside a transaction, blocking, and returns its
 * result. Register it with [PendenzJava.Builder.connectionProvider]. The
 * queue calls it on the thread that called the queue, so a provider may
 * look up a transaction bound to that thread.
 */
public interface BlockingConnectionProvider {
    @Throws(Exception::class)
    public fun <T> inTransaction(block: Function<Connection, T>): T
}

/** The suspending provider the core takes, calling the blocking one on the caller's thread. */
internal fun BlockingConnectionProvider.asConnectionProvider(): ConnectionProvider =
    object : ConnectionProvider {
        override suspend fun <T> inTransaction(block: (Connection) -> T): T =
            this@asConnectionProvider.inTransaction { block(it) }
    }
