package io.github.d4niella.pendenz.testing

import io.github.d4niella.pendenz.schema.PostgresDdl
import io.github.d4niella.pendenz.schema.TaskTable
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.extension.AfterAllCallback
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
 */
class PostgresDatabase :
    BeforeAllCallback,
    AfterAllCallback {
    lateinit var dataSource: DataSource
        private set

    private val log = LoggerFactory.getLogger(PostgresDatabase::class.java)
    private var container: PostgreSQLContainer<*>? = null
    private var embedded: EmbeddedPostgres? = null

    override fun beforeAll(context: ExtensionContext) {
        dataSource =
            if (dockerAvailable()) {
                log.info("using PostgreSQL via Testcontainers")
                val pg = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine")).also { it.start() }
                container = pg
                PGSimpleDataSource().apply {
                    setUrl(pg.jdbcUrl)
                    user = pg.username
                    password = pg.password
                }
            } else {
                log.info("Docker unavailable, using embedded PostgreSQL")
                val pg = EmbeddedPostgres.start()
                embedded = pg
                pg.postgresDatabase
            }
    }

    override fun afterAll(context: ExtensionContext) {
        container?.stop()
        embedded?.close()
    }

    /** Creates a fresh task table with a unique name and returns its mapping. */
    fun createTable(prefix: String = "task"): TaskTable {
        val table = TaskTable(name = "${prefix}_${System.nanoTime().toString(36)}")
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute(PostgresDdl.createTable(table)) }
        }
        return table
    }

    fun sql(statement: String): Int =
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.executeUpdate(statement) }
        }

    private fun dockerAvailable(): Boolean =
        runCatching {
            DockerClientFactory.instance().isDockerAvailable
        }.getOrDefault(false)
}
