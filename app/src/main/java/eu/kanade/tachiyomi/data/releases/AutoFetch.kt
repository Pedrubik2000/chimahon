package eu.kanade.tachiyomi.data.releases

import android.content.Context
import android.util.Log
import androidx.work.WorkInfo
import androidx.work.WorkManager
import chimahon.releases.AutoFetchPlan
import eu.kanade.tachiyomi.data.anilist.AniListLibrary
import eu.kanade.tachiyomi.data.coverage.CoverageMatch
import eu.kanade.tachiyomi.data.download.RealDebridImportJob
import eu.kanade.tachiyomi.data.download.RealDebridQueue
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.system.connectivityManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.time.LocalDate

/**
 * Phase 5: the auto-fetch step at the end of a Releases run, and the report back from [eu.kanade.tachiyomi.data.download.RealDebridImportJob].
 * The decisions are [AutoFetchPlan] (pure, tested); this only reads the phone (Wi-Fi, free space) and queues through the same path as
 * the Fetch button ([ReleaseFetchWorker.coverageInOrder] + [RealDebridQueue.enqueue]). Attempts live in the Releases store.
 *
 * Accepted limits (review item 9): the Wi-Fi / free-space check is at queue time (the job itself carries an UNMETERED constraint, which
 * also holds the serial chain behind it); a job WorkManager kills without a report is judged by [AutoFetchPlan.expire] the next day;
 * the attempt is written just after the enqueue, so a job that ended inside that gap would not find it (it takes seconds at least).
 * Second review, items 3-4 accepted as they are. Auto jobs run in their own chain (WORK_AUTO), so a manual and an auto import of one volume
 * can overlap: the import skips volumes already in the folder when it writes, so what remains is a race inside one file write; at queue
 * time a candidate with an unfinished job of any kind (manual included) is dropped.
 */
object AutoFetch {

    /** Never throws except for cancellation. */
    suspend fun run(context: Context, today: LocalDate) {
        try {
            step(context, today)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(ReleasesWorker.TAG, "auto-fetch step failed", e)
        }
    }

    private suspend fun step(context: Context, today: LocalDate) {
        val store = ReleasesWorker.store(context)
        // import jobs WorkManager still knows: (title, volume) of those unfinished = a manual Fetch or an earlier auto job still waiting
        // in the serial chain (never fetched twice, never expired); of those CANCELLED ("Cancel all" never reports back) = no failure
        val infos = WorkManager.getInstance(context).getWorkInfosByTag(RealDebridImportJob.TAG).get()
        fun keys(i: WorkInfo): List<Pair<Long, Int>> {
            val media = i.tags.firstOrNull { it.startsWith(RealDebridImportJob.TAG_MEDIA) }?.removePrefix(RealDebridImportJob.TAG_MEDIA)?.toLongOrNull()
                ?: return emptyList()
            return i.tags.filter { it.startsWith(RealDebridImportJob.TAG_VOLUME) }
                .mapNotNull { it.removePrefix(RealDebridImportJob.TAG_VOLUME).toIntOrNull() }.map { media to it }
        }
        val active = infos.filter { !it.state.isFinished }.flatMap(::keys).toSet()
        val cancelled = infos.filter { it.state == WorkInfo.State.CANCELLED }.flatMap(::keys).toSet()
        store.update { it.copy(attempts = AutoFetchPlan.prune(AutoFetchPlan.expire(it.attempts, today, active, cancelled), it.rows)) }
        val state = store.get()
        val wanted = AutoFetchPlan.candidates(state.series, state.rows) { ReleasesPlan.autoFetchOn(it, state.rows) }.filterNot { it in active }
        if (wanted.isEmpty()) return
        val doneToday = AutoFetchPlan.fetchedToday(state, today)
        val picked = AutoFetchPlan.pick(
            wanted, state.attempts, today,
            unmetered = !context.connectivityManager.isActiveNetworkMetered,
            freeBytes = freeBytes(),
            fetchedToday = doneToday,
        )
        Log.i(ReleasesWorker.TAG, "auto-fetch: ${wanted.size} wanted, ${picked.size} picked")
        if (picked.isEmpty()) return
        Injekt.get<SourceManager>().isInitialized.first { it } // coverage reads the sources
        val library = Injekt.get<AniListLibrary>()
        val queue = Injekt.get<RealDebridQueue>()
        var queuedCount = 0 // volumes, not jobs: a pack that holds 4-6 counts 3 toward the cap
        for ((id, vol) in picked) {
            if (doneToday + queuedCount >= AutoFetchPlan.MAX_PER_DAY) break
            val entry = library.get(id) ?: continue
            val tried = state.attempts.firstOrNull { it.anilistId == id && it.vol == vol }?.tried.orEmpty().toSet()
            var got = emptySet<Int>()
            var source: Long? = null
            try {
                val inOrder = ReleaseFetchWorker.coverageInOrder(entry, exclude = tried)
                source = CoverageMatch.links(vol, inOrder).firstOrNull()?.first
                got = queue.enqueue(entry, vol, inOrder, unmetered = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(ReleasesWorker.TAG, "auto-fetch $id vol $vol failed to queue", e)
            }
            Log.i(ReleasesWorker.TAG, "auto-fetch $id vol $vol: ${if (got.isEmpty()) "no source has it" else "queued $got"}")
            // written after the enqueue succeeded (nothing queued = no pending attempt left behind); the job takes seconds at least, so
            // its report finds the attempt. Nothing queued (no untried source has it, or an error) = one failure, tomorrow another look.
            store.update { s ->
                val q = AutoFetchPlan.queued(s.attempts, id, vol, source, today)
                s.copy(attempts = if (got.isEmpty()) AutoFetchPlan.ended(q, id, listOf(vol), emptyList(), cancelled = false, sources = emptyList()).attempts else q)
            }
            queuedCount += got.size
        }
        if (queuedCount > 0) {
            store.update { s ->
                val (day, n) = AutoFetchPlan.counted(s, today, queuedCount)
                s.copy(fetchDay = day, fetchCount = n)
            }
        }
    }

    /** Free bytes where volumes are stored (the local source folder's volume); -1 = unknown (then nothing is fetched). */
    private fun freeBytes(): Long = runCatching {
        val path = Injekt.get<StorageManager>().getLocalSourceDirectory()?.filePath ?: return -1
        DiskUtil.getAvailableStorageSpace(File(path))
    }.getOrDefault(-1)

    /**
     * Called when an import job ends (any job: it returns at once for those that are not an auto-fetch). A volume delivered
     * -> "on your tablet · Read"; else one failure on the attempt, with the sources the job used or found dead. Never throws.
     */
    fun jobEnded(
        context: Context,
        mediaId: Long,
        volumes: Collection<Int>,
        delivered: Collection<Int>,
        cancelled: Boolean,
        sources: Collection<Long>,
    ) {
        try {
            val store = ReleasesWorker.store(context)
            if (mediaId <= 0 || store.get().attempts.none { it.pending && it.anilistId == mediaId && it.vol in volumes }) return
            var arrived = emptyList<Pair<Long, Int>>()
            store.update { s ->
                val e = AutoFetchPlan.ended(s.attempts, mediaId, volumes, delivered, cancelled, sources)
                arrived = e.delivered
                s.copy(attempts = e.attempts)
            }
            arrived.forEach { (id, vol) -> ReleasesNotifier.onTablet(context, id, vol) }
        } catch (e: Exception) {
            Log.w(ReleasesWorker.TAG, "auto-fetch report failed", e)
        }
    }
}
