package io.github.d4niella.pendenz.store

import io.github.d4niella.pendenz.schema.IdType

class InMemoryTaskStoreTest : TaskStoreContractTest() {
    private lateinit var store: InMemoryTaskStore<Long>

    override fun newStore(): TaskStore<Long> = InMemoryTaskStore(IdType.BIGINT).also { store = it }

    override fun createArchiveTable(): String = "task_archive"

    override fun archivedCount(archiveTable: String): Long = store.archived().size.toLong()
}
