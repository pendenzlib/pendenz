package io.github.d4niella.pendenz.store

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** The provenance column: key-value pairs as a JSON object; empty pairs are stored as null. */
internal object ProvenanceJson {
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    fun encode(pairs: Map<String, String>): String? =
        if (pairs.isEmpty()) null else Json.encodeToString(serializer, pairs)

    fun decode(text: String?): Map<String, String> =
        if (text ==
            null
        ) {
            emptyMap()
        } else {
            Json.decodeFromString(serializer, text)
        }
}
