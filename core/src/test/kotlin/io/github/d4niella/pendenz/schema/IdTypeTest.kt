package io.github.d4niella.pendenz.schema

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

class IdTypeTest {
    @Nested
    inner class Parse {
        @Test
        fun `parse - bigint as toString prints it - the same id`() {
            val id = IdType.BIGINT.parse(4711L.toString())

            assertThat(id).isEqualTo(4711L)
        }

        @Test
        fun `parse - uuid as toString prints it - the same id`() {
            val original = UUID.fromString("5f2b1c3e-8a4d-4e6f-9b0a-1c2d3e4f5a6b")

            val id = IdType.UUID.parse(original.toString())

            assertThat(id).isEqualTo(original)
        }

        @ParameterizedTest
        @ValueSource(strings = ["", "forty-two", "4.2", "5f2b1c3e-8a4d-4e6f-9b0a-1c2d3e4f5a6b"])
        fun `parse - text that is no bigint - throws IllegalArgumentException`(text: String) {
            assertThatThrownBy { IdType.BIGINT.parse(text) }.isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `parse - text that is no uuid - throws IllegalArgumentException`() {
            assertThatThrownBy { IdType.UUID.parse("4711") }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
