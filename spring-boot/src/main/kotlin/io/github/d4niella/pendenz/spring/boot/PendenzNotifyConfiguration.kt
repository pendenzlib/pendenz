package io.github.d4niella.pendenz.spring.boot

import io.github.d4niella.pendenz.notify.PostgresNotify
import io.github.d4niella.pendenz.schema.TaskTable
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import javax.sql.DataSource

/**
 * Wakes the worker through LISTEN/NOTIFY when `pendenz-postgres-notify` is
 * on the classpath and `pendenz.worker.notify` is on (ADR 0014). The
 * listener holds one connection of the data source for as long as the
 * context runs, and is closed with it; without a worker
 * (`pendenz.worker.enabled=false`) there is nobody to wake, and no
 * connection is taken.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = ["io.github.d4niella.pendenz.notify.PostgresNotify"])
@ConditionalOnBooleanProperty(name = ["pendenz.worker.notify"])
internal class PendenzNotifyConfiguration {
    @Bean
    @ConditionalOnMissingBean(PostgresNotify::class)
    @ConditionalOnBooleanProperty(name = ["pendenz.worker.enabled"], matchIfMissing = true)
    fun pendenzNotify(
        dataSource: DataSource,
        table: TaskTable<*>,
    ): PostgresNotify = PostgresNotify.start(dataSource, table)
}
