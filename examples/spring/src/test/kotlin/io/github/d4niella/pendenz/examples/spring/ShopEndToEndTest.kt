package io.github.d4niella.pendenz.examples.spring

import io.github.d4niella.pendenz.testing.PostgresDatabase
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** The shop over HTTP against PostgreSQL, with Flyway, the starter's worker and Boot's dependency management. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ShopEndToEndTest(
    @LocalServerPort private val port: Int,
    @Autowired private val mail: MailServer,
) {
    private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

    private fun post(json: String): HttpResponse<String> =
        http.send(
            HttpRequest
                .newBuilder(URI("http://localhost:$port/invoices"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun get(id: String): String {
        val request = HttpRequest.newBuilder(URI("http://localhost:$port/invoices/$id")).build()
        return http.send(request, HttpResponse.BodyHandlers.ofString()).body()
    }

    private fun idIn(response: HttpResponse<String>): String =
        Regex("\"id\":(\\d+)").find(response.body())!!.groupValues[1]

    @Test
    fun `bill - invoice posted - the worker mails it and the invoice shows the task SUCCEEDED`() {
        // Act
        val response = post("""{"customer": "Globex", "amount": 120.50}""")

        // Assert
        assertThat(response.statusCode()).isEqualTo(201)
        await atMost Duration.ofSeconds(10) untilAsserted {
            assertThat(get(idIn(response))).contains("\"mailStatus\":\"SUCCEEDED\"").contains("\"mailAttempts\":1")
        }
    }

    @Test
    fun `bill - flaky mail server - the first attempt fails, the retry mails the invoice`() {
        // Act
        val response = post("""{"customer": "${MailServer.FLAKY_CUSTOMER}", "amount": 80.00}""")

        // Assert
        await atMost Duration.ofSeconds(10) untilAsserted {
            assertThat(get(idIn(response))).contains("\"mailStatus\":\"SUCCEEDED\"").contains("\"mailAttempts\":2")
        }
    }

    @Test
    fun `send - task of a mailed invoice restarted with SQL - runs again and mails nothing`() {
        // Arrange
        val id = idIn(post("""{"customer": "Hooli", "amount": 64.00}"""))
        await atMost Duration.ofSeconds(10) untilAsserted {
            assertThat(get(id)).contains("\"mailStatus\":\"SUCCEEDED\"")
        }

        // Act
        db.sql(
            """UPDATE $SCHEMA.billing_task SET status = 'PENDING', finished_at = NULL, version = version + 1
               WHERE invoice_id = $id""",
        )

        // Assert
        await atMost Duration.ofSeconds(10) untilAsserted {
            assertThat(get(id)).contains("\"mailStatus\":\"SUCCEEDED\"").contains("\"mailAttempts\":2")
        }
        assertThat(mail.mailed.filter { it == id.toLong() }).hasSize(1)
    }

    @Test
    fun `bill - amount not positive - 400, and neither invoice nor task is stored`() {
        // Arrange
        val invoicesBefore = db.count("SELECT count(*) FROM $SCHEMA.invoice")
        val tasksBefore = db.count("SELECT count(*) FROM $SCHEMA.billing_task")

        // Act
        val response = post("""{"customer": "Initech", "amount": 0}""")

        // Assert
        assertThat(response.statusCode()).isEqualTo(400)
        assertThat(db.count("SELECT count(*) FROM $SCHEMA.invoice")).isEqualTo(invoicesBefore)
        assertThat(db.count("SELECT count(*) FROM $SCHEMA.billing_task")).isEqualTo(tasksBefore)
    }

    companion object {
        @JvmField
        @RegisterExtension
        val db = PostgresDatabase()

        /** A schema of its own, so the shop's fixed table names do not meet another test's. */
        private val SCHEMA = "shop_${db.uniqueSuffix()}"

        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { db.jdbcUrl }
            registry.add("spring.datasource.username") { db.username }
            registry.add("spring.datasource.password") { db.password }
            registry.add("spring.datasource.hikari.schema") { SCHEMA }
            registry.add("spring.flyway.schemas") { SCHEMA }
            registry.add("spring.flyway.default-schema") { SCHEMA }
        }
    }
}
