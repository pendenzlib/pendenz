package io.github.d4niella.pendenz.store

import io.github.d4niella.pendenz.TaskStatus
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
                assertThat(record.payload).contains("Ada")
                assertThat(record.attempts).isZero()
                assertThat(record.version).isZero()
                assertThat(record.createdAt).isEqualTo(t0)
            }

        @Test
        fun `insert - creator given - find and claim both carry it`() =
            runBlocking<Unit> {
                // Arrange
                val byAgent = NewTask<Long>(null, "greet", "{}", t0, TaskStatus.PENDING, t0, createdBy = "triage-agent")

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
                assertThat(record.payload).contains("Ada")
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
        fun `claim - two pending tasks and limit 1 - claims the one due first as RUNNING with attempt 1`() =
            runBlocking<Unit> {
                // Arrange
                val later = store.transaction { store.insert(it, pending("greet", "Bob", runAt = t0.plusSeconds(1))) }
                val earlier = store.transaction { store.insert(it, pending("greet", "Ada", runAt = t0)) }

                val claimed = store.transaction { store.claim(it, "w1", setOf("greet"), 1, t0.plusSeconds(5)) }

                // Assert
                assertThat(claimed.map { it.id }).containsExactly(earlier)
                assertThat(claimed.single().attempt).isEqualTo(1)
                val record = store.transaction { store.find(it, earlier) }!!
                assertThat(record.status).isEqualTo(TaskStatus.RUNNING)
                assertThat(record.lockedBy).isEqualTo("w1")
                assertThat(record.lockedAt).isEqualTo(t0.plusSeconds(5))
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
    }

    @Nested
    inner class PromoteDue {
        @Test
        fun `promoteDue - scheduled task whose run time passed - becomes PENDING`() =
            runBlocking<Unit> {
                // Arrange
                val scheduled = pending("greet", "Ada", runAt = t0.plusSeconds(60), status = TaskStatus.SCHEDULED)
                val id = store.transaction { store.insert(it, scheduled) }

                val changed = store.transaction { store.promoteDue(it, t0.plusSeconds(61), t0.minusSeconds(60)) }

                // Assert
                assertThat(changed).isEqualTo(1)
                assertThat(store.transaction { store.find(it, id) }!!.status).isEqualTo(TaskStatus.PENDING)
            }

        @Test
        fun `promoteDue - running task with a stale lock - is released as PENDING without a lock`() =
            runBlocking<Unit> {
                // Arrange
                val id = store.transaction { store.insert(it, pending("greet", "Ada")) }
                store.transaction { store.claim(it, "dead-worker", setOf("greet"), 1, t0) }

                val changed = store.transaction { store.promoteDue(it, t0.plusSeconds(120), t0.plusSeconds(60)) }

                // Assert
                assertThat(changed).isEqualTo(1)
                val record = store.transaction { store.find(it, id) }!!
                assertThat(record.status).isEqualTo(TaskStatus.PENDING)
                assertThat(record.lockedBy).isNull()
                assertThat(record.lockedAt).isNull()
            }

        @Test
        fun `promoteDue - running task with a fresh lock - is left RUNNING`() =
            runBlocking<Unit> {
                // Arrange
                val id = store.transaction { store.insert(it, pending("greet", "Ada")) }
                store.transaction { store.claim(it, "w1", setOf("greet"), 1, t0) }

                val changed = store.transaction { store.promoteDue(it, t0.plusSeconds(10), t0.minusSeconds(60)) }

                // Assert
                assertThat(changed).isZero()
                assertThat(store.transaction { store.find(it, id) }!!.status).isEqualTo(TaskStatus.RUNNING)
            }
    }

    @Nested
    inner class Heartbeat {
        @Test
        fun `heartbeat - running task with matching version - moves lockedAt and returns true`() =
            runBlocking<Unit> {
                // Arrange
                store.transaction { store.insert(it, pending("greet", "Ada")) }
                val claimed = store.transaction { store.claim(it, "w1", setOf("greet"), 1, t0) }.single()

                val alive = store.transaction { store.heartbeat(it, claimed.id, claimed.version, t0.plusSeconds(5)) }

                // Assert
                assertThat(alive).isTrue()
                assertThat(store.transaction { store.find(it, claimed.id) }!!.lockedAt).isEqualTo(t0.plusSeconds(5))
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

                val applied = store.transaction { store.finish(it, claimed.id, claimed.version, Outcome.Succeeded, t1) }

                // Assert
                assertThat(applied).isTrue()
                val record = store.transaction { store.find(it, claimed.id) }!!
                assertThat(record.status).isEqualTo(TaskStatus.SUCCEEDED)
                assertThat(record.lockedBy).isNull()
                assertThat(record.lockedAt).isNull()
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
                            Outcome.Succeeded,
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
            store.transaction { store.finish(it, id, claimed.version, Outcome.Succeeded, at) }
            return id
        }

        private suspend fun claimAndFail(id: Long) {
            val claimed = store.transaction { store.claim(it, "w1", setOf("greet"), 100, t0) }.single { it.id == id }
            store.transaction { store.finish(it, id, claimed.version, Outcome.Failed("broken"), t1) }
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

    protected fun pending(
        type: String,
        name: String,
        runAt: Instant = t0,
        status: TaskStatus = TaskStatus.PENDING,
    ): NewTask<Long> = NewTask(null, type, """{"name":"$name"}""", runAt, status, createdAt = t0)
}
