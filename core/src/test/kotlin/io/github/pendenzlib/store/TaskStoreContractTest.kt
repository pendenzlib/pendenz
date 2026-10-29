package io.github.pendenzlib.store

import io.github.pendenzlib.CycleLane
import io.github.pendenzlib.CycleStatus
import io.github.pendenzlib.Pendenz
import io.github.pendenzlib.ResourceLimit
import io.github.pendenzlib.TaskQuery
import io.github.pendenzlib.TaskStatus
import io.github.pendenzlib.TaskStatusTransitions
import io.github.pendenzlib.testing.claim
import io.github.pendenzlib.testing.claimFromGroup
import io.github.pendenzlib.testing.dueGroups
import io.github.pendenzlib.testing.guest
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The contract every [TaskStore] fulfils, run once per implementation.
 * Subclasses provide a fresh, empty store with `bigint` ids per test.
 */
abstract class TaskStoreContractTest {
    protected abstract fun newStore(): TaskStore<Long>

    protected abstract fun createArchiveTable(): String

    protected abstract fun archivedCount(archiveTable: String): Long

    private lateinit var store: TaskStore<Long>
    private val t0 = Instant.parse("2026-10-19T09:00:00Z")
    private val t1 = t0.plusSeconds(90)

    @BeforeEach
    fun createStore() {
        store = newStore()
    }

    /**
     * Every operation an operator, a cycle or an approval performs, on a
     * task in every status: the status stays or takes a legal transition
     * (ADR 0002, ADR 0031). The run time lies ahead, so a restart and a
     * release take their different ways.
     */
    @ParameterizedTest(name = "{0} on {1}")
    @MethodSource("everyOperationOnEveryStatus")
    fun `operator operation - task in any status - stays or moves along a legal transition`(
        operation: Operation,
        status: TaskStatus,
    ) = runBlocking<Unit> {
        // Arrange
        val cycles = store.cycles!!
        val pendenz = Pendenz.create { store(store).clock(Clock.fixed(t0, ZoneOffset.UTC)) }
        val cycle = store.transaction { cycles.insertCycle(it, NewCycle(null, "invoice", "October", t0, "ops")) }
        val lane = CycleLane.of(cycle)
        val inserted = if (status == TaskStatus.QUARANTINED) TaskStatus.PENDING else status
        val id = store.transaction { store.insert(it, pending("invoice", "Ada", runAt = t1, inserted).inLane(lane)).id }
        store.transaction { tx ->
            if (status == TaskStatus.QUARANTINED) cycles.quarantineLane(tx, lane, CycleStore.PAUSED_REASON, t0)
            if (operation == Operation.RESUME_CYCLE) {
                cycles.updateCycleStatus(tx, cycle, setOf(CycleStatus.ACTIVE), CycleStatus.PAUSED, t0)
            }
        }

        // Act
        pendenz.perform(operation, id, cycle)

        // Assert
        val after = store.transaction { store.find(it, id) }!!.status
        if (after != status) {
            assertThat(TaskStatusTransitions.isAllowed(status, after))
                .describedAs("%s took %s -> %s", operation, status, after)
                .isTrue()
        }
    }

    private suspend fun Pendenz<Long>.perform(
        operation: Operation,
        id: Long,
        cycle: Long,
    ) {
        when (operation) {
            Operation.RESTART -> restart(id)
            Operation.COMPLETE -> complete(id)
            Operation.QUARANTINE -> quarantine(id, "held for ticket #123")
            Operation.CANCEL -> cancel(id)
            Operation.APPROVE -> approve(id, "grace")
            Operation.DECLINE -> decline(id, "grace", "not this month")
            Operation.PAUSE_CYCLE -> pauseCycle(cycle)
            Operation.RESUME_CYCLE -> resumeCycle(cycle)
            Operation.CANCEL_CYCLE -> cancelCycle(cycle)
            Operation.APPROVE_CYCLE -> approveCycle(cycle, "grace")
        }
    }

    enum class Operation {
        RESTART,
        COMPLETE,
        QUARANTINE,
        CANCEL,
        APPROVE,
        DECLINE,
        PAUSE_CYCLE,
        RESUME_CYCLE,
        CANCEL_CYCLE,
        APPROVE_CYCLE,
    }

    @Nested
    inner class Insert {
        @Test
        fun `insert - no id given - returns a generated id and find reads the row back`() =
            runBlocking<Unit> {
                val id = store.transaction { store.insert(it, pending("greet", "Ada")).id }

                // Assert
                val record = store.transaction { store.find(it, id) }!!
                assertThat(record.id).isEqualTo(id)
                assertThat(record.type).isEqualTo("greet")
                assertThat(record.status).isEqualTo(TaskStatus.PENDING)
                assertThat(record.payload["payload"].toString().normalised()).isEqualTo("""{"name":"Ada"}""")
                assertThat(record.attempts).isZero()
                assertThat(record.version).isZero()
                assertThat(record.createdAt).isEqualTo(t0)
            }

        @Test
        fun `insert - creator given - find and claim both carry it`() =
            runBlocking<Unit> {
                // Arrange
                val byAgent =
                    NewTask<Long>(null, "greet", json("{}"), t0, TaskStatus.PENDING, t0, createdBy = "triage-agent")

                val id = store.transaction { store.insert(it, byAgent).id }

                // Assert
                assertThat(store.transaction { store.find(it, id) }!!.createdBy).isEqualTo("triage-agent")
                val claimed = store.transaction { store.claim(it, "w1", setOf("greet"), 1, t0) }.single()
                assertThat(claimed.createdBy).isEqualTo("triage-agent")
            }

        @Test
        fun `find - unknown id - returns null`() =
            runBlocking<Unit> {
                val record = store.transaction { store.find(it, 404L) }

                assertThat(record).isNull()
            }
    }

    @Nested
    inner class IdempotencyKey {
        @Test
        fun `insert - key already used for the type - inserts nothing, returns the existing id as not new`() =
            runBlocking<Unit> {
                // Arrange
                val first = pending("greet", "Ada").withKey("ticket-4711")
                val existing = store.transaction { store.insert(it, first) }

                val repeat = pending("greet", "Bob").withKey("ticket-4711")
                val returned = store.transaction { store.insert(it, repeat) }

                // Assert
                assertThat(existing.isNew).isTrue()
                assertThat(returned.id).isEqualTo(existing.id)
                assertThat(returned.isNew).isFalse()
                val record = store.transaction { store.find(it, existing.id) }!!
                assertThat(record.payload["payload"].toString()).contains("Ada")
                assertThat(record.idempotencyKey).isEqualTo("ticket-4711")
            }

        @Test
        fun `insert - same key for another type - inserts a second task`() =
            runBlocking<Unit> {
                // Arrange
                val mail = store.transaction { store.insert(it, pending("mail", "Ada").withKey("ticket-4711")).id }

                val credit = store.transaction { store.insert(it, pending("credit", "Ada").withKey("ticket-4711")).id }

                assertThat(credit).isNotEqualTo(mail)
            }

        @Test
        fun `insert - no key twice - inserts two tasks`() =
            runBlocking<Unit> {
                val first = store.transaction { store.insert(it, pending("greet", "Ada")).id }

                val second = store.transaction { store.insert(it, pending("greet", "Ada")).id }

                assertThat(second).isNotEqualTo(first)
            }

        private fun NewTask<Long>.withKey(key: String) =
            NewTask(id, type, payload, runAt, status, createdAt, idempotencyKey = key)
    }

