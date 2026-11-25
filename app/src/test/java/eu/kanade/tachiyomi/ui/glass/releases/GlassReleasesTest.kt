package eu.kanade.tachiyomi.ui.glass.releases

import chimahon.releases.ReleaseState
import chimahon.releases.VolState
import chimahon.releases.VolumeRow
import chimahon.releases.WatchStatus
import chimahon.releases.WatchedSeries
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

class GlassReleasesTest {
    private val today = LocalDate.of(2026, 11, 25)

    private fun s(id: Long, title: String, last: Int = 0, status: WatchStatus = WatchStatus.READING) = WatchedSeries(id, title, lastKnownVol = last, status = status)
    private fun r(id: Long, vol: Int, date: String?, state: VolState, prev: String? = null) = VolumeRow(
        id, vol, date?.let(LocalDate::parse), state, null, today, today, previousDate = prev?.let(LocalDate::parse),
    )

    // one row per title (the newest volume)
    private val state = ReleaseState(
        series = listOf(s(1, "A", last = 5), s(2, "B", last = 3), s(3, "C", last = 1), s(4, "D", last = 9), s(5, "E", last = 2), s(6, "F", last = 4), s(7, "G", last = 5)),
        rows = listOf(
            r(1, 6, "2026-11-25", VolState.RELEASED), // out today, he has 5: up to date
            r(2, 8, "2026-12-04", VolState.ANNOUNCED, prev = "2026-12-01"), // announced, he has 3: 4 behind
            r(3, 2, "2026-10-01", VolState.ON_SOURCES), // 55 days ago, on sources
            r(4, 10, "2026-01-01", VolState.RELEASED), // older than 90 days: not shown
            r(5, 3, null, VolState.ON_SOURCES), // no date, on sources: shown
            r(6, 5, null, VolState.ANNOUNCED), // no date, not on sources: not shown
            r(7, 6, "2026-12-10", VolState.ANNOUNCED), // announced, he has 5: up to date
            r(9, 1, "2026-11-20", VolState.RELEASED), // series not watched: gone
        ),
    )

    @Test
    fun `past is oldest first, announced below, no date apart, old and undated-unavailable titles hidden`() {
        val t = GlassReleases.build(state, today)
        t.past.map { it.date.toString() } shouldBe listOf("2026-10-01", "2026-11-25")
        t.upcoming.map { it.date.toString() } shouldBe listOf("2026-12-04", "2026-12-10")
        t.noDate.map { it.series.anilistId } shouldBe listOf(5L)
        t.outToday shouldBe 1
        t.nextUp?.series?.anilistId shouldBe 2L
    }

    @Test
    fun `behind counts the volumes between his place and the newest, no place no note`() {
        val items = GlassReleases.build(state, today).let { it.past + it.upcoming }.flatMap { it.items }.associateBy { it.series.anilistId }
        items.getValue(1).behind shouldBe 0
        items.getValue(2).behind shouldBe 4
        items.getValue(3).behind shouldBe 0
        val unknown = state.copy(series = state.series.map { if (it.anilistId == 2L) it.copy(lastKnownVol = 0) else it })
        GlassReleases.build(unknown, today).upcoming.flatMap { it.items }.first { it.series.anilistId == 2L }.behind shouldBe 0
    }

    @Test
    fun `up to date filter keeps the titles with behind 0, in the same timeline`() {
        val t = GlassReleases.build(state, today, ReleasesFilter.UP_TO_DATE)
        (t.past + t.upcoming).flatMap { it.items }.map { it.series.anilistId } shouldBe listOf(3L, 1L, 7L) // behind 0 counts even when the volume is out or on sources (he only has to fetch it); B is 4 behind
    }

    @Test
    fun `plan to read is not shown even when an old store still has it`() {
        val old = state.copy(series = state.series.map { if (it.anilistId == 1L) it.copy(status = WatchStatus.PLAN) else it })
        (GlassReleases.build(old, today).past.flatMap { it.items }).map { it.series.anilistId } shouldBe listOf(3L)
    }

    @Test
    fun `an old store with back volumes shows only the newest of a title`() {
        val st = ReleaseState(
            series = listOf(s(1, "A", last = 0)),
            rows = (19..21).map { r(1, it, "2026-11-0${it - 18}", VolState.RELEASED) },
        )
        GlassReleases.build(st, today).past.flatMap { it.items }.map { it.row.vol } shouldBe listOf(21)
    }

    @Test
    fun `nothing out today and nothing announced`() {
        val t = GlassReleases.build(ReleaseState(series = state.series, rows = emptyList()), today)
        t.isEmpty shouldBe true
        t.outToday shouldBe 0
        t.nextUp shouldBe null
    }

    @Test
    fun `badge counts reading volumes out since the last visit`() {
        GlassReleases.newCount(state, today, LocalDate.of(2026, 11, 24)) shouldBe 1
        GlassReleases.newCount(state, today, today) shouldBe 0
    }

    @Test
    fun `days to`() {
        GlassReleases.daysTo(today, today.plusDays(9)) shouldBe 9
        GlassReleases.daysTo(today, today.minusDays(3)) shouldBe -3
    }

    @Test
    fun `an old saved READING filter falls back to ALL`() {
        ReleasesFilter.of("READING") shouldBe ReleasesFilter.ALL
        ReleasesFilter.of(null) shouldBe ReleasesFilter.ALL
        ReleasesFilter.of("UP_TO_DATE") shouldBe ReleasesFilter.UP_TO_DATE
    }
}
