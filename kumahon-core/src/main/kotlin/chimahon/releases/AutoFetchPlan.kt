package chimahon.releases

import java.time.LocalDate

/**
 * Auto-fetch decisions, pure (phase 5, plans/kumahon_releases_plan.md). One volume per series at a time: the next one on sources.
 * Wi-Fi + more than [MIN_FREE_BYTES] free, at most [MAX_PER_DAY] queued per JST day, a failed import is retried the next day on
 * another source, [MAX_FAILS] failures = given up (the Needs attention panel shows it).
 */
object AutoFetchPlan {
    const val MAX_PER_DAY = 3
    const val MAX_FAILS = 3
    const val MIN_FREE_BYTES = 5L * 1024 * 1024 * 1024

    /**
     * (series, vol) wanted: per READING, watched series with auto-fetch on, the volume right after the one he has, when it is on sources.
     * Behind is never fetched (whatever the explicit setting) and an unknown place (lastKnownVol 0) never.
     */
    fun candidates(series: List<WatchedSeries>, rows: List<VolumeRow>, autoOn: (WatchedSeries) -> Boolean): List<Pair<Long, Int>> =
        series.filter { it.status == WatchStatus.READING && it.watching != false && it.lastKnownVol > 0 && autoOn(it) }.mapNotNull { s ->
            rows.firstOrNull { it.anilistId == s.anilistId && it.vol == s.lastKnownVol + 1 && it.state == VolState.ON_SOURCES }?.let { s.anilistId to it.vol }
        }

    /**
     * A queued attempt that never reported back by the next day failed (the process died, the system stopped it), unless its job is
     * still [active] (queued or running: the serial chain can be slow) or was [cancelled] in WorkManager ("Cancel all" never reports:
     * finished, no failure).
     */
    fun expire(
        attempts: List<FetchAttempt>,
        today: LocalDate,
        active: Set<Pair<Long, Int>> = emptySet(),
        cancelled: Set<Pair<Long, Int>> = emptySet(),
    ): List<FetchAttempt> = attempts.map {
        val key = it.anilistId to it.vol
        when {
            !it.pending || !it.lastDay.isBefore(today) || key in active -> it
            key in cancelled -> it.copy(pending = false, finished = true)
            else -> it.copy(pending = false, fails = it.fails + 1)
        }
    }

    fun fetchedToday(s: ReleaseState, today: LocalDate): Int = if (s.fetchDay == today) s.fetchCount else 0

    /** The candidates to queue now, in order, within the day's cap; none off Wi-Fi or without room. [freeBytes] < 0 = unknown = none. */
    fun pick(
        candidates: List<Pair<Long, Int>>,
        attempts: List<FetchAttempt>,
        today: LocalDate,
        unmetered: Boolean,
        freeBytes: Long,
        fetchedToday: Int,
    ): List<Pair<Long, Int>> {
        if (!unmetered || freeBytes <= MIN_FREE_BYTES) return emptyList()
        val byKey = attempts.associateBy { it.anilistId to it.vol }
        return candidates.distinct().filter { c ->
            val a = byKey[c] ?: return@filter true
            !a.pending && !a.finished && a.fails < MAX_FAILS && a.lastDay.isBefore(today) // tried today (failed or cancelled): tomorrow
        }.take((MAX_PER_DAY - fetchedToday).coerceAtLeast(0))
    }

    /** The attempt of (id, vol) is queued on [source] today. */
    fun queued(attempts: List<FetchAttempt>, id: Long, vol: Int, source: Long?, today: LocalDate): List<FetchAttempt> {
        val old = attempts.firstOrNull { it.anilistId == id && it.vol == vol }
        val next = FetchAttempt(id, vol, ((old?.tried.orEmpty()) + listOfNotNull(source)).distinct(), old?.fails ?: 0, today, pending = true)
        return attempts.filterNot { it === old } + next
    }

    /** The day's count after queuing [n] more. */
    fun counted(s: ReleaseState, today: LocalDate, n: Int): Pair<LocalDate, Int> = today to fetchedToday(s, today) + n

    class Ended(val attempts: List<FetchAttempt>, val delivered: List<Pair<Long, Int>>)

    /**
     * An import job of [mediaId] for [volumes] ended: [delivered] = the volumes it put on the tablet, [cancelled] = the person
     * cancelled it, [sources] = the sources it used or found dead. A pending attempt it covers is dropped when delivered
     * (reported in [Ended.delivered]), kept without a failure when cancelled, else one failure more with the sources as tried.
     */
    fun ended(
        attempts: List<FetchAttempt>,
        mediaId: Long,
        volumes: Collection<Int>,
        delivered: Collection<Int>,
        cancelled: Boolean,
        sources: Collection<Long>,
    ): Ended {
        val done = mutableListOf<Pair<Long, Int>>()
        val out = attempts.map { a ->
            if (!a.pending || a.anilistId != mediaId || a.vol !in volumes) return@map a
            when {
                // the marker stays until the row is ON_TABLET (prune): the engine has not seen the volume yet, a new run must not fetch it again
                a.vol in delivered -> { done += a.anilistId to a.vol; a.copy(pending = false, finished = true) }
                cancelled -> a.copy(pending = false, finished = true) // the person cancelled: no retry, no problem card
                else -> a.copy(pending = false, fails = a.fails + 1, tried = (a.tried + sources).distinct())
            }
        }
        return Ended(out, done)
    }

    /** Attempts of volumes that are no longer waiting on sources (on the tablet, read, or the row is gone) are forgotten. */
    fun prune(attempts: List<FetchAttempt>, rows: List<VolumeRow>): List<FetchAttempt> {
        val open = rows.filter { it.state <= VolState.ON_SOURCES }.mapTo(hashSetOf()) { it.anilistId to it.vol }
        return attempts.filter { (it.anilistId to it.vol) in open }
    }

    /** Failed three times: what the Needs attention panel lists. */
    fun gaveUp(attempts: List<FetchAttempt>, series: List<WatchedSeries>): List<FetchAttempt> {
        val live = series.filter { it.status == WatchStatus.READING && it.watching != false }.mapTo(hashSetOf()) { it.anilistId }
        return attempts.filter { !it.pending && !it.finished && it.fails >= MAX_FAILS && it.anilistId in live }
    }
}
