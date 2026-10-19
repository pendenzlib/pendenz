package io.github.d4niella.pendenz.jackson

import io.github.d4niella.pendenz.PayloadCodec
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.JavaType
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper

/**
 * A [PayloadCodec] over Jackson 3, for payload classes without
 * kotlinx-serialization: Java records, beans, Kotlin classes with the
 * Jackson Kotlin module. The application supplies the `ObjectMapper`, so
 * its own modules and settings apply; a plain `JsonMapper` is the default.
 * See ADR 0012.
 */
public class JacksonCodec<P : Any>(
    private val mapper: ObjectMapper,
    private val type: JavaType,
) : PayloadCodec<P> {
    override fun encode(payload: P): String = mapper.writeValueAsString(payload)

    override fun decode(text: String): P = mapper.readValue(text, type)

    public companion object {
        /** A codec for [type] with [mapper], a plain `JsonMapper` when omitted. */
        @JvmStatic
        @JvmOverloads
        public fun <P : Any> of(
            type: Class<P>,
            mapper: ObjectMapper = defaultMapper(),
        ): JacksonCodec<P> = JacksonCodec(mapper, mapper.constructType(type))

        /** A codec for a generic type such as `List<Invoice>`, described by a [TypeReference]. */
        @JvmStatic
        @JvmOverloads
        public fun <P : Any> of(
            type: TypeReference<P>,
            mapper: ObjectMapper = defaultMapper(),
        ): JacksonCodec<P> = JacksonCodec(mapper, mapper.constructType(type))

        private fun defaultMapper(): ObjectMapper = JsonMapper.builder().build()
    }
}
