package eu.kanade.tachiyomi.data.releases

import chimahon.releases.VolState
import chimahon.releases.VolumeRow
import chimahon.releases.WatchStatus
import chimahon.releases.WatchedSeries
import eu.kanade.tachiyomi.data.anilist.AniListLibrary.Entry
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

class WatchListPhase5Test {
    private val today = LocalDate.parse("2026-11-25")
    private fun row(vol: Int, date: String?, state: VolState) = VolumeRow(1, vol, date?.let(LocalDate::parse), state, null, today, today)
    private val series = listOf(WatchedSeries(1, "t", lastKnownVol = 5))

    @Test
    fun `build keeps the watch switch and the auto-fetch choice of the stored series`() {
        val e = Entry(1, "CURRENT", progressVolumes = 6, native = "x", volumes = null, mediaStatus = "RELEASING", format = "MANGA")
        val stored = listOf(WatchedSeries(1, "mine", lastKnownVol = 2, autoFetch = false, watching = false))
        val built = WatchList.build(listOf(e), emptyMap(), stored).single()
        built.watching shouldBe false
        built.autoFetch shouldBe false
        built.jpTitle shouldBe "mine"
        built.lastKnownVol shouldBe 6
        WatchList.build(listOf(e), emptyMap(), emptyList()).single().watching shouldBe null
    }

    @Test
    fun `an ON_TABLET row blocks the coverage read only while the volume is still local`() {
        val rows = listOf(row(6, "2026-11-10", VolState.ON_TABLET))
        // not asked about the tablet: the state is trusted (as before)
        WatchList.dailyCoverage(series, rows, today) shouldBe emptySet()
        // still there
        WatchList.dailyCoverage(series, rows, today, mapOf(1L to setOf(5, 6))) shouldBe emptySet()
        // deleted from the tablet: it may be on sources again, read the coverage
        WatchList.dailyCoverage(series, rows, today, mapOf(1L to setOf(5))) shouldBe setOf(1L)
        WatchList.dailyCoverage(series, rows, today, emptyMap()) shouldBe setOf(1L)
    }
}
