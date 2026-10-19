package io.github.d4niella.pendenz.notify

import io.github.d4niella.pendenz.WakeUp
import io.github.d4niella.pendenz.schema.TaskTable
import io.github.d4niella.pendenz.schema.notifyChannelFor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.postgresql.PGConnection
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource

/**
 * A [WakeUp] that listens for the trigger of
 * `PostgresDdl.notifyTrigger` and wakes workers the moment a task of a
 * type they handle becomes pending. See ADR 0014.
 *
 * One dedicated connection is held open on a thread of its own, with
 * `LISTEN` on the channel. When it drops, the thread reconnects with
 * backoff; until then [await] simply waits for its timeout, which is
 * polling. Give it a plain driver data source, not a pool that evicts
 * idle connections.
 *
 * Several workers may share one instance. A notification that arrives
 * between a worker's poll and its next [await] is not remembered; the
 * worker catches that task at its next poll interval.
 */
public class PostgresNotify private constructor(
    private val dataSource: DataSource,
    public val channel: String,
) : WakeUp,
    AutoCloseable {
    private val log = LoggerFactory.getLogger(PostgresNotify::class.java)
    private val waiters = ConcurrentHashMap.newKeySet<Waiter>()
    private val running = AtomicBoolean(true)

    @Volatile
    private var connection: Connection? = null
    private val thread = Thread(::listen, "pendenz-notify-$channel").apply { isDaemon = true }

    private class Waiter(
        val types: Set<String>,
        val woken: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    override suspend fun await(
        types: Set<String>,
        atMost: Duration,
    ) {
        val waiter = Waiter(types)
        waiters += waiter
        try {
            withTimeoutOrNull(atMost.toMillis()) { waiter.woken.await() }
        } finally {
            waiters -= waiter
        }
    }

    /** Stops listening and closes the connection. Workers fall back to polling. */
    override fun close() {
        running.set(false)
        closeConnection()
        thread.interrupt()
    }

    // The listening thread must survive every database failure and reconnect.
    @Suppress("TooGenericExceptionCaught")
    private fun listen() {
        var backoff = INITIAL_BACKOFF
        while (running.get()) {
            try {
                receiveUntilStopped(connect() ?: return)
                backoff = INITIAL_BACKOFF
            } catch (e: Exception) {
                if (!running.get()) return
                log.warn("listener on {} lost its connection, retrying in {}: {}", channel, backoff, e.toString())
                closeConnection()
                if (!pause(backoff)) return
                backoff = minOf(backoff.multipliedBy(2), MAX_BACKOFF)
            }
        }
    }

    private fun receiveUntilStopped(pg: PGConnection) {
        while (running.get()) {
            pg.getNotifications(RECEIVE_TIMEOUT_MILLIS)?.forEach { wake(it.parameter) }
        }
    }

    /** Opens the connection and listens; null when [close] came while the connection was being opened. */
    private fun connect(): PGConnection? {
        val c = dataSource.connection
        connection = c
        if (!running.get()) {
            // close() ran before it could see this connection, so it is ours to close
            closeConnection()
            return null
        }
        // LISTEN takes effect at commit; a data source that hands out connections mid-transaction would never commit
        c.autoCommit = true
        c.createStatement().use { it.execute("LISTEN ${quote(channel)}") }
        log.info("listening on {}", channel)
        return c.unwrap(PGConnection::class.java)
    }

    /** Wakes every waiter that handles the announced type; an empty payload wakes everyone. */
    private fun wake(type: String?) {
        waiters.forEach { waiter ->
            if (type.isNullOrEmpty() || type in waiter.types) waiter.woken.complete(Unit)
        }
    }

    private fun closeConnection() {
        runCatching { connection?.close() }
        connection = null
    }

    private fun pause(duration: Duration): Boolean =
        try {
            Thread.sleep(duration.toMillis())
            true
        } catch (_: InterruptedException) {
            false
        }

    private fun quote(identifier: String): String {
        require(!identifier.contains('"')) { "channel must not contain a double quote: $identifier" }
        return "\"$identifier\""
    }

    public companion object {
        private const val RECEIVE_TIMEOUT_MILLIS = 1_000
        private val INITIAL_BACKOFF: Duration = Duration.ofSeconds(1)
        private val MAX_BACKOFF: Duration = Duration.ofSeconds(30)

        /** Starts listening on [channel] and returns the running instance. */
        @JvmStatic
        public fun start(
            dataSource: DataSource,
            channel: String,
        ): PostgresNotify = PostgresNotify(dataSource, channel).also { it.thread.start() }

        /** Starts listening on the channel the trigger of [table] announces on. */
        @JvmStatic
        public fun start(
            dataSource: DataSource,
            table: TaskTable<*>,
        ): PostgresNotify = start(dataSource, notifyChannelFor(table))
    }
}
