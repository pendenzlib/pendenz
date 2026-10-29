package io.github.pendenzlib

import io.github.pendenzlib.store.NewTask
import io.github.pendenzlib.store.StoreTransaction
import io.github.pendenzlib.store.TaskStore
import java.time.Instant

/**
 * A schedule as registered on the builder: which type to enqueue with
 * which payload, and when (ADR 0033).
 */
internal class ScheduledTask<P : Any>(
    val name: String,
    val type: TaskType<P>,
    val payload: P,
    val schedule: Schedule,
) {
    init {
        require(NAME.matches(name)) {
            "schedule name '$name' must be 1 to $MAX_NAME_LENGTH letters, digits, '.', '_' or '-'"
        }
        // A payload the mapping refuses fails when the queue is built, not at the first fire time
        type.encode(payload)
    }

    /** The creator of every occurrence; it finds them in the table. */
    val creator: String = ScheduleMark.PREFIX + name

    /** The occurrence at [fireTime], keyed by it, so that every instance inserts it at most once. */
    fun <ID : Any> occurrence(
        fireTime: Instant,
        now: Instant,
    ): NewTask<ID> {
        val spec = NewTaskSpec<ID>(runAt = fireTime, idempotencyKey = "$creator@$fireTime", createdBy = creator)
        return type.newTask(payload, spec, now)
    }

    override fun toString(): String = "ScheduledTask($name, ${type.name}, $schedule)"

    private companion object {
        const val MAX_NAME_LENGTH = 100
        val NAME = Regex("[A-Za-z0-9._-]{1,$MAX_NAME_LENGTH}")
    }
}

/**
 * The `schedule:` prefix that marks occurrences in `created_by` and in the
 * idempotency key; nobody else may use it (ADR 0033).
 */
internal object ScheduleMark {
    const val PREFIX: String = "schedule:"

    fun requireNotReserved(
        what: String,
        value: String?,
    ) {
        require(value?.startsWith(PREFIX) != true) { "$what '$value' starts with '$PREFIX', which schedules reserve" }
    }
}

/** An occurrence that was inserted, for the listener. */
internal class Occurrence<ID : Any>(
    val id: ID,
    val task: NewTask<ID>,
) {
    fun report(listener: TaskListener<ID>) {
        listener.onEnqueued(id, task.type)
        if (task.status == TaskStatus.AWAITING_APPROVAL) listener.onAwaitingApproval(id, task.type)
    }
}

/** Keeps one open occurrence ahead per schedule (ADR 0033). */
internal class Occurrences<ID : Any>(
    private val store: TaskStore<ID>,
    val schedules: List<ScheduledTask<*>>,
) {
    private val byCreator = schedules.associateBy { it.creator }

    /**
     * After a final outcome of a task created by [createdBy], in the same
     * transaction: the next occurrence, when the task belongs to a schedule
     * and nothing else of it is open.
     */
    fun afterOutcome(
        tx: StoreTransaction,
        createdBy: String?,
        now: Instant,
    ): Occurrence<ID>? = byCreator[createdBy]?.let { ensure(tx, it, now) }

    /**
     * Inserts the occurrence at the next fire time after [now], unless the
     * newest task the schedule created is open, or the series has ended.
     * Returns the occurrence when a row was inserted; a fire time whose
     * occurrence exists already, say one an operator cancelled, inserts none.
     */
    fun ensure(
        tx: StoreTransaction,
        scheduled: ScheduledTask<*>,
        now: Instant,
    ): Occurrence<ID>? {
        store.lockSchedule(tx, scheduled.name)
        val newest = store.search(tx, TaskQuery(scheduled.creator, setOf(scheduled.type.name), limit = 1))
        val fireTime = scheduled.schedule.next(now)
        if (newest.firstOrNull()?.status?.let { it in OPEN } == true || fireTime == null) return null
        val task = scheduled.occurrence<ID>(fireTime, now)
        val inserted = store.insert(tx, task)
        return Occurrence(inserted.id, task).takeIf { inserted.isNew }
    }

    private companion object {
        /** What holds a series: an occurrence that will still run, or waits to be approved. */
        val OPEN =
            setOf(
                TaskStatus.SCHEDULED,
                TaskStatus.PENDING,
                TaskStatus.RUNNING,
                TaskStatus.RETRY_PENDING,
                TaskStatus.AWAITING_APPROVAL,
            )
    }
}
