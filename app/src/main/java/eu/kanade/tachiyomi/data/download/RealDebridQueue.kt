package eu.kanade.tachiyomi.data.download

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import eu.kanade.tachiyomi.data.anilist.AniListLibrary
import eu.kanade.tachiyomi.data.coverage.Coverage
import eu.kanade.tachiyomi.data.coverage.CoverageMatch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.util.UUID

/**
 * Real-Debrid downloads by AniList title and volume: queues [RealDebridImportJob]s from a title's coverage and turns
 * their WorkInfo (tags + progress + output) into rows. No table: WorkManager holds the state.
 *
 * Jobs of one queue are chained, and WorkManager fails or cancels everything behind a work that fails or is
 * cancelled. So a job never fails (an error ends as success with [RealDebridImportJob.KEY_ERROR] in its output), and
 * cancelling one job never goes through WorkManager: its id goes in a persisted set, the job sees it when it starts
 * or while it downloads and ends itself as success with the error [CANCELLED].
 */
class RealDebridQueue(private val app: Application) {

    enum class State { QUEUED, RUNNING, FAILED, DONE }

    data class Row(
        val workId: UUID,
        val mediaId: Long?,
        /** The volumes the job was queued for (all a pack's label promises). */
        val volumes: List<Int>,
        val sourceId: Long?,
        val state: State,
        /** [RealDebridImportJob.STEP_WAITING] ... while running. */
        val step: String?,
        val bytes: Long,
        val total: Long,
        /** Bytes per second, 0 when unknown. */
        val speed: Long,
        /** Why it failed; [CANCELLED] when cancelled. */
        val reason: String?,
        /** The source whose link worked (a fallback's when the first was dead); while running, the one being fetched. */
        val servedBy: Long?,
        /** "<source id>|<reason>" per dead link tried. */
        val failures: List<String>,
        /** Of [volumes], those on the tablet when the job ended. */
        val delivered: List<Int> = emptyList(),
        /** Of [volumes], those none of the job's links held: the row is failed for these. */
        val missing: List<Int> = emptyList(),
    )

    private val workManager get() = WorkManager.getInstance(app)

    private val prefs by lazy { app.getSharedPreferences("real_debrid_queue", Context.MODE_PRIVATE) }

    /** Work ids cancelled but not finished yet (persisted: the job may run after the app was killed). */
    private val cancelled by lazy { MutableStateFlow(prefs.getStringSet(KEY_CANCELLED, null).orEmpty().toSet()) }

    /** For [RealDebridImportJob]: stop or skip this work. */
    fun isCancelled(workId: UUID) = workId.toString() in cancelled.value

    @Synchronized
    private fun updateCancelled(change: (Set<String>) -> Set<String>) {
        val ids = change(cancelled.value)
        if (ids == cancelled.value) return
        cancelled.value = ids
        prefs.edit().putStringSet(KEY_CANCELLED, ids).apply()
    }

    // ponytail: finished and failed rows last only as long as WorkManager keeps them (~1 day); a table if history matters
    /** Every Real-Debrid job WorkManager still knows, queued first. Group by [Row.mediaId] for a title. */
    val rows: Flow<List<Row>>
        get() = combine(workManager.getWorkInfosFlow(WorkQuery.fromTags(RealDebridImportJob.TAG)), cancelled) { infos, ids ->
            // a finished job's row reads its own output: its id is no longer needed
            val unfinished = infos.filter { !it.state.isFinished }.mapTo(mutableSetOf()) { it.id.toString() }
            if (!unfinished.containsAll(ids)) updateCancelled { pruned(it, unfinished) }
            infos.map { row(it, it.id.toString() in ids) }.sortedBy { it.state.ordinal }
        }

    /**
     * Queues one volume: the first source in [coverageInOrder] that has it, the other sources' links as fallbacks.
     * Returns the volumes the queued link holds (a batch archive holds several), empty when no source has it.
     */
    fun enqueue(entry: AniListLibrary.Entry, volume: Int, coverageInOrder: List<Coverage>, unmetered: Boolean = false): Set<Int> {
        val links = CoverageMatch.links(volume, coverageInOrder)
        val (sourceId, link) = links.firstOrNull() ?: return emptySet()
        fun heldBy(source: Long, url: String) =
            coverageInOrder.first { it.sourceId == source }.volumes.filterValues { it == url }.keys
        val holds = heldBy(sourceId, link)
        val fallbacks = links.drop(1)
        RealDebridImportJob.start(
            app,
            link,
            name = entry.native ?: entry.romaji,
            mediaId = entry.mediaId,
            volumes = holds.sorted(),
            sourceId = sourceId,
            fallbacks = fallbacks,
            fallbackVolumes = fallbacks.map { (s, url) -> heldBy(s, url) },
            unmetered = unmetered,
        )
        return holds
    }

    /** Queues [volumes] in order, one job per link (a batch archive once). Returns the volumes queued. */
    fun enqueueAll(entry: AniListLibrary.Entry, volumes: Collection<Int>, coverageInOrder: List<Coverage>): Set<Int> {
        val queued = mutableSetOf<Int>()
        volumes.sorted().forEach { v -> if (v !in queued) queued += enqueue(entry, v, coverageInOrder) }
        return queued
    }

