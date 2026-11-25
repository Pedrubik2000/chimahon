package eu.kanade.tachiyomi.ui.glass.title

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.domain.track.model.toDbTrack
import eu.kanade.domain.track.model.toDomainTrack
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.anilist.AniListLibrary
import eu.kanade.tachiyomi.data.coverage.Coverage
import eu.kanade.tachiyomi.data.coverage.SourceCoverage
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.download.LocalSeriesSync
import eu.kanade.tachiyomi.data.download.RealDebridQueue
import eu.kanade.tachiyomi.data.mangadex.VolumeCovers
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.anilist.Anilist
import eu.kanade.tachiyomi.data.track.anilist.AnilistApi
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import tachiyomi.domain.track.model.Track as DomainTrack

/**
 * The glass title page of one AniList media: the cached list entry (works offline and with nothing local), the
 * local-source series bound to it by an AniList track row, MangaDex volume covers, each Real-Debrid source's coverage
 * and the queue rows of this title.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GlassTitleScreenModel(
    val mediaId: Long,
    private val library: AniListLibrary = Injekt.get(),
    private val coverageService: SourceCoverage = Injekt.get(),
    private val queue: RealDebridQueue = Injekt.get(),
    private val volumeCovers: VolumeCovers = Injekt.get(),
    private val trackRepository: TrackRepository = Injekt.get(),
    private val chapterRepository: ChapterRepository = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val getFavorites: GetFavorites = Injekt.get(),
    private val setReadStatus: SetReadStatus = Injekt.get(),
    private val insertTrack: InsertTrack = Injekt.get(),
    private val trackerManager: TrackerManager = Injekt.get(),
    private val trackPreferences: TrackPreferences = Injekt.get(),
    val downloadPreferences: DownloadPreferences = Injekt.get(),
) : ScreenModel {

    /** The local-source series of this title, its AniList track row (null: matched by name, logged out) and its volumes. */
    data class Local(val manga: Manga, val track: DomainTrack?, val chapters: List<Chapter>, val volumes: List<LocalVolume>)

    /** An edit shown before AniList answers; dropped on failure (the control reverts). */
    private val pending = MutableStateFlow<AniListLibrary.Entry?>(null)

    /** The list entry, else what Discover left in [AniListLibrary.remember] (status ""); null when neither knows it. */
    val entry: StateFlow<AniListLibrary.Entry?> = combine(
        flow { emitAll(library.entries) }, // first access reads the cache file: off the main thread
        pending,
    ) { entries, p -> p ?: entries.firstOrNull { it.mediaId == mediaId } ?: library.get(mediaId) }
        .flowOn(Dispatchers.IO)
        .stateIn(screenModelScope, SharingStarted.Eagerly, null)

    /** On Pedro's AniList list (the cache), not only visited from Discover. */
    val onList: Boolean get() = library.entries.value.any { it.mediaId == mediaId }

    /** The fallback link ([resolveLocal]) is written once per open. */
    private var linkTried = false

    private val localFlow = combine(
        trackRepository.getTracksAsFlow()
            .map { tracks -> tracks.filter { it.trackerId == TrackerManager.ANILIST } }
            .distinctUntilChanged(),
        entry.distinctUntilChangedBy { it?.titles },
    ) { tracks, e -> tracks to e }
        .map { (tracks, e) -> resolveLocal(tracks, e) }
        .distinctUntilChanged()
        .flatMapLatest { pair ->
            if (pair == null) {
                flowOf<Local?>(null)
            } else {
                // the files are only re-checked when the chapter list changes, not on every page turn
                var seenKey = emptyList<Pair<String, Long>>()
                var empty = emptySet<String>()
                chapterRepository.getChapterByMangaIdAsFlow(pair.first.id).map { chapters ->
                    val key = chapters.map { it.url to it.dateUpload } // dateUpload = file lastModified: a replaced file changes it
                    if (key != seenKey) {
                        empty = GlassTitleState.emptyUrls(chapters)
                        seenKey = key
                    }
                    val volumes = chapters.filter { it.url !in empty }.mapNotNull { c ->
                        GlassTitleState.volumeNumber(c)?.let { LocalVolume(c.id, it, c.read, c.lastPageRead) }
                    }
                    Local(pair.first, pair.second, chapters, GlassTitleState.dedupe(volumes))
                }
            }
        }
        .flowOn(Dispatchers.IO)

    val local: StateFlow<Local?> = localFlow.stateIn(screenModelScope, SharingStarted.Eagerly, null)

    val rows: StateFlow<List<RealDebridQueue.Row>> = queue.rows
        .map { rows -> rows.filter { it.mediaId == mediaId } }
        .stateIn(screenModelScope, SharingStarted.Eagerly, emptyList())

    /** Enabled Real-Debrid sources in the preferred order. */
    private val _sources = MutableStateFlow<List<HttpSource>>(emptyList())
    val sources: StateFlow<List<HttpSource>> = _sources.asStateFlow()

    private val _coverage = MutableStateFlow<Map<Long, Coverage>>(emptyMap())
    val coverage: StateFlow<Map<Long, Coverage>> = _coverage.asStateFlow()

    /** The source card with the radio on; null = the default ([GlassTitleState.defaultSource]). */
    val chosen = MutableStateFlow<Long?>(null)

    private val _covers = MutableStateFlow<Map<Int, String>>(emptyMap())
    val covers: StateFlow<Map<Int, String>> = _covers.asStateFlow()

    /** Volume → its own first page ([LocalVolumeCovers]), filled in as each is ready; wins over [covers]. */
    private val _localCovers = MutableStateFlow<Map<Int, File>>(emptyMap())
    val localCovers: StateFlow<Map<Int, File>> = _localCovers.asStateFlow()

    /** The last failed AniList edit or fetch, shown quietly; null when none. */
    val message = MutableStateFlow<String?>(null)

    private var coverageJob: Job? = null

    init {
        screenModelScope.launch(Dispatchers.IO) {
            _sources.value = coverageService.sources()
            val e = entry.filterNotNull().first()
            loadCoverage(e, force = false)
            launch { refreshEntry() }
            // a title with every volume on the tablet asks MangaDex nothing
            if (GlassTitleState.needsMangaDex(e.volumes, localFlow.first()?.volumes.orEmpty())) {
                _covers.value = volumeCovers.get(e)
                    .filterKeys { it % 1.0 == 0.0 }
                    .mapKeys { it.key.toInt() }
            }
        }
        // chimahon: a job of this title that ended may have corrected the coverage cache (a pack held less, a link is
        // gone): read it again, so the next fetch dialog plans from the corrected coverage
        screenModelScope.launch {
            var unfinished = emptySet<java.util.UUID>()
            rows.collect { list ->
                val now = list.filter { it.state == RealDebridQueue.State.QUEUED || it.state == RealDebridQueue.State.RUNNING }
                    .mapTo(mutableSetOf()) { it.workId }
                if (!now.containsAll(unfinished)) entry.value?.let { loadCoverage(it, force = false) }
                unfinished = now
            }
        }
        // chapter rows = the folder, once per open (a fetch that landed while the page was closed, files copied in)
        screenModelScope.launch(Dispatchers.IO) {
            LocalSeriesSync.sync(local.filterNotNull().first().manga.id)
        }
        // again only when the set of volume files changes, not when one is read
        screenModelScope.launch {
            local.filterNotNull()
                .map { l ->
                    l.manga.id to l.volumes.mapNotNull { v ->
                        l.chapters.firstOrNull { it.id == v.chapterId }?.let { v.number to it }
                    }.toMap()
                }
                .distinctUntilChangedBy { (mangaId, chapters) -> mangaId to chapters.mapValues { it.value.id } }
                .collectLatest { (mangaId, chapters) ->
                    _localCovers.update { covers -> covers.filterKeys { it in chapters } }
                    LocalVolumeCovers.load(mangaId, chapters) { n, file -> _localCovers.update { it + (n to file) } }
                }
        }
    }

    /**
     * The local series of this title: the one with its AniList track (several: the one with most volumes); else a
     * local library series with no AniList track whose title or folder matches the entry's names
     * ([AniListLibrary.match]), linked to it when logged in so the next open (and the library) finds it by track.
     */
    private suspend fun resolveLocal(aniListTracks: List<DomainTrack>, e: AniListLibrary.Entry?): Pair<Manga, DomainTrack?>? {
        val linked = aniListTracks.filter { it.remoteId == mediaId }
            .mapNotNull { t -> getManga.await(t.mangaId)?.takeIf { it.source == LocalSource.ID }?.let { it to t } }
        if (linked.size > 1) {
            return linked.map { it to chapterRepository.getChapterByMangaId(it.first.id).size }.maxBy { it.second }.first
        }
        linked.firstOrNull()?.let { return it }
        if (e == null) return null
        val tracked = aniListTracks.mapTo(mutableSetOf()) { it.mangaId }
        val manga = getFavorites.await()
            .filter { it.source == LocalSource.ID && it.id !in tracked }
            .firstOrNull { m -> AniListLibrary.match(listOf(m.title, m.url), listOf(e)) != null }
            ?: return null
        if (!linkTried) {
            linkTried = true
            screenModelScope.launch(Dispatchers.IO) {
                runCatching { library.link(manga.id, mediaId, e.romaji ?: e.titles.first(), (e.chapters ?: 0).toLong()) }
                    .onFailure { logcat(LogPriority.WARN, it) { "AniList link failed: ${manga.id} -> $mediaId" } }
            }
        }
        return manga to null
    }

    /**
     * One fetch of this list entry from AniList per open, so "On AniList: Vol. N" is not a stale cache. Logged out,
     * not on the list, an edit in flight or any failure: the cache stays.
     */
    private suspend fun refreshEntry() {
        val anilist = trackerManager.aniList
        if (!anilist.isLoggedIn || !onList) return
        try {
            val probe = Track.create(anilist.id).apply {
                manga_id = 0L
                remote_id = mediaId
            }
            val (remote, volumes) = anilist.discoverApi.findLibMangaWithVolumes(probe, anilist.getUsername().toInt())
                ?: return
            if (pending.value != null) return
            val cached = library.entries.value.firstOrNull { it.mediaId == mediaId } ?: return
            library.replace(
                cached.copy(
                    status = GlassTitleState.listStatus(remote.status) ?: cached.status,
                    progress = remote.last_chapter_read.toInt(),
                    progressVolumes = volumes,
                    score = remote.score.toInt(),
                ),
            )
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            logcat(LogPriority.WARN, ex) { "AniList entry refresh failed: $mediaId" }
        }
    }

    /** "Refresh sources": every enabled source asked again; cards keep their last answer until the new one comes. */
    fun refreshSources() {
        val e = entry.value ?: return
        screenModelScope.launch(Dispatchers.IO) {
            _sources.value = coverageService.sources()
            loadCoverage(e, force = true)
        }
    }

    private fun loadCoverage(e: AniListLibrary.Entry, force: Boolean) {
        coverageJob?.cancel()
        coverageJob = screenModelScope.launch {
            coverageService.forTitle(e, force).collect { c -> _coverage.update { it + (c.sourceId to c) } }
        }
    }

    /** Queues [volumes] (packs once) from [order] ([GlassTitleState.fetchOrder]). False when no source has any. */
    fun fetch(volumes: Collection<Int>, order: List<Coverage>): Boolean {
        val e = entry.value ?: return false
        return queue.enqueueAll(e, volumes, order).isNotEmpty()
    }

    fun cancel(volume: Int) {
        screenModelScope.launch { queue.cancel(mediaId, volume) }
    }

    fun setRead(volume: LocalVolume, read: Boolean) {
        val chapter = local.value?.chapters?.firstOrNull { it.id == volume.chapterId } ?: return
        screenModelScope.launch(Dispatchers.IO) { setReadStatus.await(read, chapter) }
    }

    // AniList edits

    fun setStatus(status: String) = edit(status = status, score = null)

    /** [stars] 1..5; the current star again clears the score. */
    fun setStars(stars: Int) {
        val e = entry.value ?: return
        val raw = when {
            stars == GlassTitleState.stars(e.score) -> 0
            // the tracker's own 5-star mapping when that is the user's format; else plain 20 per star
            trackPreferences.anilistScoreType().get() == Anilist.POINT_5 -> trackerManager.aniList.indexToScore(stars).toInt()
            else -> stars * 20
        }
        edit(status = null, score = raw)
    }

    /** "+ Home": on the list as Plan to read. */
    fun addToHome() = setStatus("PLANNING")

    private fun edit(status: String?, score: Int?) {
        val e = entry.value ?: return
        pending.value = e.copy(status = status ?: e.status, score = score ?: e.score)
        message.value = null
        screenModelScope.launch(Dispatchers.IO) {
            try {
                val track = write(status, score)
                val updated = library.get(mediaId)?.copy(
                    status = GlassTitleState.listStatus(track.status) ?: status ?: e.status,
                    score = track.score.toInt(),
                    updatedAt = System.currentTimeMillis() / 1000,
                )
                if (updated != null) library.replace(updated) else library.refresh()
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                logcat(LogPriority.WARN, ex) { "AniList edit failed: $mediaId" }
                message.value = ex.message ?: ex.javaClass.simpleName
            } finally {
                pending.value = null
            }
        }
    }

    /**
     * One AniList write through the tracker. With a local series its track row is used (and saved again), so progress
     * stays volumes; without one, an in-memory track by media id (manga id 0 = not local, so the tracker sends the
     * chapter progress it just read back from AniList, unchanged). Not on the list yet → the tracker's bind adds it.
     */
    private suspend fun write(status: String?, score: Int?): Track {
        val anilist = trackerManager.aniList
        check(anilist.isLoggedIn) { "Not logged in to AniList" }
        val localTrack = local.value?.track
        val track = localTrack?.toDbTrack() ?: Track.create(anilist.id).apply {
            manga_id = 0L
            remote_id = mediaId
            title = entry.value?.let { it.romaji ?: it.native }.orEmpty()
            tracking_url = AnilistApi.mangaUrl(mediaId)
        }
        if (onList) anilist.refresh(track) else anilist.bind(track, hasReadChapters = false)
        val before = track.status to track.score
        status?.let { track.status = GlassTitleState.trackerStatus(it) }
        score?.let { track.score = it.toDouble() }
        if (before != (track.status to track.score)) anilist.update(track)
        if (localTrack != null) track.toDomainTrack(idRequired = false)?.let { insertTrack.await(it) }
        return track
    }
}
