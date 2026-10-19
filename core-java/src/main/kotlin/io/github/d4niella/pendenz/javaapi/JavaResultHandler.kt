package io.github.d4niella.pendenz.javaapi

import io.github.d4niella.pendenz.ResultHandler
import io.github.d4niella.pendenz.TaskContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** A handler written in Java that produces a result: a plain blocking method returning it. */
public fun interface JavaResultHandler<ID : Any, P : Any, R : Any> {
    @Throws(Exception::class)
    public fun handle(
        context: TaskContext<ID>,
        payload: P,
    ): R
}

internal fun <ID : Any, P : Any, R : Any> JavaResultHandler<ID, P, R>.asResultHandler(
    dispatcher: CoroutineDispatcher,
): ResultHandler<ID, P, R> = ResultHandler { context, payload -> withContext(dispatcher) { handle(context, payload) } }
