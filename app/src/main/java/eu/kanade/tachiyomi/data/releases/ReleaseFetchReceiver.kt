package eu.kanade.tachiyomi.data.releases

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat

/** The "Fetch" button of "Vol. N is on <source>": hands over to [ReleaseFetchWorker]. */
class ReleaseFetchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val mediaId = intent.getLongExtra(EXTRA_MEDIA, -1)
        val vol = intent.getIntExtra(EXTRA_VOL, -1)
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION, 0)
        if (mediaId < 0 || vol < 0) return
        // cancel first: a second tap finds nothing; the work (expedited, unique per title+volume) outlives this receiver
        NotificationManagerCompat.from(context).cancel(notificationId)
        ReleaseFetchWorker.enqueue(context.applicationContext, mediaId, vol, notificationId)
    }

    companion object {
        private const val EXTRA_MEDIA = "mediaId"
        private const val EXTRA_VOL = "vol"
        private const val EXTRA_NOTIFICATION = "notificationId"

        fun pendingIntent(context: Context, mediaId: Long, vol: Int, notificationId: Int): PendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId, // one request code per notification: each keeps its own extras
            Intent(context, ReleaseFetchReceiver::class.java).putExtra(EXTRA_MEDIA, mediaId).putExtra(EXTRA_VOL, vol)
                .putExtra(EXTRA_NOTIFICATION, notificationId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
