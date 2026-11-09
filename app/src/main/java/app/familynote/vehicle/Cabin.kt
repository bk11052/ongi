package app.familynote.vehicle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * App-wide serializer for everything that changes the cabin (ambient light, climate).
 *
 * - [run] executes a change to completion on the app scope, one at a time, even if the
 *   screen that asked for it is gone.
 * - [locked] lets short steps (one ambient fade step) take the same lock inline, so a fade
 *   can never interleave with a restore.
 * - [awaitIdle] lets the vehicle connection wait for pending changes before it is released.
 */
class Cabin(private val appScope: CoroutineScope) {
    private val lock = Mutex()
    private val jobs = mutableSetOf<Job>()

    fun run(block: suspend () -> Unit): Job {
        val job = appScope.launch {
            withContext(NonCancellable) { lock.withLock { block() } }
        }
        synchronized(jobs) { jobs += job }
        job.invokeOnCompletion { synchronized(jobs) { jobs -= job } }
        return job
    }

    suspend fun <T> locked(block: suspend () -> T): T = lock.withLock { block() }

    suspend fun awaitIdle() {
        while (true) {
            val pending = synchronized(jobs) { jobs.toList() }
            if (pending.isEmpty()) return
            pending.joinAll()
        }
    }
}
