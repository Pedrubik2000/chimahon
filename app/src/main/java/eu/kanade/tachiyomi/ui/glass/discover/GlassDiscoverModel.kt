package eu.kanade.tachiyomi.ui.glass.discover

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.data.anilist.AniListLibrary
import eu.kanade.tachiyomi.data.anilist.AniListLibrary.Entry
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.random.Random

enum class DiscoverTab { PICK, DUEL, BROWSE }

/** LENGTH group: AniList volumes_greater / volumes_lesser (both exclusive). */
enum class Length(val gt: Int?, val lt: Int?) { SHORT(null, 6), MEDIUM(5, 16), LONG(15, 31), VERY_LONG(30, null) }

enum class MyList { HIDE, ONLY_PLANNING }

/** Browse sidebar + search. [status] = AniList MediaStatus (FINISHED / RELEASING). */
data class BrowseFilters(
    val search: String = "",
    val genres: Set<String> = emptySet(),
    val status: String? = null,
    val length: Length? = null,
    val myList: MyList? = null,
    val tagsIn: Set<String> = emptySet(),
    val tagsOut: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = this == BrowseFilters()

    /** Tag chip cycle: off → include → exclude → off. */
    fun cycleTag(tag: String): BrowseFilters = when (tag) {
        in tagsIn -> copy(tagsIn = tagsIn - tag, tagsOut = tagsOut + tag)
        in tagsOut -> copy(tagsOut = tagsOut - tag)
        else -> copy(tagsIn = tagsIn + tag)
    }

    fun variables(page: Int, adultAllowed: Boolean): JsonObject = buildJsonObject {
        put("page", page)
        putJsonArray("sort") {
            if (search.isNotBlank()) add("SEARCH_MATCH")
            add("POPULARITY_DESC")
        }
        if (search.isNotBlank()) put("search", search.trim())
        if (genres.isNotEmpty()) putJsonArray("genres") { genres.forEach { add(it) } }
        if (tagsIn.isNotEmpty()) putJsonArray("tagIn") { tagsIn.forEach { add(it) } }
        if (tagsOut.isNotEmpty()) putJsonArray("tagNotIn") { tagsOut.forEach { add(it) } }
        status?.let { put("status", it) }
        length?.gt?.let { put("volGt", it) }
        length?.lt?.let { put("volLt", it) }
        if (!adultAllowed) put("isAdult", false)
        when (myList) {
            MyList.HIDE -> put("onList", false)
            MyList.ONLY_PLANNING -> put("onList", true)
            null -> Unit
        }
    }

    companion object {
        /** AniList's manga genres without Hentai. */
        val GENRES = listOf(
            "Action", "Adventure", "Comedy", "Drama", "Ecchi", "Fantasy", "Horror", "Mahou Shoujo", "Mecha", "Music",
            "Mystery", "Psychological", "Romance", "Sci-Fi", "Slice of Life", "Sports", "Supernatural", "Thriller",
        )
    }
}

data class BrowseState(
    val filters: BrowseFilters,
    val entries: List<Entry> = emptyList(),
    /** last page loaded, 0 = none yet */
    val page: Int = 0,
    val hasNext: Boolean = true,
    val loading: Boolean = false,
)

/** AniList recommendations of the top completed titles: [by] = recommended id → title it came from. */
data class Recs(val by: Map<Long, String> = emptyMap(), val extra: List<Entry> = emptyList())

