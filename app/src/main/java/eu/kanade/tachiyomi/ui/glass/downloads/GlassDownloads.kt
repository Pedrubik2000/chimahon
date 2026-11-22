package eu.kanade.tachiyomi.ui.glass.downloads

import eu.kanade.tachiyomi.data.download.RealDebridQueue
import eu.kanade.tachiyomi.data.download.RealDebridQueue.State
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.ui.glass.title.GlassTitleState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** One volume box of a title's bar on the Downloads page (canvas `Downloads-12B` key). */
enum class Cell { READ, HERE, DOWNLOADING, QUEUED, NONE, FAILED }

/**
 * The queue rows of one AniList title; [mediaId] null = shared links (no title). [here] = the title's volumes on the
 * tablet ([GlassDownloads.volumesHere]); null = not known (shared links, not read yet).
 */
data class TitleGroup(val mediaId: Long?, val rows: List<RealDebridQueue.Row>, val here: Set<Int>? = null) {
    val running: RealDebridQueue.Row? get() = rows.firstOrNull { it.state == State.RUNNING }
    val queued: List<RealDebridQueue.Row> get() = rows.filter { it.state == State.QUEUED }
    val active: Boolean get() = rows.any { it.state == State.RUNNING || it.state == State.QUEUED }

    /**
     * Volumes that cover a failed one: [here] known (the title page's rule) only those queued or running; else those
     * a job delivered or is still fetching.
     */
    private val coveredVolumes: Set<Int>
        get() = rows.filter { here == null || it.state == State.QUEUED || it.state == State.RUNNING }
            .flatMap { GlassDownloads.coveredBy(it) }.toSet()

    /** A failed row's volumes still to get: [here] known, those not on the tablet; else its failed ones; minus [covered]. */
    private fun stillFailed(row: RealDebridQueue.Row, covered: Set<Int>): List<Int> =
        (if (here != null) row.volumes.filter { it !in here } else GlassDownloads.failedOf(row)).filter { it !in covered }

    /** Failed rows (not cancelled) with a volume still to get ([here] unknown: a row with no volumes too). */
    val failed: List<RealDebridQueue.Row>
        get() {
            val covered = coveredVolumes
            return rows.filter {
                it.state == State.FAILED && it.reason != RealDebridQueue.CANCELLED &&
                    (stillFailed(it, covered).isNotEmpty() || (here == null && GlassDownloads.failedOf(it).isEmpty()))
            }
        }

    /** Volumes to fetch again with "Try again": the failed rows' volumes still to get. */
    val failedVolumes: List<Int>
        get() {
            val covered = coveredVolumes
            return failed.flatMap { stillFailed(it, covered) }.distinct().sorted()
        }
}

object GlassDownloads {

    // ponytail: matched on RealDebridQueue's literal reason; a State.CANCELLED there if it ever changes
    private const val CANCELLED = "Cancelled"

    /**
     * One group per title, running titles first, then queued, then failed, then done; shared links last.
     * Cancelled jobs are left out: the user asked for them to go. [here] = mediaId → volumes on the tablet
     * ([volumesHere]); a title not in it keeps the delivered/requested rule.
     */
    fun group(rows: List<RealDebridQueue.Row>, here: Map<Long, Set<Int>> = emptyMap()): List<TitleGroup> =
        rows.filterNot { it.state == State.FAILED && it.reason == CANCELLED }
            .groupBy { it.mediaId }
            .map { (id, rs) -> TitleGroup(id, rs, id?.let(here::get)) }
            .sortedWith(
                compareBy<TitleGroup> { it.mediaId == null }
                    .thenBy { g ->
                        when {
                            g.running != null -> 0
                            g.queued.isNotEmpty() -> 1
                            g.failed.isNotEmpty() -> 2
                            else -> 3
                        }
                    },
            )

    /** Bar length: AniList's volume count when known, else the highest volume seen here or in the queue. */
    fun volumeCount(aniListVolumes: Int?, read: Int, here: Int, rows: List<RealDebridQueue.Row>): Int =
        aniListVolumes?.takeIf { it > 0 }
            ?: maxOf(read, here, rows.flatMap { it.volumes }.maxOrNull() ?: 0)