    @Nested
    inner class Claim {
        @Test
        fun `claim - two pending tasks and limit 1 - claims the one due first as RUNNING, locked until the deadline`() =
            runBlocking<Unit> {
                // Arrange
                val later =
                    store.transaction {
                        store
                            .insert(
                                it,
                                pending("greet", "Bob", runAt = t0.plusSeconds(1)),
                            ).id
                    }
                val earlier = store.transaction { store.insert(it, pending("greet", "Ada", runAt = t0)).id }

                val claimed =
                    store.transaction { store.claim(it, "w1", setOf("greet"), 1, t0.plusSeconds(5), t1) }

                // Assert
                assertThat(claimed.map { it.id }).containsExactly(earlier)
                assertThat(claimed.single().attempt).isEqualTo(1)
                val record = store.transaction { store.find(it, earlier) }!!
                assertThat(record.status).isEqualTo(TaskStatus.RUNNING)
                assertThat(record.lockedBy).isEqualTo("w1")
                assertThat(record.lockedUntil).isEqualTo(t1)
                assertThat(record.version).isEqualTo(claimed.single().version)
                assertThat(store.transaction { store.find(it, later) }!!.status).isEqualTo(TaskStatus.PENDING)
            }

        @Test
        fun `claim - task of a type the worker does not handle - is left PENDING`() =
            runBlocking<Unit> {
                val id = store.transaction { store.insert(it, pending("other", "Ada")).id }

                val claimed = store.transaction { store.claim(it, "w1", setOf("greet"), 10, t0) }

                // Assert
                assertThat(claimed).isEmpty()
                assertThat(store.transaction { store.find(it, id) }!!.status).isEqualTo(TaskStatus.PENDING)
            }

        @Test
        fun `claim - pending task not yet due - is left alone`() =
            runBlocking<Unit> {
                store.transaction { store.insert(it, pending("greet", "Ada", runAt = t0.plusSeconds(60))).id }

                val claimed = store.transaction { store.claim(it, "w1", setOf("greet"), 10, t0) }

                assertThat(claimed).isEmpty()
            }

        @Test
        fun `claim - task due 90 seconds before the claim - returns its due time and the claim time`() =
            runBlocking<Unit> {
                store.transaction { store.insert(it, pending("greet", "Ada", runAt = t0)).id }

                val claimed = store.transaction { store.claim(it, "w1", setOf("greet"), 1, t1) }.single()

                // Assert
                assertThat(claimed.runAt).isEqualTo(t0)
                assertThat(claimed.claimedAt).isEqualTo(t1)
            }
    }

    @Nested
    inner class Lanes {
        @Test
        fun `dueGroups - two lanes, two priorities, another type - each group once, highest priority first`() =
            runBlocking<Unit> {
                // Arrange
                store.transaction { store.insert(it, pending("greet", "Ada").inLane("cycle-1")).id }
                store.transaction { store.insert(it, pending("greet", "Bob").inLane("cycle-1")).id }
                store.transaction { store.insert(it, pending("greet", "Cleo")).id }
                store.transaction { store.insert(it, pending("greet", "Dan").inLane("cycle-2", priority = 5)).id }
                store.transaction { store.insert(it, pending("other", "Eve")).id }
                store.transaction { store.insert(it, pending("greet", "Fay", runAt = t0.plusSeconds(60))).id }

                val groups = store.transaction { store.dueGroups(it, setOf("greet"), t0) }

                // Assert
                assertThat(groups).containsExactly(
                    ClaimGroup(5, "greet", "cycle-2"),
                    ClaimGroup(0, "greet", "cycle-1"),
                    ClaimGroup(0, "greet", null),
                )
            }

        @Test
        fun `claimFromGroup - two tasks in the lane and one outside - takes the lane's oldest only`() =
            runBlocking<Unit> {
                // Arrange
                val single = store.transaction { store.insert(it, pending("greet", "Ada")).id }
                val first = store.transaction { store.insert(it, pending("greet", "Bob").inLane("cycle-1")).id }
                store.transaction { store.insert(it, pending("greet", "Cleo").inLane("cycle-1")).id }

                val claimed =
                    store.transaction {
                        store.claimFromGroup(
                            it,
                            "w1",
                            ClaimGroup(0, "greet", "cycle-1"),
                            1,
                            t0,
                        )
                    }

                // Assert
                assertThat(claimed.map { it.id }).containsExactly(first)
                assertThat(claimed.single().lane).isEqualTo("cycle-1")
                assertThat(store.transaction { store.find(it, single) }!!.status).isEqualTo(TaskStatus.PENDING)
            }

        @Test
        fun `claimFromGroup - the singles group - takes tasks without a lane only`() =
            runBlocking<Unit> {
                // Arrange
                val single = store.transaction { store.insert(it, pending("greet", "Ada")).id }
                store.transaction { store.insert(it, pending("greet", "Bob").inLane("cycle-1")).id }

                val claimed = store.transaction { store.claimFromGroup(it, "w1", ClaimGroup(0, "greet", null), 5, t0) }

                assertThat(claimed.map { it.id }).containsExactly(single)
            }

        @Test
        fun `claim - a later task with higher priority - comes first even in arrival order`() =
            runBlocking<Unit> {
                // Arrange
                store.transaction { store.insert(it, pending("greet", "Ada")).id }
                val urgent =
                    store.transaction {
                        store
                            .insert(
                                it,
                                pending("greet", "Bob").inLane(null, priority = 9),
                            ).id
                    }

                val claimed = store.transaction { store.claim(it, "w1", setOf("greet"), 1, t0) }

                // Assert
                assertThat(claimed.map { it.id }).containsExactly(urgent)
                assertThat(store.transaction { store.find(it, urgent) }!!.priority).isEqualTo(9)
            }
    }

