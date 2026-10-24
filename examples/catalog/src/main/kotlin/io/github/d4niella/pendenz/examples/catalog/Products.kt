package io.github.d4niella.pendenz.examples.catalog

import javax.sql.DataSource

/** A product as the classification sees it. */
data class Product(
    val id: Long,
    val name: String,
    val description: String?,
    val recalled: Boolean,
)

/** The product table, read with plain JDBC. */
class Products(
    private val dataSource: DataSource,
) {
    fun find(id: Long): Product? =
        dataSource.query(
            "SELECT id, name, description, recalled FROM product WHERE id = ?",
            { it.setLong(1, id) },
        ) { rs ->
            if (rs.next()) {
                Product(rs.getLong("id"), rs.getString("name"), rs.getString("description"), rs.getBoolean("recalled"))
            } else {
                null
            }
        }
}
