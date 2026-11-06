package io.github.pendenzlib.javaapi.testing

import io.github.pendenzlib.TaskInterceptor
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext

/** Interceptors written in Kotlin, as a library would ship them, for the Java tests to register. */
object KotlinInterceptors {
    /** Carries the task's creator into [local] on whatever thread the handler runs, as a coroutine context element. */
    @JvmStatic
    fun creatorInto(local: ThreadLocal<String?>): TaskInterceptor<Long> =
        TaskInterceptor { context, proceed -> withContext(local.asContextElement(context.createdBy)) { proceed() } }

    /** Adds "[name] before" and "[name] after" to [calls] around the run. */
    @JvmStatic
    fun recording(
        name: String,
        calls: MutableList<String>,
    ): TaskInterceptor<Long> =
        TaskInterceptor { _, proceed ->
            calls += "$name before"
            proceed()
            calls += "$name after"
        }
}
