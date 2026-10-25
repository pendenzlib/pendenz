package io.github.d4niella.pendenz.mcp

import io.github.d4niella.pendenz.store.TaskRecord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** What `list_tasks` shows of a task. */
internal fun TaskRecord<*>.summary(): JsonObject =
    buildJsonObject {
        put("id", id.toString())
        put("type", type)
        put("status", status.name)
        put("createdAt", createdAt.toString())
        preview?.let { put("preview", it) }
    }

/** What `get_task` shows of a task: everything an agent may act on, nothing about locks or versions. */
internal fun TaskRecord<*>.details(): JsonObject =
    buildJsonObject {
        put("id", id.toString())
        put("type", type)
        put("status", status.name)
        put("attempts", attempts)
        put("runAt", runAt.toString())
        put("createdAt", createdAt.toString())
        finishedAt?.let { put("finishedAt", it.toString()) }
        lastError?.let { put("lastError", it) }
        preview?.let { put("preview", it) }
        result?.let { put("result", parsedOrText(it)) }
        if (provenance.isNotEmpty()) put("provenance", JsonObject(provenance.mapValues { JsonPrimitive(it.value) }))
        correlationId?.let { put("correlationId", it) }
        parentId?.let { put("parentId", it.toString()) }
    }

/** A result column holds JSON; should a codec have written something else, it goes out as text. */
private fun parsedOrText(json: String): JsonElement =
    runCatching { Json.parseToJsonElement(json) }.getOrElse { JsonPrimitive(json) }

internal fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.int(key: String): Int? = get(key)?.jsonPrimitive?.intOrNull

/** The schema of a string argument. */
internal fun text(
    description: String,
    format: String? = null,
    maxLength: Int? = null,
): JsonObject =
    buildJsonObject {
        put("type", "string")
        format?.let { put("format", it) }
        maxLength?.let { put("maxLength", it) }
        put("description", description)
    }

/** The schema of a tool's arguments: an object with exactly these properties. */
internal fun objectSchema(
    required: List<String>,
    vararg properties: Pair<String, JsonObject>,
): JsonObject =
    buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(properties.toMap()))
        if (required.isNotEmpty()) put("required", JsonArray(required.map(::JsonPrimitive)))
        put("additionalProperties", false)
    }
