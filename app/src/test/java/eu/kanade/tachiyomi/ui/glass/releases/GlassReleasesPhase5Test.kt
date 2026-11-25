package eu.kanade.tachiyomi.ui.glass.releases

import chimahon.releases.ReleaseState
import chimahon.releases.VolState
import chimahon.releases.VolumeRow
import chimahon.releases.WatchedSeries
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

class GlassReleasesPhase5Test {
    private val today = LocalDate.of(2026, 11, 25)
    private fun r(id: Long, vol: Int, state: VolState = VolState.RELEASED) = VolumeRow(id, vol, today, state, null, today, today)

    private val state = ReleaseState(
        series = listOf(WatchedSeries(1, "A"), WatchedSeries(2, "B", watching = false), WatchedSeries(3, "C", watching = true)),
        rows = listOf(r(1, 1), r(2, 2), r(3, 1)),
    )

    @Test
    fun `unwatched series are hidden with a count, shown dimmed on request`() {
        val hidden = GlassReleases.build(state, today)
        hidden.past.flatMap { it.items }.map { it.series.anilistId } shouldBe listOf(1L, 3L)
        hidden.unwatched shouldBe 1
        val shown = GlassReleases.build(state, today, showUnwatched = true)
        shown.past.flatMap { it.items }.filter { it.dimmed }.map { it.series.anilistId } shouldBe listOf(2L)
        shown.past.flatMap { it.items }.size shouldBe 3
    }

    @Test
    fun `the badge does not count unwatched series`() {
        GlassReleases.newCount(state, today, today.minusDays(1)) shouldBe 2
    }
}
