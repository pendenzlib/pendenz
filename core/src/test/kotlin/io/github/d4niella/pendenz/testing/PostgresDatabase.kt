package io.github.d4niella.pendenz.testing

import io.github.d4niella.pendenz.schema.PostgresDdl
import io.github.d4niella.pendenz.schema.TaskTable
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.postgresql.ds.PGSimpleDataSource
import org.slf4j.LoggerFactory
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import javax.sql.DataSource

/**
 * A real PostgreSQL for tests. Uses Testcontainers when Docker is available,
 * otherwise an embedded PostgreSQL binary, so the tests run on every
 * developer machine. Never H2: the claim query depends on `SKIP LOCKED` and
 * the payload column on `jsonb`.
 *
 * One server per JVM, started on first use and stopped when the JVM exits.
 * Every test still gets its own table through [createTable], so tests stay
 * independent and may run in parallel.
 */
class PostgresDatabase : BeforeAllCallback {
    val dataSource: DataSource
        get() = Server.dataSource

    override fun beforeAll(context: ExtensionContext) {
        Server.dataSource
    }

    /** Creates a fresh task table with a unique name and returns its mapping. */
    fun createTable(prefix: String = "task"): TaskTable {
        val table = TaskTable(name = "${prefix}_${System.nanoTime().toString(36)}")
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute(PostgresDdl.createTable(table)) }
        }
        return table
    }

    /** Runs a statement that changes rows and returns how many it changed. */
    fun sql(statement: String): Int =
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.executeUpdate(statement) }
        }

    /** Runs a query whose single row and column is a number. */
    fun count(query: String): Long =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(query).use { rs ->
                    check(rs.next()) { "query returned no row: $query" }
                    rs.getLong(1)
                }
            }
        }

    private object Server {
        private val log = LoggerFactory.getLogger(PostgresDatabase::class.java)

        val dataSource: DataSource by lazy { start() }

        private fun start(): DataSource =
            if (dockerAvailable()) {
                log.info("using PostgreSQL via Testcontainers")
                val pg = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine")).also { it.start() }
                stopOnExit { pg.stop() }
                PGSimpleDataSource().apply {
                    setUrl(pg.jdbcUrl)
                    user = pg.username
                    password = pg.password
                }
            } else {
                log.info("Docker unavailable, using embedded PostgreSQL")
                val pg = EmbeddedPostgres.start()
                stopOnExit { pg.close() }
                pg.postgresDatabase
            }

        private fun stopOnExit(stop: () -> Unit) {
            Runtime.getRuntime().addShutdownHook(Thread(stop))
        }

        private fun dockerAvailable(): Boolean =
            runCatching {
                DockerClientFactory.instance().isDockerAvailable
            }.getOrDefault(false)
    }
}
