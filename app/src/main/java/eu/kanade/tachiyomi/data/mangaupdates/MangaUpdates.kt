package eu.kanade.tachiyomi.data.mangaupdates

import eu.kanade.tachiyomi.data.anilist.AniListLibrary
import eu.kanade.tachiyomi.data.mangadex.VolumeCovers
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.ui.browse.source.MangaImportUtil
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.parser.Parser
import tachiyomi.core.common.util.lang.withIOContext
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import kotlin.math.abs

/**
 * Volumes out so far of an AniList title from MangaUpdates (public API, no account), which states them in the
 * series' `status` ("13 Volumes (Ongoing)"). MangaUpdates has no AniList id, so the series is matched by exact name
 * key + type + year ([confident]); no confident match = null, never a guess. At most 2 searches + 1 record per title.
 */
class MangaUpdates {

    data class Result(val seriesId: Long, val volumes: Int?, val completed: Boolean)

    /** A search hit (title + the matched name) or a series record (title + associated names). */
    data class Candidate(val seriesId: Long, val titles: List<String>, val type: String?, val year: Int?)

    data class Series(val candidate: Candidate, val status: String?, val completed: Boolean)

    private val network: NetworkHelper by injectLazy()

    /** Throws on 429, 5xx or a body that isn't the API's JSON (the filler stops); null = no confident match. */
    suspend fun volumes(entry: AniListLibrary.Entry): Result? = withIOContext {
        val hit = listOfNotNull(entry.romaji, entry.native, entry.english).distinct().take(2)
            .firstNotNullOfOrNull { title -> best(search(title), entry) }
            ?: return@withIOContext null
        val series = call(GET("$API/series/${hit.seriesId}"))?.let { parseSeries(it) } ?: return@withIOContext null
        if (!confident(series.candidate, entry)) return@withIOContext null
        Result(series.candidate.seriesId, volumeCount(series.status), series.completed)
    }

    private suspend fun search(title: String): List<Candidate> {
        val body = buildJsonObject {
            put("search", title)
            put("perpage", 5)
        }.toString().toRequestBody("application/json".toMediaType())
        return call(POST("$API/series/search", body = body))?.let { parseSearch(it) }.orEmpty()
    }

    /** Body, or null on 404. */
    private suspend fun call(request: Request): String? =
        network.client.newCall(request.newBuilder().header("User-Agent", VolumeCovers.USER_AGENT).build())
            .await().use { response ->
                if (response.code == 404) return@use null
                if (!response.isSuccessful) throw IOException("MangaUpdates HTTP ${response.code}")
                response.body.string()
            }

    @Serializable
    private data class SearchPage(val results: List<Hit>? = null)

    @Serializable
    private data class Hit(val record: Record, @SerialName("hit_title") val hitTitle: String? = null)

    @Serializable
    private data class Record(
        @SerialName("series_id") val seriesId: Long,
        val title: String? = null,
        val associated: List<Associated>? = null,
        val type: String? = null,
        /** "2018", sometimes "" */
        val year: String? = null,
        val status: String? = null,
        val completed: Boolean? = null,
    )

    @Serializable
    private data class Associated(val title: String? = null)

    companion object {
        private const val API = "https://api.mangaupdates.com/v1"
        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

        /** What a JP manga list holds; Novel / Artbook / Drama CD etc. never match a manga entry. */
        private val MANGA_TYPES = setOf("manga", "manhwa", "manhua", "doujinshi", "oel")

        private val VOLUMES = Regex("""(\d+)\s+(?:[\p{L}-]+\s+)?Volumes?\b""", RegexOption.IGNORE_CASE)
        private val LINE_BREAK = Regex("""\n|<br\s*/?>""", RegexOption.IGNORE_CASE)
        private val TAG = Regex("<[^>]+>")

        private fun clean(text: String) = Parser.unescapeEntities(text.replace(TAG, ""), false).trim()

        fun parseSearch(body: String): List<Candidate> =
            json.decodeFromString<SearchPage>(body).results.orEmpty().map { hit ->
                val r = hit.record
                Candidate(r.seriesId, listOfNotNull(r.title, hit.hitTitle).map(::clean), r.type, r.year?.trim()?.toIntOrNull())
            }

        fun parseSeries(body: String): Series {
            val r = json.decodeFromString<Record>(body)
            val titles = (listOfNotNull(r.title) + r.associated.orEmpty().mapNotNull { it.title }).map(::clean)
            return Series(Candidate(r.seriesId, titles, r.type, r.year?.trim()?.toIntOrNull()), r.status, r.completed == true)
        }

        /** The first line's "<n> [Tankoubon] Volumes"; null for "84 Chapters (Ongoing)", "Oneshot (Complete)", "". */
        fun volumeCount(status: String?): Int? {
            val first = status?.split(LINE_BREAK)?.map(::clean)?.firstOrNull { it.isNotEmpty() } ?: return null
            return VOLUMES.find(first)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
        }

        private fun typeFits(type: String?, entry: AniListLibrary.Entry): Boolean {
            val t = type?.lowercase() ?: return false
            // a light novel on the list must not take its manga adaptation's count (and the other way round)
            return if (entry.format == "NOVEL") t == "novel" else t in MANGA_TYPES
        }

        /**
         * Type fits, one of [Candidate.titles] has exactly the `seriesKey` of the entry's native, romaji or english
         * title, and the start years agree within ±1 when both are known. No fuzzy or contains matching.
         */
        fun confident(c: Candidate, entry: AniListLibrary.Entry): Boolean {
            if (!typeFits(c.type, entry)) return false
            val keys = entry.titles.map(MangaImportUtil::seriesKey).filter { it.isNotEmpty() }.toSet()
            if (c.titles.none { MangaImportUtil.seriesKey(it) in keys }) return false
            val year = entry.year
            return year == null || c.year == null || abs(year - c.year) <= 1
        }

        /** The one confident candidate; of several, the one with the exact year, else none. */
        fun best(candidates: List<Candidate>, entry: AniListLibrary.Entry): Candidate? {
            val ok = candidates.filter { confident(it, entry) }.distinctBy { it.seriesId }
            return ok.singleOrNull() ?: ok.singleOrNull { entry.year != null && it.year == entry.year }
        }
    }
}