    @Nested
    inner class ResourceLimits {
        @Test
        fun `claim - resource in the filter's full set - skipped, the task without resource is claimed`() =
            runBlocking<Unit> {
                // Arrange
                store.transaction { store.resourceLimits!!.setLimit(it, ResourceLimit("mail-server", 1)) }
                store.transaction { store.insert(it, pending("greet", "Ada").against("mail-server")).id }
                store.transaction { store.claim(it, "w1", setOf("greet"), 1, t0) }
                store.transaction { store.insert(it, pending("greet", "Bob").against("mail-server")).id }
                val free = store.transaction { store.insert(it, pending("greet", "Cleo")).id }

                val claimed =
                    store.transaction {
                        store.claim(
                            it,
                            setOf("greet"),
                            10,
                            terms(full = setOf("mail-server")),
                        )
                    }

                assertThat(claimed.map { it.id }).containsExactly(free)
            }

        @Test
        fun `claim - pattern paused by a limit of zero - skipped unless a longer pattern with a limit matches`() =
            runBlocking<Unit> {
                // Arrange
                store.transaction { store.resourceLimits!!.setLimit(it, ResourceLimit("customer:%", 0)) }
                store.transaction { store.resourceLimits!!.setLimit(it, ResourceLimit("customer:acme", 5)) }
                val acme = store.transaction { store.insert(it, pending("greet", "Ada").against("customer:acme")).id }
                store.transaction { store.insert(it, pending("greet", "Bob").against("customer:initech")).id }
                val paused = terms(paused = listOf("customer:%"), other = listOf("customer:acme"))

                val claimed = store.transaction { store.claim(it, setOf("greet"), 10, paused) }

                assertThat(claimed.map { it.id }).containsExactly(acme)
            }

        @Test
        fun `claimFromGroup - resource in the filter's full set - returns nothing`() =
            runBlocking<Unit> {
                // Arrange
                store.transaction { store.resourceLimits!!.setLimit(it, ResourceLimit("mail-server", 1)) }
                repeat(2) { i ->
                    store.transaction { tx ->
                        store.insert(tx, pending("greet", guest(i)).against("mail-server")).id
                    }
                }
                store.transaction { store.claimFromGroup(it, "w1", ClaimGroup(0, "greet", null), 1, t0) }
                val full = terms(full = setOf("mail-server"))

                val next = store.transaction { store.claimFromGroup(it, ClaimGroup(0, "greet", null), 1, full) }

                assertThat(next).isEmpty()
            }

        @Test
        fun `dueGroups - the group's only resource is full - the group is not listed`() =
            runBlocking<Unit> {
                // Arrange
                store.transaction { store.resourceLimits!!.setLimit(it, ResourceLimit("mail-server", 1)) }
                store.transaction {
                    store
                        .insert(
                            it,
                            pending("greet", "Ada").inLane("tenant-a").against("mail-server"),
                        ).id
                }
                store.transaction { store.claim(it, "w1", setOf("greet"), 1, t0) }
                store.transaction {
                    store
                        .insert(
                            it,
                            pending("greet", "Bob").inLane("tenant-a").against("mail-server"),
                        ).id
                }

                val groups =
                    store.transaction {
                        store.dueGroups(
                            it,
                            setOf("greet"),
                            terms(full = setOf("mail-server")),
                        )
                    }

                assertThat(groups).isEmpty()
            }

        @Test
        fun `runningPerResource - running and pending tasks - counts the running ones per resource`() =
            runBlocking<Unit> {
                // Arrange
                repeat(2) { store.transaction { store.insert(it, pending("greet", "m$it").against("mail-server")).id } }
                store.transaction { store.insert(it, pending("greet", "Ada").against("crm")).id }
                store.transaction { store.claim(it, "w1", setOf("greet"), 3, t0) }
                store.transaction { store.insert(it, pending("greet", "Bob").against("crm")).id }

                val running = store.transaction { store.runningPerResource(it, t0) }

                assertThat(running).containsExactlyInAnyOrderEntriesOf(mapOf("mail-server" to 2, "crm" to 1))
            }

        @Test
        fun `runningPerResource - two cancelled while running, one lock still held - counts the held one`() =
            runBlocking<Unit> {
                // Arrange: both cancelled while running, w1 keeps holding the first, the second's lock ran out
                val ids =
                    store.transaction { tx ->
                        listOf("Ada", "Bob").map { store.insert(tx, pending("greet", it).against("mail-server")).id }
                    }
                store.transaction { store.claim(it, "w1", setOf("greet"), 2, t0, lockedUntil = t1) }
                store.transaction { tx ->
                    ids.forEach { store.move(tx, it, setOf(TaskStatus.RUNNING), TaskStatus.CANCELLED, null, t0) }
                    store.holdCancelled(tx, ids.first(), "w1", t1.plusSeconds(60))
                }

                val running = store.transaction { store.runningPerResource(it, t1.plusSeconds(1)) }

                assertThat(running).containsExactlyEntriesOf(mapOf("mail-server" to 1))
            }

        private fun terms(
            full: Set<String> = emptySet(),
            paused: List<String> = emptyList(),
            other: List<String> = emptyList(),
        ) = ClaimTerms("w1", t0, t0.plusSeconds(60), ResourceFilter(full, paused, other))

        @Test
        fun `seedLimit - one name already set, one new - keeps the set value and adds the new name`() =
            runBlocking<Unit> {
                // Arrange
                val limits = store.resourceLimits!!
                store.transaction { limits.setLimit(it, ResourceLimit("mail-server", 2)) }

                // Act
                store.transaction { tx ->
                    limits.seedLimit(tx, ResourceLimit("mail-server", 9))
                    limits.seedLimit(tx, ResourceLimit("customer:%", 1))
                }

                // Assert
                assertThat(store.transaction { limits.limits(it) })
                    .containsExactly(ResourceLimit("customer:%", 1), ResourceLimit("mail-server", 2))
            }

        @Test
        fun `removeLimit - name with a row - returns true and the row is gone`() =
            runBlocking<Unit> {
                // Arrange
                val limits = store.resourceLimits!!
                store.transaction { limits.setLimit(it, ResourceLimit("mail-server", 2)) }

                val removed = store.transaction { limits.removeLimit(it, "mail-server") }

                // Assert
                assertThat(removed).isTrue()
                assertThat(store.transaction { limits.limits(it) }).isEmpty()
            }

        @Test
        fun `removeLimit - name without a row - returns false`() =
            runBlocking<Unit> {
                val limits = store.resourceLimits!!

                val removed = store.transaction { limits.removeLimit(it, "mail-server") }

                assertThat(removed).isFalse()
            }
    }

    @Nested
    inner class PromoteDue {
        @Test
        fun `promoteDue - scheduled task whose run time passed - becomes PENDING`() =
            runBlocking<Unit> {
                // Arrange
                val scheduled = pending("greet", "Ada", runAt = t0.plusSeconds(60), status = TaskStatus.SCHEDULED)
                val id = store.transaction { store.insert(it, scheduled).id }

                val changed = store.transaction { store.promoteDue(it, t0.plusSeconds(61)) }

                // Assert
                assertThat(changed).isEqualTo(1)
                assertThat(store.transaction { store.find(it, id) }!!.status).isEqualTo(TaskStatus.PENDING)
            }

        @Test
        fun `promoteDue - retry whose run time passed - becomes PENDING, one still ahead stays`() =
            runBlocking<Unit> {
                // Arrange
                val due =
                    store.transaction {
                        store
                            .insert(
                                it,
                                pending("greet", "Ada", runAt = t0, status = TaskStatus.RETRY_PENDING),
                            ).id
                    }
                val later =
                    store.transaction {
                        store.insert(it, pending("greet", "Bob", runAt = t1, status = TaskStatus.RETRY_PENDING)).id
                    }

                val changed = store.transaction { store.promoteDue(it, t0.plusSeconds(1)) }

                // Assert
                assertThat(changed).isEqualTo(1)
                assertThat(store.transaction { store.find(it, due) }!!.status).isEqualTo(TaskStatus.PENDING)
                assertThat(store.transaction { store.find(it, later) }!!.status).isEqualTo(TaskStatus.RETRY_PENDING)
            }
    }

    @Nested
    inner class ExpiredLocks {
        @Test
        fun `expiredLocks - lock deadline passed - returns the row with holder and deadline, still RUNNING`() =
            runBlocking<Unit> {
                // Arrange
                val id = store.transaction { store.insert(it, pending("greet", "Ada")).id }
                store.transaction { store.claim(it, "dead-worker", setOf("greet"), 1, t0, t0.plusSeconds(60)) }

                val expired = store.transaction { store.expiredLocks(it, setOf("greet"), t0.plusSeconds(61), 10) }

                // Assert
                val lock = expired.single()
                assertThat(lock.task.id).isEqualTo(id)
                assertThat(lock.task.attempt).isEqualTo(1)
                assertThat(lock.lockedBy).isEqualTo("dead-worker")
                assertThat(lock.lockedUntil).isEqualTo(t0.plusSeconds(60))
                assertThat(store.transaction { store.find(it, id) }!!.status).isEqualTo(TaskStatus.RUNNING)
            }

        @Test
        fun `expiredLocks - lock deadline still ahead - returns nothing`() =
            runBlocking<Unit> {
                // Arrange
                store.transaction { store.insert(it, pending("greet", "Ada")).id }
                store.transaction { store.claim(it, "w1", setOf("greet"), 1, t0, t0.plusSeconds(60)) }

                val expired = store.transaction { store.expiredLocks(it, setOf("greet"), t0.plusSeconds(59), 10) }

                assertThat(expired).isEmpty()
            }

        @Test
        fun `expiredLocks - expired lock of a type not asked for - returns nothing`() =
            runBlocking<Unit> {
                // Arrange
                store.transaction { store.insert(it, pending("invoice", "Ada")).id }
                store.transaction { store.claim(it, "dead-worker", setOf("invoice"), 1, t0, t0.plusSeconds(60)) }

                val expired = store.transaction { store.expiredLocks(it, setOf("greet"), t0.plusSeconds(61), 10) }

                assertThat(expired).isEmpty()
            }

        @Test
        fun `expiredLocks - three expired and limit 2 - returns the two whose deadline passed first`() =
            runBlocking<Unit> {
                // Arrange
                listOf("Ada", "Bob", "Cleo").forEach { name ->
                    store.transaction { store.insert(it, pending("greet", name)).id }
                }
                listOf(30L, 10L, 20L).forEach { seconds ->
                    store.transaction { store.claim(it, "dead-worker", setOf("greet"), 1, t0, t0.plusSeconds(seconds)) }
                }

                val expired = store.transaction { store.expiredLocks(it, setOf("greet"), t0.plusSeconds(61), 2) }

                // Assert
                assertThat(expired.map { it.lockedUntil }).containsExactly(t0.plusSeconds(10), t0.plusSeconds(20))
            }
    }

