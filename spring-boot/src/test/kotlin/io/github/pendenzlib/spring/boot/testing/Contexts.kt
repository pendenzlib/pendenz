package io.github.pendenzlib.spring.boot.testing

import io.github.pendenzlib.spring.boot.PendenzAutoConfiguration
import io.github.pendenzlib.testing.PostgresDatabase
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration

/** An application with a data source on the test database and the starter, as Spring Boot would assemble it. */
fun application(
    db: PostgresDatabase,
    table: String,
): ApplicationContextRunner =
    ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                DataSourceAutoConfiguration::class.java,
                DataSourceTransactionManagerAutoConfiguration::class.java,
                TransactionAutoConfiguration::class.java,
                PendenzAutoConfiguration::class.java,
            ),
        ).withPropertyValues(
            "spring.datasource.url=${db.jdbcUrl}",
            "spring.datasource.username=${db.username}",
            "spring.datasource.password=${db.password}",
            "pendenz.table=$table",
            "pendenz.worker.poll-interval=50ms",
        )
