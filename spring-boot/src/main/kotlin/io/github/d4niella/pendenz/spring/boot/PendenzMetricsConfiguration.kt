package io.github.d4niella.pendenz.spring.boot

import io.github.d4niella.pendenz.Pendenz
import io.github.d4niella.pendenz.micrometer.PendenzMetrics
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.UUID

/**
 * Metrics when `pendenz-micrometer` is on the classpath and the application
 * has a `MeterRegistry` (ADR 0022). The `PendenzMetrics` bean is a
 * `TaskListener` bean like any other, so it is registered once, whether
 * the starter made it or the application.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = ["io.github.d4niella.pendenz.micrometer.PendenzMetrics"])
@ConditionalOnBean(type = ["io.micrometer.core.instrument.MeterRegistry"])
internal class PendenzMetricsConfiguration {
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = ["pendenz.id-type"], havingValue = "bigint", matchIfMissing = true)
    class Bigint {
        @Bean
        @ConditionalOnMissingBean(PendenzMetrics::class)
        fun pendenzMetrics(registry: MeterRegistry): PendenzMetrics<Long> = PendenzMetrics(registry)
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = ["pendenz.id-type"], havingValue = "uuid")
    class Uuid {
        @Bean
        @ConditionalOnMissingBean(PendenzMetrics::class)
        fun pendenzMetrics(registry: MeterRegistry): PendenzMetrics<UUID> = PendenzMetrics(registry)
    }

    /** Refreshes the backlog gauges in the worker's scope, when `pendenz.metrics.backlog-interval` is set. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = ["pendenz.metrics.backlog-interval"])
    @ConditionalOnBooleanProperty(name = ["pendenz.worker.enabled"], matchIfMissing = true)
    class Backlog {
        @Bean
        fun pendenzBacklogWatch(
            metrics: PendenzMetrics<*>,
            pendenz: Pendenz<*>,
            properties: PendenzProperties,
        ): WorkerCompanion {
            val every = checkNotNull(properties.metrics.backlogInterval)
            return WorkerCompanion { scope -> metrics.watchBacklog(pendenz, scope, every) }
        }
    }
}
