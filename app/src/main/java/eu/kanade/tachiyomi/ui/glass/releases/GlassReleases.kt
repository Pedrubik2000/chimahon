package eu.kanade.tachiyomi.ui.glass.releases

import chimahon.releases.ReleaseEngine
import chimahon.releases.ReleaseState
import chimahon.releases.VolState
import chimahon.releases.VolumeRow
import chimahon.releases.WatchStatus
import chimahon.releases.WatchedSeries
import eu.kanade.tachiyomi.data.releases.WatchList
import java.time.LocalDate

enum class ReleasesFilter {
    ALL, UP_TO_DATE;

    companion object {
        /** From a saved name; an old saved READING (chip removed) or anything unknown is ALL. */
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: ALL
    }
}

/** One volume row of the timeline (a title's newest volume); [dimmed] = switched off with Watch; [behind] = volumes between his place and this one ("N behind"). */
data class ReleaseItem(val series: WatchedSeries, val row: VolumeRow, val dimmed: Boolean, val behind: Int = 0, val upToDate: Boolean = false)

/** The rows of one day, in order. */
data class DaySection(val date: LocalDate, val items: List<ReleaseItem>)

/**
 * The screen's data (canvas "Releases A · Timeline"): [past] = out, oldest day on top, today's rows last; the TODAY line;
 * [upcoming] = announced, soonest first; [noDate] = no date yet. [nextUp] = the first announced row, for the TODAY line.
 */
data class Timeline(
    val past: List<DaySection>,
    val upcoming: List<DaySection>,
    val noDate: List<ReleaseItem>,
    val outToday: Int,
    val nextUp: ReleaseItem?,
    /** Series switched off with the bell's Watch switch that have rows: hidden unless asked for ("N not watched · show"). */
    val unwatched: Int = 0,
) {
    val isEmpty: Boolean get() = past.isEmpty() && upcoming.isEmpty() && noDate.isEmpty()
}

/** The pure part of the Releases screen (no Android), so tests can run it. */
object GlassReleases {
    /** "Out · past 4 weeks": a volume already on the tablet drops off the list this long after its date (one not there yet stays). */
    const val PAST_DAYS = 28L

    /** How far back the badge looks the first time the screen has never been opened. */
    const val FIRST_BADGE_DAYS = 7L

    /** One row per title: its newest volume (the engine keeps only that; this also covers a store from before, and unwatched series). */
    private fun newestRows(state: ReleaseState): List<VolumeRow> =
        state.rows.groupBy { it.anilistId }.values.map { rows -> rows.maxBy { it.vol } }

    /**
     * Phase 5 rules (Pedro): a released volume is news for [ReleaseEngine.NEWS_DAYS] days, then the title shows nothing; announced
     * (future) volumes always show; a volume without a date shows only while it is on sources.
     */
    fun visible(r: VolumeRow, today: LocalDate): Boolean {
        val d = r.date ?: return r.state == VolState.ON_SOURCES
        return d > today || today.toEpochDay() - d.toEpochDay() <= ReleaseEngine.NEWS_DAYS
    }

    fun build(state: ReleaseState, today: LocalDate, filter: ReleasesFilter = ReleasesFilter.ALL, showUnwatched: Boolean = false): Timeline {
        val byId = state.series.associateBy { it.anilistId }
        val all = newestRows(state).mapNotNull { r ->
            val s = byId[r.anilistId]?.takeIf { it.status == WatchStatus.READING } ?: return@mapNotNull null // Plan to read: no longer part of Releases (an old store still lists it until the next run)
            if (r.state == VolState.READ || !visible(r, today)) return@mapNotNull null
            val behind = WatchList.behind(s.lastKnownVol, r.vol)
            val upToDate = s.lastKnownVol > 0 && behind == 0 // has 12, vol 13 out or on sources: up to date (he only has to fetch it)

            if (filter == ReleasesFilter.UP_TO_DATE && !upToDate) return@mapNotNull null
            ReleaseItem(s, r, dimmed = s.watching == false, behind = behind, upToDate = upToDate)
        }
        val items = if (showUnwatched) all else all.filter { it.series.watching != false }
        // one timeline, up-to-date and behind titles mixed, by date (Pedro)
        val order = compareBy<ReleaseItem>({ it.row.date }, { it.series.jpTitle }, { it.row.vol })
        val dated = items.filter { it.row.date != null }.sortedWith(order)
        val past = dated.filter {
            val d = it.row.date!!
            d <= today && (it.row.state < VolState.ON_TABLET || !d.isBefore(today.minusDays(PAST_DAYS)))
        }
        val upcoming = dated.filter { it.row.date!! > today }
        return Timeline(
            past = sections(past),
            upcoming = sections(upcoming),
            noDate = items.filter { it.row.date == null }.sortedWith(compareBy({ it.series.jpTitle }, { it.row.vol })),
            outToday = past.count { it.row.date == today },
            nextUp = upcoming.firstOrNull(),
            unwatched = all.filter { it.series.watching == false }.distinctBy { it.series.anilistId }.size,
        )
    }

    private fun sections(sorted: List<ReleaseItem>) =
        sorted.groupBy { it.row.date!! }.map { (d, l) -> DaySection(d, l) }

    /**
     * The rail badge: READING volumes that came out (RELEASED up to ON_TABLET) on a day after [seen], the day the screen was
     * last opened (null = never: [FIRST_BADGE_DAYS] back). A volume released later the same day as the last visit is not counted.
     */
    fun newCount(state: ReleaseState, today: LocalDate, seen: LocalDate?): Int {
        val since = seen ?: today.minusDays(FIRST_BADGE_DAYS)
        val reading = state.series.filter { it.status == WatchStatus.READING && it.watching != false }.mapTo(hashSetOf()) { it.anilistId }
        return newestRows(state).count {
            it.anilistId in reading && it.state >= VolState.RELEASED && it.state < VolState.READ &&
                it.date != null && it.date!! > since && it.date!! <= today && visible(it, today)
        }
    }

    /** Whole days from [today] to [date]; negative = past. */
    fun daysTo(today: LocalDate, date: LocalDate): Int = (date.toEpochDay() - today.toEpochDay()).toInt()
}
