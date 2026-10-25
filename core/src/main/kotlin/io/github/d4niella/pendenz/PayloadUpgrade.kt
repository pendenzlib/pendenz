package io.github.d4niella.pendenz

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Rewrites a stored payload from one schema version to the next, working
 * on the column values because the class of the old version may no longer
 * exist. Registered on a [TaskType] per version step; see ADR 0010.
 */
public fun interface PayloadUpgrade {
    /** Returns the column values of the next version. */
    public fun upgrade(values: Map<String, Any?>): Map<String, Any?>

    public companion object {
        /**
         * An upgrade for a JSON payload that works on the parsed document in
         * [column], `payload` by default, and leaves other columns alone.
         */
        @JvmStatic
        @JvmOverloads
        public fun json(
            json: Json = Json,
            column: String = JsonPayload.DEFAULT_COLUMN,
            rewrite: (JsonObject) -> JsonObject,
        ): PayloadUpgrade =
            PayloadUpgrade { values ->
                val text = values[column] as? String ?: throw IllegalArgumentException("no JSON in column '$column'")
                val rewritten = rewrite(json.decodeFromString(JsonObject.serializer(), text))
                values + (column to json.encodeToString(JsonObject.serializer(), rewritten))
            }
    }
}

/**
 * The stored payload carries a version the handler cannot read: newer than
 * the task type, older with no upgrade registered for a step, or older with
 * an upgrade step that failed, which is then the [cause]. The task fails at
 * once; the retry policy is not consulted.
 */
public class UnsupportedPayloadVersionException
    @JvmOverloads
    constructor(
        public val taskType: String,
        public val storedVersion: Int,
        public val supportedVersion: Int,
        cause: Throwable? = null,
    ) : RuntimeException(
            "task type '$taskType' handles payload version $supportedVersion, the task carries version $storedVersion" +
                (cause?.let { "; upgrading it failed: ${it.message}" } ?: ""),
            cause,
        )
