package io.github.pendenzlib.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class JsonSchemaTest {
    enum class Priority { LOW, HIGH }

    @JvmInline
    @Serializable
    value class TicketId(
        val value: Long,
    )

    @Serializable
    data class Customer(
        val name: String,
        val vip: Boolean = false,
    )

    @Serializable
    data class Escalation(
        val ticket: TicketId,
        val priority: Priority,
        val customer: Customer,
        val tags: List<String>,
        val scores: Map<String, Double>,
        val note: String? = null,
        val initial: Char,
    )

    @Serializable
    sealed interface Shape

    @Serializable
    data class Holder(
        val shape: Shape,
    )

    @Serializable
    data class Discussion(
        val text: String,
        val replies: List<Discussion>,
    )

    @Test
    fun `of - class with defaults - properties for every field, required only those without a default`() {
        val schema = parse(JsonSchema.of<Customer>())

        // Assert
        assertThat(schema.keys).contains("type", "properties", "required", "additionalProperties")
        assertThat(schema.child("properties").keys).containsExactly("name", "vip")
        assertThat(schema["required"].toString()).isEqualTo("""["name"]""")
        assertThat(schema["additionalProperties"].toString()).isEqualTo("false")
    }

    @Test
    fun `of - every supported kind - the JSON Schema type each one maps to`() {
        val properties = parse(JsonSchema.of<Escalation>()).child("properties")

        // Assert
        val expected =
            mapOf(
                "ticket" to """{"type":"integer"}""",
                "priority" to """{"type":"string","enum":["LOW","HIGH"]}""",
                "tags" to """{"type":"array","items":{"type":"string"}}""",
                "scores" to """{"type":"object","additionalProperties":{"type":"number"}}""",
                "note" to """{"anyOf":[{"type":"string"},{"type":"null"}]}""",
                "initial" to """{"type":"string","minLength":1,"maxLength":1}""",
            )
        expected.forEach { (field, schema) ->
            assertThat(properties[field].toString()).describedAs(field).isEqualTo(schema)
        }
        assertThat(properties.child("customer").child("properties").keys).containsExactly("name", "vip")
    }

    @Test
    fun `of - sealed field - throws IllegalArgumentException asking for a hand-written schema`() {
        assertThatThrownBy { JsonSchema.of<Holder>() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("by hand")
    }

    @Test
    fun `of - recursive type - throws IllegalArgumentException`() {
        assertThatThrownBy { JsonSchema.of<Discussion>() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("recursive")
    }

    private fun parse(schema: String): JsonObject = Json.parseToJsonElement(schema).jsonObject

    private fun JsonObject.child(key: String): JsonObject = (get(key) as JsonElement).jsonObject
}
