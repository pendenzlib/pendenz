package io.github.d4niella.pendenz.examples.helpdesk

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.flywaydb.core.Flyway

/**
 * Where the helpdesk's tables live. The agent starts the helpdesk as a
 * child process and hands this over through the environment, so both
 * see the same tickets.
 */
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

    /** Creates or updates the tables and seeds this morning's tickets. */
    fun migrate(dataSource: HikariDataSource) {
        val flyway = Flyway.configure().dataSource(dataSource).locations(MIGRATIONS)
        schema?.let { flyway.schemas(it).defaultSchema(it) }
        flyway.load().migrate()
    }

    /** The variables [fromEnvironment] reads back. */
    fun environment(): Map<String, String> =
        listOfNotNull(
            URL to jdbcUrl,
            USER to user,
            PASSWORD to password,
            schema?.let { SCHEMA to it },
        ).toMap()

    companion object {
        const val URL = "PENDENZ_JDBC_URL"
        const val USER = "PENDENZ_DB_USER"
        const val PASSWORD = "PENDENZ_DB_PASSWORD"
        const val SCHEMA = "PENDENZ_DB_SCHEMA"
        private const val POOL_SIZE = 5
        private const val MIGRATIONS = "classpath:io/github/d4niella/pendenz/examples/helpdesk/db/migration"

        /** The database named by the environment, or null when [URL] is not set. */
        fun fromEnvironment(env: Map<String, String> = System.getenv()): DatabaseAccess? =
            env[URL]?.let { DatabaseAccess(it, env[USER].orEmpty(), env[PASSWORD].orEmpty(), env[SCHEMA]) }

        /** An embedded PostgreSQL for a first look without a database; it lives as long as this JVM. */
        fun embedded(): DatabaseAccess {
            val postgres = EmbeddedPostgres.start()
            Runtime.getRuntime().addShutdownHook(Thread { postgres.close() })
            return DatabaseAccess(postgres.getJdbcUrl("postgres", "postgres"), "postgres", "postgres")
        }
    }
}
