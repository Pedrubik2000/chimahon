package eu.kanade.tachiyomi.ui.glass.review

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import chimahon.srs.Cards
import java.util.concurrent.TimeUnit

/**
 * The review sync with kumapie once answers stop for [QUIET_S] seconds. WorkManager, not a coroutine of the page: the wait must
 * outlive the page's model, a frozen background app and a killed process.
 */
class ReviewSyncJob(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Cards.sync(applicationContext) // never throws; a failure is shown in the settings status, the next answer tries again
        return Result.success()
    }

    companion object {
        private const val NAME = "kumahon-review-sync"
        const val QUIET_S = 60L

        /** (Re)starts the wait: each answer or undo pushes the sync back, so one burst = one sync. */
        fun later(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ReviewSyncJob>().setInitialDelay(QUIET_S, TimeUnit.SECONDS).build(),
            )
        }
    }
}
