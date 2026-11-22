package eu.kanade.tachiyomi.ui.glass.downloads

import eu.kanade.tachiyomi.data.download.RealDebridQueue
import eu.kanade.tachiyomi.data.download.RealDebridQueue.State

/** One volume box of a title's bar on the Downloads page (canvas `Downloads-12B` key). */
enum class Cell { READ, HERE, DOWNLOADING, QUEUED, NONE, FAILED }

/** The queue rows of one AniList title; [mediaId] null = shared links (no title). */
data class TitleGroup(val mediaId: Long?, val rows: List<RealDebridQueue.Row>) {
    val running: RealDebridQueue.Row? get() = rows.firstOrNull { it.state == State.RUNNING }
    val queued: List<RealDebridQueue.Row> get() = rows.filter { it.state == State.QUEUED }
    val active: Boolean get() = rows.any { it.state == State.RUNNING || it.state == State.QUEUED }

    /** Volumes a job delivered or is still fetching: only these cover a failed volume. */
    private val coveredVolumes: Set<Int> get() = rows.flatMap { GlassDownloads.coveredBy(it) }.toSet()

    /** Failed rows (not cancelled) with a failed volume no job delivered or is fetching again. */
    val failed: List<RealDebridQueue.Row>
        get() {
            val covered = coveredVolumes
            return rows.filter {
                val vols = GlassDownloads.failedOf(it)
                it.state == State.FAILED && it.reason != RealDebridQueue.CANCELLED && (vols.isEmpty() || !vols.all { v -> v in covered })
            }
        }

    /** Volumes to fetch again with "Try again": a pack's missing ones not delivered or fetched since. */
    val failedVolumes: List<Int>
        get() {
            val covered = coveredVolumes
            return failed.flatMap { GlassDownloads.failedOf(it) }.filter { it !in covered }.distinct().sorted()
        }
}

object GlassDownloads {

    // ponytail: matched on RealDebridQueue's literal reason; a State.CANCELLED there if it ever changes
    private const val CANCELLED = "Cancelled"

    /**
     * One group per title, running titles first, then queued, then failed, then done; shared links last.
     * Cancelled jobs are left out: the user asked for them to go.
     */
    fun group(rows: List<RealDebridQueue.Row>): List<TitleGroup> =
        rows.filterNot { it.state == State.FAILED && it.reason == CANCELLED }
            .groupBy { it.mediaId }
            .map { (id, rs) -> TitleGroup(id, rs) }
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


    /** Volumes queued or running jobs will actually add: a pack's volumes not on the tablet ([here]); no volumes = 1. */
    fun toAdd(rows: List<RealDebridQueue.Row>, here: Set<Int>): Int =
        rows.sumOf { r -> if (r.volumes.isEmpty()) 1 else r.volumes.count { it !in here } }

    // ponytail: read is a count taken as volumes 1..n (AniList progress is a count)
    /**
     * Volumes 1..[count]: on the tablet ([here], the local chapters' volume numbers) is read or here; else the
     * queue's state (a pack's volumes already here are not queued again), else read elsewhere, or not asked for.
     */
    fun cells(count: Int, read: Int, here: Set<Int>, rows: List<RealDebridQueue.Row>): List<Cell> {
        fun has(state: State) = rows.filter { it.state == state }.flatMap { it.volumes }.toSet()
        val running = has(State.RUNNING)
        val queued = has(State.QUEUED)
        // the same failed as the card's line and Try again: not cancelled, not covered by a later job
        val failed = TitleGroup(null, rows).failedVolumes.toSet()
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
