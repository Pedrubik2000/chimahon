package eu.kanade.tachiyomi.data.releases

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.anilist.AniListLibrary
import eu.kanade.tachiyomi.data.coverage.Coverage
import eu.kanade.tachiyomi.data.coverage.CoverageMatch
import eu.kanade.tachiyomi.data.coverage.SourceCoverage
import eu.kanade.tachiyomi.data.download.RealDebridQueue
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.notificationBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * The work behind the "Fetch" button ([ReleaseFetchReceiver] only enqueues it): waits for the sources to load (a cold start from
 * the notification has none yet), then the title page's own queue (coverage in the sources' order, then RealDebridQueue.enqueue).
 * Nothing found -> a short note, so the tap is never silent.
 */
class ReleaseFetchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val mediaId = inputData.getLong(KEY_MEDIA, -1)
        val vol = inputData.getInt(KEY_VOL, -1)
        val noteId = inputData.getInt(KEY_NOTIFICATION, 0)
        if (mediaId < 0 || vol < 0) return Result.failure()
        val found = try {
            Injekt.get<SourceManager>().isInitialized.first { it }
            val entry = Injekt.get<AniListLibrary>().get(mediaId) ?: error("title $mediaId not in the AniList list")
            val inOrder = coverageInOrder(entry)
            val queued = Injekt.get<RealDebridQueue>().enqueue(entry, vol, inOrder)
            Log.i(ReleasesWorker.TAG, "Fetch $mediaId vol $vol: ${if (queued.isEmpty()) "no source has it now" else "queued $queued"}")
            queued.isNotEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(ReleasesWorker.TAG, "Fetch $mediaId vol $vol failed", e)
            false
        }
        if (!found) noSource(vol, noteId)
        return Result.success()
    }

    private fun noSource(vol: Int, noteId: Int) {
        val manager = NotificationManagerCompat.from(applicationContext)
        if (!manager.areNotificationsEnabled()) return
        manager.notify(
            noteId,
            applicationContext.notificationBuilder(Notifications.CHANNEL_RELEASE_DATES) {
                setSmallIcon(R.drawable.ic_book_24dp)
                setContentTitle(applicationContext.getString(R.string.releases_notif_fetch_none, vol))
                setAutoCancel(true)
            }.build(),
        )
    }

    /** Expedited work needs it below API 31. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = applicationContext.notificationBuilder(Notifications.CHANNEL_RELEASE_DATES) { // IMPORTANCE_LOW
            setSmallIcon(android.R.drawable.stat_sys_download)
            setContentTitle(applicationContext.getString(R.string.releases_notif_fetching))
            setOngoing(true)
            setOnlyAlertOnce(true)
        }.build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        return ForegroundInfo(ID_FETCHING, notification, type)
    }

    companion object {
        private const val KEY_MEDIA = "mediaId"
        private const val KEY_VOL = "vol"
        private const val KEY_NOTIFICATION = "notificationId"
        private const val ID_FETCHING = -1913

        /** The title page's own queue input: the title's coverage in the sources' order, minus [exclude] (sources an auto-fetch already tried). Shared with [AutoFetch]. */
        suspend fun coverageInOrder(entry: AniListLibrary.Entry, exclude: Set<Long> = emptySet()): List<Coverage> {
            val service = Injekt.get<SourceCoverage>()
            return CoverageMatch.ordered(service.forTitle(entry).toList(), service.sources().map { it.id }).filterNot { it.sourceId in exclude }
        }

        fun enqueue(context: Context, mediaId: Long, vol: Int, notificationId: Int) {
            val request = OneTimeWorkRequestBuilder<ReleaseFetchWorker>()
                .setInputData(Data.Builder().putLong(KEY_MEDIA, mediaId).putInt(KEY_VOL, vol).putInt(KEY_NOTIFICATION, notificationId).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("release-fetch-$mediaId-$vol", ExistingWorkPolicy.KEEP, request)
        }
    }
}
