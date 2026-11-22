package eu.kanade.tachiyomi.ui.glass

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.anilist.AniListLibrary
import eu.kanade.tachiyomi.data.anilist.VolumeCounts
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Shared by the glass Home and Library pages; lives as long as [GlassHomeScreen], so Library filters survive tab changes. */
class GlassTitlesModel(
    private val aniList: AniListLibrary = Injekt.get(),
    private val trackRepository: TrackRepository = Injekt.get(),
    private val getLibraryManga: GetLibraryManga = Injekt.get(),
    private val getChaptersByMangaId: GetChaptersByMangaId = Injekt.get(),
    private val volumeCounts: VolumeCounts = Injekt.get(),
) : ScreenModel {

    /** null until the first load. */
    val titles: StateFlow<List<GlassTitle>?> = combine(
        combine(
            flow { emitAll(aniList.entries) }, // first access reads the cache file: keep it off the main thread
            trackRepository.getTracksAsFlow(),
            getLibraryManga.subscribe(),
        ) { entries, tracks, library -> load(entries, tracks, library) },
        // the background filler learns a count every ~2 s: recompute the grid at most once a second
        flow { emitAll(volumeCounts.counts) }.conflate().transform { emit(it); delay(1_000) },
    ) { list, counts -> GlassTitles.withCounts(list, counts) }
        .flowOn(Dispatchers.IO)
        // stops 5 s after the UI stops collecting (reader open), so page turns don't re-run this
        .stateIn(screenModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // volume counts AniList doesn't give (releasing titles), from MangaDex; once per app run, ends with this model
        screenModelScope.launch(Dispatchers.IO) {
            volumeCounts.fill(GlassTitles.fillOrder(titles.filterNotNull().first()))
        }
    }

    val error: StateFlow<String?> get() = aniList.error

    var refreshing by mutableStateOf(false)
        private set

    fun refresh() {
        if (refreshing) return
        refreshing = true
        screenModelScope.launch {
            aniList.refresh()
            refreshing = false
        }
    }

    // Library page state
    var query by mutableStateOf("")
    var filters by mutableStateOf(TitleFilters())
    var sort by mutableStateOf(GlassSort.LAST_READ)

    /** The Library grid and its chip counts, off the main thread; null until the first load. */
    val library: StateFlow<LibraryView?> = combine(
        titles.filterNotNull(),
        snapshotFlow { Triple(query, filters, sort) },
    ) { all, (q, f, s) -> GlassTitles.view(all, q, f, s) }
        .flowOn(Dispatchers.Default)
        .stateIn(screenModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private suspend fun load(
        entries: List<AniListLibrary.Entry>,
        tracks: List<Track>,
        library: List<LibraryManga>,
    ): List<GlassTitle> {
        val local = library.filter { it.manga.source == LocalSource.ID }.associateBy { it.id }
        val linked = tracks
            .filter { it.trackerId == TrackerManager.ANILIST }
            .mapNotNull { t -> local[t.mangaId]?.let { t.remoteId to it } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, ms) ->
                ms.maxBy { it.totalChapters }.let {
                    // every chapter's number (one query per linked series, ~15): volumes here count only 1..total
                    val numbers = getChaptersByMangaId.await(it.id).map { c -> c.chapterNumber }
                    GlassTitles.Local(it.id, it.totalChapters.toInt(), it.readCount.toInt(), it.lastRead, numbers)
                }
            }
        val list = GlassTitles.build(entries, linked)

        // ponytail: the next volume is read for the hero only (one chapter query); per title if a screen needs it
        val candidates = GlassTitles.heroCandidates(list) { t ->
            t.localMangaId?.let { local[it] }?.let { it.readCount < it.totalChapters } == true
        }
        for (c in candidates) {
            val manga = local.getValue(c.localMangaId!!).manga
            val chapter = getChaptersByMangaId.await(manga.id, applyFilter = true)
                .sortedWith(getChapterSort(manga, sortDescending = false))
                .firstOrNull { !it.read }
                ?: continue
            val next = NextVolume(chapter.id, chapter.name, chapter.chapterNumber, chapter.lastPageRead)
            return list.map { if (it.mediaId == c.mediaId) it.copy(next = next) else it }
        }
        return list
    }
}
