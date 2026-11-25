package eu.kanade.tachiyomi.data.releases

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Debug builds only: starts one "Check now" run from adb (see src/debug/AndroidManifest.xml). */
class ReleasesDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ReleasesWorker.runNow(context)
    }
}
