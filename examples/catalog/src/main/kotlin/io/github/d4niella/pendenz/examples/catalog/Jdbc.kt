package io.github.d4niella.pendenz.examples.catalog

import java.sql.PreparedStatement
import java.sql.ResultSet
import javax.sql.DataSource

/** Runs [sql] on a connection of its own, binds it with [bind] and hands the result set to [read]. */
internal fun <T> DataSource.query(
    sql: String,
    bind: (PreparedStatement) -> Unit = {},
    read: (ResultSet) -> T,
): T =
    connection.use { connection ->
        connection.prepareStatement(sql).use { statement ->
            bind(statement)
            statement.executeQuery().use(read)
        }
    }
