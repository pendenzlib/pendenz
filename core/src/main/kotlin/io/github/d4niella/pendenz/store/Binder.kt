package io.github.d4niella.pendenz.store

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Binds parameters in order, so SQL text and binding read the same way. */
internal class Binder(
    private val stmt: PreparedStatement,
) {
    private var index = 0

    fun string(value: String?) = stmt.setString(++index, value)

    fun long(value: Long) = stmt.setLong(++index, value)

    /** Task ids of any supported [io.github.d4niella.pendenz.schema.IdType]; the driver maps the Java type. */
    fun id(value: Any) = stmt.setObject(++index, value)

    fun int(value: Int) = stmt.setInt(++index, value)

    fun instant(value: Instant?) {
        if (value == null) {
            stmt.setNull(++index, Types.TIMESTAMP_WITH_TIMEZONE)
        } else {
            stmt.setObject(++index, OffsetDateTime.ofInstant(value, ZoneOffset.UTC))
        }
    }

    fun stringArray(
        connection: Connection,
        values: Collection<String>,
    ) = stmt.setArray(++index, connection.createArrayOf("varchar", values.toTypedArray()))
}

internal inline fun <T> Connection.query(
    sql: String,
    bind: (Binder) -> Unit,
    read: (ResultSet) -> T,
): T =
    prepareStatement(sql).use { stmt ->
        bind(Binder(stmt))
        stmt.executeQuery().use(read)
    }

internal inline fun Connection.update(
    sql: String,
    bind: (Binder) -> Unit,
): Int =
    prepareStatement(sql).use { stmt ->
        bind(Binder(stmt))
        stmt.executeUpdate()
    }

internal fun ResultSet.instant(column: String): Instant? = getObject(column, OffsetDateTime::class.java)?.toInstant()
