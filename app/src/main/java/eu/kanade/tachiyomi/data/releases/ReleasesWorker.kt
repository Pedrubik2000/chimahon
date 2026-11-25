package eu.kanade.tachiyomi.data.releases

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import chimahon.releases.BooksOrJp
import chimahon.releases.HttpStatusException
import chimahon.releases.KodanshaCalendar
import chimahon.releases.Listing
import chimahon.releases.NdlBackoff
import chimahon.releases.NdlSearch
import chimahon.releases.ReleaseEngine
import chimahon.releases.ReleaseEvent
import chimahon.releases.ReleaseHttp
import chimahon.releases.ReleaseStore
import chimahon.releases.VolumeMatcher
import chimahon.releases.VolumeRow
import chimahon.releases.WatchStatus
import chimahon.releases.WatchedSeries
import eu.kanade.tachiyomi.data.anilist.AniListLibrary
import eu.kanade.tachiyomi.data.coverage.Coverage
import eu.kanade.tachiyomi.data.coverage.SourceCoverage
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.ui.glass.title.GlassTitleState
import eu.kanade.tachiyomi.ui.glass.title.LocalVolume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * kumahon Releases, phase 2 (plans/kumahon_releases_plan.md). Three triggers, one pipeline:
 *  - WEEKLY (every 7 days, unmetered): release dates of every watched series from the sources;
 *  - DAILY (every day about 09:00 local, unmetered): listings only for series with a date within a week of today or no
 *    date, plus the coverage of series that can have news ([WatchList.dailyCoverage]);
 *  - NOW ([runNow], the "Check now" button of phase 3): like WEEKLY, on any network.
 * The state is `files/releases/releases.json`; the events a run produced wait there in `pendingEvents` for phase 4.
 */
class ReleasesWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val mode = inputData.getString(KEY_MODE) ?: DAILY
        if (mode != DAILY) setForegroundSafely() // weekly / Check now take minutes: a low-importance foreground service keeps them alive
        return try {
            val note = lock.withLock { withContext(Dispatchers.IO) { run(mode) } }
            record(mode, note)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$mode run failed", e)
            record(mode, e.message ?: e.toString())
            // what the chunks before the error found is in the store: tell the person (notifyPending never throws; runCatching for the rest)
            runCatching { ReleasesNotifier.notifyPending(applicationContext) }
            Result.success() // periodic work goes on: the next period tries again, the error waits in the store for the screen
        }
    }

    /** The store's run log (phase 3 shows it); a failing write must not turn the run into a failure. */
    private fun record(mode: String, error: String?) {
        runCatching { store(applicationContext).update { it.copy(lastRunAt = System.currentTimeMillis(), lastRunKind = mode, lastError = error) } }
            .onFailure { Log.w(TAG, "run log not saved: $it") }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, Notifications.CHANNEL_RELEASE_DATES) // IMPORTANCE_LOW, no sound
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Checking volume releases") // ponytail: not translated, a progress line that lives minutes
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        return ForegroundInfo(ID_PROGRESS, notification, type)
    }

    /**
     * Series go through in chunks of [CHUNK]: sources, then the engine for that chunk, then the store is saved. A run that WorkManager
     * stops half way (about 10 minutes) keeps what it did. The engine leaves a series without listings or coverage as it is, and
     * `notified` / `pendingEvents` are per event, so a subset is safe; the state keeps the whole watch list.
     * Returns an error note for the run log (null = fine).
     */
    private suspend fun run(mode: String): String? {
        val library = Injekt.get<AniListLibrary>()
        val entries = library.entries.value
        if (entries.isEmpty()) {
            Log.i(TAG, "$mode: no AniList list yet, nothing to watch")
            return null
        }
        val startedAt = System.currentTimeMillis()
        val now = Instant.now()
        val today = now.atZone(JST).toLocalDate()
        val store = store(applicationContext)

        val (local, read) = localAndRead(entries.filter { WatchList.statusOf(it.status) != null })
        val localMax = local.mapValues { it.value.maxOrNull() ?: 0 }
        val (allSeries, skipped) = WatchList.buildWithSkips(entries, localMax, store.get().series)
        Log.i(TAG, "$mode: skipped ${skipped.size}: " + skipped.joinToString("; ") { "${it.mediaId} ${it.reason} '${it.title}'" })
        val series = allSeries.filter { it.watching != false } // the person's Watch switch: an unwatched series is kept in the store, not checked
        val rowsBefore = store.get().rows
        Log.i(TAG, "$mode: ${series.size} watched (${allSeries.size - series.size} switched off)")

        val listIds = if (mode == DAILY) WatchList.dailyListings(series, rowsBefore, today) else series.mapTo(mutableSetOf()) { it.anilistId }
        val covIds = WatchList.dailyCoverage(series, rowsBefore, today, local)
        val http = OkHttpReleaseHttp(Injekt.get<NetworkHelper>().client)
        val kodansha = KodanshaCalendar(http) // one for the whole run: its month pages are fetched once, not once per chunk
        val ndlBackoff = NdlBackoff() // 2 throttled answers in a row: no more NDL calls this run

        var events = 0
        var listed = 0
        var covered = 0
        for (chunk in series.chunked(CHUNK).ifEmpty { listOf(emptyList()) }) { // an empty list still saves the watch list
            val listings = collect(chunk.filter { it.anilistId in listIds }, today, http, kodansha, ndlBackoff)
            val coverage = coverage(chunk.filter { it.anilistId in covIds }, rowsBefore, today)
            val ids = chunk.mapTo(mutableSetOf()) { it.anilistId }
            store.update { cur ->
                // rebuilt from the file's own series: an override edited meanwhile is kept
                val watch = WatchList.build(entries, localMax, cur.series)
                val r = ReleaseEngine().run(now, cur.copy(series = watch.filter { it.anilistId in ids && it.watching != false }), listings, coverage, local, read)
                events += r.events.size
                r.events.forEach {
                    Log.i(TAG, "event ${it.kind} id=${it.anilistId} vol=${it.vol} source=${it.source} old=${it.oldDate} new=${it.newDate}")
                }
                // rows of titles no longer watched (Plan to read before phase 5, finished, dropped) go with them
                val watchedIds = watch.mapTo(hashSetOf()) { it.anilistId }
                r.state.copy(series = watch, rows = r.state.rows.filter { it.anilistId in watchedIds }, pendingEvents = (cur.pendingEvents + r.events).takeLast(MAX_PENDING))
            }
            listed += listings.size
            covered += coverage.size
        }
        Log.i(TAG, "$mode done in ${(System.currentTimeMillis() - startedAt) / 1000}s: listings for $listed, coverage for $covered, $events new events" + (if (ndlBackoff.tripped) ", NDL backed off" else ""))
        if (ReleasesNotifier.isSeeded(applicationContext) && !isTestApp(applicationContext)) AutoFetch.run(applicationContext, today) // phase 5: the very first run only seeds the rows; before the notifier, which keeps quiet about what is fetching
        ReleasesNotifier.notifyPending(applicationContext) // phase 4
        return null
    }

    /** books.or.jp per series (NDL when it failed or matched nothing), Kodansha's calendar (one fetch per month per chunk of the run). Soft failures. */
    private suspend fun collect(series: List<WatchedSeries>, today: LocalDate, http: ReleaseHttp, kodansha: KodanshaCalendar, ndlBackoff: NdlBackoff): Map<Long, List<Listing>> {
        if (series.isEmpty()) return emptyMap()
        val books = BooksOrJp(http)
        val ndl = NdlSearch(http)
        val out = LinkedHashMap<Long, List<Listing>>()
        for (s in series) {
            val all = mutableListOf<Listing>()
            var booksOk = false
            try {
                all += books.listings(s.jpTitle, today, s.lastKnownVol)
                booksOk = VolumeMatcher.match(s.jpTitle, s.lastKnownVol, all, s.publisher).isNotEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "books.or.jp failed for ${s.anilistId}: $e")
            }
            delay(PAUSE_MS)
            try {
                all += kodansha.listings(s.jpTitle, today, s.lastKnownVol)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "kodansha failed: $e")
            }
            if (!booksOk && !ndlBackoff.tripped) {
                try {
                    all += ndl.listings(s.jpTitle, today, s.lastKnownVol)
                    ndlBackoff.succeeded()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "ndl failed for ${s.anilistId}: $e")
                    ndlBackoff.failed(e)
                    if (ndlBackoff.tripped) Log.w(TAG, "ndl throttled twice in a row: not asked again this run")
                }
                delay(PAUSE_MS)
            }
            out[s.anilistId] = all
        }
        return out
    }

    /** Sequential, each title's own cache (24 h) first: a passed date forces a fresh read, the "No date" lane does not. */
    private suspend fun coverage(series: List<WatchedSeries>, rows: List<VolumeRow>, today: LocalDate): Map<Long, Map<Int, String>> {
        if (series.isEmpty()) return emptyMap()
        val service = Injekt.get<SourceCoverage>()
        val library = Injekt.get<AniListLibrary>()
        val order = service.sources().map { it.id }
        if (order.isEmpty()) return emptyMap()
        val out = LinkedHashMap<Long, Map<Int, String>>()
        for (s in series) {
            val entry = library.get(s.anilistId) ?: continue
            try {
                val got: List<Coverage> = service.forTitle(entry, WatchList.forceCoverage(s, rows, today)).toList()
                out[s.anilistId] = WatchList.bestSources(got, order)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "coverage failed for ${s.anilistId}: $e")
            }
            delay(PAUSE_MS)
        }
        return out
    }

    /**
     * Per media id: the volumes on the tablet and those read, as the title page sees them: the local series bound by an AniList
     * track (most volumes), else an untracked favourite local series whose title matches ([AniListLibrary.match]; the title page
     * also links it, this job does not); the files numbered within the entry's volume count ([GlassTitleState.inRange]).
     */
    private suspend fun localAndRead(entries: List<AniListLibrary.Entry>): Pair<Map<Long, Set<Int>>, Map<Long, Set<Int>>> {
        val allTracks = Injekt.get<TrackRepository>().getTracks().filter { it.trackerId == TrackerManager.ANILIST }
        val linked = allTracks.groupBy { it.remoteId }
        val tracked = allTracks.mapTo(mutableSetOf()) { it.mangaId }
        val getManga = Injekt.get<GetManga>()
        val chapters = Injekt.get<ChapterRepository>()
        val untracked = Injekt.get<GetFavorites>().await().filter { it.source == LocalSource.ID && it.id !in tracked }
        val local = HashMap<Long, Set<Int>>()
        val read = HashMap<Long, Set<Int>>()
        for (e in entries) {
            val bound = linked[e.mediaId].orEmpty()
                .mapNotNull { t -> getManga.await(t.mangaId)?.takeIf { it.source == LocalSource.ID } }
                .map { chapters.getChapterByMangaId(it.id) }
                .maxByOrNull { it.size }
            val series = bound
                ?: untracked.firstOrNull { AniListLibrary.match(listOf(it.title, it.url), listOf(e)) != null }?.let { chapters.getChapterByMangaId(it.id) }
                ?: continue
            val empty = GlassTitleState.emptyUrls(series)
            val volumes: List<LocalVolume> = GlassTitleState.inRange(
                GlassTitleState.dedupe(
                    series.filter { it.url !in empty }.mapNotNull { c ->
                        GlassTitleState.volumeNumber(c)?.let { LocalVolume(c.id, it, c.read, c.lastPageRead) }
                    },
                ),
                e.volumes ?: 0,
            )
            local[e.mediaId] = volumes.mapTo(sortedSetOf()) { it.number }
            read[e.mediaId] = volumes.filter { it.read }.mapTo(sortedSetOf()) { it.number }
        }
        return local to read
    }
    companion object {
        const val TAG = "KumahonReleases"
        const val KEY_MODE = "mode"
        const val WEEKLY = "weekly"
        const val DAILY = "daily"
        const val NOW = "now"
        // KEEP keeps an old job as it was enqueued: change its period / constraints / input and these names must change too (bump the _v)
        const val WORK_WEEKLY = "KumahonReleasesWeekly_v1"
        const val WORK_DAILY = "KumahonReleasesDaily_v1"
        const val WORK_NOW = "KumahonReleasesNow"
        const val DAILY_HOUR = 9
        private const val PAUSE_MS = 1_000L // between two series (requests to the sources)
        private const val CHUNK = 8 // series per save of a run
        private const val ID_PROGRESS = -1901 // the foreground notification of weekly / Check now runs
        private const val MAX_PENDING = 200

        private val JST = ZoneId.of("Asia/Tokyo")
        private val lock = Mutex() // one run at a time (a Check now during the daily run waits)

        @Volatile private var instance: ReleaseStore? = null

        /** The one store of the process (a second instance's get() would be stale). */
        fun store(context: Context): ReleaseStore = instance ?: synchronized(this) {
            instance ?: ReleaseStore(File(context.applicationContext.filesDir, "releases/releases.json")) { Log.w(TAG, it) }
                .also { instance = it }
        }

        /** Time from [now] to the next [DAILY_HOUR]:00 local. */
        fun delayUntilNine(now: LocalDateTime): Duration {
            var at = now.with(LocalTime.of(DAILY_HOUR, 0))
            if (!at.isAfter(now)) at = at.plusDays(1)
            return Duration.between(now, at)
        }

        /**
         * The debug build (package ends in ".dev"): never scheduled, never auto-fetches (Pedro, 2026-11-25).
         */
        fun isTestApp(context: Context) = context.packageName.endsWith(".dev")

        /**
         * On app start: both periodic jobs; KEEP, so a restart does not move the 09:00 phase.
         * ponytail: the daily run drifts by up to the 1 h flex and WorkManager's own delays; the 7-day windows do not care.
         */
        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            // kumahon Test (app.kumahon.dev) sits next to the real app: no daily/weekly runs there, only Check now
            if (isTestApp(context)) {
                wm.cancelUniqueWork(WORK_DAILY)
                wm.cancelUniqueWork(WORK_WEEKLY)
                return
            }
            val unmetered = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresBatteryNotLow(true).build()
            wm.enqueueUniquePeriodicWork(
                WORK_DAILY,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ReleasesWorker>(1, TimeUnit.DAYS, 1, TimeUnit.HOURS)
                    .setInitialDelay(delayUntilNine(LocalDateTime.now()))
                    .setInputData(Data.Builder().putString(KEY_MODE, DAILY).build())
                    .setConstraints(unmetered)
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.MINUTES)
                    .build(),
            )
            wm.enqueueUniquePeriodicWork(
                WORK_WEEKLY,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ReleasesWorker>(7, TimeUnit.DAYS, 1, TimeUnit.HOURS)
                    .setInputData(Data.Builder().putString(KEY_MODE, WEEKLY).build())
                    .setConstraints(unmetered)
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.MINUTES)
                    .build(),
            )
        }

        /** "Check now": a full run right away on any network; ignored while one is queued or running. */
        fun runNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NOW,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ReleasesWorker>()
                    .setInputData(Data.Builder().putString(KEY_MODE, NOW).build())
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build(),
            )
        }
    }
}

/** The sources' two requests over the app's OkHttp (its cookie jar carries books.or.jp's session; always from the network, never the 5 MB cache). */
class OkHttpReleaseHttp(
    private val client: OkHttpClient,
    private val userAgent: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36",
) : ReleaseHttp {
    override fun get(url: String) = call(Request.Builder().url(url))

    override fun postForm(url: String, form: Map<String, String>) =
        call(Request.Builder().url(url).post(FormBody.Builder(Charsets.UTF_8).apply { form.forEach { (k, v) -> add(k, v) } }.build()))

    private fun call(b: Request.Builder): String {
        val req = b.header("User-Agent", userAgent).header("Accept-Language", "ja,en;q=0.8").cacheControl(CacheControl.FORCE_NETWORK).build()
        client.newCall(req).execute().use { r ->
            if (r.code >= 400) throw HttpStatusException(r.code, req.url.toString())
            return r.body?.string() ?: throw IOException("empty body " + req.url)
        }
    }
}
