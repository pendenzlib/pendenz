package io.github.d4niella.pendenz.javaapi

import io.github.d4niella.pendenz.TaskContext
import io.github.d4niella.pendenz.TaskHandler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * A handler written in Java: a plain blocking method, no coroutines. It
 * runs on the dispatcher given to [PendenzJava.Builder.handlerDispatcher],
 * `Dispatchers.IO` by default, so blocking inside it is expected.
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

internal fun <ID : Any, P : Any> JavaTaskHandler<ID, P>.asTaskHandler(
    dispatcher: CoroutineDispatcher,
): TaskHandler<ID, P> = TaskHandler { context, payload -> withContext(dispatcher) { handle(context, payload) } }
