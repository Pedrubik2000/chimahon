package eu.kanade.tachiyomi.data.download

import com.hippo.unifile.UniFile
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.domain.track.interactor.SyncChapterProgressWithTrack
import eu.kanade.domain.track.model.toDbTrack
import eu.kanade.domain.track.model.toDomainTrack
import eu.kanade.tachiyomi.data.anilist.AniListLibrary
import eu.kanade.tachiyomi.data.anilist.AniListPacing
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.anilist.AnilistApi
import eu.kanade.tachiyomi.ui.browse.source.ImportHandler
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import nl.adaptivity.xmlutil.serialization.XML
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.core.metadata.comicinfo.COMIC_INFO_FILE
import tachiyomi.core.metadata.comicinfo.ComicInfo
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**
 * Chapter rows of a local-source series = the files in its folder: what the classic manga screen's refresh does for a
 * local manga (LocalSource's chapter list → [SyncChaptersWithSource]). The glass screens read chapter rows, so an
 * import or a page open calls this. Failures are logged, never thrown.
 */
object LocalSeriesSync {

    private val locks = ConcurrentHashMap<Long, Mutex>()
    private val allDone = AtomicBoolean(false)

    /** [refreshTracks] false = read marks from the stored track, no AniList call (adoption has just written it). */
    suspend fun sync(mangaId: Long, refreshTracks: Boolean = true) {
        withIOContext { syncLocked(mangaId, refreshTracks) }
    }

    private suspend fun syncLocked(mangaId: Long, refreshTracks: Boolean) {
        locks.getOrPut(mangaId) { Mutex() }.withLock {
            try {
                val manga = Injekt.get<GetManga>().await(mangaId)?.takeIf { it.source == LocalSource.ID }
                    ?: return@withLock
                val source = Injekt.get<SourceManager>().get(LocalSource.ID) ?: return@withLock
                val chapters = source.getMangaUpdate(manga.toSManga(), emptyList(), fetchDetails = false, fetchChapters = true)
                    .chapters
                // an empty listing with rows in the DB is a folder we can't read right now (storage permission lost,
                // card out): syncing would drop every row and its read state
                if (chapters.isEmpty() && Injekt.get<ChapterRepository>().getChapterByMangaId(mangaId).isNotEmpty()) {
                    return@withLock
                }
                Injekt.get<SyncChaptersWithSource>().await(chapters, manga, source)
                // read marks follow the tracker: for a local series AniList's volume count (fresh from AniList)
                pullTracks(mangaId, refreshTracks)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Local series sync failed: $mangaId" }
            }
        }
    }

    /**
     * RefreshTracks' pull and read marks without its push: RefreshTracks also wrote a higher local count back to
     * AniList, an AniList write on a mere page open that bumps the entry's updatedAt (the glass "Last read" key of a
     * title not read here), so opening a title moved it to the front. Reading a volume pushes progress itself.
     */
    private suspend fun pullTracks(mangaId: Long, refresh: Boolean) {
        val trackerManager = Injekt.get<TrackerManager>()
        // as SyncChapterProgressWithTrack counts it: continuous reading only
        val readHere = Injekt.get<GetChaptersByMangaId>().await(mangaId)
            .filter { it.isRecognizedNumber }
            .sortedBy { it.chapterNumber }
            .takeWhile { it.read }
            .lastOrNull()?.chapterNumber ?: 0.0
        for (track in Injekt.get<GetTracks>().await(mangaId)) {
            val tracker = trackerManager.get(track.trackerId)?.takeIf { it.isLoggedIn } ?: continue
            try {
                val remote = if (refresh) {
                    tracker.refresh(track.toDbTrack()).toDomainTrack()?.also { Injekt.get<InsertTrack>().await(it) }
                        ?: continue
                } else {
                    track
                }
                // progress never below what is read here: sync then only marks chapters read, it never writes back
                Injekt.get<SyncChapterProgressWithTrack>()
                    .sync(mangaId, remote.copy(lastChapterRead = maxOf(remote.lastChapterRead, readHere)), tracker)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Track refresh failed: $mangaId" }
            }
        }
    }

    /**
     * The series folders are the truth: every folder of the local source is put (back) in the library and linked to
     * its AniList title ([adopt]); once per app run every local series' volumes are re-read (listing opens each
     * archive for its ComicInfo). So a series removed from the classic library, or one whose import never finished
     * its bookkeeping, still shows as on the tablet. Then, as long as the caller runs (the glass shell), adoption runs
     * again when the list gains or loses titles or the storage folder changes, and after links that failed. AniList
     * allows ~30 requests a minute: its client paces itself ([AniListPacing]), a pass stops at the first 429 and the
     * next waits out its Retry-After, and one pass runs at a time (a trigger during one is conflated into one more).
     */
    suspend fun syncAll() {
        if (allDone.compareAndSet(false, true)) {
            val adopted = AniListPacing.onePass(passing) { adopt() }
            val local = withIOContext {
                runCatching { Injekt.get<GetFavorites>().await().filter { it.source == LocalSource.ID } }
                    .onFailure { logcat(LogPriority.WARN, it) { "Local series sync: library read failed" } }
                    .getOrDefault(emptyList())
            }
            // a series this pass linked has a fresh track: no second AniList call for it
            local.forEach { sync(it.id, refreshTracks = adopted?.linked?.contains(it.id) != true) }
        }
        // the list's current titles count as a change too: the pass above may have run before the list arrived
        merge(
            Injekt.get<AniListLibrary>().entries.map { l -> l.map { it.mediaId }.toSet() }.distinctUntilChanged()
                .map { },
            Injekt.get<StorageManager>().changes,
        )
            .conflate()
            .collect {
                AniListPacing.onePass(passing) {
                    try {
                        // ponytail: three tries; the next trigger (list refresh, next app run) tries again
                        for (i in 0 until 3) {
                            val now = System.currentTimeMillis()
                            delay(AniListPacing.waitBeforePass(i, AniListPacing.blockedUntil, now))
                            val adopted = adopt()
                            adopted.changed.forEach { sync(it, refreshTracks = it !in adopted.linked) }
                            if (adopted.failed == 0) break
                        }
                    } finally {
                        _adopting.value = null
                    }
                }
            }
    }

    private val passing = AtomicBoolean(false)

    private val _adopting = MutableStateFlow<Pair<Int, Int>?>(null)

    /** (linked, of) local series while adoption links folders to AniList, else null: Home's header shows it. */
    val adopting: StateFlow<Pair<Int, Int>?> = _adopting.asStateFlow()

    /**
     * [changed] = local series added to the library or linked by this pass; [linked] = linked by it; [failed] = links
     * that failed, or were not tried after a 429.
     */
    private class Adopted(
        val changed: List<Long> = emptyList(),
        val linked: List<Long> = emptyList(),
        val failed: Int = 0,
    )

    /**
     * Every series folder in the library, and each one without an AniList track linked to its list entry
     * ([AniListLibrary.matchFolder]: the id in its ComicInfo, else its native title, else its names). A folder linked
     * by name gets the id written into its ComicInfo ([writeAniListId]), so the next fresh install links it exactly.
     * Only unlinked folders are read: a pass with nothing new is a folder listing and two queries.
     */
    private suspend fun adopt(): Adopted = withIOContext {
        try {
            val dir = Injekt.get<StorageManager>().getLocalSourceDirectory() ?: return@withIOContext Adopted()
            val folders = dir.listFiles().orEmpty()
                .filter { it.isDirectory && it.name?.startsWith(".") == false }
                .associateBy { it.name!! }
            val getFavorites = Injekt.get<GetFavorites>()
            val before = getFavorites.await().filter { it.source == LocalSource.ID }.map { it.id }.toSet()
            folders.keys.forEach { ImportHandler.addMangaToLibrary(it) }
            val local = getFavorites.await().filter { it.source == LocalSource.ID }
            val anilist = Injekt.get<TrackerManager>().aniList
            val library = Injekt.get<AniListLibrary>()
            val entries = library.entries.value
            val tracks = Injekt.get<GetTracks>().await(local.map { it.id })
            val linked = mutableListOf<Long>()
            var failed = 0
            val unlinked = local.filter { m -> tracks[m.id].orEmpty().none { it.trackerId == anilist.id } }
            // (series, folder, entry, id from its ComicInfo) for every unlinked folder that matches a list entry
            val ready = anilist.isLoggedIn && entries.isNotEmpty()
            val todo = unlinked.takeIf { ready }.orEmpty().mapNotNull { m ->
                val folder = folders[m.url] ?: return@mapNotNull null
                val text = folder.findFile(COMIC_INFO_FILE)?.let { f ->
                    runCatching { f.openInputStream().use { it.readBytes().decodeToString() } }.getOrNull()
                }
                val info = text?.let { runCatching { xml.decodeFromString(ComicInfo.serializer(), it) }.getOrNull() }
                val id = AniListLibrary.aniListId(info?.web?.value)
                val (native, english) = AniListLibrary.summaryTitles(info?.summary?.value)
                val names = listOfNotNull(m.url, m.title, info?.series?.value, english)
                val entry = AniListLibrary.matchFolder(id, native, names, entries) ?: return@mapNotNull null
                Todo(m.id, m.url, m.title, folder, entry, id)
            }
            val done = local.size - unlinked.size
            for ((i, t) in todo.withIndex()) {
                _adopting.value = done + i to done + todo.size
                try {
                    val title = t.entry.romaji ?: t.entry.native ?: t.title
                    val chapters = (t.entry.chapters ?: 0).toLong()
                    if (library.link(t.mangaId, t.entry.mediaId, title, chapters)) linked += t.mangaId
                    if (t.id == null) writeAniListId(t.folder, t.entry.mediaId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed++
                    logcat(LogPriority.WARN, e) { "AniList link failed: ${t.url} -> ${t.entry.mediaId}" }
                    // refused for the rate (a 429 set the wait): the rest waits for the next pass, after Retry-After
                    if (AniListPacing.blockedUntil > System.currentTimeMillis()) {
                        failed += todo.size - i - 1
                        break
                    }
                }
            }
            if (failed == 0) _adopting.value = null
            Adopted((local.map { it.id } - before + linked).distinct(), linked, failed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Local series adopt failed" }
            Adopted()
        }
    }

    /**
     * Writes `<Web>https://anilist.co/manga/<id></Web>` into [dir]'s ComicInfo.xml when it has no Web element: the
     * folder then names its AniList title without depending on names. Nothing when there is no plain ComicInfo.xml
     * (one inside an archive is left alone). Never throws.
     */
    fun writeAniListId(dir: UniFile, mediaId: Long) {
        runCatching {
            val file = dir.findFile(COMIC_INFO_FILE) ?: return
            val text = file.openInputStream().use { it.readBytes().decodeToString() }
            val new = AniListLibrary.withWeb(text, AnilistApi.mangaUrl(mediaId)) ?: return
            // longer than the old text, so also safe where "w" does not truncate (some document providers)
            file.openOutputStream().use { it.write(new.toByteArray()) }
        }.onFailure { logcat(LogPriority.WARN, it) { "AniList id not written: ${dir.name}" } }
    }

    private class Todo(
        val mangaId: Long,
        val url: String,
        val title: String,
        val folder: UniFile,
        val entry: AniListLibrary.Entry,
        val id: Long?,
    )

    private val xml by lazy { Injekt.get<XML>() }
}
