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
 * `LISTEN` on the channel. Every `livenessInterval` without traffic the
 * thread asks the server whether the connection still answers, because a
 * connection cut off without a reset, by a failover or a NAT timeout,
 * would otherwise wait for notifications forever. When the connection
 * drops or stops answering, the thread reconnects with backoff; until
 * then [await] simply waits for its timeout, which is polling. After a
 * reconnect every waiter is woken once, so workers poll for what was
 * announced meanwhile. Give it a plain driver data source, not a pool
 * that evicts idle connections.
 *
 * Several workers may share one instance. A notification that arrives
 * between a worker's poll and its next [await] is not remembered; the
 * worker catches that task at its next poll interval.
 */
public class PostgresNotify private constructor(
    private val dataSource: DataSource,
    public val channel: String,
    private val livenessInterval: Duration,
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
        var reconnecting = false
        while (running.get()) {
            try {
                val pg = connect() ?: return
                // What was announced while no connection listened is lost; one poll of every worker finds it
                if (reconnecting) wake(null)
                reconnecting = true
                backoff = INITIAL_BACKOFF
                receiveUntilStopped(pg)
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
        var lastSign = System.nanoTime()
        while (running.get()) {
            val notifications = pg.getNotifications(RECEIVE_TIMEOUT_MILLIS)
            if (!notifications.isNullOrEmpty()) {
                notifications.forEach { wake(it.parameter) }
                lastSign = System.nanoTime()
            } else if (System.nanoTime() - lastSign >= livenessInterval.toNanos()) {
                checkAlive()
                lastSign = System.nanoTime()
            }
        }
    }

    /** Reading notifications never writes to the socket, so only a round trip shows a connection that went dead. */
    private fun checkAlive() {
        val c = connection ?: return
        if (running.get() && !c.isValid(LIVENESS_TIMEOUT_SECONDS)) {
            throw SQLException("connection did not answer within $LIVENESS_TIMEOUT_SECONDS s")
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
        private const val LIVENESS_TIMEOUT_SECONDS = 5
        private const val MAX_CHANNEL_BYTES = 63
        private val INITIAL_BACKOFF: Duration = Duration.ofSeconds(1)
        private val MAX_BACKOFF: Duration = Duration.ofSeconds(30)

        /** How long the connection may stay silent before the listener checks that it still answers. */
        @JvmField
        public val DEFAULT_LIVENESS_INTERVAL: Duration = Duration.ofSeconds(30)

        /** Starts listening on [channel] and returns the running instance. */
        @JvmStatic
        @JvmOverloads
        public fun start(
            dataSource: DataSource,
            channel: String,
            livenessInterval: Duration = DEFAULT_LIVENESS_INTERVAL,
        ): PostgresNotify {
            require(livenessInterval > Duration.ZERO) { "livenessInterval must be positive" }
            require(channel.toByteArray(Charsets.UTF_8).size <= MAX_CHANNEL_BYTES) {
                "channel is longer than PostgreSQL's $MAX_CHANNEL_BYTES bytes and would be cut: $channel"
            }
            return PostgresNotify(dataSource, channel, livenessInterval).also { it.thread.start() }
        }

        /** Starts listening on the channel the trigger of [table] announces on. */
        @JvmStatic
        @JvmOverloads
        public fun start(
            dataSource: DataSource,
            table: TaskTable<*>,
            livenessInterval: Duration = DEFAULT_LIVENESS_INTERVAL,
        ): PostgresNotify = start(dataSource, notifyChannelFor(table), livenessInterval)
    }
}
