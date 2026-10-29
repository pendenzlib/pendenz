package io.github.pendenzlib.testing

import io.github.pendenzlib.ExponentialBackoff
import io.github.pendenzlib.Pendenz
import io.github.pendenzlib.TaskRecord
import io.github.pendenzlib.TaskStatus
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
