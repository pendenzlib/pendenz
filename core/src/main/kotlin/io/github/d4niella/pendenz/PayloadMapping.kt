package io.github.d4niella.pendenz

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * How a task type's payload is stored: which columns it occupies and how a
 * payload becomes column values and back. JSON in one `jsonb` column is
 * the default, see [JsonPayload]; [ColumnsPayload] spreads fields over
 * typed columns so that SQL can read and write them. See ADR 0013.
 *
 * Values are keyed by column name and typed per [SqlType]: `Long` for
 * `BIGINT`, `Int` for `INTEGER`, `String` for `TEXT` and `JSONB`,
 * `Boolean`, `BigDecimal`, `Instant` for `TIMESTAMPTZ`, `LocalDate`,
 * `UUID`. A missing or null value is an absent field.
 */
public interface PayloadMapping<P : Any> {
    /** The columns this payload occupies, with their types. */
    public val columns: List<PayloadColumn>

    /** The column values of [payload]; keys must be among [columns]. */
    public fun write(payload: P): Map<String, Any?>

    /** The payload read from column values. */
    public fun read(values: PayloadValues): P
}

/** A payload column: its name in the table and its SQL type. */
public class PayloadColumn(
    public val name: String,
    public val type: SqlType,
) {
    init {
        require(name.isNotBlank()) { "payload column name must not be blank" }
    }

    override fun equals(other: Any?): Boolean = other is PayloadColumn && other.name == name && other.type == type

    override fun hashCode(): Int = 31 * name.hashCode() + type.hashCode()

    override fun toString(): String = "$name $type"
}

/**
 * The SQL types a payload column may have, and the Java type each one
 * takes in [PayloadMapping.write] and gives in [PayloadValues].
 */
public enum class SqlType(
    /** The type as the DDL generator prints it. */
    public val ddl: String,
    /** The class a value of this type has on the Java side. */
    public val javaType: Class<*>,
) {
    BIGINT("bigint", Long::class.javaObjectType),
    INTEGER("integer", Int::class.javaObjectType),
    TEXT("text", String::class.java),
    BOOLEAN("boolean", Boolean::class.javaObjectType),
    NUMERIC("numeric", BigDecimal::class.java),
    TIMESTAMPTZ("timestamptz", Instant::class.java),
    DATE("date", LocalDate::class.java),
    UUID("uuid", java.util.UUID::class.java),

    /** JSON text; the value is the document as a `String`. */
    JSONB("jsonb", String::class.java),
}

/**
 * Column values as read back, with typed accessors. The `get...` methods
 * throw when the column is absent, the `...OrNull` ones return null.
 */
@Suppress("TooManyFunctions") // one pair per SqlType, by design
public class PayloadValues(
    private val values: Map<String, Any?>,
) {
    /** Every present value by column name. */
    public fun toMap(): Map<String, Any?> = values.filterValues { it != null }

    public fun getLong(column: String): Long = required(column)

    public fun getLongOrNull(column: String): Long? = optional(column)

    public fun getInt(column: String): Int = required(column)

    public fun getIntOrNull(column: String): Int? = optional(column)

    public fun getString(column: String): String = required(column)

    public fun getStringOrNull(column: String): String? = optional(column)

    public fun getBoolean(column: String): Boolean = required(column)

    public fun getBooleanOrNull(column: String): Boolean? = optional(column)

    public fun getDecimal(column: String): BigDecimal = required(column)

    public fun getDecimalOrNull(column: String): BigDecimal? = optional(column)

    public fun getInstant(column: String): Instant = required(column)

    public fun getInstantOrNull(column: String): Instant? = optional(column)

    public fun getDate(column: String): LocalDate = required(column)

    public fun getDateOrNull(column: String): LocalDate? = optional(column)

    public fun getUuid(column: String): UUID = required(column)

    public fun getUuidOrNull(column: String): UUID? = optional(column)

    /** The JSON document of a `JSONB` column as text. */
    public fun getJson(column: String): String = required(column)

    public fun getJsonOrNull(column: String): String? = optional(column)

    private inline fun <reified T : Any> required(column: String): T =
        optional<T>(column) ?: throw NoSuchElementException("payload column '$column' is absent")

    private inline fun <reified T : Any> optional(column: String): T? {
        val value = values[column] ?: return null
        return value as? T
            ?: throw ClassCastException(
                "payload column '$column' holds ${value::class.simpleName}, not ${T::class.simpleName}",
            )
    }

    override fun toString(): String = "PayloadValues(${toMap()})"
}

/**
 * The default mapping: the whole payload as one JSON document in a
 * `jsonb` column, encoded by a [PayloadCodec].
 */
public class JsonPayload<P : Any>
    @JvmOverloads
    constructor(
        private val codec: PayloadCodec<P>,
        public val column: String = DEFAULT_COLUMN,
    ) : PayloadMapping<P> {
        override val columns: List<PayloadColumn> = listOf(PayloadColumn(column, SqlType.JSONB))

        override fun write(payload: P): Map<String, Any?> = mapOf(column to codec.encode(payload))

        override fun read(values: PayloadValues): P = codec.decode(values.getJson(column))

        public companion object {
            public const val DEFAULT_COLUMN: String = "payload"
        }
    }

/**
 * A payload spread over typed columns, so that SQL can read, write and
 * join them. [write] and [read] are written by hand: no reflection.
 */
public class ColumnsPayload<P : Any>(
    override val columns: List<PayloadColumn>,
    private val write: (P) -> Map<String, Any?>,
    private val read: (PayloadValues) -> P,
) : PayloadMapping<P> {
    init {
        require(columns.isNotEmpty()) { "a columns payload needs at least one column" }
        val names = columns.map { it.name }
        require(names.toSet().size == names.size) { "payload column names must be distinct, got $names" }
    }

    override fun write(payload: P): Map<String, Any?> = write.invoke(payload)

    override fun read(values: PayloadValues): P = read.invoke(values)
}
