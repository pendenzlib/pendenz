package io.github.pendenzlib.spring.boot

import io.github.pendenzlib.Pendenz
import io.github.pendenzlib.Schedule
import io.github.pendenzlib.TaskType

/**
 * A recurring task, declared as a bean; the starter registers every such
 * bean on the queue with `Pendenz.Builder.schedule`, after the
 * [TaskRegistration]s and before the [PendenzCustomizer]s (ADR 0034). It
 * carries no id type, since a schedule does not depend on it:
 *
 * ```kotlin
 * @Bean
 * fun nightlyReport(): ScheduleRegistration =
 *     ScheduleRegistration.of("nightly-report", NIGHTLY_REPORT, ReportRequest("all"), Schedule.daily(THREE_AM, BERLIN))
 * ```
 *
 * The properties `pendenz.schedules.<name>.enabled`, `.cron` and `.zone`
 * switch the schedule off or replace its [schedule] per environment.
 */
public class ScheduleRegistration private constructor(
    /** The schedule's name, unique within the queue; the key of its properties. */
    public val name: String,
    /** The name of the task type it enqueues, which must be registered on the queue. */
    public val typeName: String,
    /** When it fires, unless `pendenz.schedules.<name>.cron` replaces it. */
    public val schedule: Schedule,
    private val register: (Pendenz.Builder<*>, Schedule) -> Unit,
) {
    /** Adds the schedule to [builder], firing at [at]. */
    internal fun registerOn(
        builder: Pendenz.Builder<*>,
        at: Schedule,
    ) = register(builder, at)

    override fun toString(): String = "ScheduleRegistration($name, $typeName, $schedule)"

    public companion object {
        /** Enqueue [payload] as a task of [type] at every fire time of [schedule], under [name]. */
        @JvmStatic
        public fun <P : Any> of(
            name: String,
            type: TaskType<P>,
            payload: P,
            schedule: Schedule,
        ): ScheduleRegistration =
            ScheduleRegistration(name, type.name, schedule) { builder, at -> builder.schedule(name, type, payload, at) }
    }
}
