package eu.kanade.domain.track.interactor

import android.content.Context
import eu.kanade.domain.track.model.toDbTrack
import eu.kanade.domain.track.model.toDomainTrack
import eu.kanade.domain.track.service.DelayedTrackingUpdateJob
import eu.kanade.domain.track.store.DelayedTrackingStore
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.anilist.Anilist
import eu.kanade.tachiyomi.data.track.mdlist.MdList
import exh.md.utils.FollowStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.injectLazy

class TrackChapter(
    private val getTracks: GetTracks,
    private val trackerManager: TrackerManager,
    private val insertTrack: InsertTrack,
    private val delayedTrackingStore: DelayedTrackingStore,
) {
    private val getManga: GetManga by injectLazy()

    /**
     * Fetches updated tracking data from all logged in trackers.
     * Then update chapter progress to all trackers.
     * This does not update local chapters' read status.
     */
    suspend fun await(context: Context, mangaId: Long, chapterNumber: Double, setupJobOnFailure: Boolean = true) {
        withNonCancellableContext {
            val tracks = getTracks.await(mangaId)
            if (tracks.isEmpty()) return@withNonCancellableContext
            // chimahon: local chapters are volumes; AniList gets progressVolumes (it checks AniList's own count,
            // since a tracker refresh puts AniList's chapter progress in lastChapterRead)
            val localVolumes = getManga.await(mangaId)?.source == LocalSource.ID

            tracks.mapNotNull { track ->
                val service = trackerManager.get(track.trackerId)
                val volumes = localVolumes && service is Anilist
                if (
                    service == null ||
                    !service.isLoggedIn ||
                    (!volumes && chapterNumber <= track.lastChapterRead) /* SY --> */ ||
                    (service is MdList && track.status == FollowStatus.UNFOLLOWED.long)/* SY <-- */
                ) {
                    return@mapNotNull null
                }

                async {
                    runCatching {
                        try {
                            val updatedTrack = if (volumes) {
                                (service as Anilist).updateVolumes(track.toDbTrack(), chapterNumber.toInt())
                                    .toDomainTrack(idRequired = true)!!
                            } else {
                                service.refresh(track.toDbTrack())
                                    .toDomainTrack(idRequired = true)!!
                                    .copy(lastChapterRead = chapterNumber)
                                    .also { service.update(it.toDbTrack(), true) }
                            }
                            insertTrack.await(updatedTrack)
                            delayedTrackingStore.remove(track.id)
                        } catch (e: Exception) {
                            delayedTrackingStore.add(track.id, chapterNumber)
                            if (setupJobOnFailure) {
                                DelayedTrackingUpdateJob.setupTask(context)
                            }
                            throw e
                        }
                    }
                }
            }
                .awaitAll()
                .mapNotNull { it.exceptionOrNull() }
                .forEach { logcat(LogPriority.WARN, it) }
        }
    }
}
