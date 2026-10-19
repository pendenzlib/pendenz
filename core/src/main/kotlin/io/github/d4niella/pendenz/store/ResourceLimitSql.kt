package io.github.d4niella.pendenz.store

/**
 * The statements behind [ResourceLimitStore], with `?` placeholders in the
 * order listed per statement.
 *
 * @property setLimit binds name, maxRunning; inserts or updates.
 * @property seedLimit binds name, maxRunning; inserts unless the name exists.
 * @property removeLimit binds name.
 * @property limits binds nothing; returns the name and maxRunning columns.
 * @property lockClaims binds a text key to lock on for the transaction.
 */
public class ResourceLimitSql(
    public val setLimit: String,
    public val seedLimit: String,
    public val removeLimit: String,
    public val limits: String,
    public val lockClaims: String,
)
