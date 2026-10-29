package io.github.pendenzlib.spring

import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.ResourceTransactionManager
import org.springframework.util.ClassUtils
import javax.sql.DataSource

/** Checks that a transaction manager binds connections of the data source Pendenz writes to. */
internal object TransactionPair {
    private val jpaPresent =
        ClassUtils.isPresent(
            "org.springframework.orm.jpa.JpaTransactionManager",
            TransactionPair::class.java.classLoader,
        )

    /** Returns the data source [manager] binds its connections under, after checking it is [dataSource]. */
    fun check(
        dataSource: DataSource,
        manager: PlatformTransactionManager,
    ): DataSource {
        val bound =
            checkNotNull(boundDataSource(manager)) {
                "Pendenz cannot join the transactions of ${manager.javaClass.name}: it binds no JDBC connection " +
                    "Pendenz could use, as a JTA transaction manager does not. Use a DataSourceTransactionManager, " +
                    "JdbcTransactionManager or JpaTransactionManager, see ADR 0023."
            }
        check(target(bound) === target(dataSource)) {
            "the transaction manager ${manager.javaClass.name} manages the data source $bound, but Pendenz was given " +
                "$dataSource; an enqueue would not join its transactions. Give both the same data source."
        }
        return bound
    }

    private fun boundDataSource(manager: PlatformTransactionManager): DataSource? =
        when {
            jpaPresent && Jpa.isJpa(manager) -> Jpa.dataSourceOf(manager)
            manager is ResourceTransactionManager -> manager.resourceFactory as? DataSource
            else -> null
        }

    /** The data source behind the proxies Spring puts in front of one for transactions. */
    private fun target(dataSource: DataSource): DataSource =
        generateSequence(dataSource) {
            when (it) {
                is TransactionAwareDataSourceProxy, is LazyConnectionDataSourceProxy -> it.targetDataSource
                else -> null
            }
        }.last()

    /** Kept apart so that `spring-orm` is loaded only when it is on the classpath. */
    private object Jpa {
        fun isJpa(manager: PlatformTransactionManager): Boolean = manager is JpaTransactionManager

        fun dataSourceOf(manager: PlatformTransactionManager): DataSource? =
            (manager as JpaTransactionManager).dataSource
    }
}
