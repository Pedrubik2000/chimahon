package eu.kanade.tachiyomi.data.releases

import chimahon.releases.VolState
import chimahon.releases.VolumeRow
import chimahon.releases.WatchStatus
import chimahon.releases.WatchedSeries
import eu.kanade.tachiyomi.data.anilist.AniListLibrary
import eu.kanade.tachiyomi.data.coverage.Coverage
import eu.kanade.tachiyomi.data.coverage.CoverageMatch
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** The pure decisions of the Releases jobs (no Android), so tests can run them. */
object WatchList {
    /** Dates within this many days of today are re-checked every day (delays are edited in place). */
    const val WINDOW_DAYS = 7L

    /** AniList list status -> watch status; null = not watched (planning, completed, paused, dropped). */
    fun statusOf(listStatus: String): WatchStatus? = when (listStatus) {
        "CURRENT", "REPEATING" -> WatchStatus.READING
        // PLANNING is not watched any more (phase 5, Pedro): only what he is reading
        else -> null
    }

    /**
     * The watch list from the AniList cache. [localMax] = highest volume on the tablet per media id.
     * Not watched: other list statuses, novels / one-shots, no native title (nothing to search), and a FINISHED title whose
     * volume count is known and already reached (local max or AniList progress): nothing left to wait for.
     * [stored] keeps its jpTitle (it may be an override), publisher, autoFetch and watching; only lastKnownVol and status follow AniList.
     */
    fun build(entries: List<AniListLibrary.Entry>, localMax: Map<Long, Int>, stored: List<WatchedSeries>): List<WatchedSeries> =
        buildWithSkips(entries, localMax, stored).first

    /** A READING entry that [build] left out: [reason] is NOVEL, ONE_SHOT, FINISHED_COVERED or NO_NATIVE_TITLE. */
    data class Skipped(val mediaId: Long, val reason: String, val title: String)

    /** [build] plus why each READING entry was dropped (pure: the worker logs it). */
    fun buildWithSkips(entries: List<AniListLibrary.Entry>, localMax: Map<Long, Int>, stored: List<WatchedSeries>): Pair<List<WatchedSeries>, List<Skipped>> {
        val old = stored.associateBy { it.anilistId }
        val skipped = mutableListOf<Skipped>()
        val list = entries.mapNotNull { e ->
            val status = statusOf(e.status) ?: return@mapNotNull null
            fun skip(reason: String): WatchedSeries? {
                skipped += Skipped(e.mediaId, reason, e.native ?: e.romaji ?: e.english ?: "?")
                return null
            }
            if (e.format == AniListLibrary.NOVEL) return@mapNotNull skip("NOVEL")
            if (e.format == "ONE_SHOT") return@mapNotNull skip("ONE_SHOT")
            val last = lastKnownVol(localMax[e.mediaId], e.progressVolumes)
            if (e.mediaStatus == "FINISHED" && e.volumes != null && e.volumes > 0 && e.volumes <= last) return@mapNotNull skip("FINISHED_COVERED")
            val prev = old[e.mediaId]
            val title = prev?.jpTitle?.takeIf { it.isNotBlank() } ?: e.native?.takeIf { it.isNotBlank() } ?: return@mapNotNull skip("NO_NATIVE_TITLE")
            WatchedSeries(e.mediaId, title, prev?.publisher, last, status, prev?.autoFetch, prev?.watching)
        }.distinctBy { it.anilistId }
        return list to skipped
    }

    fun lastKnownVol(localMax: Int?, progressVolumes: Int): Int = maxOf(localMax ?: 0, progressVolumes, 0)

    /**
     * Volumes between what he has and the [newest] known one ("N behind"). 0 when he is up to date, and 0 when he has no known place
     * ([lastKnownVol] 0: not in kumahon, no AniList progress): then only the newest volume shows, no note.
     */
    fun behind(lastKnownVol: Int, newest: Int): Int = if (lastKnownVol <= 0) 0 else maxOf(0, newest - lastKnownVol - 1)

    /** The first of the volumes the series still waits for: rows above [WatchedSeries.lastKnownVol]. */
    private fun waiting(s: WatchedSeries, rows: List<VolumeRow>) = rows.filter { it.anilistId == s.anilistId && it.vol > s.lastKnownVol }

    /**
     * Whose listings the daily run asks the sources for: READING and PLAN series with a date in the last/next [WINDOW_DAYS]
     * days (a delay shows up there) or no date at all. The weekly run asks for everything.
     */
    fun dailyListings(series: List<WatchedSeries>, rows: List<VolumeRow>, today: LocalDate): Set<Long> =
        series.filter { s ->
            val w = waiting(s, rows).filter { it.state < VolState.ON_TABLET }
            val dates = w.mapNotNull { it.date }
            dates.isEmpty() || dates.any { ChronoUnit.DAYS.between(today, it) in -WINDOW_DAYS..WINDOW_DAYS }
        }.mapTo(linkedSetOf()) { it.anilistId }

    /**
     * Whose source coverage the daily run reads (a fan-out of every enabled source, so only where it can change something):
     * READING series (Plan to read gets no news) with a waiting volume whose date has passed, or with no date at all. A series
     * whose next volume is still announced for the future, or already on the tablet, is skipped.
     */
    fun dailyCoverage(series: List<WatchedSeries>, rows: List<VolumeRow>, today: LocalDate, local: Map<Long, Set<Int>>? = null): Set<Long> =
        series.filter { s ->
            if (s.status != WatchStatus.READING) return@filter false
            val w = waiting(s, rows)
            // a row still ON_TABLET blocks only while the volume is in [local] (null = not asked: the state is trusted): a deleted file must not
            if (w.any { it.state == VolState.ON_TABLET && (local == null || it.vol in local[s.anilistId].orEmpty()) }) return@filter false // READ rows are not waiting
            val open = w.filter { it.state < VolState.ON_TABLET }
            open.isEmpty() || open.any { it.date.let { d -> d == null || d <= today } }
        }.mapTo(linkedSetOf()) { it.anilistId }

    /**
     * A date that passed within the last [WINDOW_DAYS] days is news worth a fresh answer; an older one (a volume no source has yet)
     * and the "No date" lane read the 24 h cache (nothing should cost a fan-out every day).
     */
    fun forceCoverage(s: WatchedSeries, rows: List<VolumeRow>, today: LocalDate): Boolean =
        waiting(s, rows).any { it.state < VolState.ON_TABLET && it.date.let { d -> d != null && d <= today && ChronoUnit.DAYS.between(d, today) <= WINDOW_DAYS } }

    /** Volume -> name of the first source (in [order]) that has it, as the engine's coverage input. */
    fun bestSources(coverages: Collection<Coverage>, order: List<Long>): Map<Int, String> {
        val inOrder = CoverageMatch.ordered(coverages, order)
        val names = inOrder.associate { it.sourceId to it.sourceName }
        return CoverageMatch.firstSource(inOrder).mapValues { names.getValue(it.value) }
    }
}
