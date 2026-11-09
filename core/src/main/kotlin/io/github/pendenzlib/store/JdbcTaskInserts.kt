package io.github.pendenzlib.store

import io.github.pendenzlib.PayloadColumn
import io.github.pendenzlib.schema.IdType
import io.github.pendenzlib.schema.TaskColumns
import java.sql.ResultSet
import java.sql.Statement

/**
 * The inserts of [JdbcTaskStore]: one task, or a list as JDBC batches, and
 * what follows when a key is taken (ADR 0009, ADR 0044, ADR 0046).
 */
internal class JdbcTaskInserts<ID : Any>(
    private val sql: TaskSql,
    private val payloadColumns: List<PayloadColumn>,
    private val idType: IdType<ID>,
    private val names: TaskColumns,
) {
    fun insert(
        tx: StoreTransaction,
        task: NewTask<ID>,
    ): InsertedTask<ID> {
        requireKnownPayload(task)
        val id = task.id ?: idType.generate()
        val statement = if (id == null) sql.insert else sql.insertWithId
        val inserted =
            tx.jdbc.query(
                statement,
                { bind -> bindInsert(bind, task, id) },
            ) { rs -> if (rs.next()) rs.id() else null }
        return if (inserted != null) InsertedTask(inserted, isNew = true) else keyTaken(tx, task)
    }

    /**
     * One JDBC batch per run of tasks that share a statement, so the order holds. A row whose key was taken
     * returns nothing; the returned rows are matched to the tasks by type and key (ADR 0046).
     */
    fun insertAll(
        tx: StoreTransaction,
        tasks: List<NewTask<ID>>,
    ): List<InsertedTask<ID>> {
        tasks.forEach(::requireKnownPayload)
        val ids = tasks.map { it.id ?: idType.generate() }
        val inserted = arrayOfNulls<Any>(tasks.size)
        var start = 0
        while (start < tasks.size) {
            val withId = ids[start] != null
            var end = start
            while (end < tasks.size && (ids[end] != null) == withId) end++
            insertBatch(tx, tasks, ids, start until end, if (withId) sql.insertWithId else sql.insert, inserted)
            start = end
        }
        @Suppress("UNCHECKED_CAST") // filled with ids read through rs.id()
        return tasks.mapIndexed { i, task ->
            (inserted[i] as ID?)?.let { InsertedTask(it, isNew = true) } ?: keyTaken(tx, task)
        }
    }

    @Suppress("LongParameterList") // the batch's tasks, their ids, the range and where to put what came back
    private fun insertBatch(
        tx: StoreTransaction,
        tasks: List<NewTask<ID>>,
        ids: List<ID?>,
        range: IntRange,
        statement: String,
        inserted: Array<Any?>,
    ) {
        tx.jdbc.prepareStatement(statement, Statement.RETURN_GENERATED_KEYS).use { stmt ->
            range.forEach { i ->
                bindInsert(Binder(stmt), tasks[i], ids[i])
                stmt.addBatch()
            }
            stmt.executeBatch()
            stmt.generatedKeys.use { rs -> matchReturned(rs, tasks, range, inserted) }
        }
    }

    /**
     * Walks the returned rows and the tasks of [range] in order. A task without a row had its key taken and is
     * skipped; a task without a key always returns one.
     */
    private fun matchReturned(
        rs: ResultSet,
        tasks: List<NewTask<ID>>,
        range: IntRange,
        inserted: Array<Any?>,
    ) {
        var next = range.first
        while (rs.next()) {
            val type = rs.getString(names.type)
            val key = rs.getString(names.idempotencyKey)
            while (next <= range.last && !(tasks[next].type == type && tasks[next].idempotencyKey == key)) {
                check(tasks[next].idempotencyKey != null) { "task of type '${tasks[next].type}' returned no row" }
                next++
            }
            check(next <= range.last) { "the batch returned a row of type '$type' and key '$key' it did not insert" }
            inserted[next++] = rs.id()
        }
    }

    private fun requireKnownPayload(task: NewTask<ID>) {
        val unknown = task.payload.keys - payloadColumns.map { it.name }.toSet()
        require(unknown.isEmpty()) {
            "payload columns $unknown are not among this store's columns; " +
                "build the store with every task type's columns"
        }
    }

    /** Binds [task] to an insert statement, with [id] first when the statement takes one. */
    private fun bindInsert(
        bind: Binder,
        task: NewTask<ID>,
        id: ID?,
    ) {
        if (id != null) bind.id(id)
        bind.string(task.type)
        bind.string(task.status.name)
        bind.instant(task.runAt)
        bind.instant(task.createdAt)
        bind.string(task.idempotencyKey)
        bind.string(task.createdBy)
        bind.int(task.payloadVersion)
        bind.string(task.lane)
        bind.int(task.priority)
        bind.string(task.resource)
        bind.idOrNull(task.parentId)
        bind.string(task.correlationId)
        bind.string(task.preview)
        payloadColumns.forEach { bind.payload(it, task.payload[it.name]) }
    }

    /** What an insert that stored no row returns: the replaced waiting task, or the one holding the key. */
    private fun keyTaken(
        tx: StoreTransaction,
        task: NewTask<ID>,
    ): InsertedTask<ID> {
        val key = checkNotNull(task.idempotencyKey) { "INSERT ... RETURNING produced no row" }
        if (task.replaceWaiting) replaceWaiting(tx, task, key)?.let { return InsertedTask(it, false, true) }
        val existing =
            tx.jdbc.query(sql.findIdByKey, { bind ->
                bind.string(task.type)
                bind.string(key)
            }) { rs ->
                check(rs.next()) { "task of type '${task.type}' with idempotency key '$key' vanished" }
                rs.id()
            }
        return InsertedTask(existing, isNew = false)
    }

    /** Replaces the waiting row that holds [key] with [task]; null when there is none (ADR 0044). */
    private fun replaceWaiting(
        tx: StoreTransaction,
        task: NewTask<ID>,
        key: String,
    ): ID? {
        val statement =
            checkNotNull(sql.replaceWaiting) {
                "the SQL dialect has no replaceWaiting statement; task type '${task.type}' replaces waiting tasks"
            }
        return tx.jdbc.query(statement, { bind ->
            bind.string(task.status.name)
            bind.instant(task.runAt)
            bind.int(task.payloadVersion)
            bind.string(task.lane)
            bind.int(task.priority)
            bind.string(task.resource)
            bind.string(task.preview)
            payloadColumns.forEach { bind.payload(it, task.payload[it.name]) }
            bind.string(task.type)
            bind.string(key)
        }) { rs -> if (rs.next()) rs.id() else null }
    }

    private fun ResultSet.id(): ID = getObject(names.id, idType.javaType)
}
