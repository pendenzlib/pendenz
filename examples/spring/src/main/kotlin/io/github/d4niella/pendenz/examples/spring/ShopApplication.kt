package io.github.d4niella.pendenz.examples.spring

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * A shop that bills customers over HTTP. Storing an invoice and queueing
 * its mail is one transaction; a worker the starter runs mails it, with a
 * retry for one flaky customer. Flyway creates the tables.
 *
 * Runs against `PENDENZ_JDBC_URL` (with `PENDENZ_DB_USER` and
 * `PENDENZ_DB_PASSWORD`) when set, otherwise against an embedded PostgreSQL.
 */
@SpringBootApplication
class ShopApplication

fun main(args: Array<String>) {
    useDatabase(System.getenv())
    @Suppress("SpreadOperator") // once, at startup; the idiomatic way to hand Spring the arguments
    runApplication<ShopApplication>(*args)
}

/** Points Spring at the database from the environment, or at an embedded one started for this JVM. */
private fun useDatabase(env: Map<String, String>) {
    val url = env["PENDENZ_JDBC_URL"]
    if (url != null) {
        System.setProperty("spring.datasource.url", url)
        System.setProperty("spring.datasource.username", env["PENDENZ_DB_USER"].orEmpty())
        System.setProperty("spring.datasource.password", env["PENDENZ_DB_PASSWORD"].orEmpty())
    } else {
        val postgres = EmbeddedPostgres.start()
        Runtime.getRuntime().addShutdownHook(Thread { postgres.close() })
        System.setProperty("spring.datasource.url", postgres.getJdbcUrl("postgres", "postgres"))
        System.setProperty("spring.datasource.username", "postgres")
        System.setProperty("spring.datasource.password", "postgres")
    }
}