    @Nested
    inner class Heartbeat {
        @Test
        fun `heartbeat - running task with matching version - moves the lock deadline on and returns true`() =
            runBlocking<Unit> {
                // Arrange
                store.transaction { store.insert(it, pending("greet", "Ada")).id }
                val claimed = store.transaction { store.claim(it, "w1", setOf("greet"), 1, t0) }.single()

                val alive = store.transaction { store.heartbeat(it, claimed.id, claimed.version, t0.plusSeconds(65)) }

                // Assert
                assertThat(alive).isTrue()
                assertThat(store.transaction { store.find(it, claimed.id) }!!.lockedUntil).isEqualTo(t0.plusSeconds(65))
            }

        @Test
        fun `heartbeat - version moved on - returns false`() =
            runBlocking<Unit> {
                // Arrange
                store.transaction { store.insert(it, pending("greet", "Ada")).id }
                val claimed = store.transaction { store.claim(it, "w1", setOf("greet"), 1, t0) }.single()

                val alive = store.transaction { store.heartbeat(it, claimed.id, claimed.version + 1, t0) }

                assertThat(alive).isFalse()
            }
    }

    @Nested
    inner class CancelledWhileRunning {
        /** A task w1 claimed with a lock until [t1], then cancelled by an operator. */
        private suspend fun cancelledRunningTask(name: String = "Ada"): Long {
            val id = store.transaction { store.insert(it, pending("greet", name)).id }
            store.transaction { store.claim(it, "w1", setOf("greet"), 1, t0, lockedUntil = t1) }
            store.transaction { store.move(it, id, setOf(TaskStatus.RUNNING), TaskStatus.CANCELLED, null, t0) }
            return id
        }

        @Test
        fun `holdCancelled - the worker that ran it - moves the lock on`() =
            runBlocking<Unit> {
                val id = cancelledRunningTask()

                val held = store.transaction { store.holdCancelled(it, id, "w1", t1.plusSeconds(60)) }

                // Assert
                assertThat(held).isTrue()
                assertThat(store.transaction { store.find(it, id) }!!.lockedUntil).isEqualTo(t1.plusSeconds(60))
            }

        @Test
        fun `holdCancelled - another worker, or a task not cancelled - returns false, lock unchanged`() =
            runBlocking<Unit> {
                // Arrange
                val cancelled = cancelledRunningTask()
                val running = store.transaction { store.insert(it, pending("greet", "Bob")).id }
                store.transaction { store.claim(it, "w1", setOf("greet"), 1, t0, lockedUntil = t1) }

                // Act
                val held =
                    store.transaction { tx ->
                        listOf(
                            store.holdCancelled(tx, cancelled, "w2", t1.plusSeconds(60)),
                            store.holdCancelled(tx, running, "w1", t1.plusSeconds(60)),
                        )
                    }

                // Assert
                assertThat(held).containsExactly(false, false)
                val locks =
                    store.transaction { tx ->
                        listOf(cancelled, running).map { store.find(tx, it)!!.lockedUntil }
                    }
                assertThat(locks).containsExactly(t1, t1)
            }

        @Test
        fun `unlockCancelled - the worker that ran it - clears the lock, the task stays CANCELLED`() =
            runBlocking<Unit> {
                val id = cancelledRunningTask()

                val unlocked = store.transaction { store.unlockCancelled(it, id, "w1") }

                // Assert
                assertThat(unlocked).isTrue()
                val record = store.transaction { store.find(it, id) }!!
                assertThat(record.status).isEqualTo(TaskStatus.CANCELLED)
                assertThat(record.lockedBy).isNull()
                assertThat(record.lockedUntil).isNull()
            }

        @Test
        fun `unlockExpiredCancelled - one lock expired, one still held - clears the expired one only`() =
            runBlocking<Unit> {
                // Arrange
                val expired = cancelledRunningTask("Ada")
                val held = cancelledRunningTask("Bob")
                store.transaction { store.holdCancelled(it, held, "w1", t1.plusSeconds(60)) }

                val cleared = store.transaction { store.unlockExpiredCancelled(it, t1.plusSeconds(1)) }

                // Assert
                assertThat(cleared).isEqualTo(1)
                val owners = store.transaction { tx -> listOf(expired, held).map { store.find(tx, it)!!.lockedBy } }
                assertThat(owners).containsExactly(null, "w1")
            }
    }

    @Nested
    inner class Finish {
        @Test
        fun `finish - retry of a restarted task that had a result - result and finish time are cleared`() =
            runBlocking<Unit> {
                // Arrange: rejected with a result, restarted by an operator, claimed again
                val id = store.transaction { store.insert(it, pending("greet", "Ada")).id }
                val first = store.transaction { store.claim(it, "w1", setOf("greet"), 1, t0) }.single()
                val rejected = Outcome.Rejected("""{"sentences":0}""", "empty")
                store.transaction { store.finish(it, id, first.version, rejected, t0) }
                store.transaction { store.move(it, id, setOf(TaskStatus.REJECTED), TaskStatus.PENDING, null, t0) }
                val second = store.transaction { store.claim(it, "w1", setOf("greet"), 1, t0) }.single()

                store.transaction { store.finish(it, id, second.version, Outcome.Retry(t1, "mail server down"), t0) }

                // Assert
                val record = store.transaction { store.find(it, id) }!!
                assertThat(record.status).isEqualTo(TaskStatus.RETRY_PENDING)
                assertThat(record.result).isNull()
                assertThat(record.finishedAt).isNull()
            }

        @Test
        fun `finish - succeeded - SUCCEEDED without lock or error and a bumped version`() =
            runBlocking<Unit> {
                // Arrange
                store.transaction { store.insert(it, pending("greet", "Ada")).id }
                val claimed = store.transaction { store.claim(it, "w1", setOf("greet"), 1, t0) }.single()

                val applied =
                    store.transaction {
                        store.finish(
                            it,
                            claimed.id,
                            claimed.version,
                            Outcome.Succeeded(),
                            t1,
                        )
                    }

                // Assert
                assertThat(applied).isTrue()
                val record = store.transaction { store.find(it, claimed.id) }!!
                assertThat(record.status).isEqualTo(TaskStatus.SUCCEEDED)
                assertThat(record.lockedBy).isNull()
                assertThat(record.lockedUntil).isNull()
                assertThat(record.lastError).isNull()
                assertThat(record.version).isEqualTo(claimed.version + 1)
                assertThat(record.finishedAt).isEqualTo(t1)
            }

        @Test
        fun `finish - retry - RETRY_PENDING with the next run time and the error`() =
            runBlocking<Unit> {
                // Arrange
                store.transaction { store.insert(it, pending("greet", "Ada")).id }
                val claimed = store.transaction { store.claim(it, "w1", setOf("greet"), 1, t0) }.single()
                val outcome = Outcome.Retry(t0.plusSeconds(30), "downstream is down")

                val applied = store.transaction { store.finish(it, claimed.id, claimed.version, outcome, t1) }

                // Assert
                assertThat(applied).isTrue()
                val record = store.transaction { store.find(it, claimed.id) }!!
                assertThat(record.status).isEqualTo(TaskStatus.RETRY_PENDING)
                assertThat(record.runAt).isEqualTo(t0.plusSeconds(30))
                assertThat(record.lastError).isEqualTo("downstream is down")
                assertThat(record.finishedAt).isNull()
            }

        @Test
        fun `finish - failed - FAILED with the error and the run time untouched`() =
            runBlocking<Unit> {
                // Arrange
                store.transaction { store.insert(it, pending("greet", "Ada")).id }
                val claimed = store.transaction { store.claim(it, "w1", setOf("greet"), 1, t0) }.single()

                val failed = Outcome.Failed("gave up")
                val applied = store.transaction { store.finish(it, claimed.id, claimed.version, failed, t1) }

                // Assert
                assertThat(applied).isTrue()
                val record = store.transaction { store.find(it, claimed.id) }!!
                assertThat(record.status).isEqualTo(TaskStatus.FAILED)
                assertThat(record.runAt).isEqualTo(t0)
                assertThat(record.lastError).isEqualTo("gave up")
                assertThat(record.finishedAt).isEqualTo(t1)
            }

        @Test
        fun `finish - version moved on - returns false and leaves the row as it is`() =
            runBlocking<Unit> {
                // Arrange
                store.transaction { store.insert(it, pending("greet", "Ada")).id }
                val claimed = store.transaction { store.claim(it, "w1", setOf("greet"), 1, t0) }.single()

                val applied =
                    store.transaction {
                        store.finish(
                            it,
                            claimed.id,
                            claimed.version + 1,
                            Outcome.Succeeded(),
                            t1,
                        )
                    }

                // Assert
                assertThat(applied).isFalse()
                assertThat(store.transaction { store.find(it, claimed.id) }!!.status).isEqualTo(TaskStatus.RUNNING)
            }
    }

