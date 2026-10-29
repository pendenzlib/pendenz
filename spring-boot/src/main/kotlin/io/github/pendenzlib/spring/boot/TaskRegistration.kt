package io.github.pendenzlib.spring.boot

import io.github.pendenzlib.Pendenz
import io.github.pendenzlib.ResultHandler
import io.github.pendenzlib.TaskHandler
import io.github.pendenzlib.TaskResult
import io.github.pendenzlib.TaskType
import io.github.pendenzlib.javaapi.JavaResultHandler
import io.github.pendenzlib.javaapi.JavaTaskHandler
import io.github.pendenzlib.javaapi.asResultHandler
import io.github.pendenzlib.javaapi.asTaskHandler
import org.springframework.beans.factory.BeanFactory

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
 *
 * Each also takes the handler's bean type instead of an instance. The
 * registration then has no dependencies of its own, and the handler bean
 * is looked up when a task of the type first runs. Use it when the handler
 * depends on a bean that injects the queue: the queue is built from its
 * registrations, so a registration that needs such a bean closes a circle
 * (ADR 0025).
 *
 * ```kotlin
 * @Bean
 * fun sendInvoice(): TaskRegistration<Long> = TaskRegistration.of(SEND_INVOICE, SendInvoiceHandler::class.java)
 * ```
 */
public class TaskRegistration<ID : Any> private constructor(
    /** The name of the registered task type. */
    public val typeName: String,
    /** The handler's bean type, when the handler is looked up on its first run; null for an instance. */
    internal val handlerType: Class<*>?,
    private val register: (Pendenz.Builder<ID>, BeanFactory) -> Unit,
) {
    internal fun registerOn(
        builder: Pendenz.Builder<ID>,
        beans: BeanFactory,
    ) = register(builder, beans)

    override fun toString(): String = "TaskRegistration($typeName)"

    public companion object {
        /** A Kotlin handler for [type]. */
        @JvmStatic
        public fun <ID : Any, P : Any> of(
            type: TaskType<P>,
            handler: TaskHandler<ID, P>,
        ): TaskRegistration<ID> = TaskRegistration(type.name, null) { builder, _ -> builder.register(type, handler) }

        /** A Kotlin handler for [type] that produces a result, stored and validated as [result] says. */
        @JvmStatic
        public fun <ID : Any, P : Any, R : Any> of(
            type: TaskType<P>,
            result: TaskResult<P, R>,
            handler: ResultHandler<ID, P, R>,
        ): TaskRegistration<ID> =
            TaskRegistration(type.name, null) { builder, _ -> builder.register(type, result, handler) }

        /** A blocking handler for [type], as Java code writes it. */
        @JvmStatic
        public fun <ID : Any, P : Any> blocking(
            type: TaskType<P>,
            handler: JavaTaskHandler<ID, P>,
        ): TaskRegistration<ID> =
            TaskRegistration(type.name, null) { builder, _ -> builder.register(type, handler.asTaskHandler()) }

        /** A blocking handler for [type] that produces a result, stored and validated as [result] says. */
        @JvmStatic
        public fun <ID : Any, P : Any, R : Any> blocking(
            type: TaskType<P>,
            result: TaskResult<P, R>,
            handler: JavaResultHandler<ID, P, R>,
        ): TaskRegistration<ID> =
            TaskRegistration(
                type.name,
                null,
            ) { builder, _ -> builder.register(type, result, handler.asResultHandler()) }

        /** The Kotlin handler bean of [handlerType] for [type], looked up when a task of the type first runs. */
        @JvmStatic
        public fun <ID : Any, P : Any> of(
            type: TaskType<P>,
            handlerType: Class<out TaskHandler<ID, P>>,
        ): TaskRegistration<ID> =
            TaskRegistration(type.name, handlerType) { builder, beans ->
                val handler = LazyBean(handlerType, beans)
                builder.register(type) { context, payload -> handler.get().handle(context, payload) }
            }

        /** Like [of] with a handler type, for a handler that produces a result. */
        @JvmStatic
        public fun <ID : Any, P : Any, R : Any> of(
            type: TaskType<P>,
            result: TaskResult<P, R>,
            handlerType: Class<out ResultHandler<ID, P, R>>,
        ): TaskRegistration<ID> =
            TaskRegistration(type.name, handlerType) { builder, beans ->
                val handler = LazyBean(handlerType, beans)
                builder.register(type, result) { context, payload -> handler.get().handle(context, payload) }
            }

        /** The blocking handler bean of [handlerType] for [type], looked up when a task of the type first runs. */
        @JvmStatic
        public fun <ID : Any, P : Any> blocking(
            type: TaskType<P>,
            handlerType: Class<out JavaTaskHandler<ID, P>>,
        ): TaskRegistration<ID> =
            TaskRegistration(type.name, handlerType) { builder, beans ->
                val handler = LazyBean(handlerType, beans)
                val blocking = JavaTaskHandler<ID, P> { context, payload -> handler.get().handle(context, payload) }
                builder.register(type, blocking.asTaskHandler())
            }

        /** Like [blocking] with a handler type, for a handler that produces a result. */
        @JvmStatic
        public fun <ID : Any, P : Any, R : Any> blocking(
            type: TaskType<P>,
            result: TaskResult<P, R>,
            handlerType: Class<out JavaResultHandler<ID, P, R>>,
        ): TaskRegistration<ID> =
            TaskRegistration(type.name, handlerType) { builder, beans ->
                val handler = LazyBean(handlerType, beans)
                val blocking =
                    JavaResultHandler<ID, P, R> {
                        context,
                        payload,
                        ->
                        handler.get().handle(context, payload)
                    }
                builder.register(type, result, blocking.asResultHandler())
            }
    }
}

/** A bean looked up on first use and kept; the starter checks at startup that exactly one exists. */
private class LazyBean<T : Any>(
    private val type: Class<out T>,
    private val beans: BeanFactory,
) {
    private val bean: T by lazy { beans.getBean(type) }

    fun get(): T = bean
}
