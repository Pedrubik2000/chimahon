package eu.kanade.tachiyomi.data.mangadex

import android.app.Application
import eu.kanade.tachiyomi.data.anilist.AniListLibrary
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import logcat.LogPriority
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.injectLazy
import java.io.File
import kotlin.time.Duration.Companion.days

/**
 * Volume covers of an AniList title from MangaDex (no account): the MangaDex manga whose `links.al` is the AniList id
 * (no such hit = no covers, never a guess), then its covers by volume. Cached per AniList id under filesDir.
 */
class VolumeCovers(private val app: Application) {

    @Serializable
    private data class Saved(val fetchedAt: Long, val mangaDexId: String? = null, val covers: Map<Double, String> = emptyMap())

    private val network: NetworkHelper by injectLazy()

    private fun file(mediaId: Long) = File(app.filesDir, "anilist/covers/$mediaId.json")

    /** Volume number → cover url (256 px). Cached for 30 days; [force] fetches again. Empty when MangaDex has none. */
    suspend fun get(entry: AniListLibrary.Entry, force: Boolean = false): Map<Double, String> = withIOContext {
        val file = file(entry.mediaId)
        val cached = runCatching { json.decodeFromString<Saved>(file.readText()) }.getOrNull()
        if (!force && cached != null && System.currentTimeMillis() - cached.fetchedAt < MAX_AGE) {
            return@withIOContext cached.covers
        }
        try {
            val saved = fetch(entry)
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.encodeToString(saved))
            tmp.renameTo(file)
            saved.covers
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "MangaDex covers failed: ${entry.mediaId}" }
            cached?.covers.orEmpty()
        }
    }

    private suspend fun fetch(entry: AniListLibrary.Entry): Saved {
        val mangaDexId = listOfNotNull(entry.romaji, entry.english, entry.native).distinct()
            .firstNotNullOfOrNull { title ->
                val url = "$API/manga".toHttpUrl().newBuilder()
                    .addQueryParameter("title", title)
                    .addQueryParameter("limit", "10")
                    .addQueryParameter("order[relevance]", "desc")
                    .build()
                matchMangaId(text(GET(url)), entry.mediaId)
            }
            ?: return Saved(System.currentTimeMillis())
        val covers = mutableListOf<Cover>()
        var offset = 0
        do {
            val url = "$API/cover".toHttpUrl().newBuilder()
                .addQueryParameter("manga[]", mangaDexId)
                .addQueryParameter("limit", "100")
                .addQueryParameter("offset", offset.toString())
                .addQueryParameter("order[volume]", "asc")
                .build()
            val page = parseCovers(text(GET(url)))
            covers += page.covers
            offset += 100
        } while (offset < page.total && page.covers.isNotEmpty())
        return Saved(System.currentTimeMillis(), mangaDexId, volumeCovers(mangaDexId, covers))
    }

    private suspend fun text(request: Request) =
        network.client.newCall(request).awaitSuccess().use { it.body.string() }

    data class Cover(val volume: String?, val locale: String?, val fileName: String)

    data class CoverPage(val total: Int, val covers: List<Cover>)

    companion object {
        private const val API = "https://api.mangadex.org"
        private val MAX_AGE = 30.days.inWholeMilliseconds
        private val json = Json { ignoreUnknownKeys = true }

        private fun JsonObject.str(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull

        /** Id of the `GET /manga` hit whose `attributes.links.al` is [aniListId], or null. */
        fun matchMangaId(body: String, aniListId: Long): String? =
            json.parseToJsonElement(body).jsonObject["data"]?.jsonArray.orEmpty()
                .map { it.jsonObject }
                .firstOrNull { manga ->
                    // links is null, or [] when empty
                    val links = manga["attributes"]?.jsonObject?.get("links") as? JsonObject
                    links?.str("al") == aniListId.toString()
                }
                ?.str("id")

        fun parseCovers(body: String): CoverPage {
            val root = json.parseToJsonElement(body).jsonObject
            val covers = root["data"]?.jsonArray.orEmpty().mapNotNull { element ->
                val attributes = element.jsonObject["attributes"]?.jsonObject ?: return@mapNotNull null
                val fileName = attributes.str("fileName") ?: return@mapNotNull null
                Cover(attributes.str("volume"), attributes.str("locale"), fileName)
            }
            return CoverPage(root["total"]?.jsonPrimitive?.int ?: covers.size, covers)
        }

        /** Volume → 256 px cover url; a volume with several covers takes the Japanese one, else the first. */
        fun volumeCovers(mangaDexId: String, covers: List<Cover>): Map<Double, String> =
            covers.groupBy { it.volume?.toDoubleOrNull() }
                .filterKeys { it != null }
                .map { (volume, list) ->
                    val cover = list.firstOrNull { it.locale == "ja" } ?: list.first()
                    volume!! to "https://uploads.mangadex.org/covers/$mangaDexId/${cover.fileName}.256.jpg"
                }
                .toMap()
                .toSortedMap()
    }
}
