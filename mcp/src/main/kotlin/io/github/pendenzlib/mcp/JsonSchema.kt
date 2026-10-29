package io.github.pendenzlib.mcp

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

/**
 * JSON Schemas for tool arguments, derived from the descriptor the
 * kotlinx-serialization plugin generates at compile time; no reflection
 * (ADR 0021). Covers classes, objects, enums, lists, maps with string
 * keys, primitives, value classes and nullable types. Polymorphic and
 * contextual types have no schema the descriptor could tell; write those
 * by hand.
 */
public object JsonSchema {
    /** The schema of [P] as JSON text. */
    public inline fun <reified P : Any> of(): String = of(serializer<P>())

    /** The schema of what [serializer] reads, as JSON text. */
    @JvmStatic
    public fun of(serializer: KSerializer<*>): String = of(serializer.descriptor)

    /** The schema of what [descriptor] describes, as JSON text. */
    @JvmStatic
    public fun of(descriptor: SerialDescriptor): String = schema(descriptor, emptyList()).toString()

    private fun schema(
        descriptor: SerialDescriptor,
        enclosing: List<String>,
    ): JsonElement {
        val plain =
            if (descriptor.isInline) {
                schema(
                    descriptor.getElementDescriptor(0),
                    enclosing,
                )
            } else {
                plain(descriptor, enclosing)
            }
        if (!descriptor.isNullable) return plain
        return buildJsonObject { put("anyOf", JsonArray(listOf(plain, buildJsonObject { put("type", "null") }))) }
    }

    private fun plain(
        descriptor: SerialDescriptor,
        enclosing: List<String>,
    ): JsonElement =
        when (val kind = descriptor.kind) {
            is PrimitiveKind -> {
                primitive(kind)
            }

            SerialKind.ENUM -> {
                buildJsonObject {
                    put("type", "string")
                    put(
                        "enum",
                        JsonArray(
                            (0 until descriptor.elementsCount).map { JsonPrimitive(descriptor.getElementName(it)) },
                        ),
                    )
                }
            }

            StructureKind.CLASS, StructureKind.OBJECT -> {
                structure(descriptor, enclosing)
            }

            StructureKind.LIST -> {
                buildJsonObject {
                    put("type", "array")
                    put("items", schema(descriptor.getElementDescriptor(0), enclosing))
                }
            }

            StructureKind.MAP -> {
                map(descriptor, enclosing)
            }

            else -> {
                unsupported(descriptor, "polymorphic and contextual types")
            }
        }

    private fun primitive(kind: PrimitiveKind): JsonObject =
        when (kind) {
            PrimitiveKind.STRING -> {
                type("string")
            }

            PrimitiveKind.CHAR -> {
                buildJsonObject {
                    put("type", "string")
                    put("minLength", 1)
                    put("maxLength", 1)
                }
            }

            PrimitiveKind.BOOLEAN -> {
                type("boolean")
            }

            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> {
                type("integer")
            }

            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> {
                type("number")
            }
        }

    private fun structure(
        descriptor: SerialDescriptor,
        enclosing: List<String>,
    ): JsonObject {
        val name = descriptor.serialName.removeSuffix("?")
        if (name in enclosing) unsupported(descriptor, "recursive types")
        val inside = enclosing + name
        val elements = 0 until descriptor.elementsCount
        return buildJsonObject {
            put("type", "object")
            put(
                "properties",
                JsonObject(
                    elements.associate {
                        descriptor.getElementName(it) to
                            schema(descriptor.getElementDescriptor(it), inside)
                    },
                ),
            )
            val required =
                elements
                    .filterNot {
                        descriptor.isElementOptional(it)
                    }.map { JsonPrimitive(descriptor.getElementName(it)) }
            if (required.isNotEmpty()) put("required", JsonArray(required))
            put("additionalProperties", false)
        }
    }

    private fun map(
        descriptor: SerialDescriptor,
        enclosing: List<String>,
    ): JsonObject {
        val key = descriptor.getElementDescriptor(0)
        if (key.kind != PrimitiveKind.STRING &&
            key.kind != SerialKind.ENUM
        ) {
            unsupported(descriptor, "maps without string keys")
        }
        return buildJsonObject {
            put("type", "object")
            put("additionalProperties", schema(descriptor.getElementDescriptor(1), enclosing))
        }
    }

    private fun type(name: String): JsonObject = buildJsonObject { put("type", name) }

    private fun unsupported(
        descriptor: SerialDescriptor,
        what: String,
    ): Nothing =
        throw IllegalArgumentException("${descriptor.serialName}: $what have no derived schema; write it by hand")
}
