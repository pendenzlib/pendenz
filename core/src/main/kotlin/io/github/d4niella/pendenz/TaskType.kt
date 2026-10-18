package io.github.d4niella.pendenz

import kotlinx.serialization.json.Json

/**
 * A kind of work the application knows how to do.
 *
 * Task types are registered by the developer together with a [TaskHandler].
 * Anyone enqueuing work, be it the application itself, a UI, another system
 * or an AI agent, can only pick a registered type and supply a payload that
 * the codec accepts. Nothing else can be enqueued.
 *
 * @property name stored in the type column; stable, part of the schema contract.
 * @property codec how the payload is written to and read from the payload column.
 * @property retryPolicy what happens after the handler throws.
 */
public class TaskType<P : Any>(
    public val name: String,
    public val codec: PayloadCodec<P>,
    public val retryPolicy: RetryPolicy = ExponentialBackoff(),
) {
    init {
        require(name.isNotBlank()) { "task type name must not be blank" }
    }

    override fun toString(): String = "TaskType($name)"

    public companion object {
        /** A task type whose payload is a `@Serializable` class stored as JSON. */
        public inline fun <reified P : Any> json(
            name: String,
            retryPolicy: RetryPolicy = ExponentialBackoff(),
            json: Json = Json,
        ): TaskType<P> = TaskType(name, KotlinxJsonCodec.of<P>(json), retryPolicy)
    }
}

/**
 * The code that runs a task. Return normally for success, throw for failure.
 *
 * Handlers are registered as instances, so they are built by whatever wiring
 * the application already has, with or without a dependency injection container.
 */
public fun interface TaskHandler<P : Any> {
    public suspend fun handle(
        context: TaskContext,
        payload: P,
    )
}

/**
 * What a handler may know about the task it is running.
 *
 * @property id the row's id.
 * @property type the task type name.
 * @property attempt which attempt this is, starting at 1.
 */
public class TaskContext(
    public val id: Long,
    public val type: String,
    public val attempt: Int,
) {
    override fun toString(): String = "TaskContext(id=$id, type=$type, attempt=$attempt)"
}