    @Nested
    inner class Retention {
        @Test
        fun `deleteFinished - succeeded task older than the cut-off - is deleted, younger and non-final ones stay`() =
            runBlocking<Unit> {
                // Arrange
                val old = finished("greet", "Ada", at = t1)
                val young = finished("greet", "Bob", at = t1.plusSeconds(3600))
                val failed = store.transaction { store.insert(it, pending("greet", "Cleo")).id }
                claimAndFail(failed)
                val pending = store.transaction { store.insert(it, pending("greet", "Dan")).id }

                val removed = store.transaction { store.deleteFinished(it, "greet", t1.plusSeconds(1), 100) }

                // Assert
                assertThat(removed).isEqualTo(1)
                assertThat(store.transaction { store.find(it, old) }).isNull()
                assertThat(store.transaction { store.find(it, young) }).isNotNull()
                assertThat(store.transaction { store.find(it, failed) }).isNotNull()
                assertThat(store.transaction { store.find(it, pending) }).isNotNull()
            }

        @Test
        fun `deleteFinished - cancelled by SQL without a finish time - expires by its creation time`() =
            runBlocking<Unit> {
                // Arrange: inserted CANCELLED as an SQL recipe might leave it, created at t0
                val cancelled = pending("greet", "Ada", status = TaskStatus.CANCELLED)
                val id = store.transaction { store.insert(it, cancelled).id }

                val removed = store.transaction { store.deleteFinished(it, "greet", t0.plusSeconds(1), 100) }

                // Assert
                assertThat(removed).isEqualTo(1)
                assertThat(store.transaction { store.find(it, id) }).isNull()
            }

        @Test
        fun `deleteFinished - cancelled and rejected task older than the cut-off - cancelled goes, rejected stays`() =
            runBlocking<Unit> {
                // Arrange
                val cancelled = store.transaction { store.insert(it, pending("greet", "Ada")).id }
                store.transaction {
                    store.move(
                        it,
                        cancelled,
                        setOf(TaskStatus.PENDING),
                        TaskStatus.CANCELLED,
                        null,
                        t0,
                    )
                }
                val rejected = store.transaction { store.insert(it, pending("greet", "Bob")).id }
                val claimed = store.transaction { store.claim(it, "w1", setOf("greet"), 1, t0) }.single()
                store.transaction { store.finish(it, rejected, claimed.version, Outcome.Rejected(null, "rude"), t0) }

                val removed = store.transaction { store.deleteFinished(it, "greet", t0.plusSeconds(1), 100) }

                // Assert
                assertThat(removed).isEqualTo(1)
                assertThat(store.transaction { store.find(it, cancelled) }).isNull()
                assertThat(store.transaction { store.find(it, rejected) }).isNotNull()
            }

        @Test
        fun `deleteFinished - another task type - is left alone`() =
            runBlocking<Unit> {
                val other = finished("other", "Ada", at = t1)

                val removed = store.transaction { store.deleteFinished(it, "greet", t1.plusSeconds(1), 100) }

                // Assert
                assertThat(removed).isZero()
                assertThat(store.transaction { store.find(it, other) }).isNotNull()
            }

        @Test
        fun `deleteFinished - limit below the expired count - removes only that many`() =
            runBlocking<Unit> {
                repeat(3) { finished("greet", guest(it), at = t1) }

                val removed = store.transaction { store.deleteFinished(it, "greet", t1.plusSeconds(1), 2) }

                assertThat(removed).isEqualTo(2)
            }

        @Test
        fun `archiveFinished - expired task - is moved to the archive table with its columns`() =
            runBlocking<Unit> {
                // Arrange
                val archive = createArchiveTable()
                val id = finished("greet", "Ada", at = t1)

                val moved = store.transaction { store.archiveFinished(it, "greet", t1.plusSeconds(1), 100, archive) }

                // Assert
                assertThat(moved).isEqualTo(1)
                assertThat(store.transaction { store.find(it, id) }).isNull()
                assertThat(archivedCount(archive)).isEqualTo(1)
            }

        private suspend fun finished(
            type: String,
            name: String,
            at: Instant,
        ): Long {
            val id = store.transaction { store.insert(it, pending(type, name)).id }
            val claimed = store.transaction { store.claim(it, "w1", setOf(type), 100, t0) }.single { it.id == id }
            store.transaction { store.finish(it, id, claimed.version, Outcome.Succeeded(), at) }
            return id
        }

        private suspend fun claimAndFail(id: Long) {
            val claimed = store.transaction { store.claim(it, "w1", setOf("greet"), 100, t0) }.single { it.id == id }
            store.transaction { store.finish(it, id, claimed.version, Outcome.Failed("broken"), t1) }
        }
    }

    @Nested
    inner class Results {
        @Test
        fun `finish - succeeded with a result and provenance - both are stored and read back`() =
            runBlocking<Unit> {
                // Arrange
                store.transaction { store.insert(it, pending("greet", "Ada")).id }
                val claimed = store.transaction { store.claim(it, "w1", setOf("greet"), 1, t0) }.single()
                val outcome = Outcome.Succeeded("""{"score": 7}""", mapOf("model" to "m1"))

                val applied = store.transaction { store.finish(it, claimed.id, claimed.version, outcome, t1) }

                // Assert
                assertThat(applied).isTrue()
                val record = store.transaction { store.find(it, claimed.id) }!!
                assertThat(record.result!!.normalised()).isEqualTo("""{"score":7}""")
                assertThat(record.provenance).containsExactlyEntriesOf(mapOf("model" to "m1"))
            }

        @Test
        fun `finish - rejected - REJECTED with result, reason and finish time`() =
            runBlocking<Unit> {
                // Arrange
                store.transaction { store.insert(it, pending("greet", "Ada")).id }
                val claimed = store.transaction { store.claim(it, "w1", setOf("greet"), 1, t0) }.single()
                val outcome = Outcome.Rejected("""{"score": 500}""", "out of range")

                store.transaction { store.finish(it, claimed.id, claimed.version, outcome, t1) }

                // Assert
                val record = store.transaction { store.find(it, claimed.id) }!!
                assertThat(record.status).isEqualTo(TaskStatus.REJECTED)
                assertThat(record.result!!.normalised()).isEqualTo("""{"score":500}""")
                assertThat(record.lastError).isEqualTo("out of range")
                assertThat(record.finishedAt).isEqualTo(t1)
            }

        @Test
        fun `insert - preview given - find reads it back`() =
            runBlocking<Unit> {
                val task = NewTask<Long>(null, "greet", json("{}"), t0, TaskStatus.PENDING, t0, preview = "Greet Ada")

                val id = store.transaction { store.insert(it, task).id }

                assertThat(store.transaction { store.find(it, id) }!!.preview).isEqualTo("Greet Ada")
            }
    }

