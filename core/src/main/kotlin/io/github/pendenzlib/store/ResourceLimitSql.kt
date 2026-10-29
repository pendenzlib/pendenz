package io.github.pendenzlib.store

/**
 * The statements behind [ResourceLimitStore], with `?` placeholders in the
 * order listed per statement.
 *
 * @property setLimit binds name, maxRunning; inserts or updates.
 * @property seedLimit binds name, maxRunning; inserts unless the name exists.
 * @property removeLimit binds name.
 * @property limits binds nothing; returns the name and maxRunning columns.
 * @property lockClaims binds nothing; takes a lock for the transaction that every store over the same task
 *   table shares, however its name is spelled.
 */
public class ResourceLimitSql(
    public val setLimit: String,
    public val seedLimit: String,
    public val removeLimit: String,
    public val limits: String,
    public val lockClaims: String,
)
