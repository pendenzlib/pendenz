package io.github.d4niella.pendenz.testing

import io.github.d4niella.pendenz.ExponentialBackoff
import io.github.d4niella.pendenz.Pendenz
import io.github.d4niella.pendenz.TaskStatus
import io.github.d4niella.pendenz.store.TaskRecord
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import java.time.Duration

/** Waits until the task has the given status and returns the row as it was then. */
fun <ID : Any> awaitStatus(
    pendenz: Pendenz<ID>,
    id: ID,
    status: TaskStatus,
): TaskRecord<ID> {
    lateinit var record: TaskRecord<ID>
    await atMost Duration.ofSeconds(10) untilAsserted {
        record = runBlocking { pendenz.find(id)!! }
        assertThat(record.status).isEqualTo(status)
    }
    return record
}

/** A retry policy that retries within milliseconds and without randomness, for tests. */
fun fastBackoff(maxAttempts: Int): ExponentialBackoff =
    ExponentialBackoff(maxAttempts = maxAttempts, initialDelay = Duration.ofMillis(20), jitter = 0.0)
