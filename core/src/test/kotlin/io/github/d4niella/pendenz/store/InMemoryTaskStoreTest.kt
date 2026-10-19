package io.github.d4niella.pendenz.store

import io.github.d4niella.pendenz.schema.IdType

class InMemoryTaskStoreTest : TaskStoreContractTest() {
    override fun newStore(): TaskStore<Long> = InMemoryTaskStore(IdType.BIGINT)
}
