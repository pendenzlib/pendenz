// One class for Java, JavaHandlers.asTaskHandler and JavaHandlers.asResultHandler, not one per file
@file:JvmName("JavaHandlers")
@file:JvmMultifileClass

package io.github.d4niella.pendenz.javaapi

import io.github.d4niella.pendenz.TaskContext
import io.github.d4niella.pendenz.TaskHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.atomic.AtomicReference

/**
 * A handler written in Java: a plain blocking method, no coroutines. It
 * runs on the dispatcher given to [PendenzJava.Builder.handlerDispatcher],
 * `Dispatchers.IO` by default, so blocking inside it is expected. When the
 * task is cancelled, the thread is interrupted; code that honours
 * interruption stops early, code that does not runs to its end, and what
 * it returns is still recorded.
 *
 * Return normally for success, throw for failure.
 */
public fun interface JavaTaskHandler<ID : Any, P : Any> {
    @Throws(Exception::class)
    public fun handle(
        context: TaskContext<ID>,
        payload: P,
    )
}

/**
 * This handler as a [TaskHandler] that runs it on [dispatcher], for code
 * that registers Java handlers on a [io.github.d4niella.pendenz.Pendenz.Builder]
 * directly, such as the Spring Boot starter.
 */
@JvmOverloads
public fun <ID : Any, P : Any> JavaTaskHandler<ID, P>.asTaskHandler(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
): TaskHandler<ID, P> = TaskHandler { context, payload -> blocking(dispatcher) { handle(context, payload) } }

/**
 * Runs [block] on [dispatcher] with the thread interrupted on cancellation.
 * A block that ignores the interruption and completes anyway has done its
 * work; its value is returned instead of the cancellation, so the outcome
 * is recorded rather than thrown away (ADR 0005).
 */
internal suspend fun <R> blocking(
    dispatcher: CoroutineDispatcher,
    block: () -> R,
): R {
    val completed = AtomicReference<Completed<R>?>(null)
    try {
        return runInterruptible(dispatcher) { block().also { completed.set(Completed(it)) } }
    } catch (e: CancellationException) {
        return completed.get()?.value ?: throw e
    }
}

/** Holds a value so that a completed block returning `Unit` is told apart from one that never completed. */
internal class Completed<R>(
    val value: R,
)
