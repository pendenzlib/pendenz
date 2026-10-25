package io.github.d4niella.pendenz.testing

import io.github.d4niella.pendenz.WorkerOptions
import io.github.d4niella.pendenz.store.ClaimGroup
import io.github.d4niella.pendenz.store.ClaimedTask
import io.github.d4niella.pendenz.store.StoreTransaction
import io.github.d4niella.pendenz.store.TaskStore
import java.time.Instant

/** Claims as a worker with the default lock timeout would, for tests that do not look at the lock deadline. */
fun <ID : Any> TaskStore<ID>.claim(
    tx: StoreTransaction,
    workerId: String,
    types: Collection<String>,
    limit: Int,
    now: Instant,
): List<ClaimedTask<ID>> = claim(tx, workerId, types, limit, now, now.plus(WorkerOptions.DEFAULT_LOCK_TIMEOUT))

/** Claims from one group as a worker with the default lock timeout would. */
fun <ID : Any> TaskStore<ID>.claimFromGroup(
    tx: StoreTransaction,
    workerId: String,
    group: ClaimGroup,
    limit: Int,
    now: Instant,
): List<ClaimedTask<ID>> = claimFromGroup(tx, workerId, group, limit, now, now.plus(WorkerOptions.DEFAULT_LOCK_TIMEOUT))
