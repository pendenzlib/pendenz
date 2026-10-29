package io.github.pendenzlib.notify.testing

import io.github.pendenzlib.WakeUp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * Delegates to [inner] and counts the workers waiting in it. A notification
 * that arrives before a worker waits is not remembered, so a test announces
 * a task only once [waiting] says the worker is listening for it.
 */
class CountingWakeUp(
    private val inner: WakeUp,
) : WakeUp {
    val waiting = AtomicInteger()

    override suspend fun await(
        types: Set<String>,
        atMost: Duration,
    ) = coroutineScope {
        // Undispatched, inner.await runs until it suspends: the waiter is registered before it is counted
        val waited = launch(start = CoroutineStart.UNDISPATCHED) { inner.await(types, atMost) }
        waiting.incrementAndGet()
        try {
            waited.join()
        } finally {
            waiting.decrementAndGet()
        }
    }
}
