package io.github.d4niella.pendenz.mcp.testing

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** kotlinx JSON into the plain maps and lists the SDK's request types take. */
object JsonMaps {
    fun toMap(json: JsonObject): Map<String, Any?> = json.mapValues { plain(it.value) }

    private fun plain(element: JsonElement): Any? =
        when (element) {
            is JsonNull -> {
                null
            }

            is JsonObject -> {
                toMap(element)
            }

            is JsonArray -> {
                element.map { plain(it) }
            }

            is JsonPrimitive -> {
                when {
                    element.isString -> element.content
                    element.booleanOrNull != null -> element.booleanOrNull
                    element.longOrNull != null -> element.longOrNull
                    else -> element.content.toDouble()
                }
            }
        }
}
