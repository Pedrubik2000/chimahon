package eu.kanade.tachiyomi.data.anilist

import android.app.Application
import eu.kanade.domain.track.model.toDomainTrack
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.anilist.AnilistApi
import eu.kanade.tachiyomi.ui.browse.source.MangaImportUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.injectLazy
import java.io.File

/**
 * Pedro's AniList manga list (one `MediaListCollection` request), kept as one JSON file in filesDir and in memory.
 * A title on the tablet is bound to its AniList entry by a normal AniList track row ([link]).
 */
class AniListLibrary(private val app: Application) {

    @Serializable
    data class Tag(val name: String, val rank: Int = 0, val spoiler: Boolean = false)

    @Serializable
    data class Entry(
        val mediaId: Long,
        /** AniList list status: CURRENT, PLANNING, COMPLETED, PAUSED, DROPPED, REPEATING */
        val status: String,
        val progress: Int = 0,
        val progressVolumes: Int = 0,
        /** 0-100 */
        val score: Int = 0,
        /** epoch seconds */
        val updatedAt: Long = 0,
        val native: String? = null,
        val romaji: String? = null,
        val english: String? = null,
        val cover: String? = null,
        val coverLarge: String? = null,
        val banner: String? = null,
        val volumes: Int? = null,
        val chapters: Int? = null,
        /** publishing status: FINISHED, RELEASING, ... */
        val mediaStatus: String? = null,
        val format: String? = null,
        val genres: List<String> = emptyList(),
        val tags: List<Tag> = emptyList(),
        val averageScore: Int? = null,
        val popularity: Int? = null,
        val year: Int? = null,
    ) {
        val titles: List<String> get() = listOfNotNull(native, romaji, english)
    }

    @Serializable
    private data class Saved(val refreshedAt: Long = 0, val entries: List<Entry> = emptyList())

    private val trackerManager: TrackerManager by injectLazy()
    private val getFavorites: GetFavorites by injectLazy()
    private val getTracks: GetTracks by injectLazy()
    private val insertTrack: InsertTrack by injectLazy()

    private val file get() = File(app.filesDir, "anilist/list.json")

    // ponytail: first use reads the file on the caller's thread (~300 KB); move to a load() at app start if it shows
    private val saved by lazy {
        runCatching { json.decodeFromString<Saved>(file.readText()) }.getOrDefault(Saved())
    }
    private val _entries by lazy { MutableStateFlow(saved.entries) }
    private val _refreshedAt by lazy { MutableStateFlow(saved.refreshedAt) }
    private val _error = MutableStateFlow<String?>(null)

    val entries: StateFlow<List<Entry>> get() = _entries.asStateFlow()

    /** Epoch millis of the last successful refresh, 0 = never. */
    val refreshedAt: StateFlow<Long> get() = _refreshedAt.asStateFlow()

    /** Message of the last failed refresh, null after a good one. The old list stays. */
    val error: StateFlow<String?> = _error.asStateFlow()

    fun get(mediaId: Long): Entry? = _entries.value.firstOrNull { it.mediaId == mediaId }

