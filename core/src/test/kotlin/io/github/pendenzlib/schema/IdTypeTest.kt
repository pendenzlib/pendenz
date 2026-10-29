package io.github.pendenzlib.schema

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

        @ParameterizedTest
        @ValueSource(strings = ["", "4711", "1-2-3-4-5", "5f2b1c3e8a4d4e6f9b0a1c2d3e4f5a6b"])
        fun `parse - text that is no canonical uuid - throws IllegalArgumentException`(text: String) {
            assertThatThrownBy { IdType.UUID.parse(text) }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
