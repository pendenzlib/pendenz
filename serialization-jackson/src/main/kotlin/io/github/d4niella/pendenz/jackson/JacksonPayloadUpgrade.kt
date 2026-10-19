package io.github.d4niella.pendenz.jackson

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
    /** An upgrade that hands the stored object to [rewrite] and stores what comes back. */
    @JvmStatic
    @JvmOverloads
    public fun of(
        rewrite: UnaryOperator<ObjectNode>,
        mapper: ObjectMapper = JsonMapper.builder().build(),
    ): PayloadUpgrade =
        PayloadUpgrade { payload ->
            val node =
                mapper.readTree(payload) as? ObjectNode
                    ?: throw IllegalArgumentException("payload is not a JSON object")
            mapper.writeValueAsString(rewrite.apply(node))
        }
}
