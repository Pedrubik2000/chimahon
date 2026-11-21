package eu.kanade.tachiyomi.ui.glass

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.anilist.AniListLibrary
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
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
) : ScreenModel {

    /** null until the first load. */
    val titles: StateFlow<List<GlassTitle>?> = combine(
        flow { emitAll(aniList.entries) }, // first access reads the cache file: keep it off the main thread
        trackRepository.getTracksAsFlow(),
        getLibraryManga.subscribe(),
    ) { entries, tracks, library -> load(entries, tracks, library) }
        .flowOn(Dispatchers.IO)
        // stops 5 s after the UI stops collecting (reader open), so page turns don't re-run this
        .stateIn(screenModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
    var status by mutableStateOf(ListFilter.ALL)
    var here by mutableStateOf<Here?>(null)
    var sort by mutableStateOf(GlassSort.LAST_READ)

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
                    GlassTitles.Local(it.id, it.totalChapters.toInt(), it.readCount.toInt(), it.lastRead)
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
