package io.github.pendenzlib

import io.github.pendenzlib.store.NewTask
import java.time.Instant

/** The optional parts of an enqueue, shared by `Pendenz.enqueue`, cycles and follow-ups. */
@Suppress("LongParameterList") // one field per concept the caller may set, by design
internal class NewTaskSpec<ID : Any>(
    val runAt: Instant? = null,
    val id: ID? = null,
    val idempotencyKey: String? = null,
    val createdBy: String? = null,
    val lane: String? = null,
    val priority: Int = 0,
    val resource: String? = null,
    val parentId: ID? = null,
    val correlationId: String? = null,
)

/** The row for [payload] at [now]: status by approval rule and run time, resource by spec or type. */
internal fun <ID : Any, P : Any> TaskType<P>.newTask(
    payload: P,
    spec: NewTaskSpec<ID>,
    now: Instant,
): NewTask<ID> {
    val due = spec.runAt ?: now
    val status =
        when {
            approval.requires(spec.createdBy, payload) -> TaskStatus.AWAITING_APPROVAL
            due.isDue(now) -> TaskStatus.PENDING
            else -> TaskStatus.SCHEDULED
        }
    return NewTask(
        id = spec.id,
        type = name,
        payload = encode(payload),
        runAt = due,
        status = status,
        createdAt = now,
        idempotencyKey = spec.idempotencyKey,
        createdBy = spec.createdBy,
        payloadVersion = version,
        lane = spec.lane,
        priority = spec.priority,
        resource = spec.resource ?: resource.resourceFor(payload),
        parentId = spec.parentId,
        correlationId = spec.correlationId,
        preview = preview?.invoke(payload),
    )
}
