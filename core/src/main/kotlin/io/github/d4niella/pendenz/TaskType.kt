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
 * @property version the payload schema version this type writes and its handler reads, starting at 1.
 * @property upgrades how to rewrite stored payloads of older versions, keyed by the version they upgrade
 *   from; every step from the oldest supported version up to [version] needs one. See ADR 0010.
 * @property retention what happens to final rows of this type; kept forever by default. See ADR 0011.
 */
@Suppress("LongParameterList") // one parameter per aspect of a task type, by design
public class TaskType<P : Any>
    @JvmOverloads
    constructor(
        public val name: String,
        public val codec: PayloadCodec<P>,
        public val retryPolicy: RetryPolicy = ExponentialBackoff(),
        public val version: Int = 1,
        public val upgrades: Map<Int, PayloadUpgrade> = emptyMap(),
        public val retention: Retention = Retention.Keep,
    ) {
        init {
            require(name.isNotBlank()) { "task type name must not be blank" }
            require(version >= 1) { "version must be at least 1" }
            upgrades.keys.forEach {
                require(it in 1 until version) { "upgrade from version $it makes no sense for version $version" }
            }
        }

        /**
         * Decodes a stored payload, upgrading it first when it is older than [version].
         *
         * @throws UnsupportedPayloadVersionException when no upgrade path exists.
         */
        public fun decode(
            payload: String,
            storedVersion: Int,
        ): P {
            var text = payload
            for (from in storedVersion until version) {
                val step = upgrades[from] ?: throw UnsupportedPayloadVersionException(name, storedVersion, version)
                text = step.upgrade(text)
            }
            if (storedVersion > version) throw UnsupportedPayloadVersionException(name, storedVersion, version)
            return codec.decode(text)
        }

        override fun toString(): String = "TaskType($name, v$version)"

        public companion object {
            /** A task type whose payload is a `@Serializable` class stored as JSON. */
            public inline fun <reified P : Any> json(
                name: String,
                retryPolicy: RetryPolicy = ExponentialBackoff(),
                json: Json = Json,
                version: Int = 1,
                upgrades: Map<Int, PayloadUpgrade> = emptyMap(),
                retention: Retention = Retention.Keep,
            ): TaskType<P> = TaskType(name, KotlinxJsonCodec.of<P>(json), retryPolicy, version, upgrades, retention)
        }
    }

/**
 * The code that runs a task. Return normally for success, throw for failure.
 *
 * Handlers are registered as instances, so they are built by whatever wiring
 * the application already has, with or without a dependency injection container.
 *
 * @param ID the id type of the table the handler is registered on.
 * @param P the payload type.
 */
public fun interface TaskHandler<ID : Any, P : Any> {
    public suspend fun handle(
        context: TaskContext<ID>,
        payload: P,
    )
}

/**
 * What a handler may know about the task it is running.
 *
 * @property id the row's id.
 * @property type the task type name.
 * @property attempt which attempt this is, starting at 1.
 * @property createdBy who or what enqueued the task, null when not given.
 */
public class TaskContext<ID : Any>(
    public val id: ID,
    public val type: String,
    public val attempt: Int,
    public val createdBy: String?,
) {
    override fun toString(): String = "TaskContext(id=$id, type=$type, attempt=$attempt, createdBy=$createdBy)"
}
