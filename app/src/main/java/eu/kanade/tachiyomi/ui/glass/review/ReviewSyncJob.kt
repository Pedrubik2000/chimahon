package eu.kanade.tachiyomi.ui.glass.review

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import chimahon.srs.Cards
import chimahon.srs.PeerSync
import java.util.concurrent.TimeUnit

/**
 * The review sync with kumapie once answers stop for [QUIET_S] seconds. WorkManager, not a coroutine of the page: the wait must
 * outlive the page's model, a frozen background app and a killed process.
 */
class ReviewSyncJob(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    // Never throws. A failed sync while paired (kumapie not listening yet) is tried again with backoff (1, 2, 4... min, capped by
    // WorkManager at 5 h) until it goes through; the reason is in the settings status meanwhile.
    override suspend fun doWork(): Result =
        if (Cards.sync(applicationContext) || !PeerSync.paired(applicationContext)) Result.success() else Result.retry()

    companion object {
        private const val NAME = "kumahon-review-sync"
        const val QUIET_S = 60L

        /** (Re)starts the wait: each answer or undo pushes the sync back, so one burst = one sync. */
        fun later(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ReviewSyncJob>().setInitialDelay(QUIET_S, TimeUnit.SECONDS)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).build(),
            )
        }
    }
}
