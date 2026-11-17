package io.github.pendenzlib

import io.github.pendenzlib.store.ClaimedTask
import io.github.pendenzlib.store.Outcome
import io.github.pendenzlib.store.TaskStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant

/**
 * How a run ended: the outcome to record, what the handler threw if it
 * threw, the block it asked for, and the reason it gave for a deferral.
 */
internal class EndedRun(
    val outcome: Outcome,
    val cause: Throwable?,
    val block: DecidedBlock? = null,
    val reason: String? = null,
)

/**
 * What was written for a run: how it ended as far as the row says, when,
 * and the events of what was written with it, in their order.
 */
internal class Recorded<ID : Any>(
    val ended: EndedRun,
    val at: Instant,
    private val events: List<(TaskListener<ID>) -> Unit>,
) {
    /** Reports the follow-ups, cycles and the occurrence written with the outcome. */
    fun reportTo(listener: TaskListener<ID>) = events.forEach { it(listener) }
}

/**
 * Writes the outcome of a run. A successful outcome takes the handler's
 * follow-ups with it, in the same transaction (ADR 0019), and the cycles
 * it started (ADR 0047); a final outcome of an occurrence takes its
 * schedule's next one (ADR 0033); a resource the handler reported
 * unavailable is blocked with it (ADR 0035). A write that still fails
 * after its repeats is followed by a second one of the row alone, so
 * that the task leaves RUNNING with the cause instead of waiting for its
 * lock to expire (ADR 0048).
 */
