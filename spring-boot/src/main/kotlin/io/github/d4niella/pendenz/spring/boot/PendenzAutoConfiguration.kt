package io.github.d4niella.pendenz.spring.boot

import io.github.d4niella.pendenz.ClaimStrategy
import io.github.d4niella.pendenz.ConnectionProvider
import io.github.d4niella.pendenz.FairClaim
import io.github.d4niella.pendenz.Pendenz
import io.github.d4niella.pendenz.WakeUp
import io.github.d4niella.pendenz.javaapi.PendenzJava
import io.github.d4niella.pendenz.schema.IdType
import io.github.d4niella.pendenz.schema.TaskTable
import io.github.d4niella.pendenz.spring.SpringConnectionProvider
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.SpringBootVersion
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.transaction.PlatformTransactionManager
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource

/**
 * Builds the queue from the application's data source, transaction
 * manager and beans, and runs a worker in the context's lifecycle. See
 * ADR 0023 and `docs/spring.md`.
 */
@AutoConfiguration(
    afterName = [
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
        "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
        "org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration",
        "org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration",
        "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration",
    ],
)
@ConditionalOnSingleCandidate(DataSource::class)
@EnableConfigurationProperties(PendenzProperties::class)
@Import(
    PendenzAutoConfiguration.BigintQueue::class,
    PendenzAutoConfiguration.UuidQueue::class,
    PendenzAutoConfiguration.Worker::class,
    PendenzMetricsConfiguration::class,
    PendenzNotifyConfiguration::class,
)
public class PendenzAutoConfiguration {
    init {
        requireBoot4(SpringBootVersion.getVersion())
    }

    @Bean
    @ConditionalOnMissingBean(ConnectionProvider::class)
    public fun pendenzConnectionProvider(
        dataSource: DataSource,
        properties: PendenzProperties,
        beans: ListableBeanFactory,
    ): SpringConnectionProvider =
        SpringConnectionProvider(dataSource, transactionManager(beans, properties.transactionManager))

    @Bean
    @ConditionalOnBooleanProperty(name = ["pendenz.schema-check"], matchIfMissing = true)
    public fun pendenzSchemaCheck(
        dataSource: DataSource,
        table: TaskTable<*>,
    ): SmartInitializingSingleton = SchemaCheck(dataSource, table)

    @Bean
    public fun pendenzHandlerBeanCheck(beans: ListableBeanFactory): SmartInitializingSingleton = HandlerBeanCheck(beans)

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = ["pendenz.id-type"], havingValue = "bigint", matchIfMissing = true)
    internal class BigintQueue {
        @Bean
        @ConditionalOnMissingBean(TaskTable::class)
        fun pendenzTaskTable(properties: PendenzProperties): TaskTable<Long> =
            TaskTable(IdType.BIGINT, properties.table)

        @Bean
        @ConditionalOnMissingBean(Pendenz::class)
        fun pendenz(
            table: TaskTable<*>,
            connections: ConnectionProvider,
            beans: ListableBeanFactory,
        ): Pendenz<Long> =
            QueueBeans(beans, IdType.BIGINT, Long::class.javaObjectType, PendenzProperties.IdType.BIGINT)
                .build(table, connections)

        @Bean
        @ConditionalOnMissingBean(PendenzJava::class)
        fun pendenzJava(pendenz: Pendenz<Long>): PendenzJava<Long> = PendenzJava.wrap(pendenz)
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = ["pendenz.id-type"], havingValue = "uuid")
    internal class UuidQueue {
        @Bean
        @ConditionalOnMissingBean(TaskTable::class)
        fun pendenzTaskTable(properties: PendenzProperties): TaskTable<UUID> = TaskTable(IdType.UUID, properties.table)

        @Bean
        @ConditionalOnMissingBean(Pendenz::class)
        fun pendenz(
            table: TaskTable<*>,
            connections: ConnectionProvider,
            beans: ListableBeanFactory,
        ): Pendenz<UUID> =
            QueueBeans(beans, IdType.UUID, UUID::class.java, PendenzProperties.IdType.UUID).build(table, connections)

        @Bean
        @ConditionalOnMissingBean(PendenzJava::class)
        fun pendenzJava(pendenz: Pendenz<UUID>): PendenzJava<UUID> = PendenzJava.wrap(pendenz)
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBooleanProperty(name = ["pendenz.worker.enabled"], matchIfMissing = true)
    internal class Worker {
        // No back-off: the worker is switched off with pendenz.worker.enabled=false, not replaced
        @Bean
        fun pendenzWorkerLifecycle(
            pendenz: Pendenz<*>,
            properties: PendenzProperties,
            wakeUp: ObjectProvider<WakeUp>,
            claimStrategy: ObjectProvider<ClaimStrategy>,
            companions: ObjectProvider<WorkerCompanion>,
            environment: Environment,
        ): PendenzWorkerLifecycle {
            val shutdown = properties.worker.shutdownTimeout
            val phase =
                Binder
                    .get(environment)
                    .bind(SHUTDOWN_PHASE_PROPERTY, Duration::class.java)
                    .orElse(DEFAULT_SHUTDOWN_PHASE)
            check(shutdown < phase) {
                "pendenz.worker.shutdown-timeout ($shutdown) must be below $SHUTDOWN_PHASE_PROPERTY ($phase), " +
                    "or Spring stops waiting before the worker has released the tasks it cancelled"
            }
            val options =
                properties.worker.toOptions(
                    wakeUp.getIfAvailable { WakeUp.POLLING },
                    claimStrategy.getIfAvailable { FairClaim() },
                )
            return PendenzWorkerLifecycle(pendenz, options, companions.orderedStream().toList())
        }

        private companion object {
            const val SHUTDOWN_PHASE_PROPERTY = "spring.lifecycle.timeout-per-shutdown-phase"
            val DEFAULT_SHUTDOWN_PHASE: Duration = Duration.ofSeconds(30)
        }
    }

    public companion object {
        /**
         * The lifecycle phase the worker stops in: before the web server's
         * graceful shutdown (`DEFAULT_PHASE - 1024`), long before the data
         * source. A bean of the application that must stop after the worker
         * takes a lower phase.
         */
        public const val WORKER_PHASE: Int = SmartLifecycle.DEFAULT_PHASE - 512

        /** The transaction manager named by `pendenz.transaction-manager`, or the application's only one. */
        internal fun transactionManager(
            beans: ListableBeanFactory,
            name: String?,
        ): PlatformTransactionManager =
            if (name != null) {
                beans.getBean(name, PlatformTransactionManager::class.java)
            } else {
                checkNotNull(beans.getBeanProvider(PlatformTransactionManager::class.java).getIfUnique()) {
                    val found = beans.getBeanNamesForType(PlatformTransactionManager::class.java).toList()
                    "Pendenz needs one PlatformTransactionManager to join, found $found; " +
                        "name one with pendenz.transaction-manager"
                }
            }

        /** Fails on Spring Boot 3, whose Kotlin and kotlinx versions lie below Pendenz' floor (ADR 0024). */
        internal fun requireBoot4(version: String?) {
            val major = version?.substringBefore('.')?.toIntOrNull() ?: return
            check(major >= BOOT_MAJOR) {
                "pendenz-spring-boot-starter needs Spring Boot 4, found $version; Spring Boot 3 pins Kotlin " +
                    "and kotlinx versions below the ones Pendenz is built against (ADR 0024)"
            }
        }

        private const val BOOT_MAJOR = 4
    }
}
