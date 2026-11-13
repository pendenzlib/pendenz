package io.github.pendenzlib

import io.github.pendenzlib.store.CycleStore
import io.github.pendenzlib.store.NewCycle
import io.github.pendenzlib.store.NewTask
import io.github.pendenzlib.store.StoreTransaction
import io.github.pendenzlib.store.TaskStore
import java.time.Instant

/**
 * A cycle about to be started: what `Pendenz.startCycle` takes, or what a
 * handler collected on its context (ADR 0047). [parentId] is the task whose
 * handler started the cycle, null for a start from outside a handler.
 */
@Suppress("LongParameterList") // one field per aspect of a cycle, by design
internal class CycleStart<ID : Any, P : Any>(
    val type: TaskType<P>,
    val tasks: CycleTasks<P>,
    val description: String?,
    val createdBy: String?,
    val priority: Int,
    val id: ID?,
    val resource: CycleResource,
    val correlationId: String?,
    val parentId: ID? = null,
) {
    init {
        require(tasks !is CycleTasks.Query || tasks.resource == null || resource == CycleResource.Default) {
            "the query gives each task its resource; a resource for the whole cycle cannot apply as well"
        }
    }
}

/**
 * Inserts a cycle row and the cycle's tasks in the caller's transaction
 * (ADR 0016). The caller has checked the type, the creator and the
 * permission, and reports the returned event after the commit (ADR 0032).
 */
internal class CycleStarter<ID : Any>(
    private val store: TaskStore<ID>,
    private val cycles: CycleStore<ID>,
) {
    fun <P : Any> start(
        tx: StoreTransaction,
        start: CycleStart<ID, P>,
        now: Instant,
    ): CycleEvent<ID> {
        val type = start.type
        val cycleId = cycles.insertCycle(tx, NewCycle(start.id, type.name, start.description, now, start.createdBy))
        val (inserted, awaiting) = insertTasks(tx, start, CycleLane.of(cycleId), now)
        return CycleEvent(
            cycleId,
            type.name,
            Action.START_CYCLE,
            null,
            CycleStatus.ACTIVE,
            start.createdBy,
            inserted,
            awaiting,
        )
    }

    /** Inserts the tasks of a cycle into [lane]; returns how many were inserted and how many of them wait. */
    private fun <P : Any> insertTasks(
        tx: StoreTransaction,
        start: CycleStart<ID, P>,
        lane: String,
        now: Instant,
    ): Pair<Int, Int> {
        val type = start.type
        val own = (start.resource as? CycleResource.Fixed)?.name
        return when (val tasks = start.tasks) {
            is CycleTasks.Query -> {
                val approval = type.approval.requires(start.createdBy, null)
                val fixed =
                    NewTask(
                        id = null,
                        type = type.name,
                        payload = emptyMap(),
                        runAt = now,
                        status = if (approval) TaskStatus.AWAITING_APPROVAL else TaskStatus.PENDING,
                        createdAt = now,
                        createdBy = start.createdBy,
                        payloadVersion = type.version,
                        lane = lane,
                        priority = start.priority,
                        resource = own ?: (type.resource as? TaskResource.Fixed)?.name,
                        parentId = start.parentId,
                        correlationId = start.correlationId,
                    )
                val count =
                    cycles.insertTasksFromQuery(
                        tx,
                        tasks.sql,
                        type.payload.columns,
                        lane,
                        fixed,
                        tasks.resource,
                    )
                count to if (approval) count else 0
            }

            is CycleTasks.Payloads -> {
                val spec =
                    NewTaskSpec(
                        createdBy = start.createdBy,
                        lane = lane,
                        priority = start.priority,
                        resource = own,
                        parentId = start.parentId,
                        correlationId = start.correlationId,
                    )
                val stored = tasks.payloads.map { type.newTask(it, spec, now) }
                stored.forEach { store.insert(tx, it) }
                stored.size to stored.count { it.status == TaskStatus.AWAITING_APPROVAL }
            }
        }
    }
}
