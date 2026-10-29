package io.github.pendenzlib

import kotlinx.serialization.json.Json

/**
 * A handler that produces a result (ADR 0020). Registered together with a
 * [TaskResult] that says how to store and validate it.
 */
public fun interface ResultHandler<ID : Any, P : Any, R : Any> {
    public suspend fun handle(
        context: TaskContext<ID>,
        payload: P,
    ): R
}

/** What the worker does with a result after the handler returned it: accept, or refuse with a reason. */
public sealed class Verdict {
    public object Accept : Verdict()

    public class Reject(
        public val reason: String,
    ) : Verdict() {
        init {
            require(reason.isNotBlank()) { "a rejection needs a reason" }
        }
    }

    public companion object {
        @JvmStatic
        public fun accept(): Verdict = Accept

        @JvmStatic
        public fun reject(reason: String): Verdict = Reject(reason)
    }
}

/** Validates a result against the payload it was produced for. */
public fun interface ResultValidator<P : Any, R : Any> {
    public fun validate(
        payload: P,
        result: R,
    ): Verdict
}

/**
 * How a result is stored in the `result` column and whether it is
 * accepted. A rejected result moves the task to `REJECTED`, where a
 * person decides; the retry policy is not consulted.
 *
 * The [codec] must produce JSON: the column is `jsonb` (ADR 0020), and a
 * value PostgreSQL cannot parse makes recording the outcome fail, so the
 * task stays `RUNNING` until its lock times out and then runs again, to
 * the same end. The kotlinx and Jackson codecs produce JSON.
 */
public class TaskResult<P : Any, R : Any>
    @JvmOverloads
    constructor(
        public val codec: PayloadCodec<R>,
        public val validator: ResultValidator<P, R> = ResultValidator { _, _ -> Verdict.Accept },
    ) {
        public companion object {
            /** A `@Serializable` result stored as JSON, validated by [validator]. */
            public inline fun <P : Any, reified R : Any> json(
                json: Json = Json,
                validator: ResultValidator<P, R> = ResultValidator { _, _ -> Verdict.Accept },
            ): TaskResult<P, R> = TaskResult(KotlinxJsonCodec.of<R>(json), validator)
        }
    }
