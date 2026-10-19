package io.github.d4niella.pendenz.store

import io.github.d4niella.pendenz.PayloadColumn
import io.github.d4niella.pendenz.SqlType
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** The unit of work of the JDBC stores: a connection the caller or the provider owns. */
internal class JdbcTransaction(
    val connection: Connection,
) : StoreTransaction

/** The connection behind a handle, which must be this store's own. */
internal val StoreTransaction.jdbc: Connection
    get() =
        (this as? JdbcTransaction)?.connection
            ?: throw IllegalArgumentException("transaction ${this::class.simpleName} is not from this store")

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

    /** A payload value, converted from its API type to what the driver takes for the column's type. */
    fun payload(
        column: PayloadColumn,
        value: Any?,
    ) {
        if (value == null) {
            stmt.setNull(++index, Types.NULL)
            return
        }
        require(column.type.javaType.isInstance(value)) {
            "payload column '${column.name}' takes ${column.type.javaType.simpleName}, got ${value::class.simpleName}"
        }
        when (column.type) {
            SqlType.TIMESTAMPTZ -> stmt.setObject(++index, OffsetDateTime.ofInstant(value as Instant, ZoneOffset.UTC))
            else -> stmt.setObject(++index, value)
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

/** Reads the payload columns into values of their API types; absent columns are left out. */
internal fun ResultSet.payload(columns: List<PayloadColumn>): Map<String, Any?> =
    buildMap {
        columns.forEach { column ->
            val value: Any? =
                when (column.type) {
                    SqlType.TIMESTAMPTZ -> instant(column.name)

                    // The driver refuses getObject(String) for jsonb; getString serves text and JSON alike.
                    SqlType.TEXT, SqlType.JSONB -> getString(column.name)

                    else -> getObject(column.name, column.type.javaType)
                }
            if (value != null) put(column.name, value)
        }
    }
