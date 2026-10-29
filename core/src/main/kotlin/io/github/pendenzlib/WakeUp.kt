package io.github.pendenzlib

import kotlinx.coroutines.delay
import java.time.Duration

/**
 * How a worker waits when a poll found nothing. The default waits for the
 * poll interval; `pendenz-postgres-notify` returns early when the database
 * announces a new task. See ADR 0014.
 *
 * The poll interval stays the upper bound either way: tasks become due by
 * time, and an announcement can be lost while a connection is down.
 */
public fun interface WakeUp {
    /**
     * Returns when a task of one of [types] may have become pending, or
     * after [atMost], whichever comes first.
     */
    public suspend fun await(
        types: Set<String>,
        atMost: Duration,
    )

    public companion object {
        /** Waits for the whole interval: plain polling, the default. */
        @JvmField
        public val POLLING: WakeUp = WakeUp { _, atMost -> delay(atMost.toMillis()) }
    }
}
