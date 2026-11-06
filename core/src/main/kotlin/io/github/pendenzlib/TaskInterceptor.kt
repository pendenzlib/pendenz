package io.github.pendenzlib

/**
 * Code that encloses every handler run: a logging context, a span, an
 * identity for the duration of the run, or a translation of exceptions
 * into decisions such as [RetryLaterException]. Registered with
 * [Pendenz.Builder.interceptor]; the first registered is the outermost.
 * See ADR 0036.
 *
 * An interceptor runs inside the execution timeout, around the payload
 * decoding and the handler, and before the outcome is written. What it
 * throws counts as if the handler had thrown it. It sees the task's
 * context, neither payload nor result, and cannot skip the handler:
 * [intercept] must call `proceed` exactly once, or the attempt fails with
 * an [IllegalStateException]. Swallowing what `proceed` throws does not
 * turn a failed run into a success; the handler's exception is recorded.
 *
 * Maintenance and schedule checks run no handler and pass through no
 * interceptor.
 */
public fun interface TaskInterceptor<ID : Any> {
    /**
     * Runs around one handler run. [proceed] runs the next interceptor, or
     * the handler when this is the innermost; call it with `withContext` to
     * change the coroutine context the handler runs in.
     */
    public suspend fun intercept(
        context: TaskContext<ID>,
        proceed: suspend () -> Unit,
    )
}

/** Runs one handler through the interceptors, outermost first, and checks each calls `proceed` once. */
internal class InterceptedRun<ID : Any>(
    private val interceptors: List<TaskInterceptor<ID>>,
    private val context: TaskContext<ID>,
    private val handler: suspend () -> Produced,
) {
    private var produced: Produced? = null
    private var thrown: Throwable? = null

    suspend fun run(): Produced {
        enter(0)
        produced?.let { return it }
        // An interceptor caught what the handler threw and returned: the run still failed
        thrown?.let { throw it }
        error("the handler of task ${context.id} produced nothing")
    }

    private suspend fun enter(index: Int) {
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
    private suspend fun runHandler() {
        try {
            produced = handler()
        } catch (e: Throwable) {
            thrown = e
            throw e
        }
    }

    private fun describe(interceptor: TaskInterceptor<ID>): String = "interceptor ${interceptor::class.java.name}"
}
