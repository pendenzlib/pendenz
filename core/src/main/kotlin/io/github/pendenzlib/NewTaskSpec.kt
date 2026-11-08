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

/**
 * The row for [payload] at [now]: status by approval rule and run time, resource by spec or type. A taken key
 * replaces a waiting row when the type says so and [replaceable] allows it (ADR 0044).
 */
internal fun <ID : Any, P : Any> TaskType<P>.newTask(
    payload: P,
    spec: NewTaskSpec<ID>,
    now: Instant,
    replaceable: Boolean = true,
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
        replaceWaiting = replaceable && onDuplicateKey == OnDuplicateKey.REPLACE && spec.idempotencyKey != null,
    )
}
