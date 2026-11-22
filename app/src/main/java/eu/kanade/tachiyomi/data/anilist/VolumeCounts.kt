package eu.kanade.tachiyomi.data.anilist

import android.app.Application
import eu.kanade.tachiyomi.data.coverage.SourceCoverage
import eu.kanade.tachiyomi.data.mangadex.VolumeCovers
import eu.kanade.tachiyomi.data.mangaupdates.MangaUpdates
import eu.kanade.tachiyomi.util.system.connectivityManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.days

/**
 * Volumes out so far of titles AniList gives no count for (RELEASING ones): learned from source coverage (the title
 * page) and, in the background, from MangaUpdates' stated count, else MangaDex's covers by volume. One small JSON
 * file, `anilist/volume_counts.json`.
 */
class VolumeCounts(private val app: Application) {

    /**
     * [count] null = asked MangaDex on [at] and it had nothing. [mu] = MangaUpdates' stated count, asked on [muAt]
     * (0 = never); [muDone] = MangaUpdates says the series is complete, never asked again.
     */
    @Serializable
    data class Known(
        val count: Int? = null,
        val source: String = "",
        val at: Long = 0,
        val mu: Int? = null,
        val muAt: Long = 0,
        val muDone: Boolean = false,
    )

    private val volumeCovers: VolumeCovers by injectLazy()
    private val mangaUpdates = MangaUpdates()
    private val coverage: SourceCoverage by injectLazy()

    private val file get() = File(app.filesDir, "anilist/volume_counts.json")
    private val lock = Mutex()

    // ponytail: first use reads the file on the caller's thread (a few KB); GlassTitlesModel reads it on IO
    private val known by lazy {
        MutableStateFlow(runCatching { json.decodeFromString<Map<Long, Known>>(file.readText()) }.getOrDefault(emptyMap()))
    }
    private val _counts by lazy { MutableStateFlow(countsOf(known.value)) }

    /** Media id → volumes out so far. */
    val counts: StateFlow<Map<Long, Int>> get() = _counts.asStateFlow()

    fun get(mediaId: Long): Int? = counts.value[mediaId]

    /** Keeps the highest count seen; [count] null or 0 = asked, nothing found. */
    suspend fun put(mediaId: Long, count: Int?, source: String) {
        putAll(mapOf(mediaId to count), source)
    }

    private suspend fun putAll(found: Map<Long, Int?>, source: String) {
        val now = System.currentTimeMillis()
        edit { old -> old + found.map { (id, c) -> id to merge(old[id], c, source, now) } }
    }

    private suspend fun edit(change: (Map<Long, Known>) -> Map<Long, Known>): Unit = withIOContext {
        lock.withLock {
            val old = known.value
            val new = change(old)
            if (new == old) return@withLock
            known.value = new
            _counts.value = countsOf(new)
            runCatching {
                file.parentFile?.mkdirs()
                val tmp = File(file.path + ".tmp")
                tmp.writeText(json.encodeToString(new))
                check(tmp.renameTo(file)) { "Can't write ${file.name}" }
            }.onFailure { logcat(LogPriority.WARN, it) { "Volume counts write failed" } }
        }
    }

    private val running = AtomicBoolean(false)

    @Volatile
    private var done = false

