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

    /** Failed rows whose volumes no later job (queued, running or done) covers again. */
    val failed: List<RealDebridQueue.Row>
        get() {
            val covered = rows.filter { it.state != State.FAILED }.flatMap { it.volumes }.toSet()
            return rows.filter { it.state == State.FAILED && (it.volumes.isEmpty() || !it.volumes.all { v -> v in covered }) }
        }

    /** Volumes to fetch again with "Try again". */
    val failedVolumes: List<Int> get() = failed.flatMap { it.volumes }.distinct().sorted()
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

    // ponytail: read / here are counts taken as volumes 1..n (GlassTitlesModel has counts, not volume numbers)
    /** Volumes 1..[count]: the queue's state where it has one, else read, on the tablet, or not asked for. */
    fun cells(count: Int, read: Int, here: Int, rows: List<RealDebridQueue.Row>): List<Cell> {
        fun has(state: State) = rows.filter { it.state == state }.flatMap { it.volumes }.toSet()
        val running = has(State.RUNNING)
        val queued = has(State.QUEUED)
        val done = has(State.DONE)
        val failed = has(State.FAILED)
        return (1..count).map { v ->
            when {
                v in running -> Cell.DOWNLOADING
                v in queued -> Cell.QUEUED
                v <= read -> Cell.READ
                v <= here || v in done -> Cell.HERE
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
