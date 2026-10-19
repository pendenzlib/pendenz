package io.github.d4niella.pendenz.store

/**
 * The statements behind [CycleStore], with `?` placeholders in the order
 * listed per statement. Task ids and cycle ids bind as the id type's Java
 * object; a lane binds as text.
 *
 * @property insertCycle binds type, the ACTIVE status, description, createdAt, createdBy; returns the id column.
 * @property insertCycleWithId binds id, then the same as [insertCycle].
 * @property insertTasksFromQuery takes the application's query and an optional SQL expression for the resource
 *   and returns a statement that binds type, status, runAt, createdAt, createdBy, payloadVersion, lane,
 *   priority, then the resource unless the expression was given, then correlationId, and inserts one task per
 *   row of the query, whose columns are the payload columns in declared order.
 * @property findCycle binds id; returns all mapped cycle columns.
 * @property updateCycleStatus binds the new status, finishedAt or null, id, the expected status.
 * @property quarantineLane binds the QUARANTINED status, the reason, lane, then the PENDING, SCHEDULED and
 *   RETRY_PENDING statuses.
 * @property releaseLane binds now (for the SCHEDULED or PENDING decision), the SCHEDULED status, the PENDING
 *   status, lane, the QUARANTINED status, the reason.
 * @property cancelLane binds the CANCELLED status, finishedAt, lane, then the SUCCEEDED and CANCELLED statuses.
 * @property countLane binds lane; returns the task status column and a count column named `count`.
 * @property finishCycles binds the FINISHED status, finishedAt, the ACTIVE status, then the SUCCEEDED and
 *   CANCELLED statuses.
 */
@Suppress("LongParameterList") // one parameter per statement, by design
public class CycleSql(
    public val insertCycle: String,
    public val insertCycleWithId: String,
    public val insertTasksFromQuery: (query: String, resourceSql: String?) -> String,
    public val findCycle: String,
    public val updateCycleStatus: String,
    public val quarantineLane: String,
    public val releaseLane: String,
    public val cancelLane: String,
    public val countLane: String,
    public val finishCycles: String,
)