    /** A row's failed volumes: those its links did not hold, else all it was queued for. */
    fun failedOf(row: RealDebridQueue.Row): List<Int> = row.missing.ifEmpty { row.volumes }

    /**
     * The volumes a row covers: a queued or running job all it was queued for; a finished one what it delivered
     * (a done row from before jobs reported it: queued for minus missing); a failed or cancelled one only what it delivered.
     */
    fun coveredBy(row: RealDebridQueue.Row): List<Int> = when (row.state) {
        State.QUEUED, State.RUNNING -> row.volumes
        State.DONE -> row.delivered.ifEmpty { row.volumes - row.missing.toSet() }
        State.FAILED -> row.delivered
    }


    /**
     * The volumes on the tablet of each of [mediaIds] (the bars' "on tablet", the failed rule's [TitleGroup.here]): the
     * volume numbers of the chapters of the local series bound to it by an AniList track (several: the one with the
     * most chapters). Left out (unknown) when no tracked local series (the title page may still match one by name) or
     * its chapters could not be read.
     */
    suspend fun volumesHere(mediaIds: Collection<Long>): Map<Long, Set<Int>> = withContext(Dispatchers.IO) {
        if (mediaIds.isEmpty()) return@withContext emptyMap()
        val tracks = runCatching { Injekt.get<TrackRepository>().getTracks() }.getOrNull() ?: return@withContext emptyMap()
        val getManga = Injekt.get<GetManga>()
        val chapters = Injekt.get<ChapterRepository>()
        val bound = tracks.filter { it.trackerId == TrackerManager.ANILIST && it.remoteId in mediaIds }
            .filter { getManga.await(it.mangaId)?.source == LocalSource.ID }
            .groupBy({ it.remoteId }, { it.mangaId })
        buildMap {
            for (id in mediaIds.distinct()) {
                val series = bound[id].orEmpty().distinct().map { m -> runCatching { chapters.getChapterByMangaId(m) }.getOrNull() }
                if (series.isEmpty() || null in series) continue
                val most = series.filterNotNull().maxBy { it.size }
                put(id, most.mapNotNullTo(mutableSetOf()) { GlassTitleState.volumeNumber(it) })
            }
        }
    }

    /** Volumes queued or running jobs will actually add: a pack's volumes not on the tablet ([here]); no volumes = 1. */
    fun toAdd(rows: List<RealDebridQueue.Row>, here: Set<Int>): Int =
        rows.sumOf { r -> if (r.volumes.isEmpty()) 1 else r.volumes.count { it !in here } }

    // ponytail: read is a count taken as volumes 1..n (AniList progress is a count)
    /**
     * Volumes 1..[count]: on the tablet ([here], the local chapters' volume numbers) is read or here; else the
     * queue's state (a pack's volumes already here are not queued again), else read elsewhere, or not asked for.
     * [failed] = the card's [TitleGroup.failedVolumes] (the same as its line and Try again).
     */
    fun cells(
        count: Int,
        read: Int,
        here: Set<Int>,
        rows: List<RealDebridQueue.Row>,
        failed: Set<Int> = TitleGroup(null, rows, here).failedVolumes.toSet(),
    ): List<Cell> {
        fun has(state: State) = rows.filter { it.state == state }.flatMap { it.volumes }.toSet()
        val running = has(State.RUNNING)
        val queued = has(State.QUEUED)
        return (1..count).map { v ->
            when {
                v in here -> if (v <= read) Cell.READ else Cell.HERE
                v in running -> Cell.DOWNLOADING
                v in queued -> Cell.QUEUED
                v <= read -> Cell.READ
                v in failed -> Cell.FAILED
                else -> Cell.NONE
            }
        }
    }

    /**
     * [current] = the sources in their shown order (pref order merged with the built-in one, new ones included);
     * moves [id] by [delta] places. The result is the whole new order, so stale ids drop out of the pref.
     */
    fun move(current: List<Long>, id: Long, delta: Int): List<Long> {
        val from = current.indexOf(id)
        val to = from + delta
        if (from < 0 || to !in current.indices) return current
        return current.toMutableList().apply { add(to, removeAt(from)) }
    }

    /** [off] with [id] switched on or off. */
    fun toggle(off: List<Long>, id: Long, on: Boolean): List<Long> = if (on) off - id else (off + id).distinct()
}
