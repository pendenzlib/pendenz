package io.github.d4niella.pendenz.micrometer

import io.github.d4niella.pendenz.Approval
import io.github.d4niella.pendenz.ExponentialBackoff
import io.github.d4niella.pendenz.NoRetry
import io.github.d4niella.pendenz.Pendenz
import io.github.d4niella.pendenz.TaskResult
import io.github.d4niella.pendenz.TaskStatus
import io.github.d4niella.pendenz.TaskType
import io.github.d4niella.pendenz.Verdict
import io.github.d4niella.pendenz.testing.MutableClock
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.TimeUnit

/** The meters of ADR 0022, fed by the in-memory mode on a clock the test moves. */
class PendenzMetricsTest {
    @Serializable
    data class Greeting(
        val name: String,
    )

    @Serializable
    data class Score(
        val value: Int,
    )

    private val clock = MutableClock()
    private val registry = SimpleMeterRegistry()
    private val metrics = PendenzMetrics<Long>(registry, Tags.of("queue", "greetings"), clock)
    private val greet = TaskType.json<Greeting>("greet")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterEach
    fun stopScope() = scope.cancel()

    @Nested
    inner class Execution {
        @Test
        fun `execution timer - handler succeeds after 2 seconds - records a succeeded run of 2 seconds`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { register(greet) { _, _ -> clock.advance(Duration.ofSeconds(2)) } }

                // Act
                pendenz.enqueue(greet, Greeting("Ada"))
                pendenz.runPending()

                // Assert
                val timer = execution("succeeded", "none")
                assertThat(timer.count()).isEqualTo(1)
                assertThat(timer.totalTime(TimeUnit.SECONDS)).isEqualTo(2.0)
            }

        @Test
        fun `execution timer - handler throws with attempts left - records a retry with the exception and duration`() =
            runBlocking<Unit> {
                // Arrange
                val backoff = ExponentialBackoff(maxAttempts = 2, initialDelay = Duration.ofMinutes(1), jitter = 0.0)
                val flaky = TaskType.json<Greeting>("greet", retryPolicy = backoff)
                val pendenz =
                    pendenz {
                        register(flaky) { _, _ ->
                            clock.advance(Duration.ofSeconds(3))
                            error("mail server down")
                        }
                    }

                // Act
                pendenz.enqueue(flaky, Greeting("Bob"))
                pendenz.runPending()

                // Assert
                val timer = execution("retry", "IllegalStateException")
                assertThat(timer.count()).isEqualTo(1)
                assertThat(timer.totalTime(TimeUnit.SECONDS)).isEqualTo(3.0)
            }

        @Test
        fun `execution timer - attempts exhausted - records a failed run with the exception`() =
            runBlocking<Unit> {
                // Arrange
                val once = TaskType.json<Greeting>("greet", retryPolicy = NoRetry)
                val pendenz = pendenz { register(once) { _, _ -> throw IllegalArgumentException("no such mailbox") } }

                // Act
                pendenz.enqueue(once, Greeting("Cleo"))
                pendenz.runPending()

                assertThat(execution("failed", "IllegalArgumentException").count()).isEqualTo(1)
            }