internal class OutcomeWriter<ID : Any>(
    private val workerId: String,
    private val store: TaskStore<ID>,
    private val registry: TaskRegistry<ID>,
    private val clock: Clock,
    private val options: WorkerOptions,
) {
    private val log = LoggerFactory.getLogger(OutcomeWriter::class.java)
    private val occurrences = Occurrences(store, registry.schedules)
    private val cycleStarter = store.cycles?.let { CycleStarter(store, it) }

    /**
     * Writes how [task]'s run [ended] and returns what was written, or
     * null when the row moved on meanwhile, for example by an operator's
     * SQL. A failure of the second write propagates, and the lock expires.
     */
    @Suppress("TooGenericExceptionCaught") // whatever the store throws, the attempt must still get an outcome
    suspend fun write(
        task: ClaimedTask<ID>,
        context: TaskContext<ID>,
        ended: EndedRun,
    ): Recorded<ID>? {
        val now = clock.instant()
        return try {
            repeatedly(task) { writeAll(task, context, ended, now) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            writeRowAlone(task, context, ended, e)
        }
    }

    /** The outcome and everything that belongs to it, in one transaction; null when the row is not ours any more. */
    private suspend fun writeAll(
        task: ClaimedTask<ID>,
        context: TaskContext<ID>,
        ended: EndedRun,
        now: Instant,
    ): Recorded<ID>? {
        val outcome = ended.outcome
        val followUps = if (outcome is Outcome.Succeeded) context.followUps else emptyList()
        val cycleStarts = if (outcome is Outcome.Succeeded) context.cycleStarts else emptyList()
        return store.transaction { tx ->
            if (!store.finish(tx, task.id, task.version, outcome, now)) return@transaction null
            val events = ArrayList<(TaskListener<ID>) -> Unit>()
            // A follow-up whose idempotency key was taken stored no row and reports no enqueue (ADR 0032); one
            // that replaced a waiting task may wait for approval again (ADR 0044)
            followUps.zip(store.insertAll(tx, followUps)).forEach { (followUp, inserted) ->
                if (inserted.isNew) events += { it.onEnqueued(inserted.id, followUp.type) }
                if ((inserted.isNew || inserted.replaced) && followUp.status == TaskStatus.AWAITING_APPROVAL) {
                    events += { it.onAwaitingApproval(inserted.id, followUp.type) }
                }
            }
            // The handler could only collect a start with cycles enabled, so the starter exists
            cycleStarts.forEach { start ->
                val started = cycleStarter!!.start(tx, start, now)
                events += { it.onCycleStarted(started) }
            }
            if (outcome !is Outcome.Retry && outcome !is Outcome.Deferred) {
                val next = occurrences.afterOutcome(tx, task.createdBy, task.type, now)
                if (next != null) events += { next.report(it) }
            }
            ended.block?.let { store.resourceBlocks?.block(tx, it.resource, it.until, it.reason, now) }
            Recorded(ended, now, events)
        }
    }

    /**
     * Runs [write], and runs it again after a pause when it fails, as long as
     * half the lock timeout has not passed: the work is done, and losing the
     * outcome to a brief database hiccup would let the lock expire and the
     * work run once more. A write the database refuses is repeated only while
     * its pauses fit into the type's or the worker's `refusedWriteRetry`,
     * since waiting does not change a refusal. Past that point the failure
     * propagates.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> repeatedly(
        task: ClaimedTask<ID>,
        write: suspend () -> T,
    ): T {
        val giveUpAt = System.nanoTime() + options.lockTimeout.toNanos() / 2
        val refusedRetry = registry.refusedWriteRetry(task.type, options.refusedWriteRetry).toMillis()
        var paused = 0L
        var pause = FIRST_PAUSE_MILLIS
        while (true) {
            try {
                return write()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val outOfTime = System.nanoTime() + pause * NANOS_PER_MILLI > giveUpAt
                val refusedLongEnough = RefusedWrite.isRefused(e) && paused + pause > refusedRetry
                if (outOfTime || refusedLongEnough) throw e
                log.warn(
                    "worker {} could not record the outcome of task {}, again in {} ms",
                    workerId,
                    task.id,
                    pause,
                    e,
                )
                delay(pause)
                paused += pause
                pause = (pause * 2).coerceAtMost(MAX_PAUSE_MILLIS)
            }
        }
    }

    /**
     * The write of the outcome failed for good with [failure]: the row
     * alone is written, in a transaction of its own. After a FAILED of an
     * occurrence, its schedule gets the next one in a further transaction.
     */
    private suspend fun writeRowAlone(
        task: ClaimedTask<ID>,
        context: TaskContext<ID>,
        ended: EndedRun,
        failure: Exception,
    ): Recorded<ID>? {
        log.error(
            "worker {} could not record outcome {} of task {}, recording the attempt without it",
            workerId,
            ended.outcome::class.simpleName,
            task.id,
            failure,
        )
        val now = clock.instant()
        val second = withoutOutcome(task, context, ended, failure, now)
        if (!store.transaction { store.finish(it, task.id, task.version, second.outcome, now) }) return null
        val next = if (second.outcome is Outcome.Failed) nextOccurrence(task, now) else null
        return Recorded(second, now, listOfNotNull(next).map { occurrence -> { occurrence.report(it) } })
    }

    /**
     * What the second write records instead of [ended]. An attempt that had
     * failed already keeps the decision of its retry policy, and after a
     * refusal only the class name of its text. Any other outcome becomes a
     * failed attempt with an [OutcomeNotRecordedException], and the policy
     * is asked.
     */
    private fun withoutOutcome(
        task: ClaimedTask<ID>,
        context: TaskContext<ID>,
        ended: EndedRun,
        failure: Exception,
        now: Instant,
    ): EndedRun {
        val outcome = ended.outcome
        val provenance = outcome.provenance
        if (outcome is Outcome.Retry || outcome is Outcome.Failed) {
            if (!RefusedWrite.isRefused(failure)) return ended
            // Of what this write contains, the text is what a constraint most likely refused: the class name fits
            val cause = checkNotNull(ended.cause) { "a failed attempt has a cause" }
            val name = cause.javaClass.name
            val retryAt = (outcome as? Outcome.Retry)?.runAt
            val shortened =
                if (retryAt == null) Outcome.Failed(name, provenance) else Outcome.Retry(retryAt, name, provenance)
            return EndedRun(shortened, cause)
        }
        val notRecorded = OutcomeNotRecordedException.of(outcome, failure)
        val error = registry.describe(context, notRecorded)
        val next = registry.nextAttemptAt(task, now, notRecorded)
        val failed = if (next == null) Outcome.Failed(error, provenance) else Outcome.Retry(next, error, provenance)
        return EndedRun(failed, notRecorded)
    }

    /** A failure is logged; the maintenance sweep inserts the occurrence later (ADR 0033). */
    @Suppress("TooGenericExceptionCaught") // the task has its outcome; the sweep covers whatever goes wrong here
    private suspend fun nextOccurrence(
        task: ClaimedTask<ID>,
        now: Instant,
    ): Occurrence<ID>? =
        try {
            store.transaction { occurrences.afterOutcome(it, task.createdBy, task.type, now) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("worker {} could not insert the next occurrence after task {}", workerId, task.id, e)
            null
        }

    private companion object {
        const val FIRST_PAUSE_MILLIS = 100L
        const val MAX_PAUSE_MILLIS = 5_000L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
