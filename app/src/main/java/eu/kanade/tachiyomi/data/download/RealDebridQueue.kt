package eu.kanade.tachiyomi.data.download

import android.app.Application
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import eu.kanade.tachiyomi.data.anilist.AniListLibrary
import eu.kanade.tachiyomi.data.coverage.Coverage
import eu.kanade.tachiyomi.data.coverage.CoverageMatch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Real-Debrid downloads by AniList title and volume: queues [RealDebridImportJob]s from a title's coverage and turns
 * their WorkInfo (tags + progress + output) into rows. No table: WorkManager holds the state.
 */
class RealDebridQueue(private val app: Application) {

    enum class State { QUEUED, RUNNING, FAILED, DONE }

    data class Row(
        val workId: UUID,
        val mediaId: Long?,
        val volumes: List<Int>,
        val sourceId: Long?,
        val state: State,
        /** [RealDebridImportJob.STEP_WAITING] ... while running. */
        val step: String?,
        val bytes: Long,
        val total: Long,
        /** Bytes per second, 0 when unknown. */
        val speed: Long,
        /** Why it failed. */
        val reason: String?,
        /** The source whose link worked (a fallback's when the first was dead). */
        val servedBy: Long?,
        /** "<source id>|<reason>" per dead link tried. */
        val failures: List<String>,
    )

    private val workManager get() = WorkManager.getInstance(app)

    // ponytail: finished and failed rows last only as long as WorkManager keeps them (~1 day); a table if history matters
    /** Every Real-Debrid job WorkManager still knows, queued first. Group by [Row.mediaId] for a title. */
    val rows: Flow<List<Row>> get() = workManager.getWorkInfosFlow(WorkQuery.fromTags(RealDebridImportJob.TAG))
        .map { infos -> infos.map(::row).sortedBy { it.state.ordinal } }

    /**
     * Queues one volume: the first source in [coverageInOrder] that has it, the other sources' links as fallbacks.
     * Returns the volumes the queued link holds (a batch archive holds several), empty when no source has it.
     */
    fun enqueue(entry: AniListLibrary.Entry, volume: Int, coverageInOrder: List<Coverage>): Set<Int> {
        val links = CoverageMatch.links(volume, coverageInOrder)
        val (sourceId, link) = links.firstOrNull() ?: return emptySet()
        val holds = coverageInOrder.first { it.sourceId == sourceId }.volumes.filterValues { it == link }.keys
        RealDebridImportJob.start(
            app,
            link,
            name = entry.native ?: entry.romaji,
            mediaId = entry.mediaId,
            volumes = holds.sorted(),
            sourceId = sourceId,
            fallbacks = links.drop(1),
        )
        return holds
    }

    /** Queues [volumes] in order, one job per link (a batch archive once). Returns the volumes queued. */
    fun enqueueAll(entry: AniListLibrary.Entry, volumes: Collection<Int>, coverageInOrder: List<Coverage>): Set<Int> {
        val queued = mutableSetOf<Int>()
        volumes.sorted().forEach { v -> if (v !in queued) queued += enqueue(entry, v, coverageInOrder) }
        return queued
    }

    // ponytail: jobs of one queue are chained (APPEND_OR_REPLACE), so cancelling one also cancels the ones queued behind it
    /** Cancels a title's jobs, or only those holding [volume]. */
    suspend fun cancel(mediaId: Long, volume: Int? = null) {
        if (volume == null) {
            workManager.cancelAllWorkByTag(RealDebridImportJob.TAG_MEDIA + mediaId)
            return
        }
        workManager.getWorkInfosFlow(WorkQuery.fromTags(RealDebridImportJob.TAG_MEDIA + mediaId)).first()
            .filter { RealDebridImportJob.TAG_VOLUME + volume in it.tags && !it.state.isFinished }
            .forEach { workManager.cancelWorkById(it.id) }
    }

    private fun row(info: WorkInfo): Row {
        fun tag(prefix: String) = info.tags.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)
        val out = info.outputData
        val progress = info.progress
        return Row(
            workId = info.id,
            mediaId = tag(RealDebridImportJob.TAG_MEDIA)?.toLongOrNull(),
            volumes = info.tags.filter { it.startsWith(RealDebridImportJob.TAG_VOLUME) }
                .mapNotNull { it.removePrefix(RealDebridImportJob.TAG_VOLUME).toIntOrNull() }.sorted(),
            sourceId = tag(RealDebridImportJob.TAG_SOURCE)?.toLongOrNull(),
            state = when (info.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> State.QUEUED
                WorkInfo.State.RUNNING -> State.RUNNING
                WorkInfo.State.SUCCEEDED -> State.DONE
                WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> State.FAILED
            },
            step = progress.getString(RealDebridImportJob.KEY_STEP),
            bytes = progress.getLong(RealDebridImportJob.KEY_BYTES, 0),
            total = progress.getLong(RealDebridImportJob.KEY_TOTAL, 0),
            speed = progress.getLong(RealDebridImportJob.KEY_SPEED, 0),
            reason = when (info.state) {
                WorkInfo.State.CANCELLED -> "Cancelled"
                // a job behind a failed one in the same queue fails with no output of its own
                WorkInfo.State.FAILED -> out.getString(RealDebridImportJob.KEY_ERROR) ?: "An earlier job in the queue failed"
                else -> null
            },
            servedBy = out.getLong(RealDebridImportJob.KEY_SERVED_BY, RealDebridImportJob.NO_SOURCE).takeIf { it != RealDebridImportJob.NO_SOURCE },
            failures = out.getStringArray(RealDebridImportJob.KEY_FAILURES)?.toList().orEmpty(),
        )
    }
}