    /** Fetch → save → emit. On failure the cached list stays and [error] says why. */
    suspend fun refresh(): Result<Unit> = withIOContext {
        runCatching {
            val anilist = trackerManager.aniList
            check(anilist.isLoggedIn) { "Not logged in to AniList" }
            val list = parseList(anilist.fetchMangaList())
            val now = System.currentTimeMillis()
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.encodeToString(Saved(now, list)))
            check(tmp.renameTo(file)) { "Can't write ${file.name}" }
            _entries.value = list
            _refreshedAt.value = now
            _error.value = null
        }.onFailure {
            logcat(LogPriority.WARN, it) { "AniList list refresh failed" }
            _error.value = it.message ?: it.javaClass.simpleName
        }
    }

    /**
     * Binds a local manga to an AniList media: a normal AniList track row, made through the tracker's bind, so the
     * title is also on Pedro's AniList list (added as Plan to read when it wasn't). Nothing when logged out or when the
     * manga already has an AniList track. lastChapterRead = AniList's volume count (local chapters are volumes).
     * True when a track row was written.
     */
    suspend fun link(mangaId: Long, mediaId: Long, title: String, chapters: Long): Boolean = withIOContext {
        val anilist = trackerManager.aniList
        if (!anilist.isLoggedIn) return@withIOContext false
        if (getTracks.await(mangaId).any { it.trackerId == anilist.id }) return@withIOContext false
        val track = Track.create(anilist.id).apply {
            manga_id = mangaId
            remote_id = mediaId
            this.title = title
            total_chapters = chapters
            tracking_url = AnilistApi.mangaUrl(mediaId)
        }
        anilist.bind(track, hasReadChapters = false) // local manga: bind sets lastChapterRead = live progressVolumes
        insertTrack.await(track.toDomainTrack(idRequired = false)!!)
        true
    }

    data class LinkProposal(val mangaId: Long, val mangaTitle: String, val entry: Entry)

    /** [matches] to confirm; [unmatched] = titles of local series with no AniList track and no title match. */
    data class LinkProposals(val matches: List<LinkProposal>, val unmatched: List<String>)

    /** For every favourite local series without an AniList track: the cached entry whose title matches. */
    suspend fun proposeLinks(): LinkProposals = withIOContext {
        val anilistId = trackerManager.aniList.id
        val local = getFavorites.await().filter { it.source == LocalSource.ID }
        val tracks = getTracks.await(local.map { it.id })
        val todo = local.filter { m -> tracks[m.id].orEmpty().none { it.trackerId == anilistId } }
        val list = _entries.value
        val matched = todo.map { m -> m to match(listOf(m.title, m.url), list) }
        LinkProposals(
            matches = matched.mapNotNull { (m, e) -> e?.let { LinkProposal(m.id, m.title, it) } },
            unmatched = matched.filter { it.second == null }.map { it.first.title },
        )
    }

    /** Applies confirmed (mangaId, mediaId) pairs; returns how many got a track row. */
    suspend fun applyLinks(pairs: List<Pair<Long, Long>>): Int = pairs.count { (mangaId, mediaId) ->
        val entry = get(mediaId) ?: return@count false
        runCatching {
            link(mangaId, mediaId, entry.romaji ?: entry.titles.first(), (entry.chapters ?: 0).toLong())
        }.onFailure { logcat(LogPriority.WARN, it) { "AniList link failed: $mangaId -> $mediaId" } }.getOrDefault(false)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Entries of a `MediaListCollection` response; custom lists are skipped (they repeat entries). */
        fun parseList(body: String): List<Entry> =
            json.decodeFromString<ListResponse>(body).data.collection.lists
                .filterNot { it.isCustomList }
                .flatMap { it.entries }
                .distinctBy { it.media.id }
                .map { e ->
                    val m = e.media
                    Entry(
                        mediaId = m.id,
                        status = e.status,
                        progress = e.progress ?: 0,
                        progressVolumes = e.progressVolumes ?: 0,
                        score = e.score?.toInt() ?: 0,
                        updatedAt = e.updatedAt ?: 0,
                        native = m.title?.native,
                        romaji = m.title?.romaji,
                        english = m.title?.english,
                        cover = m.coverImage?.large,
                        coverLarge = m.coverImage?.extraLarge,
                        banner = m.bannerImage,
                        volumes = m.volumes,
                        chapters = m.chapters,
                        mediaStatus = m.status,
                        format = m.format,
                        genres = m.genres.orEmpty(),
                        tags = m.tags.orEmpty().map {
                            Tag(it.name, it.rank ?: 0, it.isMediaSpoiler == true || it.isGeneralSpoiler == true)
                        },
                        averageScore = m.averageScore,
                        popularity = m.popularity,
                        year = m.startDate?.year,
                    )
                }

        /**
         * The entry one of [names] (a local series' title, its folder name) belongs to: same [MangaImportUtil.seriesKey]
         * as the entry's native, romaji or english title. The names are also tried without volume marks.
         */
        fun match(names: List<String>, entries: List<Entry>): Entry? {
            val keys = names.flatMap { listOf(it, MangaImportUtil.seriesTitle(it)) }
                .map { MangaImportUtil.seriesKey(it) }
                .filter { it.isNotEmpty() }
                .toSet()
            return entries.firstOrNull { e -> e.titles.any { MangaImportUtil.seriesKey(it) in keys } }
        }
    }
}

// The response, as far as it is read

@Serializable
private class ListResponse(val data: ListData)

@Serializable
private class ListData(@SerialName("MediaListCollection") val collection: ListCollection)

@Serializable
private class ListCollection(val lists: List<ListGroup> = emptyList())

@Serializable
private class ListGroup(val isCustomList: Boolean = false, val entries: List<ListEntry> = emptyList())

@Serializable
private class ListEntry(
    val status: String,
    val progress: Int? = null,
    val progressVolumes: Int? = null,
    val score: Double? = null,
    val updatedAt: Long? = null,
    val media: ListMedia,
)

@Serializable
private class ListMedia(
    val id: Long,
    val title: ListTitle? = null,
    val coverImage: ListCover? = null,
    val bannerImage: String? = null,
    val volumes: Int? = null,
    val chapters: Int? = null,
    val status: String? = null,
    val format: String? = null,
    val genres: List<String>? = null,
    val tags: List<ListTag>? = null,
    val averageScore: Int? = null,
    val popularity: Int? = null,
    val startDate: ListDate? = null,
)

@Serializable
private class ListTitle(val native: String? = null, val romaji: String? = null, val english: String? = null)

@Serializable
private class ListCover(val large: String? = null, val extraLarge: String? = null)

@Serializable
private class ListTag(
    val name: String,
    val rank: Int? = null,
    val isMediaSpoiler: Boolean? = null,
    val isGeneralSpoiler: Boolean? = null,
)

@Serializable
private class ListDate(val year: Int? = null)
