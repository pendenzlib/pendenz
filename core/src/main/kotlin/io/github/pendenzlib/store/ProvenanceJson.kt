package io.github.pendenzlib.store

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The provenance column: key-value pairs as a JSON object; empty pairs are stored as null. */
internal object ProvenanceJson {
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    fun encode(pairs: Map<String, String>): String? =
        if (pairs.isEmpty()) null else Json.encodeToString(serializer, pairs)

    /**
     * Reads what Pendenz wrote and what an operator may have written by
     * hand: the column belongs to the application's schema, so a number or
     * a nested object is taken as its JSON text instead of failing the read.
     */
    fun decode(text: String?): Map<String, String> {
        if (text == null) return emptyMap()
        val element = Json.parseToJsonElement(text)
        check(element is JsonObject) { "the provenance column holds no JSON object: $text" }
        return element.mapValues { (_, value) -> if (value is JsonPrimitive) value.content else value.toString() }
    }
}
