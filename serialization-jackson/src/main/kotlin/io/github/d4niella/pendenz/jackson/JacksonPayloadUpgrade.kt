package io.github.d4niella.pendenz.jackson

import io.github.d4niella.pendenz.JsonPayload
import io.github.d4niella.pendenz.PayloadUpgrade
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.util.function.UnaryOperator

/**
 * [PayloadUpgrade] steps for JSON payloads, working on a Jackson
 * `ObjectNode` so that the class of the old version need not exist.
 * See ADR 0010.
 */
public object JacksonPayloadUpgrade {
    /**
     * An upgrade that hands the stored document in [column], `payload` by
     * default, to [rewrite] and stores what comes back. Other columns stay.
     */
    @JvmStatic
    @JvmOverloads
    public fun of(
        rewrite: UnaryOperator<ObjectNode>,
        mapper: ObjectMapper = JsonMapper.builder().build(),
        column: String = JsonPayload.DEFAULT_COLUMN,
    ): PayloadUpgrade =
        PayloadUpgrade { values ->
            val text = values[column] as? String ?: throw IllegalArgumentException("no JSON in column '$column'")
            val node =
                mapper.readTree(text) as? ObjectNode ?: throw IllegalArgumentException("payload is not a JSON object")
            values + (column to mapper.writeValueAsString(rewrite.apply(node)))
        }
}
