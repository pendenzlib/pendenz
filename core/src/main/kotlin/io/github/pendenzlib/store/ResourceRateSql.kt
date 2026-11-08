package io.github.pendenzlib.store

/**
 * The statements behind [ResourceRateStore], with `?` placeholders in the
 * order listed per statement. Instants are bound as `OffsetDateTime` in UTC,
 * windows as whole milliseconds.
 *
 * @property setRate binds name, maxStarts, per in milliseconds; inserts or updates.
 * @property seedRate binds name, maxStarts, per in milliseconds; inserts unless the name exists.
 * @property removeRate binds name.
 * @property rates binds nothing; returns the name and maxStarts columns and the window in milliseconds as
 *   [PER_MILLIS], by name.
 * @property startsPerResource binds now twice; returns the resource column of the start table and, as
 *   [STARTS_COUNT], the starts after now minus the window of the longest matching rate name, one row per
 *   resource value with such starts.
 * @property recordStarts binds the resources as a string array and now; inserts one start per array element.
 * @property deleteStartsBefore binds an instant; deletes the starts at or before it.
 */
public class ResourceRateSql(
    public val setRate: String,
    public val seedRate: String,
    public val removeRate: String,
    public val rates: String,
    public val startsPerResource: String,
    public val recordStarts: String,
    public val deleteStartsBefore: String,
) {
    public companion object {
        /** The column alias [ResourceRateSql.rates] returns the window under, in milliseconds. */
        public const val PER_MILLIS: String = "per_millis"

        /** The column alias [ResourceRateSql.startsPerResource] returns the count under. */
        public const val STARTS_COUNT: String = "starts_count"
    }
}