    @Nested
    inner class Release {
        @Test
        fun `release - running task - PENDING without lock, attempts kept`() =
            runBlocking<Unit> {
                // Arrange
                store.transaction { store.insert(it, pending("greet", "Ada")).id }
                val claimed = store.transaction { store.claim(it, "w1", setOf("greet"), 1, t0) }.single()

                val released = store.transaction { store.release(it, claimed.id, claimed.version) }

                // Assert
                assertThat(released).isTrue()
                val record = store.transaction { store.find(it, claimed.id) }!!
                assertThat(record.status).isEqualTo(TaskStatus.PENDING)
                assertThat(record.lockedBy).isNull()
                assertThat(record.attempts).isEqualTo(1)
            }

        @Test
        fun `release - version moved on - returns false and the row stays as it is`() =
            runBlocking<Unit> {
                // Arrange
                store.transaction { store.insert(it, pending("greet", "Ada")).id }
                val claimed = store.transaction { store.claim(it, "w1", setOf("greet"), 1, t0) }.single()

                val released = store.transaction { store.release(it, claimed.id, claimed.version + 1) }

                // Assert
                assertThat(released).isFalse()
                assertThat(store.transaction { store.find(it, claimed.id) }!!.status).isEqualTo(TaskStatus.RUNNING)
            }
    }

    @Nested
    inner class Search {
        @Test
        fun `search - no filter - every task, newest first`() =
            runBlocking<Unit> {
                // Arrange
                val older = store.transaction { store.insert(it, task("greet", createdAt = t0)).id }
                val newer = store.transaction { store.insert(it, task("remind", createdAt = t1)).id }

                val found = store.transaction { store.search(it, TaskQuery()) }

                assertThat(found.map { it.id }).containsExactly(newer, older)
            }

        @Test
        fun `search - creator, type, status and correlation - only the task matching all four`() =
            runBlocking<Unit> {
                // Arrange
                val match =
                    store.transaction {
                        store.insert(it, task("greet", createdBy = "ticket-agent", correlationId = "ticket-4711")).id
                    }
                store.transaction {
                    store.insert(it, task("greet", createdBy = "billing-clerk", correlationId = "ticket-4711")).id
                    store.insert(it, task("remind", createdBy = "ticket-agent", correlationId = "ticket-4711")).id
                    store
                        .insert(
                            it,
                            task("greet", TaskStatus.SCHEDULED, "ticket-agent", correlationId = "ticket-4711"),
                        ).id
                    store.insert(it, task("greet", createdBy = "ticket-agent", correlationId = "ticket-0815")).id
                }
                val query =
                    TaskQuery(
                        createdBy = "ticket-agent",
                        types = setOf("greet"),
                        statuses = setOf(TaskStatus.PENDING, TaskStatus.AWAITING_APPROVAL),
                        correlationId = "ticket-4711",
                    )

                val found = store.transaction { store.search(it, query) }

                assertThat(found.map { it.id }).containsExactly(match)
            }

        @Test
        fun `search - more matches than the limit - the newest up to the limit`() =
            runBlocking<Unit> {
                // Arrange
                store.transaction { tx ->
                    (0L..4L).forEach { store.insert(tx, task("greet", createdAt = t0.plusSeconds(it))).id }
                }

                val found = store.transaction { store.search(it, TaskQuery(limit = 2)) }

                assertThat(found.map { it.createdAt }).containsExactly(t0.plusSeconds(4), t0.plusSeconds(3))
            }

        @Test
        fun `search - lane and parent - only the follow-up of that parent in that lane`() =
            runBlocking<Unit> {
                // Arrange
                val parent = store.transaction { store.insert(it, task("greet", lane = "october")).id }
                val followUp =
                    store.transaction { store.insert(it, task("remind", lane = "october", parentId = parent)).id }
                store.transaction { tx ->
                    store.insert(tx, task("remind", lane = "november", parentId = parent)).id
                    store.insert(tx, task("remind", lane = "october")).id
                }

                val found = store.transaction { store.search(it, TaskQuery(lane = "october", parentId = parent)) }

                assertThat(found.map { it.id }).containsExactly(followUp)
            }

        @Test
        fun `search - pages after the last task of the one before - every task once, in order, ties included`() =
            runBlocking<Unit> {
                // Arrange: three of five tasks share their creation time
                store.transaction { tx ->
                    listOf(
                        t0,
                        t1,
                        t1,
                        t1,
                        t1.plusSeconds(1),
                    ).forEach { store.insert(tx, task("greet", createdAt = it)).id }
                }
                val all = store.transaction { store.search(it, TaskQuery()) }
                val first = store.transaction { store.search(it, TaskQuery(limit = 2)) }
                val second = store.transaction { store.search(it, TaskQuery(limit = 2).after(first.last())) }

                val third = store.transaction { store.search(it, TaskQuery(limit = 2).after(second.last())) }

                // Assert
                assertThat((first + second + third).map { it.id }).containsExactlyElementsOf(all.map { it.id })
                assertThat(third).hasSize(1)
            }

        @Suppress("LongParameterList") // one parameter per column a search test filters on
        private fun task(
            type: String,
            status: TaskStatus = TaskStatus.PENDING,
            createdBy: String? = null,
            correlationId: String? = null,
            createdAt: Instant = t0,
            lane: String? = null,
            parentId: Long? = null,
        ) = NewTask(
            null,
            type,
            json("{}"),
            t0,
            status,
            createdAt,
            createdBy = createdBy,
            correlationId = correlationId,
            lane = lane,
            parentId = parentId,
        )
    }

    @Nested
    inner class Backlog {
        @Test
        fun `backlog - tasks in open and final statuses - counts the open ones per type and status`() =
            runBlocking<Unit> {
                // Arrange
                store.transaction { tx ->
                    store.insert(tx, pending("greet", "Ada")).id
                    store.insert(tx, pending("greet", "Bob")).id
                    store.insert(tx, pending("greet", "Cleo", status = TaskStatus.FAILED)).id
                    store.insert(tx, pending("greet", "Dan", status = TaskStatus.SUCCEEDED)).id
                    store.insert(tx, pending("remind", "Eve", status = TaskStatus.AWAITING_APPROVAL)).id
                    store.insert(tx, pending("archive", "Fay", status = TaskStatus.CANCELLED)).id
                }

                val backlog = store.transaction { store.backlog(it, t0) }

                // Assert
                assertThat(backlog.map { it.type }).containsExactly("greet", "remind")
                val greet = backlog.first { it.type == "greet" }
                assertThat(greet.count(TaskStatus.PENDING)).isEqualTo(2)
                assertThat(greet.count(TaskStatus.FAILED)).isEqualTo(1)
                assertThat(greet.count(TaskStatus.RUNNING)).isZero()
                assertThat(greet.counts.keys).doesNotContain(TaskStatus.SUCCEEDED, TaskStatus.CANCELLED)
                assertThat(backlog.first { it.type == "remind" }.count(TaskStatus.AWAITING_APPROVAL)).isEqualTo(1)
            }

        @Test
        fun `backlog - pending task and an overdue retry not yet promoted - oldestDue is the retry's run time`() =
            runBlocking<Unit> {
                // Arrange
                store.transaction { tx ->
                    store.insert(tx, pending("greet", "Ada", runAt = t0.plusSeconds(30))).id
                    store.insert(tx, pending("greet", "Bob", runAt = t0, status = TaskStatus.RETRY_PENDING)).id
                }

                val backlog = store.transaction { store.backlog(it, t1) }.single()

                assertThat(backlog.oldestDue).isEqualTo(t0)
            }

        @Test
        fun `backlog - scheduled task ahead and a running one - oldestDue is null`() =
            runBlocking<Unit> {
                // Arrange
                store.transaction { tx ->
                    store.insert(tx, pending("greet", "Ada")).id
                    store.insert(tx, pending("greet", "Bob", runAt = t1, status = TaskStatus.SCHEDULED)).id
                }
                store.transaction { store.claim(it, "w1", setOf("greet"), 1, t0) }

                val backlog = store.transaction { store.backlog(it, t0) }.single()

                // Assert
                assertThat(backlog.count(TaskStatus.RUNNING)).isEqualTo(1)
                assertThat(backlog.count(TaskStatus.SCHEDULED)).isEqualTo(1)
                assertThat(backlog.oldestDue).isNull()
            }
    }

