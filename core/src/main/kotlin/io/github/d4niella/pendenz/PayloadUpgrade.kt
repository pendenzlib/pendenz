package io.github.d4niella.pendenz

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Rewrites a stored payload from one schema version to the next, working
 * on the stored text because the class of the old version may no longer
 * exist. Registered on a [TaskType] per version step; see ADR 0010.
 */
public fun interface PayloadUpgrade {
    /** Returns the payload text of the next version. */
    public fun upgrade(payload: String): String

    public companion object {
        /** An upgrade for JSON payloads that works on the parsed object. */
        @JvmStatic
        @JvmOverloads
        public fun json(
            json: Json = Json,
            rewrite: (JsonObject) -> JsonObject,
        ): PayloadUpgrade =
            PayloadUpgrade { payload ->
                json.encodeToString(
                    JsonObject.serializer(),
                    rewrite(json.decodeFromString(JsonObject.serializer(), payload)),
                )
            }
    }
}

/**
 * The stored payload carries a version the handler cannot read: newer than
 * the task type, or older with no upgrade registered for a step. The task
 * fails at once; the retry policy is not consulted.
 */
public class UnsupportedPayloadVersionException(
    public val taskType: String,
    public val storedVersion: Int,
    public val supportedVersion: Int,
) : RuntimeException(
        "task type '$taskType' handles payload version $supportedVersion, the task carries version $storedVersion",
    )
