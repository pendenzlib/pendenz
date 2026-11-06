package io.github.pendenzlib.javaapi

import io.github.pendenzlib.TaskContext

/**
 * Code written in Java that encloses every blocking handler run, on the
 * handler's own thread: a thread-local value it sets, such as an MDC
 * entry or a security context, is visible to the handler. Registered with
 * [PendenzJava.Builder.interceptor]; the first registered is the
 * outermost. It encloses the handlers registered through the facade, and
 * those the Spring starter registers with `TaskRegistration.blocking`; a
 * Kotlin [io.github.pendenzlib.TaskInterceptor], registered on the same
 * builder, encloses every handler and the blocking interceptors.
 * See ADR 0036.
 *
 * The rules of [io.github.pendenzlib.TaskInterceptor] apply: what it
 * throws counts as the handler's exception, and it must call
 * [Proceed.proceed] exactly once, or the attempt fails with an
 * [IllegalStateException].
 */
public fun interface BlockingTaskInterceptor<ID : Any> {
    @Throws(Exception::class)
    public fun intercept(
        context: TaskContext<ID>,
        proceed: Proceed,
    )
}

/** Runs the next interceptor, or the handler when this is the innermost. */
public fun interface Proceed {
    @Throws(Exception::class)
    public fun proceed()
}

/** Runs one blocking handler through the interceptors, outermost first, and checks each calls proceed once. */
internal class BlockingRun<ID : Any, R>(
    private val interceptors: List<BlockingTaskInterceptor<ID>>,
    private val context: TaskContext<ID>,
    private val handler: () -> R,
) {
    private var completed: Completed<R>? = null
    private var thrown: Throwable? = null

    fun run(): R {
        if (interceptors.isEmpty()) return handler()
        enter(0)
        completed?.let { return it.value }
        // An interceptor caught what the handler threw and returned: the run still failed
        thrown?.let { throw it }
        error("the handler of task ${context.id} produced nothing")
    }

    private fun enter(index: Int) {
        if (index == interceptors.size) {
            runHandler()
            return
        }
        val interceptor = interceptors[index]
        var calls = 0
        interceptor.intercept(context) {
            calls++
            check(calls == 1) { "${describe(interceptor)} called proceed more than once for task ${context.id}" }
            enter(index + 1)
        }
        // Also when the interceptor caught the exception of a second call
        check(calls == 1) {
            val what = if (calls == 0) "returned without calling proceed" else "called proceed more than once"
            "${describe(interceptor)} $what for task ${context.id}"
        }
    }

    @Suppress("TooGenericExceptionCaught") // kept to be rethrown should an interceptor swallow it
    private fun runHandler() {
        try {
            completed = Completed(handler())
        } catch (e: Throwable) {
            thrown = e
            throw e
        }
    }

    private fun describe(interceptor: BlockingTaskInterceptor<ID>): String =
        "interceptor ${interceptor::class.java.name}"
}
