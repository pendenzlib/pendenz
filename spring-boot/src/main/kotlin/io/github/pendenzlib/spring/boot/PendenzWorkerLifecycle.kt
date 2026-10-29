package io.github.pendenzlib.spring.boot

import io.github.pendenzlib.Pendenz
import io.github.pendenzlib.WorkerOptions
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
 * It stops in [PendenzAutoConfiguration.WORKER_PHASE], before the web
 * server's graceful shutdown, and
 * asynchronously: the worker lets running handlers finish within its
 * shutdown timeout and releases what is left, then Spring is called back.
 */
internal class PendenzWorkerLifecycle(
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

    override fun getPhase(): Int = PendenzAutoConfiguration.WORKER_PHASE
}

/** Something that runs in the worker's scope and ends with it, such as the backlog refresh of ADR 0022. */
internal fun interface WorkerCompanion {
    fun start(scope: CoroutineScope)
}
