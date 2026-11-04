package io.github.pendenzlib

import io.github.pendenzlib.store.ClaimedTask
import io.github.pendenzlib.store.Outcome
import io.github.pendenzlib.store.TaskStore
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * A decision a handler reports by throwing, instead of a failed attempt
 * (ADR 0035). The worker looks for one in what the handler threw and in its
 * causes, so client code deep in the handler may throw it, and records the
 * outcome without asking the type's [RetryPolicy]. The first one found in
 * the cause chain wins.
 */
public sealed class HandlerDecisionException(
    message: String,
) : RuntimeException(message)

/**
 * The work cannot be done yet, and that is not a failure: the task runs
 * again at [at] and the attempt does not count. A time that is not after
 * now is a programming error; it counts as a failed attempt for the retry
 * policy, so that a handler cannot make a task loop at the poll rate.
 */
public class RetryLaterException(
    public val at: Instant,
    public val reason: String,
) : HandlerDecisionException(reason)

/**
 * The resource is unavailable, not only this task: the task runs again at
 * [until], the attempt does not count, and no task of [resource], or of the
 * task's own resource when that is null, is claimed before [until]. The
 * block needs `enableResourceBlocks()`; without it, or for a task with no
 * resource and none named here, the task is only deferred.
 */
public class ResourceUnavailableException
    @JvmOverloads
    constructor(
        public val until: Instant,
        public val reason: String,
        public val resource: String? = null,
    ) : HandlerDecisionException(reason)

/**
 * A person must look at this task: it is set aside as `QUARANTINED` with
 * [reason], without a retry, as an operator's quarantine would.
 */
public class HandOverException(
    public val reason: String,
) : HandlerDecisionException(reason)

/** Turns a decision the handler threw into the outcome to record (ADR 0035). */
internal class Decisions<ID : Any>(
    private val store: TaskStore<ID>,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(Decisions::class.java)

    /** Resources whose block was asked for without `enableResourceBlocks()`, warned about once each. */
    private val unblockable = ConcurrentHashMap.newKeySet<String>()

    /**
     * The outcome of the decision in [cause] or its causes; null when there
     * is none, or when its time is not after now and it counts as a failed
     * attempt for the retry policy.
     */
    fun outcomeOf(
        task: ClaimedTask<ID>,
        context: TaskContext<ID>,
        cause: Throwable,
    ): Decided? {
        val decision = decisionIn(cause) ?: return null
        val provenance = context.provenance
        return when (decision) {
            is HandOverException -> {
                log.info("task {} handed over to a person: {}", task.id, decision.reason)
                Decided(Outcome.HandedOver(decision.reason, provenance))
            }

            is RetryLaterException -> {
                deferral(task, decision.at, decision.reason, provenance) { null }
            }

            is ResourceUnavailableException -> {
                deferral(task, decision.until, decision.reason, provenance) { blockFor(task, decision) }
            }
        }
    }

    /** A deferral to [at], with the block [block] asks for; null when [at] is not after now. */
    private fun deferral(
        task: ClaimedTask<ID>,
        at: Instant,
        reason: String,
        provenance: Map<String, String>,
        block: () -> DecidedBlock?,
    ): Decided? {
        if (!at.isAfter(clock.instant())) {
            log.warn("task {} deferred to {}, which is not after now; counted as a failed attempt", task.id, at)
            return null
        }
        log.debug("task {} deferred until {}: {}", task.id, at, reason)
        return Decided(Outcome.Deferred(at, "deferred until $at: $reason", provenance), block(), reason)
    }

    /** The block a [decision] asks for, or null when there is no resource or blocks are not enabled. */
    private fun blockFor(
        task: ClaimedTask<ID>,
        decision: ResourceUnavailableException,
    ): DecidedBlock? {
        val resource = decision.resource ?: task.resource ?: return null
        if (store.resourceBlocks == null) {
            if (unblockable.add(resource)) {
                log.warn(
                    "task {} reported resource {} unavailable, but resource blocks are not enabled; " +
                        "only the task is deferred. Call enableResourceBlocks() on the builder",
                    task.id,
                    resource,
                )
            }
            return null
        }
        return DecidedBlock(resource, decision.until, decision.reason)
    }
}

/** What a decision records: the outcome, the block it asks for, and the reason the handler gave for a deferral. */
internal class Decided(
    val outcome: Outcome,
    val block: DecidedBlock? = null,
    val reason: String? = null,
)

/** A resource to block in the transaction that records the outcome. */
internal class DecidedBlock(
    val resource: String,
    val until: Instant,
    val reason: String,
)

/** The first decision in [error] and its causes, or null when the chain holds none. */
internal fun decisionIn(error: Throwable): HandlerDecisionException? =
    generateSequence(error) { it.cause.takeIf { cause -> cause !== it } }
        .take(MAX_CAUSE_DEPTH)
        .filterIsInstance<HandlerDecisionException>()
        .firstOrNull()

/** Deep enough for any wrapping a client or framework does; a cause chain with a loop stops here. */
private const val MAX_CAUSE_DEPTH = 32
