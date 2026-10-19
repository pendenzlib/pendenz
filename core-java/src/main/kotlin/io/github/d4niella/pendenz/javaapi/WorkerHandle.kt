package io.github.d4niella.pendenz.javaapi

import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking

/**
 * A running worker. [close] stops it the graceful way (ADR 0005): no new
 * claims, running handlers get the shutdown timeout, then the call returns.
 */
public class WorkerHandle internal constructor(
    private val job: Job,
) : AutoCloseable {
    /** Whether the worker is still polling. */
    public val isRunning: Boolean
        get() = job.isActive

    /** Stops the worker and waits until it has stopped. Same as [close]. */
    public fun stop() {
        runBlocking { job.cancelAndJoin() }
    }

    override fun close(): Unit = stop()
}
