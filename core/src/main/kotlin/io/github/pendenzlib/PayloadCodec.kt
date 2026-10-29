package io.github.pendenzlib

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/**
 * Converts a payload to and from the text stored in the payload column.
 *
 * The default is JSON via kotlinx-serialization, see [KotlinxJsonCodec]. Teams
 * that prefer Jackson or a different wire format implement this interface.
 */
public interface PayloadCodec<P : Any> {
    public fun encode(payload: P): String

    public fun decode(text: String): P
}

/** JSON payloads through kotlinx-serialization. */
public class KotlinxJsonCodec<P : Any>(
    private val serializer: KSerializer<P>,
    private val json: Json = Json,
) : PayloadCodec<P> {
    override fun encode(payload: P): String = json.encodeToString(serializer, payload)

    override fun decode(text: String): P = json.decodeFromString(serializer, text)

    public companion object {
        /** Codec for any `@Serializable` type. */
        public inline fun <reified P : Any> of(json: Json = Json): KotlinxJsonCodec<P> =
            KotlinxJsonCodec(serializer<P>(), json)
    }
}