    /**
     * Once per app run: seeds from the coverage cache, then for each of [entries] (in that order) that needs it asks
     * MangaUpdates ([askMangaUpdates]) and, when that gave no number, MangaDex ([shouldAsk]); one title at a time with
     * [PAUSE] between. Any failure (429, 5xx, a challenge page, a redirect, offline) stops the run; the next app start
     * tries again. A cancelled run (the shell closed) starts over next time.
     */
    suspend fun fill(entries: List<AniListLibrary.Entry>) {
        if (done || !running.compareAndSet(false, true)) return
        try {
            val seed = coverage.cachedMaxVolumes()
            putAll(seed, SOURCE_COVERAGE)
            if (app.connectivityManager.isActiveNetworkMetered) {
                logcat(LogPriority.INFO) { "Volume counts: metered network, not asking MangaUpdates or MangaDex" }
                return
            }
            val now = System.currentTimeMillis()
            fun askMu(e: AniListLibrary.Entry) =
                askMangaUpdates(known.value[e.mediaId], e.mediaStatus == "RELEASING", now)
            val todo = entries
                .filter { (it.volumes ?: 0) <= 0 }
                .filter { askMu(it) || shouldAsk(known.value[it.mediaId], now) }
            var asked = 0
            var fromMu = 0
            var fromDex = 0
            var stop: String? = null
            for ((i, e) in todo.withIndex()) {
                if (i > 0) delay(PAUSE)
                try {
                    if (askMu(e)) {
                        val r = mangaUpdates.volumes(e)
                        if (r?.volumes != null) fromMu++
                        val at = System.currentTimeMillis()
                        edit { old -> old + (e.mediaId to withMangaUpdates(old[e.mediaId], r, seed[e.mediaId], at)) }
                    }
                    // MangaUpdates gave no number (now or before) and nothing else did either
                    if (shouldAsk(known.value[e.mediaId], now)) {
                        val covers = volumeCovers.get(e, rethrow = true)
                        val max = covers.keys.maxOrNull()?.toInt()?.takeIf { it > 0 }
                        if (max != null) fromDex++
                        put(e.mediaId, max, SOURCE_MANGADEX)
                    }
                } catch (c: CancellationException) {
                    throw c
                } catch (x: Exception) {
                    stop = x.message ?: x.javaClass.simpleName
                    break
                }
                asked++
            }
            logcat(LogPriority.INFO) {
                "Volume counts: asked $asked of ${todo.size}, found MangaUpdates $fromMu, MangaDex $fromDex, " +
                    "coverage seed ${seed.size}" + (stop?.let { ", stopped: $it" } ?: "")
            }
            done = true
        } finally {
            running.set(false)
        }
    }

    companion object {
        const val SOURCE_COVERAGE = "coverage"
        const val SOURCE_MANGADEX = "mangadex"
        const val SOURCE_MANGAUPDATES = "mangaupdates"

        /** Between titles; MangaDex allows 5 requests/s, a title is ≤ 3 MangaUpdates + 2–4 MangaDex requests. */
        const val PAUSE = 1_500L
        val ASK_AGAIN = 30.days.inWholeMilliseconds

        /** A RELEASING title's MangaUpdates count is asked again after this. */
        val REFRESH = 14.days.inWholeMilliseconds
        private val json = Json { ignoreUnknownKeys = true }

        /** Coverage / MangaDex: the highest count wins; nothing found never erases a count. */
        fun merge(old: Known?, count: Int?, source: String, now: Long): Known {
            val base = old ?: Known()
            if (count == null || count <= 0) return if (base.count != null) base else base.copy(source = source, at = now)
            return if ((base.count ?: 0) >= count) base else base.copy(count = count, source = source, at = now)
        }

        /**
         * MangaUpdates' answer: its stated count replaces a MangaDex one up or down, but source coverage above it
         * (scans of a volume out yesterday) is kept: max(MangaUpdates, [coverage]). No number keeps what was there.
         */
        fun withMangaUpdates(old: Known?, found: MangaUpdates.Result?, coverage: Int?, now: Long): Known {
            val base = (old ?: Known()).copy(muAt = now, muDone = found?.completed == true)
            val n = found?.volumes?.takeIf { it > 0 } ?: return base
            val cov = maxOf(coverage ?: 0, if (base.source == SOURCE_COVERAGE) base.count ?: 0 else 0)
            return if (cov > n) {
                base.copy(count = cov, source = SOURCE_COVERAGE, at = now, mu = n)
            } else {
                base.copy(count = n, source = SOURCE_MANGAUPDATES, at = now, mu = n)
            }
        }

        /**
         * Never asked (also titles filled from MangaDex before MangaUpdates was a source) → ask; complete on
         * MangaUpdates → never; a stated count → again after [REFRESH] while RELEASING; no match → after [ASK_AGAIN].
         */
        fun askMangaUpdates(k: Known?, releasing: Boolean, now: Long): Boolean = when {
            k == null || k.muAt == 0L -> true
            k.muDone -> false
            k.mu != null -> releasing && now - k.muAt >= REFRESH
            else -> now - k.muAt >= ASK_AGAIN
        }

        /** MangaDex: only while no count is known at all, again [ASK_AGAIN] after it had nothing. */
        fun shouldAsk(k: Known?, now: Long): Boolean = k == null || (k.count == null && now - k.at >= ASK_AGAIN)

        fun countsOf(known: Map<Long, Known>): Map<Long, Int> =
            known.mapNotNull { (id, k) -> k.count?.let { id to it } }.toMap()
    }
}
