package kumahon.desktop

import chimahon.srs.PeerSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** When kumahon syncs with kumapie: at start, [WAIT_MS] after the last answer (each answer restarts the wait), on Sync now, and on close. */
class SyncControl(private val scope: CoroutineScope, private val sync: PeerSync, private val waitMs: Long = WAIT_MS, private val afterSync: suspend () -> Unit = {}) {
    companion object { const val WAIT_MS = 60_000L }

    private var job: Job? = null

    /** One burst of answers = one sync. */
    fun later() {
        job?.cancel()
        job = scope.launch { delay(waitMs); doSync() }
    }

    /** Syncs now (nothing happens when not paired) and drops a pending timer; answers whether it worked. */
    suspend fun now(): Boolean {
        job?.cancel()
        return doSync()
    }

    // the timer's own coroutine runs this, so it must never cancel [job]
    private suspend fun doSync(): Boolean {
        val ok = withContext(Dispatchers.IO) { sync.sync() }
        afterSync()
        return ok
    }
}
