package io.github.d4niella.pendenz.testing

import io.github.d4niella.pendenz.WorkerOptions
import io.github.d4niella.pendenz.store.ClaimGroup
import io.github.d4niella.pendenz.store.ClaimTerms
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
    lockedUntil: Instant = now.plus(WorkerOptions.DEFAULT_LOCK_TIMEOUT),
): List<ClaimedTask<ID>> = claim(tx, types, limit, ClaimTerms(workerId, now, lockedUntil))

/** Claims from one group as a worker with the default lock timeout would. */
fun <ID : Any> TaskStore<ID>.claimFromGroup(
    tx: StoreTransaction,
    workerId: String,
    group: ClaimGroup,
    limit: Int,
    now: Instant,
): List<ClaimedTask<ID>> =
    claimFromGroup(tx, group, limit, ClaimTerms(workerId, now, now.plus(WorkerOptions.DEFAULT_LOCK_TIMEOUT)))

/** The due groups as a worker would see them without resource limits. */
fun <ID : Any> TaskStore<ID>.dueGroups(
    tx: StoreTransaction,
    types: Collection<String>,
    now: Instant,
): List<ClaimGroup> = dueGroups(tx, types, ClaimTerms("test", now, now))
