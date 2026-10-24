package io.github.d4niella.pendenz.spring.boot

import io.github.d4niella.pendenz.Pendenz
import io.github.d4niella.pendenz.WorkerOptions
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.springframework.context.SmartLifecycle

/**
 * Runs one worker from the context's start until its stop, in a
 * coroutine scope of its own on `Dispatchers.Default`. See ADR 0023.
 *
 * It stops in [PHASE], before the web server's graceful shutdown, and
 * asynchronously: the worker lets running handlers finish within its
 * shutdown timeout and releases what is left, then Spring is called back.
 */
public class PendenzWorkerLifecycle internal constructor(
    private val pendenz: Pendenz<*>,
    private val options: WorkerOptions,
    private val companions: List<WorkerCompanion>,
) : SmartLifecycle {
    @Volatile
    private var scope: CoroutineScope? = null

    override fun start() {
        val started = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("pendenz-worker"))
        pendenz.startWorker(started, options)
        companions.forEach { it.start(started) }
        scope = started
    }

    override fun stop(callback: Runnable) {
        val stopping = scope ?: return callback.run()
        scope = null
        val job = stopping.coroutineContext.job
        job.invokeOnCompletion { callback.run() }
        job.cancel()
    }

    override fun stop() {
        val stopping = scope ?: return
        scope = null
        runBlocking { stopping.coroutineContext.job.cancelAndJoin() }
    }

    override fun isRunning(): Boolean = scope != null

    override fun getPhase(): Int = PHASE

    public companion object {
        /** Stops before the web server's graceful shutdown (`DEFAULT_PHASE - 1024`), long before the data source. */
        public const val PHASE: Int = SmartLifecycle.DEFAULT_PHASE - 512
    }
}

/** Something that runs in the worker's scope and ends with it, such as the backlog refresh of ADR 0022. */
internal fun interface WorkerCompanion {
    fun start(scope: CoroutineScope)
}
