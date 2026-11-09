package io.github.pendenzlib

import java.time.Instant

/**
 * One task of [Pendenz.enqueueAll]: what a single `enqueue` takes per task.
 * The creator is given once per call. See ADR 0046.
 *
 * @property type the task's type, registered with the queue.
 * @property payload the task's payload.
 * @property idempotencyKey a key unique per task type; a taken key stores nothing and yields the existing
 *   task's id, or replaces a waiting task when the type says so. See ADR 0009 and ADR 0044.
 * @property runAt when the task is due; now when null.
 * @property lane the fairness group within the type, or null. Lanes starting with `cycle:` are refused.
 * @property priority higher runs first, strictly; 0 by default.
 * @property resource what the task runs against; the type's choice when null. See ADR 0017.
 * @property correlationId ties the task and its follow-ups together; [CallerDefaults.correlationId] when null.
 */
@Suppress("LongParameterList") // one parameter per concept a single enqueue takes per task
public class EnqueueRequest<P : Any>
    @JvmOverloads
    constructor(
        public val type: TaskType<P>,
        public val payload: P,
        public val idempotencyKey: String? = null,
        public val runAt: Instant? = null,
        public val lane: String? = null,
        public val priority: Int = 0,
        public val resource: String? = null,
        public val correlationId: String? = null,
    )
