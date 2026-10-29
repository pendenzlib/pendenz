package io.github.pendenzlib.micrometer

import io.github.pendenzlib.CycleEvent
import io.github.pendenzlib.OperatorMove
import io.github.pendenzlib.Pendenz
import io.github.pendenzlib.TaskContext
import io.github.pendenzlib.TaskListener
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Micrometer meters for one queue: counters and timers from the listener
 * events, and, once [watchBacklog] runs, gauges for what waits in the
 * table. See ADR 0022 for the meters and their tags.
 *
 * Register it on the builder with `listener(metrics)`. Use one instance per
 * queue and tell queues apart with [tags], such as `queue=billing`.
 *
 * @param registry where the meters go.
 * @param tags added to every meter.
 * @param clock the clock the queue runs on; durations of runs that did not succeed are measured with it
 *   from the claim on, and the age of the oldest due task at every scrape.
 */
@Suppress("TooManyFunctions") // one method per listener event, the interface dictates the count
public class PendenzMetrics<ID : Any>
    @JvmOverloads
    constructor(
        private val registry: MeterRegistry,
        tags: Iterable<Tag> = Tags.empty(),
        private val clock: Clock = Clock.systemUTC(),
    ) : TaskListener<ID> {
        private val tags: Tags = Tags.of(tags)
        private val backlog = BacklogGauges(registry, this.tags, clock)

        override fun onEnqueued(
            id: ID,
            type: String,
        ) {
            enqueued(type, 1)
        }

        override fun onClaimed(task: TaskContext<ID>) {
            timer(WAIT, "Time from a task's run time to its claim", Tags.of(TYPE, task.type))
                .record(Duration.between(task.dueAt, task.claimedAt).coerceAtLeast(Duration.ZERO))
        }

        override fun onSucceeded(
            task: TaskContext<ID>,
            duration: Duration,
        ) {
            execution(task, "succeeded", null, duration)
        }

        override fun onRetryScheduled(
            task: TaskContext<ID>,
            error: Throwable,
            nextRunAt: Instant,
        ) {
            execution(task, "retry", error)
        }

        override fun onRejected(
            task: TaskContext<ID>,
            reason: String,
        ) {
            execution(task, "rejected", null)
        }

        override fun onFailed(
            task: TaskContext<ID>,
            error: Throwable,
        ) {
            execution(task, "failed", error)
        }

        override fun onReleased(task: TaskContext<ID>) {
            execution(task, "released", null)
        }

        override fun onOutcomeDropped(task: TaskContext<ID>) {
            execution(task, "dropped", null)
        }

        override fun onRetentionApplied(
            type: String,
            count: Int,
        ) {
            counter(RETENTION, "Finished tasks deleted or archived by retention", type).increment(count.toDouble())
        }

        override fun onAwaitingApproval(
            id: ID,
            type: String,
        ) {
            approval(type, "requested", 1)
        }

        override fun onApproved(
            id: ID,
            type: String,
            actor: String,
        ) {
            approval(type, "approved", 1)
        }

        override fun onDeclined(
            id: ID,
            type: String,
            actor: String,
            reason: String,
        ) {
            approval(type, "declined", 1)
        }

        override fun onOperatorMove(move: OperatorMove<ID>) {
            Counter
                .builder(OPERATOR)
                .description("Tasks restarted, completed, quarantined or cancelled by an operator")
                .tags(tags)
                .tags(TYPE, move.type, ACTION, move.action.name.lowercase())
                .register(registry)
                .increment()
        }

        override fun onCycleStarted(cycle: CycleEvent<ID>) {
            cycleOperation(cycle)
            enqueued(cycle.type, cycle.tasks)
            approval(cycle.type, "requested", cycle.awaitingApproval)
        }

        override fun onCycleApproved(cycle: CycleEvent<ID>) {
            cycleOperation(cycle)
            approval(cycle.type, "approved", cycle.tasks)
        }

        override fun onCycleMoved(cycle: CycleEvent<ID>) {
            cycleOperation(cycle)
        }

        /** Reads [Pendenz.backlog] once and updates the backlog gauges; throws when the query fails. */
        public suspend fun refreshBacklog(pendenz: Pendenz<*>) {
            backlog.update(pendenz.backlog())
        }

        /**
         * Like [refreshBacklog] for callers without coroutines that schedule
         * the refresh themselves, such as Spring's `@Scheduled` or Quartz:
         * blocks until the gauges are updated.
         */
        public fun refreshBacklogBlocking(pendenz: Pendenz<*>) {
            runBlocking { refreshBacklog(pendenz) }
        }

        /**
         * Refreshes the backlog gauges every [every] in [scope], until the
         * scope ends or the returned job is cancelled. A failed query is
         * logged and the gauges keep their last values. Scrapes read those
         * values and never touch the database.
         */
        @JvmOverloads
        public fun watchBacklog(
            pendenz: Pendenz<*>,
            scope: CoroutineScope,
            every: Duration = DEFAULT_BACKLOG_INTERVAL,
        ): Job {
            requirePositive(every)
            return scope.launch(CoroutineName("pendenz-backlog")) {
                while (isActive) {
                    refreshLogged(pendenz)
                    delay(every.toMillis())
                }
            }
        }

        /**
         * Like [watchBacklog] for callers without coroutines, such as Java:
         * refreshes on [executor] every [every] until the returned future is
         * cancelled.
         */
        @JvmOverloads
        public fun watchBacklog(
            pendenz: Pendenz<*>,
            executor: ScheduledExecutorService,
            every: Duration = DEFAULT_BACKLOG_INTERVAL,
        ): ScheduledFuture<*> {
            requirePositive(every)
            return executor.scheduleWithFixedDelay(
                { runBlocking { refreshLogged(pendenz) } },
                0,
                every.toMillis(),
                TimeUnit.MILLISECONDS,
            )
        }

        /** Zero would read the backlog in a loop without a pause. */
        private fun requirePositive(every: Duration) {
            require(every.toMillis() >= 1) { "the backlog interval must be at least 1 ms, was $every" }
        }

        // A failed refresh must not end the loop; the next one may succeed.
        @Suppress("TooGenericExceptionCaught")
        private suspend fun refreshLogged(pendenz: Pendenz<*>) {
            try {
                refreshBacklog(pendenz)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("reading the backlog failed, the gauges keep their last values", e)
            }
        }

        private fun execution(
            task: TaskContext<ID>,
            outcome: String,
            error: Throwable?,
            duration: Duration = Duration.between(task.claimedAt, clock.instant()),
        ) {
            val exception = error?.javaClass?.let { it.simpleName.ifEmpty { it.name } } ?: "none"
            val meterTags = Tags.of(TYPE, task.type, OUTCOME, outcome, EXCEPTION, exception)
            timer(EXECUTION, "Runs of a handler, by how they ended", meterTags)
                .record(duration.coerceAtLeast(Duration.ZERO))
        }

        private fun enqueued(
            type: String,
            count: Int,
        ) {
            counter(ENQUEUED, "Tasks stored by enqueue and by cycle starts", type).increment(count.toDouble())
        }

        private fun approval(
            type: String,
            decision: String,
            count: Int,
        ) {
            if (count == 0) return
            Counter
                .builder(APPROVALS)
                .description("Tasks held for approval, approved and declined")
                .tags(tags)
                .tags(TYPE, type, DECISION, decision)
                .register(registry)
                .increment(count.toDouble())
        }

        private fun cycleOperation(cycle: CycleEvent<ID>) {
            Counter
                .builder(CYCLES)
                .description("Operations on cycles: start, approve, pause, resume, cancel")
                .tags(tags)
                .tags(
                    TYPE,
                    cycle.type,
                    ACTION,
                    cycle.action.name
                        .lowercase()
                        .removeSuffix("_cycle"),
                ).register(registry)
                .increment()
        }

        private fun counter(
            name: String,
            description: String,
            type: String,
        ): Counter =
            Counter
                .builder(name)
                .description(description)
                .tags(tags)
                .tag(TYPE, type)
                .register(registry)

        private fun timer(
            name: String,
            description: String,
            meterTags: Tags,
        ): Timer =
            Timer
                .builder(name)
                .description(description)
                .tags(tags)
                .tags(meterTags)
                .register(registry)

        public companion object {
            /** How often [watchBacklog] reads the backlog unless told otherwise. */
            @JvmField
            public val DEFAULT_BACKLOG_INTERVAL: Duration = Duration.ofSeconds(30)

            public const val ENQUEUED: String = "pendenz.tasks.enqueued"
            public const val WAIT: String = "pendenz.tasks.wait"
            public const val EXECUTION: String = "pendenz.tasks.execution"
            public const val APPROVALS: String = "pendenz.tasks.approvals"
            public const val RETENTION: String = "pendenz.tasks.retention"
            public const val OPERATOR: String = "pendenz.tasks.operator"
            public const val CYCLES: String = "pendenz.cycles.operations"
            public const val BACKLOG: String = "pendenz.tasks.backlog"
            public const val BACKLOG_OLDEST: String = "pendenz.tasks.backlog.oldest"

            /** Tag key: the task type, on every meter. */
            public const val TYPE: String = "type"

            /** Tag key of [EXECUTION]: how the run ended, such as `succeeded`, `retry` or `failed`. */
            public const val OUTCOME: String = "outcome"

            /** Tag key of [EXECUTION]: the simple class name of what the handler threw, or `none`. */
            public const val EXCEPTION: String = "exception"

            /** Tag key of [APPROVALS]: `requested`, `approved` or `declined`. */
            public const val DECISION: String = "decision"

            /**
             * Tag key of [OPERATOR] and [CYCLES]: the operation in lower case, such as `restart` or `cancel`;
             * for cycles without the suffix, such as `start` or `pause`.
             */
            public const val ACTION: String = "action"

            /** Tag key of [BACKLOG]: the task status counted. */
            public const val STATUS: String = "status"

            private val log = LoggerFactory.getLogger(PendenzMetrics::class.java)
        }
    }