    /** Imports files the person downloaded by hand ([uris] = content URIs they picked) as [volumes] of [entry]; the row has no source. */
    fun enqueueLocal(entry: AniListLibrary.Entry, volumes: Collection<Int>, uris: List<Uri>): UUID {
        // the job may run after the picker's temporary grant ended: keep it (fails quietly for a non-document URI)
        uris.forEach { runCatching { app.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        return RealDebridImportJob.start(
            app,
            LOCAL_LINK,
            name = entry.native ?: entry.romaji,
            mediaId = entry.mediaId,
            volumes = volumes.sorted(),
            uris = uris.map { it.toString() },
        )
    }

    /** Fetches one torrent with this device's own torrent engine (no Real-Debrid) as [volumes] of [entry]. */
    fun enqueueTorrent(entry: AniListLibrary.Entry, volumes: Collection<Int>, sourceId: Long, magnet: String): UUID =
        RealDebridImportJob.start(
            app,
            magnet,
            name = entry.native ?: entry.romaji,
            mediaId = entry.mediaId,
            volumes = volumes.sorted(),
            sourceId = sourceId,
            onDevice = true,
        )

    /** Cancels one job; the others of its queue keep their place. */
    fun cancel(workId: UUID) = updateCancelled { it + workId.toString() }

    /** Cancels a title's unfinished jobs, or only those holding [volume]. Other titles' jobs keep running. */
    suspend fun cancel(mediaId: Long, volume: Int? = null) {
        val ids = workManager.getWorkInfosFlow(WorkQuery.fromTags(RealDebridImportJob.TAG_MEDIA + mediaId)).first()
            .filter { !it.state.isFinished && (volume == null || RealDebridImportJob.TAG_VOLUME + volume in it.tags) }
            .map { it.id.toString() }
        updateCancelled { it + ids }
    }

    private fun row(info: WorkInfo, flagged: Boolean): Row {
        fun tag(prefix: String) = info.tags.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)
        val out = info.outputData
        val progress = info.progress
        val missing = out.getIntArray(RealDebridImportJob.KEY_MISSING)?.toList().orEmpty()
        val (state, reason) = stateOf(info.state, out.getString(RealDebridImportJob.KEY_ERROR), flagged, missing)
        return Row(
            workId = info.id,
            mediaId = tag(RealDebridImportJob.TAG_MEDIA)?.toLongOrNull(),
            volumes = info.tags.filter { it.startsWith(RealDebridImportJob.TAG_VOLUME) }
                .mapNotNull { it.removePrefix(RealDebridImportJob.TAG_VOLUME).toIntOrNull() }.sorted(),
            sourceId = tag(RealDebridImportJob.TAG_SOURCE)?.toLongOrNull(),
            state = state,
            step = progress.getString(RealDebridImportJob.KEY_STEP),
            bytes = progress.getLong(RealDebridImportJob.KEY_BYTES, 0),
            total = progress.getLong(RealDebridImportJob.KEY_TOTAL, 0),
            speed = progress.getLong(RealDebridImportJob.KEY_SPEED, 0),
            reason = reason,
            // finished: the output's; running: the source being fetched now (the progress', a fallback's included)
            servedBy = (out.getLong(RealDebridImportJob.KEY_SERVED_BY, RealDebridImportJob.NO_SOURCE).takeIf { it != RealDebridImportJob.NO_SOURCE })
                ?: progress.getLong(RealDebridImportJob.KEY_SERVED_BY, RealDebridImportJob.NO_SOURCE).takeIf { it != RealDebridImportJob.NO_SOURCE },
            failures = out.getStringArray(RealDebridImportJob.KEY_FAILURES)?.toList().orEmpty(),
            delivered = out.getIntArray(RealDebridImportJob.KEY_DELIVERED)?.toList().orEmpty(),
            missing = missing,
        )
    }

    companion object {
        /** The reason of a cancelled row; the pages hide rows with it. */
        const val CANCELLED = "Cancelled"

        /** The link of a hand-downloaded job (the job reads its URIs instead; the link only fills its logs). */
        private const val LOCAL_LINK = "local files"
        private const val KEY_CANCELLED = "cancelled"

        /**
         * Row state and reason from the work's state, its output error, whether its id was cancelled, and the volumes
         * its links did not hold ([missing]: failed even without an error).
         */
        internal fun stateOf(
            work: WorkInfo.State,
            error: String?,
            flagged: Boolean,
            missing: List<Int> = emptyList(),
        ): Pair<State, String?> = when (work) {
            // "Cancel all" cancels every job through WorkManager; works cancelled by the previous version
            WorkInfo.State.CANCELLED -> State.FAILED to CANCELLED
            // only works of the previous version fail; one behind a failed one has no output of its own
            WorkInfo.State.FAILED -> State.FAILED to (error ?: "An earlier job in the queue failed")
            // a job's error ends as success so the jobs behind it still run
            WorkInfo.State.SUCCEEDED -> when {
                error != null -> State.FAILED to error
                missing.isNotEmpty() -> State.FAILED to "Vol. ${missing.joinToString()} missing"
                else -> State.DONE to null
            }
            // cancelled: it stops (or is skipped when its turn comes) within seconds
            else -> if (flagged) {
                State.FAILED to CANCELLED
            } else if (work == WorkInfo.State.RUNNING) {
                State.RUNNING to null
            } else {
                State.QUEUED to null
            }
        }

        /** The cancelled ids still worth keeping: those of works WorkManager still has unfinished. */
        internal fun pruned(cancelled: Set<String>, unfinished: Set<String>) = cancelled intersect unfinished
    }
}
