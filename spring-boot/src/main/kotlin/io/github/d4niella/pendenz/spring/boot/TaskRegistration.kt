package io.github.d4niella.pendenz.spring.boot

import io.github.d4niella.pendenz.Pendenz
import io.github.d4niella.pendenz.ResultHandler
import io.github.d4niella.pendenz.TaskHandler
import io.github.d4niella.pendenz.TaskResult
import io.github.d4niella.pendenz.TaskType
import io.github.d4niella.pendenz.javaapi.JavaResultHandler
import io.github.d4niella.pendenz.javaapi.JavaTaskHandler
import io.github.d4niella.pendenz.javaapi.asResultHandler
import io.github.d4niella.pendenz.javaapi.asTaskHandler

/**
 * A task type and its handler, declared as a bean; the starter registers
 * every such bean on the queue. The type argument is the queue's id type,
 * so declare the bean's type with it:
 *
 * ```kotlin
 * @Bean
 * fun sendInvoice(mail: MailService): TaskRegistration<Long> =
 *     TaskRegistration.of(SEND_INVOICE) { _, invoice -> mail.send(invoice) }
 * ```
 *
 * [of] takes Kotlin handlers, which suspend; [blocking] takes handlers
 * written in Java, which run on `Dispatchers.IO`.
 */
public class TaskRegistration<ID : Any> private constructor(
    /** The name of the registered task type. */
    public val typeName: String,
    private val register: (Pendenz.Builder<ID>) -> Unit,
) {
    internal fun registerOn(builder: Pendenz.Builder<ID>) = register(builder)

    override fun toString(): String = "TaskRegistration($typeName)"

    public companion object {
        /** A Kotlin handler for [type]. */
        @JvmStatic
        public fun <ID : Any, P : Any> of(
            type: TaskType<P>,
            handler: TaskHandler<ID, P>,
        ): TaskRegistration<ID> = TaskRegistration(type.name) { it.register(type, handler) }

        /** A Kotlin handler for [type] that produces a result, stored and validated as [result] says. */
        @JvmStatic
        public fun <ID : Any, P : Any, R : Any> of(
            type: TaskType<P>,
            result: TaskResult<P, R>,
            handler: ResultHandler<ID, P, R>,
        ): TaskRegistration<ID> = TaskRegistration(type.name) { it.register(type, result, handler) }

        /** A blocking handler for [type], as Java code writes it. */
        @JvmStatic
        public fun <ID : Any, P : Any> blocking(
            type: TaskType<P>,
            handler: JavaTaskHandler<ID, P>,
        ): TaskRegistration<ID> = TaskRegistration(type.name) { it.register(type, handler.asTaskHandler()) }

        /** A blocking handler for [type] that produces a result, stored and validated as [result] says. */
        @JvmStatic
        public fun <ID : Any, P : Any, R : Any> blocking(
            type: TaskType<P>,
            result: TaskResult<P, R>,
            handler: JavaResultHandler<ID, P, R>,
        ): TaskRegistration<ID> = TaskRegistration(type.name) { it.register(type, result, handler.asResultHandler()) }
    }
}

/**
 * Receives the queue's builder before the starter builds it, for anything
 * beyond handlers: a clock, cycles, resource limits. Declared as a bean
 * with the queue's id type, like [TaskRegistration].
 */
public fun interface PendenzCustomizer<ID : Any> {
    public fun customize(builder: Pendenz.Builder<ID>)
}
