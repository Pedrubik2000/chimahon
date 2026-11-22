package eu.kanade.tachiyomi.data.anilist

import android.app.Application
import eu.kanade.tachiyomi.data.coverage.SourceCoverage
import eu.kanade.tachiyomi.data.mangadex.VolumeCovers
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
 * page) and, in the background, from MangaDex's covers by volume. One small JSON file, `anilist/volume_counts.json`.
 */
class VolumeCounts(private val app: Application) {

    /** [count] null = asked MangaDex on [at] and it had nothing. */
    @Serializable
    data class Known(val count: Int? = null, val source: String = "", val at: Long = 0)

    private val volumeCovers: VolumeCovers by injectLazy()
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

    private suspend fun putAll(found: Map<Long, Int?>, source: String): Unit = withIOContext {
        lock.withLock {
            val now = System.currentTimeMillis()
            val old = known.value
            val new = old + found.map { (id, c) -> id to merge(old[id], c, source, now) }
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
     * Once per app run: seeds from the coverage cache, then asks MangaDex for each of [entries] (in that order) not
     * known yet, one title at a time with [PAUSE] between. Any failure (429, a challenge page, a redirect, offline)
     * stops the run; the next app start tries again. A cancelled run (the shell closed) starts over next time.
     */
    suspend fun fill(entries: List<AniListLibrary.Entry>) {
        if (done || !running.compareAndSet(false, true)) return
        try {
            putAll(coverage.cachedMaxVolumes(), SOURCE_COVERAGE)
            if (app.connectivityManager.isActiveNetworkMetered) {
                logcat(LogPriority.INFO) { "Volume counts: metered network, not asking MangaDex" }
                return
            }
            val now = System.currentTimeMillis()
            val todo = entries.filter { shouldAsk(known.value[it.mediaId], now) }
            var asked = 0
            var found = 0
            var stop: String? = null
            for ((i, e) in todo.withIndex()) {
                if (i > 0) delay(PAUSE)
                val covers = try {
                    volumeCovers.get(e, rethrow = true)
                } catch (c: CancellationException) {
                    throw c
                } catch (x: Exception) {
                    stop = x.message ?: x.javaClass.simpleName
                    break
                }
                asked++
                val max = covers.keys.maxOrNull()?.toInt()?.takeIf { it > 0 }
                if (max != null) found++
                put(e.mediaId, max, SOURCE_MANGADEX)
            }
            logcat(LogPriority.INFO) {
                "Volume counts: asked $asked of ${todo.size}, found $found" + (stop?.let { ", stopped: $it" } ?: "")
            }
            done = true
        } finally {
            running.set(false)
        }
    }

    companion object {
        const val SOURCE_COVERAGE = "coverage"
        const val SOURCE_MANGADEX = "mangadex"

        /** Between titles; MangaDex allows 5 requests/s, a title is 2–4 requests. */
        const val PAUSE = 1_500L
        val ASK_AGAIN = 30.days.inWholeMilliseconds
        private val json = Json { ignoreUnknownKeys = true }

        /** The highest count wins; nothing found never erases a count. */
        fun merge(old: Known?, count: Int?, source: String, now: Long): Known {
            if (count == null || count <= 0) return old?.takeIf { it.count != null } ?: Known(null, source, now)
            return old?.takeIf { (it.count ?: 0) >= count } ?: Known(count, source, now)
        }

        // ponytail: a found count is never asked again, so a releasing title's count only grows from coverage;
        // re-ask counts older than ASK_AGAIN if they lag
        fun shouldAsk(k: Known?, now: Long): Boolean = k == null || (k.count == null && now - k.at >= ASK_AGAIN)

        fun countsOf(known: Map<Long, Known>): Map<Long, Int> =
            known.mapNotNull { (id, k) -> k.count?.let { id to it } }.toMap()
    }
}
