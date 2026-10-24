package io.github.d4niella.pendenz.examples.catalog

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.flywaydb.core.Flyway

/** Where the catalogue's tables live: a database named by the environment, or an embedded one. */
data class DatabaseAccess(
    val jdbcUrl: String,
    val user: String,
    val password: String,
    /** A schema of its own, for tests that run side by side; the user's default schema when null. */
    val schema: String? = null,
) {
    fun dataSource(): HikariDataSource {
        val config = HikariConfig()
        config.jdbcUrl = jdbcUrl
        config.username = user
        config.password = password
        config.schema = schema
        config.maximumPoolSize = POOL_SIZE
        return HikariDataSource(config)
    }

    /** Creates the tables and fills the catalogue with [products] generated products and three troublesome ones. */
    fun migrate(
        dataSource: HikariDataSource,
        products: Int,
    ) {
        val flyway =
            Flyway
                .configure()
                .dataSource(dataSource)
                .locations(MIGRATIONS)
                .placeholders(mapOf("products" to products.toString()))
        schema?.let { flyway.schemas(it).defaultSchema(it) }
        flyway.load().migrate()
    }

    companion object {
        private const val POOL_SIZE = 10
        private const val MIGRATIONS = "classpath:io/github/d4niella/pendenz/examples/catalog/db/migration"

        /** `PENDENZ_JDBC_URL` with `PENDENZ_DB_USER` and `PENDENZ_DB_PASSWORD`, or null when the URL is not set. */
        fun fromEnvironment(env: Map<String, String> = System.getenv()): DatabaseAccess? =
            env["PENDENZ_JDBC_URL"]?.let {
                DatabaseAccess(it, env["PENDENZ_DB_USER"].orEmpty(), env["PENDENZ_DB_PASSWORD"].orEmpty())
            }

        /** An embedded PostgreSQL for a first look without a database; it lives as long as this JVM. */
        fun embedded(): DatabaseAccess {
            val postgres = EmbeddedPostgres.start()
            Runtime.getRuntime().addShutdownHook(Thread { postgres.close() })
            return DatabaseAccess(postgres.getJdbcUrl("postgres", "postgres"), "postgres", "postgres")
        }
    }
}