/** Discover page state; lives as long as the glass shell (created on the first Discover visit). */
class GlassDiscoverModel(
    private val aniList: AniListLibrary = Injekt.get(),
    private val trackerManager: TrackerManager = Injekt.get(),
    private val uiPreferences: UiPreferences = Injekt.get(),
) : ScreenModel {

    private val random = Random.Default

    /** One AniList request at a time (rate limit 30/min). */
    private val gate = Mutex()

    val loggedIn: Boolean get() = trackerManager.aniList.isLoggedIn

    /** For the pages: signing in from the card redraws them. */
    val loggedInState: StateFlow<Boolean> = trackerManager.aniList.isLoggedInFlow
        .stateIn(screenModelScope, SharingStarted.Eagerly, trackerManager.aniList.isLoggedIn)

    var tab by mutableStateOf(DiscoverTab.PICK)

    /** Quiet line for the last failed AniList call; what is shown stays. */
    var error by mutableStateOf<String?>(null)
        private set

    val entries: StateFlow<List<Entry>?> = flow { emitAll(aniList.entries) } // first access reads the cache file
        .flowOn(Dispatchers.IO)
        .stateIn(screenModelScope, SharingStarted.Eagerly, null)

    private val recs = MutableStateFlow(Recs())
    val recommendedBy: StateFlow<Map<Long, String>> = recs.map { it.by }
        .stateIn(screenModelScope, SharingStarted.Eagerly, emptyMap())

    private val notForMePref = uiPreferences.discoverNotForMe()
    val hidden: StateFlow<Set<Long>> = notForMePref.changes()
        .map { s -> s.mapNotNullTo(HashSet()) { it.toLongOrNull() } }
        .stateIn(screenModelScope, SharingStarted.Eagerly, emptySet())

    /** null until the list cache is read; recommendations join the candidates when the list has fewer than 20. */
    val ranking: StateFlow<NextVolumeRanking?> = combine(entries, recs) { list, r ->
        list?.let {
            val nv = NextVolumeRanking(it)
            if (nv.ranked.size < 20 && r.extra.isNotEmpty()) NextVolumeRanking(it, r.extra) else nv
        }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(screenModelScope, SharingStarted.Eagerly, null)

    // Pick for me
    var pickId by mutableStateOf<Long?>(null)
        private set
    var pickCount by mutableStateOf(0)
        private set
    var adding by mutableStateOf(emptySet<Long>())
        private set

    /** A pick when there is none (first visit, or the shown one left the candidates). */
    fun ensurePick() {
        val nv = ranking.value ?: return
        if (nv.ranked.none { it.mediaId == pickId && it.mediaId !in hidden.value }) next()
    }

    fun next() {
        val nv = ranking.value ?: return
        pickId = nv.pick(hidden.value, pickId, random)?.mediaId
        if (pickId != null) pickCount++
    }

    fun notForMe(mediaId: Long) {
        notForMePref.set(notForMePref.get() + mediaId.toString())
        // the pref flow updates later: pick now without it
        val nv = ranking.value ?: return
        pickId = nv.pick(hidden.value + mediaId, mediaId, random)?.mediaId
        if (pickId != null) pickCount++
    }

    /** "+ Home": Plan to read on AniList, then the list cache is refreshed. */
    fun addHome(entry: Entry) {
        val id = entry.mediaId
        if (id in adding) return
        adding = adding + id
        screenModelScope.launch {
            gate.withLock {
                runCatching { trackerManager.aniList.discoverApi.savePlanning(id) }
                    .onSuccess {
                        error = null
                        aniList.refresh()
                    }
                    .onFailure { fail(it, "AniList + Home failed") }
            }
            adding = adding - id
        }
    }

    // Recommendations
    private var recsAsked = false

    /** Once per model: AniList recommendations of the 5 best-scored completed titles. */
    fun loadRecs() {
        val list = entries.value ?: return
        if (recsAsked || !loggedIn) return
        val top = list.filter { (it.status == "COMPLETED" || it.status == "REPEATING") && it.score > 0 }
            .sortedByDescending { it.score }
            .take(5)
        if (top.isEmpty()) return
        recsAsked = true
        screenModelScope.launch {
            gate.withLock {
                runCatching { parseRecs(trackerManager.aniList.discoverApi.mangaRecommendations(top.map { it.mediaId })) }
                    .onSuccess { (pairs, adult) ->
                        adultAllowed = adult ?: adultAllowed
                        val names = top.associate { it.mediaId to NextVolumeRanking.name(it) }
                        val best = pairs.sortedByDescending { it.rating }.distinctBy { it.entry.mediaId }
                        recs.value = Recs(
                            by = best.mapNotNull { p -> names[p.from]?.let { p.entry.mediaId to it } }.toMap(),
                            extra = best.filter {
                                it.entry.status.isEmpty() && it.country == "JP" &&
                                    it.entry.format in setOf("MANGA", "ONE_SHOT") && (it.adult != true || adultAllowed == true)
                            }.map { it.entry },
                        )
                    }
                    .onFailure { fail(it, "AniList recommendations failed") }
            }
        }
    }

    // This or that
    var duel by mutableStateOf<Duel?>(null)
        private set

    fun startDuel() {
        duel = ranking.value?.duel(hidden.value, random)
    }

    fun choose(first: Boolean) {
        duel = duel?.choose(first)
    }

    // Browse
    var filters by mutableStateOf(BrowseFilters())
    var browse by mutableStateOf(BrowseState(BrowseFilters()))
        private set

    // ponytail: in memory, unbounded; a few dozen filter sets per session at most
    private val cache = HashMap<BrowseFilters, BrowseState>()
    private var adultAllowed: Boolean? = null
    private var browseStarted = false

    fun startBrowse() {
        if (browseStarted) return
        browseStarted = true
        screenModelScope.launch {
            snapshotFlow { filters }.debounce(400).collectLatest { f ->
                cache[f]?.let {
                    browse = it
                    return@collectLatest
                }
                browse = BrowseState(f, loading = true)
                loadPage(f, 1)
            }
        }
    }

    fun loadMore() {
        val b = browse
        if (b.loading || !b.hasNext || b.page == 0 || b.page >= 20) return
        browse = b.copy(loading = true)
        screenModelScope.launch { loadPage(b.filters, b.page + 1) }
    }

    private suspend fun loadPage(f: BrowseFilters, page: Int) {
        val result = gate.withLock {
            runCatching {
                parseBrowse(trackerManager.aniList.discoverApi.browseManga(f.variables(page, adultAllowed == true)))
            }
        }
        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        val cur = browse
        if (cur.filters != f) return
        result
            .onSuccess { p ->
                adultAllowed = p.adultAllowed ?: adultAllowed
                val got = if (f.myList == MyList.ONLY_PLANNING) p.entries.filter { it.status == "PLANNING" } else p.entries
                val s = cur.copy(
                    entries = (cur.entries + got).distinctBy { it.mediaId },
                    page = page,
                    hasNext = p.hasNext,
                    loading = false,
                )
                browse = s
                cache[f] = s
                error = null
            }
            .onFailure {
                // no retry on scroll: changing a filter asks again
                browse = cur.copy(page = maxOf(cur.page, 1), hasNext = false, loading = false)
                fail(it, "AniList browse failed")
            }
    }

    private fun fail(e: Throwable, what: String) {
        if (e is CancellationException) throw e
        logcat(LogPriority.WARN, e) { what }
        error = e.message ?: e.javaClass.simpleName
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        class BrowsePage(val entries: List<Entry>, val hasNext: Boolean, val adultAllowed: Boolean?)

        class RecPair(val from: Long, val rating: Int, val entry: Entry, val country: String?, val adult: Boolean?)

        fun parseBrowse(body: String): BrowsePage {
            val d = json.decodeFromString<DResponse>(body).data
            return BrowsePage(
                entries = d.page?.media.orEmpty().map { it.toEntry() },
                hasNext = d.page?.pageInfo?.hasNextPage == true,
                adultAllowed = d.viewer?.options?.displayAdultContent,
            )
        }

        fun parseRecs(body: String): Pair<List<RecPair>, Boolean?> {
            val d = json.decodeFromString<DResponse>(body).data
            val pairs = d.page?.media.orEmpty().flatMap { m ->
                m.recommendations?.nodes.orEmpty().mapNotNull { n ->
                    n.mediaRecommendation?.let { RecPair(m.id, n.rating ?: 0, it.toEntry(), it.countryOfOrigin, it.isAdult) }
                }
            }
            return pairs to d.viewer?.options?.displayAdultContent
        }
    }
}

/** status = the viewer's list status, "" when not on the list. */
private fun DMedia.toEntry() = Entry(
    mediaId = id,
    status = mediaListEntry?.status ?: "",
    native = title?.native,
    romaji = title?.romaji,
    english = title?.english,
    cover = coverImage?.large,
    coverLarge = coverImage?.extraLarge,
    banner = bannerImage,
    volumes = volumes,
    chapters = chapters,
    mediaStatus = status,
    format = format,
    genres = genres.orEmpty(),
    tags = tags.orEmpty().map {
        AniListLibrary.Tag(it.name, it.rank ?: 0, it.isMediaSpoiler == true || it.isGeneralSpoiler == true)
    },
    averageScore = averageScore,
    popularity = popularity,
    year = startDate?.year,
)

// The Discover responses, as far as they are read

@Serializable
private class DResponse(val data: DData)

@Serializable
private class DData(
    @SerialName("Page") val page: DPage? = null,
    @SerialName("Viewer") val viewer: DViewer? = null,
)

@Serializable
private class DPage(val pageInfo: DPageInfo? = null, val media: List<DMedia> = emptyList())

@Serializable
private class DPageInfo(val hasNextPage: Boolean = false)

@Serializable
private class DViewer(val options: DOptions? = null)

@Serializable
private class DOptions(val displayAdultContent: Boolean? = null)

@Serializable
private class DMedia(
    val id: Long,
    val title: DTitle? = null,
    val coverImage: DCover? = null,
    val bannerImage: String? = null,
    val volumes: Int? = null,
    val chapters: Int? = null,
    val status: String? = null,
    val format: String? = null,
    val genres: List<String>? = null,
    val tags: List<DTag>? = null,
    val averageScore: Int? = null,
    val popularity: Int? = null,
    val startDate: DDate? = null,
    val isAdult: Boolean? = null,
    val countryOfOrigin: String? = null,
    val mediaListEntry: DListEntry? = null,
    val recommendations: DRecs? = null,
)

@Serializable
private class DTitle(val native: String? = null, val romaji: String? = null, val english: String? = null)

@Serializable
private class DCover(val large: String? = null, val extraLarge: String? = null)

@Serializable
private class DTag(
    val name: String,
    val rank: Int? = null,
    val isMediaSpoiler: Boolean? = null,
    val isGeneralSpoiler: Boolean? = null,
)

@Serializable
private class DDate(val year: Int? = null)

@Serializable
private class DListEntry(val status: String? = null)

@Serializable
private class DRecs(val nodes: List<DRec> = emptyList())

@Serializable
private class DRec(val rating: Int? = null, val mediaRecommendation: DMedia? = null)