        @Test
        fun `execution timer - validator refuses the result - records a rejected run without an exception`() =
            runBlocking<Unit> {
                // Arrange
                val inRange =
                    TaskResult.json<Greeting, Score> { _, score ->
                        if (score.value in 0..100) Verdict.accept() else Verdict.reject("out of range")
                    }
                val pendenz = pendenz { register(greet, inRange) { _, _ -> Score(400) } }

                // Act
                pendenz.enqueue(greet, Greeting("Dan"))
                pendenz.runPending()

                assertThat(execution("rejected", "none").count()).isEqualTo(1)
            }
    }

    @Nested
    inner class Wait {
        @Test
        fun `wait timer - scheduled task claimed 90 seconds after its run time - records 90 seconds`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { register(greet) { _, _ -> } }
                pendenz.enqueue(greet, Greeting("Eve"), runAt = clock.now.plus(Duration.ofMinutes(1)))
                clock.advance(Duration.ofSeconds(150))

                pendenz.runPending()

                // Assert
                val timer = registry.find(PendenzMetrics.WAIT).tags("type", "greet").timer()!!
                assertThat(timer.count()).isEqualTo(1)
                assertThat(timer.totalTime(TimeUnit.SECONDS)).isEqualTo(90.0)
            }
    }

    @Nested
    inner class Counters {
        @Test
        fun `enqueued counter - two tasks and one idempotent repeat - counts 3 calls`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { register(greet) { _, _ -> } }

                // Act
                pendenz.enqueue(greet, Greeting("Ada"), idempotencyKey = "ada")
                pendenz.enqueue(greet, Greeting("Ada"), idempotencyKey = "ada")
                pendenz.enqueue(greet, Greeting("Bob"))

                assertThat(
                    registry
                        .find(PendenzMetrics.ENQUEUED)
                        .tags("type", "greet")
                        .counter()!!
                        .count(),
                ).isEqualTo(3.0)
            }

        @Test
        fun `approvals counter - two held, one approved, one declined - counts each decision once per task`() =
            runBlocking<Unit> {
                // Arrange
                val refund = TaskType.json<Greeting>("refund", approval = Approval.ALWAYS)
                val pendenz = pendenz { register(refund) { _, _ -> } }
                val first = pendenz.enqueue(refund, Greeting("Ada"), createdBy = "ada")
                val second = pendenz.enqueue(refund, Greeting("Bob"), createdBy = "ada")

                // Act
                pendenz.approve(first, actor = "bob")
                pendenz.decline(second, actor = "bob", reason = "duplicate")

                // Assert
                listOf("requested" to 2.0, "approved" to 1.0, "declined" to 1.0).forEach { (decision, count) ->
                    val counter = registry.find(PendenzMetrics.APPROVALS).tags("decision", decision).counter()
                    assertThat(counter?.count()).describedAs(decision).isEqualTo(count)
                }
            }

        @Test
        fun `common tags - queue tag given - every meter carries it`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { register(greet) { _, _ -> } }

                // Act
                pendenz.enqueue(greet, Greeting("Ada"))
                pendenz.runPending()

                // Assert
                assertThat(registry.meters).isNotEmpty()
                registry.meters.forEach { meter ->
                    assertThat(meter.id.getTag("queue")).describedAs(meter.id.name).isEqualTo("greetings")
                }
            }
    }

    @Nested
    inner class Backlog {
        @Test
        fun `backlog gauge - two pending and one held for approval - reports the count per type and status`() =
            runBlocking<Unit> {
                // Arrange
                val refund = TaskType.json<Greeting>("refund", approval = Approval.ALWAYS)
                val pendenz =
                    pendenz {
                        register(greet) { _, _ -> }
                        register(refund) { _, _ -> }
                    }
                pendenz.enqueue(greet, Greeting("Ada"))
                pendenz.enqueue(greet, Greeting("Bob"))
                pendenz.enqueue(refund, Greeting("Cleo"))

                metrics.refreshBacklog(pendenz)

                // Assert
                assertThat(backlog("greet", TaskStatus.PENDING)).isEqualTo(2.0)
                assertThat(backlog("greet", TaskStatus.RUNNING)).isZero()
                assertThat(backlog("refund", TaskStatus.AWAITING_APPROVAL)).isEqualTo(1.0)
            }

        @Test
        fun `backlog gauge - type drained since the last refresh - reports 0 instead of the last value`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { register(greet) { _, _ -> } }
                pendenz.enqueue(greet, Greeting("Ada"))
                metrics.refreshBacklog(pendenz)
                pendenz.runPending()

                metrics.refreshBacklog(pendenz)

                assertThat(backlog("greet", TaskStatus.PENDING)).isZero()
            }

        @Test
        fun `oldest gauge - task due 5 minutes ago - reports 5 minutes and keeps growing with the clock`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { register(greet) { _, _ -> } }
                pendenz.enqueue(greet, Greeting("Ada"))
                clock.advance(Duration.ofMinutes(5))

                metrics.refreshBacklog(pendenz)

                // Assert
                val oldest = registry.find(PendenzMetrics.BACKLOG_OLDEST).tags("type", "greet").timeGauge()!!
                assertThat(oldest.value(TimeUnit.MINUTES)).isEqualTo(5.0)
                clock.advance(Duration.ofMinutes(1))
                assertThat(oldest.value(TimeUnit.MINUTES)).isEqualTo(6.0)
            }

        @Test
        fun `watchBacklog - task enqueued after the watch started - the gauge reaches 1`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { register(greet) { _, _ -> } }
                metrics.watchBacklog(pendenz, scope, Duration.ofMillis(20))

                pendenz.enqueue(greet, Greeting("Ada"))

                await atMost Duration.ofSeconds(5) untilAsserted {
                    assertThat(backlog("greet", TaskStatus.PENDING)).isEqualTo(1.0)
                }
            }
    }

    private fun execution(
        outcome: String,
        exception: String,
    ) = registry
        .find(PendenzMetrics.EXECUTION)
        .tags("type", "greet", "outcome", outcome, "exception", exception)
        .timer()!!

    private fun backlog(
        type: String,
        status: TaskStatus,
    ): Double? =
        registry
            .find(PendenzMetrics.BACKLOG)
            .tags("type", type, "status", status.name)
            .gauge()
            ?.value()

    private fun pendenz(configure: Pendenz.Builder<Long>.() -> Unit): Pendenz<Long> =
        Pendenz.create {
            inMemory()
            clock(clock)
            listener(metrics)
            configure()
        }
}
