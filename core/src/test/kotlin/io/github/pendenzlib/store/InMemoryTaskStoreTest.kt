package io.github.pendenzlib.store

import io.github.pendenzlib.TaskStatus
import io.github.pendenzlib.schema.IdType
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class InMemoryTaskStoreTest : TaskStoreContractTest() {
    private lateinit var store: InMemoryTaskStore<Long>

    override fun newStore(): TaskStore<Long> = InMemoryTaskStore(IdType.BIGINT).also { store = it }

    override fun createArchiveTable(): String = "task_archive"

    override fun archivedCount(archiveTable: String): Long = store.archived().size.toLong()

    @Test
    fun `insert - caller changes its payload map afterwards - the stored task keeps what was inserted`() =
        runBlocking<Unit> {
            // Arrange
            val store = InMemoryTaskStore(IdType.BIGINT)
            val now = Instant.parse("2026-10-25T09:00:00Z")
            val payload = mutableMapOf<String, Any?>("payload" to """{"name":"Ada"}""")
            val id =
                store.transaction {
                    store
                        .insert(
                            it,
                            NewTask(null, "greet", payload, now, TaskStatus.PENDING, now),
                        ).id
                }

            payload["payload"] = """{"name":"Mallory"}"""

            assertThat(store.transaction { store.find(it, id) }!!.payload["payload"]).isEqualTo("""{"name":"Ada"}""")
        }
}
