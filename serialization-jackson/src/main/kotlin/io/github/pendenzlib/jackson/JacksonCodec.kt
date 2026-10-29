package io.github.pendenzlib.jackson

import io.github.pendenzlib.PayloadCodec
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.JavaType
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper

/**
 * A [PayloadCodec] over Jackson 3, for payload classes without
 * kotlinx-serialization: Java records, beans, Kotlin classes with the
 * Jackson Kotlin module. The application supplies the `ObjectMapper`, so
 * its own modules and settings apply; a plain `JsonMapper` is the default.
 * Built with [of], which ties the codec's type to the Java type it reads.
 *
 * Payloads come from the table, which operators and other services write
 * too, so a mapper with default typing is refused: it would let a stored
 * document name any class on the classpath to instantiate. For the same
 * reason, payload classes should not carry `@JsonTypeInfo(use = CLASS)`;
 * name subtypes with `@JsonSubTypes` instead. See ADR 0012.
 */
public class JacksonCodec<P : Any> private constructor(
    private val mapper: ObjectMapper,
    private val type: JavaType,
) : PayloadCodec<P> {
    override fun encode(payload: P): String = mapper.writeValueAsString(payload)

    override fun decode(text: String): P = mapper.readValue(text, type)

    public companion object {
        /**
         * A codec for [type] with [mapper], a plain `JsonMapper` when omitted.
         *
         * @throws IllegalArgumentException when [mapper] has default typing activated.
         */
        @JvmStatic
        @JvmOverloads
        public fun <P : Any> of(
            type: Class<P>,
            mapper: ObjectMapper = defaultMapper(),
        ): JacksonCodec<P> = JacksonCodec(requireNoDefaultTyping(mapper), mapper.constructType(type))

        /**
         * A codec for a generic type such as `List<Invoice>`, described by a [TypeReference].
         *
         * @throws IllegalArgumentException when [mapper] has default typing activated.
         */
        @JvmStatic
        @JvmOverloads
        public fun <P : Any> of(
            type: TypeReference<P>,
            mapper: ObjectMapper = defaultMapper(),
        ): JacksonCodec<P> = JacksonCodec(requireNoDefaultTyping(mapper), mapper.constructType(type))

        /** Every kind of default typing covers `Object`, so asking for it finds any. */
        private fun requireNoDefaultTyping(mapper: ObjectMapper): ObjectMapper {
            val typer = mapper.deserializationConfig().getDefaultTyper(mapper.constructType(Any::class.java))
            require(typer == null) {
                "the ObjectMapper has default typing activated, which lets a stored payload name any class " +
                    "to instantiate; give the codec a mapper without it"
            }
            return mapper
        }

        private fun defaultMapper(): ObjectMapper = JsonMapper.builder().build()
    }
}
