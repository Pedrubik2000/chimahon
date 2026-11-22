package eu.kanade.tachiyomi.data.download

import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.domain.track.interactor.RefreshTracks
import eu.kanade.tachiyomi.data.anilist.AniListLibrary
import eu.kanade.tachiyomi.ui.browse.source.ImportHandler
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
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

    suspend fun sync(mangaId: Long) {
        withIOContext { syncLocked(mangaId) }
    }

    private suspend fun syncLocked(mangaId: Long) {
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
                Injekt.get<RefreshTracks>().await(mangaId, enhancedTrackersOnly = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Local series sync failed: $mangaId" }
            }
        }
    }

    /**
     * The series folders are the truth: every folder of the local source is put (back) in the library, linked to its
     * AniList title when its name matches one on the list, and its volumes re-read. So a series removed from the
     * classic library, or one whose import never finished its bookkeeping, still shows as on the tablet.
     * Once per app run (listing opens each archive for its ComicInfo).
     */
    suspend fun syncAll() {
        if (!allDone.compareAndSet(false, true)) return
        withIOContext {
            runCatching {
                val folders = Injekt.get<StorageManager>().getLocalSourceDirectory()?.listFiles().orEmpty()
                    .filter { it.isDirectory && it.name?.startsWith(".") == false }
                    .mapNotNull { it.name }
                folders.forEach { ImportHandler.addMangaToLibrary(it) }
                val library = Injekt.get<AniListLibrary>()
                library.applyLinks(library.proposeLinks().matches.map { it.mangaId to it.entry.mediaId })
            }.onFailure { logcat(LogPriority.WARN, it) { "Local series adopt failed" } }
        }
        val local = withIOContext {
            runCatching { Injekt.get<GetFavorites>().await().filter { it.source == LocalSource.ID } }
                .onFailure { logcat(LogPriority.WARN, it) { "Local series sync: library read failed" } }
                .getOrDefault(emptyList())
        }
        local.forEach { sync(it.id) }
    }
}
