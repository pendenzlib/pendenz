package io.github.d4niella.pendenz.store

import io.github.d4niella.pendenz.ResourceLimit
import io.github.d4niella.pendenz.TaskQuery
import io.github.d4niella.pendenz.TaskStatus
import io.github.d4niella.pendenz.testing.claim
import io.github.d4niella.pendenz.testing.claimFromGroup
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

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

    @Nested
    inner class Insert {
        @Test
        fun `insert - no id given - returns a generated id and find reads the row back`() =
            runBlocking<Unit> {
                val id = store.transaction { store.insert(it, pending("greet", "Ada")) }

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

                val id = store.transaction { store.insert(it, byAgent) }

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
        fun `insert - key already used for the type - inserts nothing and returns the existing id`() =
            runBlocking<Unit> {
                // Arrange
                val first = pending("greet", "Ada").withKey("ticket-4711")
                val existing = store.transaction { store.insert(it, first) }

                val repeat = pending("greet", "Bob").withKey("ticket-4711")
                val returned = store.transaction { store.insert(it, repeat) }

                // Assert
                assertThat(returned).isEqualTo(existing)
                val record = store.transaction { store.find(it, existing) }!!
                assertThat(record.payload["payload"].toString()).contains("Ada")
                assertThat(record.idempotencyKey).isEqualTo("ticket-4711")
            }

        @Test
        fun `insert - same key for another type - inserts a second task`() =
            runBlocking<Unit> {
                // Arrange
                val mail = store.transaction { store.insert(it, pending("mail", "Ada").withKey("ticket-4711")) }

                val credit = store.transaction { store.insert(it, pending("credit", "Ada").withKey("ticket-4711")) }

                assertThat(credit).isNotEqualTo(mail)
            }

        @Test
        fun `insert - no key twice - inserts two tasks`() =
            runBlocking<Unit> {
                val first = store.transaction { store.insert(it, pending("greet", "Ada")) }

                val second = store.transaction { store.insert(it, pending("greet", "Ada")) }

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
                val later = store.transaction { store.insert(it, pending("greet", "Bob", runAt = t0.plusSeconds(1))) }
                val earlier = store.transaction { store.insert(it, pending("greet", "Ada", runAt = t0)) }

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
                val id = store.transaction { store.insert(it, pending("other", "Ada")) }

                val claimed = store.transaction { store.claim(it, "w1", setOf("greet"), 10, t0) }

                // Assert
                assertThat(claimed).isEmpty()
                assertThat(store.transaction { store.find(it, id) }!!.status).isEqualTo(TaskStatus.PENDING)
            }

        @Test
        fun `claim - pending task not yet due - is left alone`() =
            runBlocking<Unit> {
                store.transaction { store.insert(it, pending("greet", "Ada", runAt = t0.plusSeconds(60))) }

                val claimed = store.transaction { store.claim(it, "w1", setOf("greet"), 10, t0) }

                assertThat(claimed).isEmpty()
            }

        @Test
        fun `claim - task due 90 seconds before the claim - returns its due time and the claim time`() =
            runBlocking<Unit> {
                store.transaction { store.insert(it, pending("greet", "Ada", runAt = t0)) }

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
                store.transaction { store.insert(it, pending("greet", "Ada").inLane("cycle-1")) }
                store.transaction { store.insert(it, pending("greet", "Bob").inLane("cycle-1")) }
                store.transaction { store.insert(it, pending("greet", "Cleo")) }
                store.transaction { store.insert(it, pending("greet", "Dan").inLane("cycle-2", priority = 5)) }
                store.transaction { store.insert(it, pending("other", "Eve")) }
                store.transaction { store.insert(it, pending("greet", "Fay", runAt = t0.plusSeconds(60))) }

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
                val single = store.transaction { store.insert(it, pending("greet", "Ada")) }
                val first = store.transaction { store.insert(it, pending("greet", "Bob").inLane("cycle-1")) }
                store.transaction { store.insert(it, pending("greet", "Cleo").inLane("cycle-1")) }

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
                val single = store.transaction { store.insert(it, pending("greet", "Ada")) }
                store.transaction { store.insert(it, pending("greet", "Bob").inLane("cycle-1")) }

                val claimed = store.transaction { store.claimFromGroup(it, "w1", ClaimGroup(0, "greet", null), 5, t0) }

                assertThat(claimed.map { it.id }).containsExactly(single)
            }

        @Test
        fun `claim - a later task with higher priority - comes first even in arrival order`() =
            runBlocking<Unit> {
                // Arrange
                store.transaction { store.insert(it, pending("greet", "Ada")) }
                val urgent = store.transaction { store.insert(it, pending("greet", "Bob").inLane(null, priority = 9)) }

                val claimed = store.transaction { store.claim(it, "w1", setOf("greet"), 1, t0) }

                // Assert
                assertThat(claimed.map { it.id }).containsExactly(urgent)
                assertThat(store.transaction { store.find(it, urgent) }!!.priority).isEqualTo(9)
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
    }

    @Nested
    inner class ResourceLimits {
        @Test
        fun `claim - three tasks of a resource limited to one - claims one, the others stay PENDING`() =
            runBlocking<Unit> {
                // Arrange
                repeat(3) { store.transaction { store.insert(it, pending("greet", "n$it").against("mail-server")) } }
                store.transaction { store.resourceLimits!!.setLimit(it, ResourceLimit("mail-server", 1)) }

                val claimed = store.transaction { store.claim(it, "w1", setOf("greet"), 10, t0) }

                // Assert
                assertThat(claimed).hasSize(1)
                assertThat(claimed.single().resource).isEqualTo("mail-server")
            }

        @Test
        fun `claim - full resource and a task without resource - the unlimited task is claimed`() =
            runBlocking<Unit> {
                // Arrange
                store.transaction { store.insert(it, pending("greet", "Ada").against("mail-server")) }
                store.transaction { store.claim(it, "w1", setOf("greet"), 1, t0) }
                store.transaction { store.resourceLimits!!.setLimit(it, ResourceLimit("mail-server", 1)) }
                store.transaction { store.insert(it, pending("greet", "Bob").against("mail-server")) }
                val free = store.transaction { store.insert(it, pending("greet", "Cleo")) }

                val claimed = store.transaction { store.claim(it, "w1", setOf("greet"), 10, t0) }

                assertThat(claimed.map { it.id }).containsExactly(free)
            }

        @Test
        fun `claimFromGroup - full resource - returns nothing`() =
            runBlocking<Unit> {
                // Arrange
                repeat(2) { store.transaction { store.insert(it, pending("greet", "n$it").against("mail-server")) } }
                store.transaction { store.resourceLimits!!.setLimit(it, ResourceLimit("mail-server", 1)) }
                store.transaction { store.claimFromGroup(it, "w1", ClaimGroup(0, "greet", null), 1, t0) }

                val next = store.transaction { store.claimFromGroup(it, "w1", ClaimGroup(0, "greet", null), 1, t0) }

                assertThat(next).isEmpty()
            }

        @Test
        fun `limits - set, seed and remove - seed never overwrites and remove reports whether a row went`() =
            runBlocking<Unit> {
                val limits = store.resourceLimits!!

                // Act
                store.transaction { limits.setLimit(it, ResourceLimit("mail-server", 2)) }
                store.transaction { limits.seedLimit(it, ResourceLimit("mail-server", 9)) }
                store.transaction { limits.seedLimit(it, ResourceLimit("customer:%", 1)) }

                // Assert
                assertThat(store.transaction { limits.limits(it) })
                    .containsExactly(ResourceLimit("customer:%", 1), ResourceLimit("mail-server", 2))
                assertThat(store.transaction { limits.removeLimit(it, "mail-server") }).isTrue()
                assertThat(store.transaction { limits.removeLimit(it, "mail-server") }).isFalse()
            }

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
    }

    @Nested
    inner class PromoteDue {
        @Test
        fun `promoteDue - scheduled task whose run time passed - becomes PENDING`() =
            runBlocking<Unit> {
                // Arrange
                val scheduled = pending("greet", "Ada", runAt = t0.plusSeconds(60), status = TaskStatus.SCHEDULED)
                val id = store.transaction { store.insert(it, scheduled) }

                val changed = store.transaction { store.promoteDue(it, t0.plusSeconds(61)) }

                // Assert
                assertThat(changed).isEqualTo(1)
                assertThat(store.transaction { store.find(it, id) }!!.status).isEqualTo(TaskStatus.PENDING)
            }
    }

    @Nested
    inner class ExpiredLocks {
        @Test
        fun `expiredLocks - lock deadline passed - returns the row with holder and deadline, still RUNNING`() =
            runBlocking<Unit> {
                // Arrange
                val id = store.transaction { store.insert(it, pending("greet", "Ada")) }
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
                store.transaction { store.insert(it, pending("greet", "Ada")) }
                store.transaction { store.claim(it, "w1", setOf("greet"), 1, t0, t0.plusSeconds(60)) }

                val expired = store.transaction { store.expiredLocks(it, setOf("greet"), t0.plusSeconds(59), 10) }

                assertThat(expired).isEmpty()
            }

        @Test
        fun `expiredLocks - expired lock of a type not asked for - returns nothing`() =
            runBlocking<Unit> {
                // Arrange
                store.transaction { store.insert(it, pending("invoice", "Ada")) }
                store.transaction { store.claim(it, "dead-worker", setOf("invoice"), 1, t0, t0.plusSeconds(60)) }

                val expired = store.transaction { store.expiredLocks(it, setOf("greet"), t0.plusSeconds(61), 10) }

                assertThat(expired).isEmpty()
            }

        @Test
        fun `expiredLocks - three expired and limit 2 - returns the two whose deadline passed first`() =
            runBlocking<Unit> {
                // Arrange
                listOf("Ada", "Bob", "Cleo").forEach { name ->
                    store.transaction { store.insert(it, pending("greet", name)) }
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
                store.transaction { store.insert(it, pending("greet", "Ada")) }
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
                store.transaction { store.insert(it, pending("greet", "Ada")) }
                val claimed = store.transaction { store.claim(it, "w1", setOf("greet"), 1, t0) }.single()

                val alive = store.transaction { store.heartbeat(it, claimed.id, claimed.version + 1, t0) }

                assertThat(alive).isFalse()
            }
    }

    @Nested
    inner class Finish {
        @Test
        fun `finish - succeeded - SUCCEEDED without lock or error and a bumped version`() =
            runBlocking<Unit> {
                // Arrange
                store.transaction { store.insert(it, pending("greet", "Ada")) }
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
                store.transaction { store.insert(it, pending("greet", "Ada")) }
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
                store.transaction { store.insert(it, pending("greet", "Ada")) }
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
                store.transaction { store.insert(it, pending("greet", "Ada")) }
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
                val failed = store.transaction { store.insert(it, pending("greet", "Cleo")) }
                claimAndFail(failed)
                val pending = store.transaction { store.insert(it, pending("greet", "Dan")) }

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
                val id = store.transaction { store.insert(it, cancelled) }

                val removed = store.transaction { store.deleteFinished(it, "greet", t0.plusSeconds(1), 100) }

                // Assert
                assertThat(removed).isEqualTo(1)
                assertThat(store.transaction { store.find(it, id) }).isNull()
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
                repeat(3) { finished("greet", "n$it", at = t1) }

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
            val id = store.transaction { store.insert(it, pending(type, name)) }
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
                store.transaction { store.insert(it, pending("greet", "Ada")) }
                val claimed = store.transaction { store.claim(it, "w1", setOf("greet"), 1, t0) }.single()
                val outcome = Outcome.Succeeded("""{"score": 7}""", mapOf("model" to "m1"))

                val applied = store.transaction { store.finish(it, claimed.id, claimed.version, outcome, t1) }

                // Assert
                assertThat(applied).isTrue()
                val record = store.transaction { store.find(it, claimed.id) }!!
                assertThat(record.result).contains("7")
                assertThat(record.provenance).containsExactlyEntriesOf(mapOf("model" to "m1"))
            }

        @Test
        fun `finish - rejected - REJECTED with result, reason and finish time`() =
            runBlocking<Unit> {
                // Arrange
                store.transaction { store.insert(it, pending("greet", "Ada")) }
                val claimed = store.transaction { store.claim(it, "w1", setOf("greet"), 1, t0) }.single()
                val outcome = Outcome.Rejected("""{"score": 500}""", "out of range")

                store.transaction { store.finish(it, claimed.id, claimed.version, outcome, t1) }

                // Assert
                val record = store.transaction { store.find(it, claimed.id) }!!
                assertThat(record.status).isEqualTo(TaskStatus.REJECTED)
                assertThat(record.result).contains("500")
                assertThat(record.lastError).isEqualTo("out of range")
                assertThat(record.finishedAt).isEqualTo(t1)
            }

        @Test
        fun `insert - preview given - find reads it back`() =
            runBlocking<Unit> {
                val task = NewTask<Long>(null, "greet", json("{}"), t0, TaskStatus.PENDING, t0, preview = "Greet Ada")

                val id = store.transaction { store.insert(it, task) }

                assertThat(store.transaction { store.find(it, id) }!!.preview).isEqualTo("Greet Ada")
            }
    }

    @Nested
    inner class Release {
        @Test
        fun `release - running task - PENDING without lock, attempts kept`() =
            runBlocking<Unit> {
                // Arrange
                store.transaction { store.insert(it, pending("greet", "Ada")) }
                val claimed = store.transaction { store.claim(it, "w1", setOf("greet"), 1, t0) }.single()

                val released = store.transaction { store.release(it, claimed.id, claimed.version) }

                // Assert
                assertThat(released).isTrue()
                val record = store.transaction { store.find(it, claimed.id) }!!
                assertThat(record.status).isEqualTo(TaskStatus.PENDING)
                assertThat(record.lockedBy).isNull()
                assertThat(record.attempts).isEqualTo(1)
            }
    }

    @Nested
    inner class Search {
        @Test
        fun `search - no filter - every task, newest first`() =
            runBlocking<Unit> {
                // Arrange
                val older = store.transaction { store.insert(it, task("greet", createdAt = t0)) }
                val newer = store.transaction { store.insert(it, task("remind", createdAt = t1)) }

                val found = store.transaction { store.search(it, TaskQuery()) }

                assertThat(found.map { it.id }).containsExactly(newer, older)
            }

        @Test
        fun `search - creator, type, status and correlation - only the task matching all four`() =
            runBlocking<Unit> {
                // Arrange
                val match =
                    store.transaction {
                        store.insert(it, task("greet", createdBy = "ticket-agent", correlationId = "ticket-4711"))
                    }
                store.transaction {
                    store.insert(it, task("greet", createdBy = "billing-clerk", correlationId = "ticket-4711"))
                    store.insert(it, task("remind", createdBy = "ticket-agent", correlationId = "ticket-4711"))
                    store.insert(it, task("greet", TaskStatus.SCHEDULED, "ticket-agent", correlationId = "ticket-4711"))
                    store.insert(it, task("greet", createdBy = "ticket-agent", correlationId = "ticket-0815"))
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
                    (0L..4L).forEach { store.insert(tx, task("greet", createdAt = t0.plusSeconds(it))) }
                }

                val found = store.transaction { store.search(it, TaskQuery(limit = 2)) }

                assertThat(found.map { it.createdAt }).containsExactly(t0.plusSeconds(4), t0.plusSeconds(3))
            }

        private fun task(
            type: String,
            status: TaskStatus = TaskStatus.PENDING,
            createdBy: String? = null,
            correlationId: String? = null,
            createdAt: Instant = t0,
        ) = NewTask<Long>(
            null,
            type,
            json("{}"),
            t0,
            status,
            createdAt,
            createdBy = createdBy,
            correlationId = correlationId,
        )
    }

    @Nested
    inner class Backlog {
        @Test
        fun `backlog - tasks in open and final statuses - counts the open ones per type and status`() =
            runBlocking<Unit> {
                // Arrange
                store.transaction { tx ->
                    store.insert(tx, pending("greet", "Ada"))
                    store.insert(tx, pending("greet", "Bob"))
                    store.insert(tx, pending("greet", "Cleo", status = TaskStatus.FAILED))
                    store.insert(tx, pending("greet", "Dan", status = TaskStatus.SUCCEEDED))
                    store.insert(tx, pending("remind", "Eve", status = TaskStatus.AWAITING_APPROVAL))
                    store.insert(tx, pending("archive", "Fay", status = TaskStatus.CANCELLED))
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
                    store.insert(tx, pending("greet", "Ada", runAt = t0.plusSeconds(30)))
                    store.insert(tx, pending("greet", "Bob", runAt = t0, status = TaskStatus.RETRY_PENDING))
                }

                val backlog = store.transaction { store.backlog(it, t1) }.single()

                assertThat(backlog.oldestDue).isEqualTo(t0)
            }

        @Test
        fun `backlog - scheduled task ahead and a running one - oldestDue is null`() =
            runBlocking<Unit> {
                // Arrange
                store.transaction { tx ->
                    store.insert(tx, pending("greet", "Ada"))
                    store.insert(tx, pending("greet", "Bob", runAt = t1, status = TaskStatus.SCHEDULED))
                }
                store.transaction { store.claim(it, "w1", setOf("greet"), 1, t0) }

                val backlog = store.transaction { store.backlog(it, t0) }.single()

                // Assert
                assertThat(backlog.count(TaskStatus.RUNNING)).isEqualTo(1)
                assertThat(backlog.count(TaskStatus.SCHEDULED)).isEqualTo(1)
                assertThat(backlog.oldestDue).isNull()
            }
    }

    protected fun pending(
        type: String,
        name: String,
        runAt: Instant = t0,
        status: TaskStatus = TaskStatus.PENDING,
    ): NewTask<Long> = NewTask(null, type, json("""{"name":"$name"}"""), runAt, status, createdAt = t0)

    protected fun json(document: String): Map<String, Any?> = mapOf("payload" to document)

    /** jsonb normalises whitespace; the in-memory store keeps the text. Both forms are the same document. */
    private fun String.normalised(): String = replace(": ", ":")
}
