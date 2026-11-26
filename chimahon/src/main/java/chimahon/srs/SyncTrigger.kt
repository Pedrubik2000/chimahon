package chimahon.srs

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * When kumahon syncs with the hub ([PeerSync]): right after a rating or a mining ([request], coalesced over [SyncGate.COALESCE_MS], one
 * run at a time), every 15 minutes while the app is in the foreground, and on start/resume when the last attempt is older than that.
 * Only when paired; a failure is one log line (tag KumahonSync). [Cards.sync] clears the page colours after every run, so a sync that
 * brought kumapie's events recolours the page.
 */
object SyncTrigger {
    private const val TAG = "KumahonSync"
    private val gate = SyncGate()
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "kumahon-sync").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var foreground = false

    /** A reason to sync ([why] is for the log). Cheap and safe from any thread; nothing when not paired. */
    fun request(context: Context, why: String) {
        val app = context.applicationContext
        if (!Cards.peer(app).paired()) return
        Log.i(TAG, "requested: $why")
        if (gate.request()) arm(app)
    }

    private fun arm(app: Context) { worker.schedule({ run(app) }, SyncGate.COALESCE_MS, TimeUnit.MILLISECONDS) }

    private fun run(app: Context) {
        if (!gate.start()) return
        try {
            Log.i(TAG, if (Cards.sync(app)) "synced" else "sync failed (see settings)")
        } catch (e: Exception) {
            Log.i(TAG, "sync failed: $e")
        } finally {
            if (gate.done()) arm(app)
        }
    }

    /** The app came to the foreground (App.onStart). */
    fun onForeground(context: Context) {
        val app = context.applicationContext
        foreground = true
        val last = runCatching { Cards.peer(app).lastSyncTime() }.getOrDefault(0L)
        if (SyncGate.dueOnStart(last, System.currentTimeMillis())) request(app, "app start")
        main.removeCallbacksAndMessages(null)
        tick(app)
    }

    /** The app left the foreground (App.onStop). */
    fun onBackground() {
        foreground = false
        main.removeCallbacksAndMessages(null)
    }

    private fun tick(app: Context) {
        main.postDelayed({
            if (foreground) { request(app, "every 15 minutes"); tick(app) }
        }, SyncGate.INTERVAL_MS)
    }
}
