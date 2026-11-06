// One class for Java, JavaHandlers.asTaskHandler and JavaHandlers.asResultHandler, not one per file
@file:JvmName("JavaHandlers")
@file:JvmMultifileClass

package io.github.pendenzlib.javaapi

import io.github.pendenzlib.ResultHandler
import io.github.pendenzlib.TaskContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executor

/** A handler written in Java that produces a result: a plain blocking method returning it, never null. */
public fun interface JavaResultHandler<ID : Any, P : Any, R : Any> {
    @Throws(Exception::class)
    public fun handle(
        context: TaskContext<ID>,
        payload: P,
    ): R
}

/** This handler as a [ResultHandler] that runs it on [dispatcher]; see [asTaskHandler]. */
@JvmOverloads
public fun <ID : Any, P : Any, R : Any> JavaResultHandler<ID, P, R>.asResultHandler(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
): ResultHandler<ID, P, R> = asResultHandler(dispatcher, emptyList())

/** Like [asResultHandler], with [interceptors] around the handler on its thread, outermost first (ADR 0036). */
public fun <ID : Any, P : Any, R : Any> JavaResultHandler<ID, P, R>.asResultHandler(
    dispatcher: CoroutineDispatcher,
    interceptors: List<BlockingTaskInterceptor<ID>>,
): ResultHandler<ID, P, R> =
    ResultHandler { context, payload ->
        blocking(dispatcher) {
            BlockingRun(interceptors, context) {
                // Java can return null where Kotlin promises a value; fail the attempt here, naming the type
                val result: R? = handle(context, payload)
                checkNotNull(result) { "the result handler of task type '${context.type}' returned null" }
            }.run()
        }
    }

/** This handler as a [ResultHandler] that runs it on [executor]; see [asTaskHandler]. */
public fun <ID : Any, P : Any, R : Any> JavaResultHandler<ID, P, R>.asResultHandler(
    executor: Executor,
): ResultHandler<ID, P, R> = asResultHandler(executor.asCoroutineDispatcher())
