package io.github.pendenzlib.jackson

import io.github.pendenzlib.JsonPayload
import io.github.pendenzlib.PayloadUpgrade
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.util.function.UnaryOperator

/**
 * [PayloadUpgrade] steps for JSON payloads, working on Jackson nodes so
 * that the class of the old version need not exist. The same shape as
 * `PayloadUpgrade.json` of the core: mapper, column, then the rewrite.
 * A step that fails, say because the stored document is not what it
 * expects, fails the task at once, without retries. See ADR 0010.
 */
public object JacksonPayloadUpgrade {
    /**
     * An upgrade that hands the JSON object stored in [column], `payload`
     * by default, to [rewrite] and stores what comes back. Other columns
     * stay.
     */
    @JvmStatic
    @JvmOverloads
    public fun json(
        mapper: ObjectMapper = JsonMapper.builder().build(),
        column: String = JsonPayload.DEFAULT_COLUMN,
        rewrite: UnaryOperator<ObjectNode>,
    ): PayloadUpgrade =
        node(mapper, column) { node ->
            require(node is ObjectNode) { "payload is not a JSON object" }
            rewrite.apply(node)
        }

    /**
     * Like [json] for any JSON document, an array included, as the codec
     * stores a `List` payload.
     */
    @JvmStatic
    @JvmOverloads
    public fun node(
        mapper: ObjectMapper = JsonMapper.builder().build(),
        column: String = JsonPayload.DEFAULT_COLUMN,
        rewrite: UnaryOperator<JsonNode>,
    ): PayloadUpgrade =
        PayloadUpgrade { values ->
            val text = values[column] as? String ?: throw IllegalArgumentException("no JSON in column '$column'")
            values + (column to mapper.writeValueAsString(rewrite.apply(mapper.readTree(text))))
        }
}
