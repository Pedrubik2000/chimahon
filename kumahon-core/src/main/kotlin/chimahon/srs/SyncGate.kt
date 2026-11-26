package chimahon.srs

/**
 * When the sync with the hub runs, without the clock or the threads (those are [SyncGate]'s caller's): a burst of requests inside the
 * coalescing window is ONE run, at most one run is in flight, and a request that comes while one runs asks for one more after it.
 */
class SyncGate {
    private enum class S { IDLE, ARMED, RUNNING, RUNNING_AGAIN }
    private var s = S.IDLE

    /** A reason to sync arrived: true = arm the timer ([COALESCE_MS]); false = a timer is armed already, or a run will repeat. */
    @Synchronized fun request(): Boolean = when (s) {
        S.IDLE -> { s = S.ARMED; true }
        S.ARMED, S.RUNNING_AGAIN -> false
        S.RUNNING -> { s = S.RUNNING_AGAIN; false }
    }

    /** The timer fired: true = run now. */
    @Synchronized fun start(): Boolean = if (s == S.ARMED) { s = S.RUNNING; true } else false

    /** The run ended: true = requests came meanwhile, arm the timer again. */
    @Synchronized fun done(): Boolean = when (s) {
        S.RUNNING_AGAIN -> { s = S.ARMED; true }
        else -> { s = S.IDLE; false }
    }

    companion object {
        const val COALESCE_MS = 5_000L
        const val INTERVAL_MS = 15 * 60_000L

        /** On app start or resume: sync when the last attempt is more than [INTERVAL_MS] ago (or never). */
        fun dueOnStart(lastMs: Long, nowMs: Long): Boolean = lastMs == 0L || nowMs - lastMs > INTERVAL_MS
    }
}
