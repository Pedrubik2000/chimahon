package eu.kanade.tachiyomi.data.coverage

import android.app.Application
import eu.kanade.tachiyomi.data.anilist.AniListLibrary
import eu.kanade.tachiyomi.data.anilist.VolumeCounts
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.all.RealDebridSource
import eu.kanade.tachiyomi.source.online.all.VolumeLabels
import eu.kanade.tachiyomi.source.online.all.WpRawParser
import eu.kanade.tachiyomi.ui.browse.source.MangaImportUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.injectLazy
import java.io.File

/** What one Real-Debrid source has of one AniList title. */
@Serializable
data class Coverage(
    val sourceId: Long,
    val sourceName: String,
    val state: State,
    /** The matched post (the first one when several match, as on Nyaa: one torrent per post). */
    val postTitle: String? = null,
    val postUrl: String? = null,
    /** Volume -> hoster link or magnet that holds it (a batch link covers several volumes). */
    val volumes: Map<Int, String> = emptyMap(),
    /** Epoch millis. */
    val fetchedAt: Long = 0,
    /** Why, for [State.NEEDS_VISIT] and [State.ERROR]. */
    val message: String? = null,
) {
    enum class State { FOUND, NOT_FOUND, NEEDS_VISIT, ERROR }
}

/**
 * Per AniList title: which volumes each Real-Debrid source has. Searches each source with the native title (romaji
 * when native finds nothing) through its normal search, keeps only posts whose title is the entry's, reads their
 * chapters (= volumes). Cached per (media id, source) in `filesDir/coverage/<media id>.json` for 24 h.
 */
class SourceCoverage(private val app: Application) {

    private val sourceManager: SourceManager by injectLazy()
    private val prefs: DownloadPreferences by injectLazy()
    private val volumeCounts: VolumeCounts by injectLazy()
    private val fileLock = Mutex()

    /** Installed Real-Debrid sources in the preferred order (pref ids first, then the built-in order); on ones only. */
    fun sources(includeOff: Boolean = false): List<HttpSource> {
        val order = prefs.realDebridSourceOrder().get()
        val off = prefs.realDebridSourcesOff().get().toSet()
        return sourceManager.getOnlineSources()
            .filter { it is RealDebridSource && (includeOff || it.id !in off) }
            .sortedBy { s ->
                order.indexOf(s.id).takeIf { it >= 0 }
                    ?: (order.size + DEFAULT_ORDER.indexOf(s.name).let { if (it < 0) DEFAULT_ORDER.size else it })
            }
    }

