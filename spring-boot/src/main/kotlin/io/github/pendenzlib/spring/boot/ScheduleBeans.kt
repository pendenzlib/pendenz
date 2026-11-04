package io.github.pendenzlib.spring.boot

import io.github.pendenzlib.Pendenz
import io.github.pendenzlib.Schedule
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ListableBeanFactory
import java.time.DateTimeException
import java.time.ZoneId

/**
 * Registers the [ScheduleRegistration] beans on the builder, as the
 * `pendenz.schedules.*` properties say (ADR 0034). Every failure names the
 * bean or the property it comes from.
 */
internal class ScheduleBeans(
    beans: ListableBeanFactory,
    private val settings: Map<String, PendenzProperties.ScheduleSettings>,
) {
    /** The beans by bean name, in the order they were declared. */
    private val registrations: Map<String, ScheduleRegistration> =
        beans.getBeansOfType(ScheduleRegistration::class.java)

    fun registerOn(builder: Pendenz.Builder<*>) {
        val names = registrations.values.map { it.name }.toSet()
        val unknown = settings.keys - names
        check(unknown.isEmpty()) {
            "the properties ${unknown.map { "$PREFIX.$it" }} name no ScheduleRegistration bean; " +
                "the schedules are ${names.sorted()}"
        }
        registrations.forEach { (bean, registration) ->
            val setting = settings[registration.name] ?: PendenzProperties.ScheduleSettings()
            if (!setting.enabled) {
                log.info(
                    "schedule '{}' of bean '{}' is switched off by {}",
                    registration.name,
                    bean,
                    enabled(registration),
                )
                return@forEach
            }
            try {
                registration.registerOn(builder, scheduleOf(registration, setting))
            } catch (e: IllegalArgumentException) {
                throw IllegalStateException("ScheduleRegistration bean '$bean': ${e.message}", e)
            }
        }
    }

    /**
     * The failure of `build()` with the bean named, when it concerns one of
     * the schedules registered here, such as one whose type is not registered.
     */
    fun explain(failure: IllegalArgumentException): IllegalStateException? {
        val message = failure.message ?: return null
        val (bean, _) =
            registrations.entries.firstOrNull { message.contains("schedule '${it.value.name}'") } ?: return null
        return IllegalStateException("ScheduleRegistration bean '$bean': $message", failure)
    }

    private fun scheduleOf(
        registration: ScheduleRegistration,
        setting: PendenzProperties.ScheduleSettings,
    ): Schedule {
        val property = "$PREFIX.${registration.name}"
        val cron = setting.cron
        val zone = setting.zone
        check((cron == null) == (zone == null)) {
            "$property.cron and $property.zone go together: a zone taken from the JVM would differ between " +
                "instances and break the deduplication by fire time"
        }
        if (cron == null || zone == null) return registration.schedule
        val zoneId =
            try {
                ZoneId.of(zone)
            } catch (e: DateTimeException) {
                throw IllegalStateException("$property.zone '$zone' is not a time zone: ${e.message}", e)
            }
        return try {
            Schedule.cron(cron, zoneId)
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("$property.cron '$cron' is refused: ${e.message}", e)
        }
    }

    private fun enabled(registration: ScheduleRegistration) = "$PREFIX.${registration.name}.enabled=false"

    private companion object {
        const val PREFIX = "pendenz.schedules"
        val log = LoggerFactory.getLogger(ScheduleBeans::class.java)
    }
}