    @Test
    fun `operation - transaction handle from another store - throws IllegalArgumentException`() {
        val foreign = object : StoreTransaction {}

        val attempt = ThrowingCallable { store.find(foreign, 1L) }

        assertThatThrownBy(attempt).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Nested
    inner class OperatorMoves {
        @Test
        fun `move - task in a source status - writes status, reason and finish time, returns the status left`() =
            runBlocking<Unit> {
                // Arrange
                val id = store.transaction { store.insert(it, pending("greet", "Ada", status = TaskStatus.FAILED)).id }

                val moved =
                    store.transaction {
                        store.move(
                            it,
                            id,
                            setOf(TaskStatus.FAILED),
                            TaskStatus.CANCELLED,
                            "duplicate",
                            t1,
                        )
                    }

                // Assert
                assertThat(moved).isEqualTo(TaskStatus.FAILED)
                val record = store.transaction { store.find(it, id) }!!
                assertThat(record.status).isEqualTo(TaskStatus.CANCELLED)
                assertThat(record.lastError).isEqualTo("duplicate")
                assertThat(record.finishedAt).isEqualTo(t1)
            }

        @Test
        fun `move - running task to an open status without a reason - clears the lock and keeps the last error`() =
            runBlocking<Unit> {
                // Arrange
                val id = store.transaction { store.insert(it, pending("greet", "Ada")).id }
                val claimed = store.transaction { store.claim(it, "w1", setOf("greet"), 1, t0) }.single()
                store.transaction { store.finish(it, id, claimed.version, Outcome.Retry(t0, "timeout"), t0) }
                store.transaction { store.promoteDue(it, t0) }
                val again = store.transaction { store.claim(it, "w1", setOf("greet"), 1, t0) }.single()

                store.transaction { store.move(it, id, setOf(TaskStatus.RUNNING), TaskStatus.PENDING, null, t1) }

                // Assert
                val record = store.transaction { store.find(it, id) }!!
                assertThat(record.status).isEqualTo(TaskStatus.PENDING)
                assertThat(record.lockedBy).isNull()
                assertThat(record.lockedUntil).isNull()
                assertThat(record.lastError).isEqualTo("timeout")
                assertThat(record.finishedAt).isNull()
                assertThat(record.version).isGreaterThan(again.version)
            }

        @Test
        fun `move - to PENDING with a run time ahead - the run time becomes now, a past one stays`() =
            runBlocking<Unit> {
                // Arrange
                val later = t1.plusSeconds(3600)
                val (ahead, past) =
                    store.transaction { tx ->
                        listOf(
                            store
                                .insert(
                                    tx,
                                    pending("greet", "Ada", runAt = later, status = TaskStatus.QUARANTINED),
                                ).id,
                            store.insert(tx, pending("greet", "Bob", status = TaskStatus.QUARANTINED)).id,
                        )
                    }

                // Act
                store.transaction { tx ->
                    listOf(ahead, past).forEach {
                        store.move(tx, it, setOf(TaskStatus.QUARANTINED), TaskStatus.PENDING, null, t1)
                    }
                }

                // Assert
                val runAt = store.transaction { tx -> listOf(ahead, past).map { store.find(tx, it)!!.runAt } }
                assertThat(runAt).containsExactly(t1, t0)
            }

        @Test
        fun `move - running task cancelled - CANCELLED with a finish time, the worker's lock kept`() =
            runBlocking<Unit> {
                // Arrange
                val id = store.transaction { store.insert(it, pending("greet", "Ada")).id }
                store.transaction { store.claim(it, "w1", setOf("greet"), 1, t0, lockedUntil = t1) }

                store.transaction { store.move(it, id, setOf(TaskStatus.RUNNING), TaskStatus.CANCELLED, null, t0) }

                // Assert
                val record = store.transaction { store.find(it, id) }!!
                assertThat(record.status).isEqualTo(TaskStatus.CANCELLED)
                assertThat(record.finishedAt).isEqualTo(t0)
                assertThat(record.lockedBy).isEqualTo("w1")
                assertThat(record.lockedUntil).isEqualTo(t1)
            }

        @Test
        fun `move - task in another status - returns null and leaves the row unchanged`() =
            runBlocking<Unit> {
                // Arrange
                val id = store.transaction { store.insert(it, pending("greet", "Ada")).id }

                val moved =
                    store.transaction {
                        store.move(
                            it,
                            id,
                            setOf(TaskStatus.FAILED),
                            TaskStatus.CANCELLED,
                            "duplicate",
                            t1,
                        )
                    }

                // Assert
                assertThat(moved).isNull()
                val record = store.transaction { store.find(it, id) }!!
                assertThat(record.status).isEqualTo(TaskStatus.PENDING)
                assertThat(record.version).isZero()
            }
    }

    @Nested
    inner class Approval {
        @Test
        fun `approve - task awaiting approval and due - becomes PENDING and names the actor`() =
            runBlocking<Unit> {
                // Arrange
                val id =
                    store.transaction {
                        store
                            .insert(
                                it,
                                pending("refund", "Ada", status = TaskStatus.AWAITING_APPROVAL),
                            ).id
                    }

                val approved = store.transaction { store.approve(it, id, "grace", t1) }

                // Assert
                assertThat(approved).isTrue()
                val record = store.transaction { store.find(it, id) }!!
                assertThat(record.status).isEqualTo(TaskStatus.PENDING)
                assertThat(record.approvedBy).isEqualTo("grace")
                assertThat(record.approvedAt).isEqualTo(t1)
            }

        @Test
        fun `approve - task awaiting approval with a later run time - becomes SCHEDULED`() =
            runBlocking<Unit> {
                // Arrange
                val later = pending("refund", "Ada", runAt = t1, status = TaskStatus.AWAITING_APPROVAL)
                val id = store.transaction { store.insert(it, later).id }

                store.transaction { store.approve(it, id, "grace", t0) }

                assertThat(store.transaction { store.find(it, id) }!!.status).isEqualTo(TaskStatus.SCHEDULED)
            }

        @Test
        fun `approve - task not awaiting approval - returns false and leaves it PENDING`() =
            runBlocking<Unit> {
                // Arrange
                val id = store.transaction { store.insert(it, pending("refund", "Ada")).id }

                val approved = store.transaction { store.approve(it, id, "grace", t1) }

                // Assert
                assertThat(approved).isFalse()
                assertThat(store.transaction { store.find(it, id) }!!.approvedBy).isNull()
            }

        @Test
        fun `approveLane - tasks of the type in the lane and elsewhere - approves only those in the lane`() =
            runBlocking<Unit> {
                // Arrange
                val waiting = TaskStatus.AWAITING_APPROVAL
                val (inLane, otherType, otherLane) =
                    store.transaction { tx ->
                        listOf(
                            store.insert(tx, pending("refund", "Ada", status = waiting).inLane("batch-7")).id,
                            store.insert(tx, pending("notify", "Bob", status = waiting).inLane("batch-7")).id,
                            store.insert(tx, pending("refund", "Cleo", status = waiting).inLane("batch-8")).id,
                        )
                    }

                val approved = store.transaction { store.approveLane(it, "batch-7", "refund", "grace", t1) }

                // Assert
                assertThat(approved).isEqualTo(1)
                val statuses =
                    store.transaction { tx ->
                        listOf(inLane, otherType, otherLane).map { store.find(tx, it)!!.status }
                    }
                assertThat(statuses).containsExactly(TaskStatus.PENDING, waiting, waiting)
            }
    }

    @Nested
    inner class Cycles {
        private val cycleStore get() = store.cycles!!

        private suspend fun startCycle(): Long =
            store.transaction { cycleStore.insertCycle(it, NewCycle(null, "invoice-run", "October", t0, "ops")) }

        @Test
        fun `insertCycle - new cycle - findCycle reads it back as ACTIVE`() =
            runBlocking<Unit> {
                val id = startCycle()

                // Assert
                val cycle = store.transaction { cycleStore.findCycle(it, id) }!!
                assertThat(cycle.status).isEqualTo(CycleStatus.ACTIVE)
                assertThat(cycle.description).isEqualTo("October")
                assertThat(cycle.createdBy).isEqualTo("ops")
                assertThat(cycle.finishedAt).isNull()
            }

        @Test
        fun `updateCycleStatus - status among sources, final target - writes status, finish time, returns old`() =
            runBlocking<Unit> {
                // Arrange
                val id = startCycle()

                val changed =
                    store.transaction {
                        cycleStore.updateCycleStatus(
                            it,
                            id,
                            setOf(CycleStatus.ACTIVE, CycleStatus.PAUSED),
                            CycleStatus.CANCELLED,
                            t1,
                        )
                    }

                // Assert
                assertThat(changed).isEqualTo(CycleStatus.ACTIVE)
                val cycle = store.transaction { cycleStore.findCycle(it, id) }!!
                assertThat(cycle.status).isEqualTo(CycleStatus.CANCELLED)
                assertThat(cycle.finishedAt).isEqualTo(t1)
            }

        @Test
        fun `updateCycleStatus - cycle not in the source status - returns null`() =
            runBlocking<Unit> {
                // Arrange
                val id = startCycle()

                val changed =
                    store.transaction {
                        cycleStore.updateCycleStatus(
                            it,
                            id,
                            setOf(CycleStatus.PAUSED),
                            CycleStatus.ACTIVE,
                            t1,
                        )
                    }

                assertThat(changed).isNull()
            }

        @Test
        fun `quarantineLane and releaseLane - waiting, running and foreign tasks - move only the waiting ones back`() =
            runBlocking<Unit> {
                // Arrange
                val lane = CycleLane.of(startCycle())
                val (waiting, running, quarantinedElsewhere) =
                    store.transaction { tx ->
                        listOf(
                            store.insert(tx, pending("invoice", "Ada").inLane(lane)).id,
                            store.insert(tx, pending("invoice", "Bob").inLane(lane, priority = 1)).id,
                            store
                                .insert(
                                    tx,
                                    pending("invoice", "Cleo", status = TaskStatus.QUARANTINED).inLane(lane),
                                ).id,
                        )
                    }
                store.transaction { store.claim(it, "w1", setOf("invoice"), 1, t0) }
                val quarantined =
                    store.transaction { cycleStore.quarantineLane(it, lane, CycleStore.PAUSED_REASON, t0) }

                val released = store.transaction { cycleStore.releaseLane(it, lane, CycleStore.PAUSED_REASON, t1) }

                // Assert
                assertThat(quarantined).isEqualTo(1)
                assertThat(released).isEqualTo(1)
                val statuses =
                    store.transaction { tx ->
                        listOf(waiting, running, quarantinedElsewhere).map { store.find(tx, it)!!.status }
                    }
                assertThat(statuses).containsExactly(TaskStatus.PENDING, TaskStatus.RUNNING, TaskStatus.QUARANTINED)
            }

        @Test
        fun `releaseLane - one task due, one with a run time ahead - PENDING and SCHEDULED, run times kept`() =
            runBlocking<Unit> {
                // Arrange
                val lane = CycleLane.of(startCycle())
                val later = t1.plusSeconds(3600)
                val (due, ahead) =
                    store.transaction { tx ->
                        listOf(
                            store.insert(tx, pending("invoice", "Ada").inLane(lane)).id,
                            store
                                .insert(
                                    tx,
                                    pending("invoice", "Bob", runAt = later, TaskStatus.SCHEDULED).inLane(lane),
                                ).id,
                        )
                    }
                store.transaction { cycleStore.quarantineLane(it, lane, CycleStore.PAUSED_REASON, t0) }

                store.transaction { cycleStore.releaseLane(it, lane, CycleStore.PAUSED_REASON, t1) }

                // Assert
                val tasks = store.transaction { tx -> listOf(due, ahead).map { store.find(tx, it)!! } }
                assertThat(tasks.map { it.status }).containsExactly(TaskStatus.PENDING, TaskStatus.SCHEDULED)
                assertThat(tasks.map { it.runAt }).containsExactly(t0, later)
            }

        @Test
        fun `cancelLane - open and finished tasks in the lane - cancels the open ones and counts them by status`() =
            runBlocking<Unit> {
                // Arrange
                val lane = CycleLane.of(startCycle())
                store.transaction { tx ->
                    store.insert(tx, pending("invoice", "Ada").inLane(lane)).id
                    store.insert(tx, pending("invoice", "Bob", status = TaskStatus.FAILED).inLane(lane)).id
                    store.insert(tx, pending("invoice", "Cleo", status = TaskStatus.SUCCEEDED).inLane(lane)).id
                }

                val cancelled = store.transaction { cycleStore.cancelLane(it, lane, t1) }

                // Assert
                assertThat(cancelled).isEqualTo(2)
                val counts = store.transaction { cycleStore.countLane(it, lane) }
                assertThat(counts).containsExactlyInAnyOrderEntriesOf(
                    mapOf(TaskStatus.CANCELLED to 2, TaskStatus.SUCCEEDED to 1),
                )
            }

        @Test
        fun `finishCycles - one cycle with only final tasks, one with an open task - finishes the first only`() =
            runBlocking<Unit> {
                // Arrange
                val done = startCycle()
                val open = startCycle()
                store.transaction { tx ->
                    store
                        .insert(
                            tx,
                            pending("invoice", "Ada", status = TaskStatus.SUCCEEDED).inLane(CycleLane.of(done)),
                        ).id
                    store.insert(tx, pending("invoice", "Bob").inLane(CycleLane.of(open))).id
                }

                val finished = store.transaction { cycleStore.finishCycles(it, t1) }

                // Assert
                assertThat(finished).isEqualTo(1)
                val statuses =
                    store.transaction { tx ->
                        listOf(done, open).map { cycleStore.findCycle(tx, it)!!.status }
                    }
                assertThat(statuses).containsExactly(CycleStatus.FINISHED, CycleStatus.ACTIVE)
            }

        @Test
        fun `deleteFinishedCycles - finished cycles with and without tasks left - deletes only the empty one`() =
            runBlocking<Unit> {
                // Arrange
                val empty = startCycle()
                val withTask = startCycle()
                store.transaction { tx ->
                    store
                        .insert(
                            tx,
                            pending("invoice", "Ada", status = TaskStatus.SUCCEEDED).inLane(CycleLane.of(withTask)),
                        ).id
                    cycleStore.finishCycles(tx, t0)
                }

                val deleted = store.transaction { cycleStore.deleteFinishedCycles(it, "invoice-run", t1, 10) }

                // Assert
                assertThat(deleted).isEqualTo(1)
                assertThat(store.transaction { cycleStore.findCycle(it, empty) }).isNull()
                assertThat(store.transaction { cycleStore.findCycle(it, withTask) }).isNotNull()
            }
    }

    private fun NewTask<Long>.inLane(
        lane: String?,
        priority: Int = 0,
    ) = NewTask(
        id,
        type,
        payload,
        runAt,
        status,
        createdAt,
        idempotencyKey,
        createdBy,
        payloadVersion,
        lane,
        priority,
    )

    private fun NewTask<Long>.against(resource: String) =
        NewTask(
            id,
            type,
            payload,
            runAt,
            status,
            createdAt,
            idempotencyKey,
            createdBy,
            payloadVersion,
            lane,
            priority,
            resource,
        )

    protected fun pending(
        type: String,
        name: String,
        runAt: Instant = t0,
        status: TaskStatus = TaskStatus.PENDING,
    ): NewTask<Long> = NewTask(null, type, json("""{"name":"$name"}"""), runAt, status, createdAt = t0)

    protected fun json(document: String): Map<String, Any?> = mapOf("payload" to document)

    /** jsonb normalises whitespace; the in-memory store keeps the text. Both forms are the same document. */
    private fun String.normalised(): String = replace(": ", ":")

    companion object {
        @JvmStatic
        fun everyOperationOnEveryStatus(): List<Arguments> =
            Operation.entries.flatMap { operation -> TaskStatus.entries.map { Arguments.of(operation, it) } }
    }
}
