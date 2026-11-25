package chimahon.releases

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

enum class EventKind { OUT_TODAY, ON_SOURCES, DATE_CHANGED }

/** [source] = the kumahon source that has the volume (ON_SOURCES); [oldDate]/[newDate] = DATE_CHANGED. */
data class ReleaseEvent(
    val kind: EventKind,
    val anilistId: Long,
    val vol: Int,
    val source: String? = null,
    val oldDate: LocalDate? = null,
    val newDate: LocalDate? = null,
)

/**
 * The state machine, pure: ANNOUNCED -> RELEASED (today >= date, JST) -> ON_SOURCES -> ON_TABLET -> READ, never back.
 * Dates: each source's latest date is kept per row, the earliest wins; a source that did not answer this run changes nothing.
 * A row that is RELEASED or further keeps its date. Each (series, vol, kind) is emitted once (DATE_CHANGED: once per new date).
 */
class ReleaseEngine(private val zone: ZoneId = ZoneId.of("Asia/Tokyo")) {
    class Result(val state: ReleaseState, val events: List<ReleaseEvent>)

    companion object {
        /** An OUT_TODAY older than this is not news (the app was off, or a first scan). */
        const val OUT_TODAY_DAYS = 7L
    }

    /**
     * @param listings what the sources listed per watched series (raw: the matcher runs here).
     * @param coverage per series: volume -> the kumahon source that has it (the best one).
     * @param local volumes already on the tablet; @param read volumes read.
     */
    fun run(
        now: Instant,
        state: ReleaseState,
        listings: Map<Long, List<Listing>> = emptyMap(),
        coverage: Map<Long, Map<Int, String>> = emptyMap(),
        local: Map<Long, Set<Int>> = emptyMap(),
        read: Map<Long, Set<Int>> = emptyMap(),
    ): Result {
        val today = now.atZone(zone).toLocalDate()
        val rows = state.rows.associateBy { it.anilistId to it.vol }.toMutableMap()
        val notified = state.notified.toMutableSet()
        val events = mutableListOf<ReleaseEvent>()
        fun emit(key: String, e: ReleaseEvent) { if (notified.add(key)) events += e }

        for (s in state.series) {
            val id = s.anilistId
            val cands = VolumeMatcher.match(s.jpTitle, s.lastKnownVol, listings[id].orEmpty(), s.publisher).groupBy { it.vol }
            val cov = coverage[id].orEmpty()
            // spec: the lowest volume still only on sources = next; the back volumes are not news. Announced once (the `notified` key), whenever it becomes the next.
            val nextOnSources = cov.keys.filter { it > s.lastKnownVol && it !in local[id].orEmpty() && it !in read[id].orEmpty() }.minOrNull()
            val vols = (cands.keys + cov.keys.filter { it > s.lastKnownVol } + rows.keys.filter { it.first == id }.map { it.second }).toSortedSet()
            for (vol in vols) {
                val old = rows[id to vol]
                var date = old?.date
                var source = old?.source
                var previous = old?.previousDate

                // latest known date per source: only the sources that answered change their entry (a failed or silent one keeps its last date);
                // the row date is Kodansha's calendar date if it has one, else the earliest of the others (NDL last).
                var dates = old?.dates.orEmpty().ifEmpty { if (old?.source != null && old.date != null) mapOf(old.source to old.date) else emptyMap() }
                val answered = cands[vol].orEmpty().filter { it.date != null }.groupBy { it.source }.mapValues { e -> e.value.minOf { it.date!! } }
                if (answered.isNotEmpty() && (old == null || old.date == null || old.state < VolState.RELEASED)) {
                    // a source that answered for this series but no longer lists this volume drops its future date (a postponed volume leaves the calendar)
                    val answeredSeries = listings[id].orEmpty().map { it.source }.toSet()
                    dates = dates.filter { (src, d) -> src !in answeredSeries || src in answered || d < today } + answered
                    // Kodansha's own calendar is the publisher's word and shows delays first: when it has a date it rules alone, earlier or later.
                    // Otherwise NDL is the fallback (plan): its dates count only when no other source has one, so a stale NDL date can't hide a delay
                    val ruling = dates.filterKeys { it == KodanshaCalendar.ID }.ifEmpty { dates.filterKeys { it != NdlSearch.ID } }.ifEmpty { dates }
                    val newDate = ruling.values.min()
                    val oldDate = old?.date
                    // on a tie keep the stored source (no flapping), else the lowest id
                    source = if (old?.source != null && ruling[old.source] == newDate) old.source else ruling.filterValues { it == newDate }.keys.min()
                    // NDL -> a better source is a handover, not news
                    val handover = old?.source == NdlSearch.ID && source != NdlSearch.ID
                    if (oldDate != null && newDate != oldDate && !handover) {
                        previous = oldDate
                        emit("$id:$vol:${EventKind.DATE_CHANGED}:$oldDate>$newDate",ReleaseEvent(EventKind.DATE_CHANGED, id, vol, oldDate = oldDate, newDate = newDate))
                    }
                    date = newDate
                }

                val computed = when {
                    vol in read[id].orEmpty() -> VolState.READ
                    vol in local[id].orEmpty() -> VolState.ON_TABLET
                    vol in cov -> VolState.ON_SOURCES
                    date != null && today >= date -> VolState.RELEASED
                    else -> VolState.ANNOUNCED
                }
                val before = old?.state ?: VolState.ANNOUNCED
                val now2 = if (old != null && old.state > computed) old.state else computed

                if (before == VolState.ANNOUNCED && now2 in VolState.RELEASED..VolState.ON_SOURCES && date != null && today >= date &&
                    ChronoUnit.DAYS.between(date, today) <= OUT_TODAY_DAYS
                ) emit("$id:$vol:${EventKind.OUT_TODAY}", ReleaseEvent(EventKind.OUT_TODAY, id, vol))
                if (now2 == VolState.ON_SOURCES && vol == nextOnSources && s.status != WatchStatus.PLAN) { // Plan to read: dimmed, never auto-fetched, no news
                    emit("$id:$vol:${EventKind.ON_SOURCES}", ReleaseEvent(EventKind.ON_SOURCES, id, vol, source = cov[vol]))
                }

                rows[id to vol] = VolumeRow(id, vol, date, now2, source, old?.firstSeen ?: today, today, previous, dates)
            }
        }
        val out = rows.values.sortedWith(compareBy({ it.anilistId }, { it.vol }))
        return Result(state.copy(rows = out, notified = notified), events)
    }
}
