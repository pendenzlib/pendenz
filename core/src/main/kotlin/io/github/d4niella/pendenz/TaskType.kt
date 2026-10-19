package io.github.d4niella.pendenz

import kotlinx.serialization.json.Json

/**
 * A kind of work the application knows how to do.
 *
 * Task types are registered by the developer together with a [TaskHandler].
 * Anyone enqueuing work, be it the application itself, a UI, another system
 * or an AI agent, can only pick a registered type and supply a payload that
 * the mapping accepts. Nothing else can be enqueued.
 *
 * @property name stored in the type column; stable, part of the schema contract.
 * @property payload how the payload is laid out in the table, see [PayloadMapping] and ADR 0013.
 * @property retryPolicy what happens after the handler throws.
 * @property version the payload schema version this type writes and its handler reads, starting at 1.
 * @property upgrades how to rewrite stored payloads of older versions, keyed by the version they upgrade
 *   from; every step from the oldest supported version up to [version] needs one. See ADR 0010.
 * @property retention what happens to final rows of this type; kept forever by default. See ADR 0011.
 * @property resource what the tasks run against, for concurrency limits; none by default. See ADR 0017.
 * @property claimBatchSize how many tasks of this type a worker takes from one lane per visit; 1 by default.
 *   Larger batches save polls for cheap tasks; lanes still take turns, see ADR 0015.
 * @property approval whether a task must be approved before it runs; never by default. See ADR 0018.
 */
@Suppress("LongParameterList") // one parameter per aspect of a task type, by design
public class TaskType<P : Any>
    @JvmOverloads
    constructor(
        public val name: String,
        public val payload: PayloadMapping<P>,
        public val retryPolicy: RetryPolicy = ExponentialBackoff(),
        public val version: Int = 1,
        public val upgrades: Map<Int, PayloadUpgrade> = emptyMap(),
        public val retention: Retention = Retention.Keep,
        public val resource: TaskResource<P> = TaskResource.None,
        public val claimBatchSize: Int = 1,
        public val approval: Approval<P> = Approval.NEVER,
    ) {
        /** A task type whose payload is one JSON document encoded by [codec], in the column `payload`. */
        @JvmOverloads
        public constructor(
            name: String,
            codec: PayloadCodec<P>,
            retryPolicy: RetryPolicy = ExponentialBackoff(),
            version: Int = 1,
            upgrades: Map<Int, PayloadUpgrade> = emptyMap(),
            retention: Retention = Retention.Keep,
            resource: TaskResource<P> = TaskResource.None,
            claimBatchSize: Int = 1,
            approval: Approval<P> = Approval.NEVER,
        ) : this(
            name,
            JsonPayload(codec),
            retryPolicy,
            version,
            upgrades,
            retention,
            resource,
            claimBatchSize,
            approval,
        )

        init {
            require(name.isNotBlank()) { "task type name must not be blank" }
            require(version >= 1) { "version must be at least 1" }
            require(claimBatchSize >= 1) { "claimBatchSize must be at least 1" }
            upgrades.keys.forEach {
                require(it in 1 until version) { "upgrade from version $it makes no sense for version $version" }
            }
        }

        private val columnNames: Set<String> = payload.columns.map { it.name }.toSet()

        /** The column values of [value], checked against the declared columns. */
        public fun encode(value: P): Map<String, Any?> {
            val values = payload.write(value)
            val unknown = values.keys - columnNames
            require(unknown.isEmpty()) { "task type '$name' wrote undeclared payload columns $unknown" }
            return values
        }

        /**
         * Decodes stored column values, upgrading them first when they are
         * older than [version].
         *
         * @throws UnsupportedPayloadVersionException when no upgrade path exists.
         */
        public fun decode(
            values: Map<String, Any?>,
            storedVersion: Int,
        ): P {
            var current = values
            for (from in storedVersion until version) {
                val step = upgrades[from] ?: throw UnsupportedPayloadVersionException(name, storedVersion, version)
                current = step.upgrade(current)
            }
            if (storedVersion > version) throw UnsupportedPayloadVersionException(name, storedVersion, version)
            return payload.read(PayloadValues(current))
        }

        override fun toString(): String = "TaskType($name, v$version)"

        public companion object {
            /** A task type whose payload is a `@Serializable` class stored as JSON in the column `payload`. */
            public inline fun <reified P : Any> json(
                name: String,
                retryPolicy: RetryPolicy = ExponentialBackoff(),
                json: Json = Json,
                version: Int = 1,
                upgrades: Map<Int, PayloadUpgrade> = emptyMap(),
                retention: Retention = Retention.Keep,
                resource: TaskResource<P> = TaskResource.None,
                claimBatchSize: Int = 1,
                approval: Approval<P> = Approval.NEVER,
            ): TaskType<P> =
                TaskType(
                    name,
                    JsonPayload(KotlinxJsonCodec.of<P>(json)),
                    retryPolicy,
                    version,
                    upgrades,
                    retention,
                    resource,
                    claimBatchSize,
                    approval,
                )

            /**
             * A task type whose payload is spread over typed columns. [write]
             * turns a payload into column values, [read] turns them back.
             */
            @JvmStatic
            @JvmOverloads
            @Suppress("LongParameterList") // the mapping plus the aspects every task type has
            public fun <P : Any> columns(
                name: String,
                columns: List<PayloadColumn>,
                write: (P) -> Map<String, Any?>,
                read: (PayloadValues) -> P,
                retryPolicy: RetryPolicy = ExponentialBackoff(),
                version: Int = 1,
                upgrades: Map<Int, PayloadUpgrade> = emptyMap(),
                retention: Retention = Retention.Keep,
                resource: TaskResource<P> = TaskResource.None,
                claimBatchSize: Int = 1,
                approval: Approval<P> = Approval.NEVER,
            ): TaskType<P> =
                TaskType(
                    name,
                    ColumnsPayload(columns, write, read),
                    retryPolicy,
                    version,
                    upgrades,
                    retention,
                    resource,
                    claimBatchSize,
                    approval,
                )
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
 * @property lane the fairness group the task was enqueued in, null for a single task. See ADR 0015.
 * @property resource what the task runs against, null for none. See ADR 0017.
 */
@Suppress("LongParameterList") // one parameter per thing a handler may ask about, by design
public class TaskContext<ID : Any>(
    public val id: ID,
    public val type: String,
    public val attempt: Int,
    public val createdBy: String?,
    public val lane: String?,
    public val resource: String?,
) {
    override fun toString(): String =
        "TaskContext(id=$id, type=$type, attempt=$attempt, createdBy=$createdBy, lane=$lane, resource=$resource)"
}