    /** Every enabled source's coverage, emitted as each answers (cache first unless [force]). */
    fun forTitle(entry: AniListLibrary.Entry, force: Boolean = false): Flow<Coverage> = channelFlow {
        val cached = if (force) emptyMap() else read(entry.mediaId)
        val now = System.currentTimeMillis()
        val gate = Semaphore(PARALLEL)
        sources().forEach { source ->
            launch {
                val hit = cached[source.id]?.takeIf { now - it.fetchedAt < TTL }
                val c = hit ?: gate.withPermit { fetch(entry, source) }.also { save(entry.mediaId, it) }
                maxVolume(c)?.let { volumeCounts.put(entry.mediaId, it, VolumeCounts.SOURCE_COVERAGE) }
                send(c)
            }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun fetch(entry: AniListLibrary.Entry, source: HttpSource): Coverage {
        val base = Coverage(source.id, source.name, Coverage.State.NOT_FOUND, fetchedAt = System.currentTimeMillis())
        return try {
            val posts = listOfNotNull(entry.native, entry.romaji).distinct().firstNotNullOfOrNull { query ->
                source.getSearchManga(1, query, source.getFilterList()).mangas
                    .filter { CoverageMatch.matches(entry.titles, it.title) }
                    .ifEmpty { null }
            } ?: return base
            val chapters = posts.take(MAX_POSTS).flatMap { post ->
                source.getMangaUpdate(post, emptyList(), fetchDetails = false, fetchChapters = true).chapters
                    .map { it.name to it.url }
            }
            base.copy(
                state = Coverage.State.FOUND,
                postTitle = posts.first().title,
                postUrl = source.getMangaUrl(posts.first()),
                volumes = CoverageMatch.volumes(chapters),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Coverage: ${source.name} for ${entry.mediaId}" }
            val message = e.message ?: e.javaClass.simpleName
            base.copy(state = if (isBotGate(e)) Coverage.State.NEEDS_VISIT else Coverage.State.ERROR, message = message)
        }
    }

    private fun file(mediaId: Long) = File(app.filesDir, "coverage/$mediaId.json")

    /** Media id → highest volume any cached FOUND coverage has (any age), for seeding [VolumeCounts]. */
    fun cachedMaxVolumes(): Map<Long, Int> =
        File(app.filesDir, "coverage").listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { f ->
                val id = f.name.removeSuffix(".json").toLongOrNull() ?: return@mapNotNull null
                read(id).values.mapNotNull { maxVolume(it) }.maxOrNull()?.let { id to it }
            }
            .toMap()

    private fun read(mediaId: Long): Map<Long, Coverage> =
        runCatching { json.decodeFromString<List<Coverage>>(file(mediaId).readText()) }.getOrDefault(emptyList())
            .associateBy { it.sourceId }

    /** Found / not found are kept; a bot gate or an error is asked again next time. */
    private suspend fun save(mediaId: Long, coverage: Coverage) {
        if (coverage.state == Coverage.State.ERROR || coverage.state == Coverage.State.NEEDS_VISIT) return
        fileLock.withLock {
            runCatching {
                val all = read(mediaId) + (coverage.sourceId to coverage)
                val file = file(mediaId)
                file.parentFile?.mkdirs()
                val tmp = File(file.path + ".tmp")
                tmp.writeText(json.encodeToString(all.values.toList()))
                check(tmp.renameTo(file)) { "Can't write ${file.name}" }
            }.onFailure { logcat(LogPriority.WARN, it) { "Coverage cache write failed" } }
        }
    }

    companion object {
        private const val PARALLEL = 3
        private const val MAX_POSTS = 5
        private const val TTL = 24 * 60 * 60 * 1000L
        private val json = Json { ignoreUnknownKeys = true }

        private fun maxVolume(c: Coverage): Int? =
            c.volumes.keys.maxOrNull()?.takeIf { c.state == Coverage.State.FOUND && it > 0 }

        /** Built-in order (Source.name) when the preference has no say. */
        val DEFAULT_ORDER = listOf("X3DL", "Excnn", "Rawxt", "Manga Zone", "Nyaa (raw manga)", "BsZip", "A-Z Manga")

        /**
         * A Cloudflare / bot gate the app's own WebView step didn't pass: 403/503, or the interceptor's bypass failure.
         * Never worked around: the user opens the source once in the WebView.
         */
        fun isBotGate(e: Throwable): Boolean = generateSequence(e) { it.cause }.any {
            (it is HttpException && (it.code == 403 || it.code == 503)) ||
                it.javaClass.simpleName == "CloudflareBypassException"
        }
    }
}

/** Pure parts of [SourceCoverage], for tests. */
object CoverageMatch {

    private val tags = Regex("""[\[({【（][^\])}】）]*[\])}】）]""")
    private val inner = Regex("""[\[({【（]([^\])}】）]*)[\])}】）]""")

    /**
     * True when a post title is the entry's: one of its readings equals one of the entry's titles, ignoring case,
     * spaces and punctuation. Readings: the title without volume range / "raw" / trailing [alias]
     * ([WpRawParser.seriesTitle]), the same without [author] (tags), and each bracketed part (Excnn puts the
     * Japanese title in parentheses, Nyaa the romaji in brackets). Exact only: no match = not on this source.
     */
    fun matches(entryTitles: List<String>, postTitle: String): Boolean {
        val wanted = entryTitles.map { MangaImportUtil.seriesKey(it) }.filter { it.isNotEmpty() }.toSet()
        return keys(postTitle).any { it in wanted }
    }

    fun keys(postTitle: String): Set<String> {
        val base = WpRawParser.seriesTitle(postTitle)
        val readings = listOf(base, tags.replace(base, " ")) +
            inner.findAll(postTitle).map { WpRawParser.seriesTitle(it.groupValues[1]) }
        return readings.map { MangaImportUtil.seriesKey(it) }.filter { it.isNotEmpty() }.toSet()
    }

    /**
     * Volume -> link from (chapter name, url) pairs, newest first as sources list them. The link covering the fewest
     * volumes wins a volume (a single volume over a batch), then the first listed.
     */
    fun volumes(chapters: List<Pair<String, String>>): Map<Int, String> {
        val result = sortedMapOf<Int, String>()
        chapters.map { (name, url) -> VolumeLabels.volumes(name) to url }
            .filter { it.first.isNotEmpty() }
            .sortedBy { it.first.size }
            .forEach { (vols, url) -> vols.forEach { result.putIfAbsent(it, url) } }
        return result
    }

    /** [coverages] in [order] (source ids); sources not in it keep their place after. */
    fun ordered(coverages: Collection<Coverage>, order: List<Long>): List<Coverage> =
        coverages.sortedBy { c -> order.indexOf(c.sourceId).let { if (it < 0) Int.MAX_VALUE else it } }

    /** (source id, link) for one volume, in source order, without the same link twice. First = the one to try. */
    fun links(volume: Int, inOrder: List<Coverage>): List<Pair<Long, String>> =
        inOrder.filter { it.state == Coverage.State.FOUND }
            .mapNotNull { c -> c.volumes[volume]?.let { c.sourceId to it } }
            .distinctBy { it.second }

    /** Volume -> the source that gives it first, given the order. */
    fun firstSource(inOrder: List<Coverage>): Map<Int, Long> {
        val result = sortedMapOf<Int, Long>()
        inOrder.filter { it.state == Coverage.State.FOUND }
            .forEach { c -> c.volumes.keys.forEach { result.putIfAbsent(it, c.sourceId) } }
        return result
    }

    /** "1–26, 28" */
    fun ranges(volumes: Collection<Int>): String {
        val sorted = volumes.toSortedSet().toList()
        if (sorted.isEmpty()) return ""
        val parts = mutableListOf<String>()
        var start = sorted[0]
        var prev = start
        (sorted.drop(1) + Int.MIN_VALUE).forEach { v ->
            if (v != prev + 1) {
                parts += if (start == prev) "$start" else "$start–$prev"
                start = v
            }
            prev = v
        }
        return parts.joinToString(", ")
    }
}
