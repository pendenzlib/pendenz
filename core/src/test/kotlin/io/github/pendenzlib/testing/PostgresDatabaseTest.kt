package io.github.pendenzlib.testing

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class PostgresDatabaseTest {
    @Test
    fun `server - Testcontainers or embedded - runs PostgreSQL 17`() {
        val major = db.long("SELECT current_setting('server_version_num')::int / 10000")

        assertThat(major).isEqualTo(17)
    }

    companion object {
        @JvmField
        @RegisterExtension
        val db = PostgresDatabase()
    }
}
