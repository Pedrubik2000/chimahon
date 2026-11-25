package eu.kanade.tachiyomi.data.releases

import chimahon.releases.EventKind
import chimahon.releases.ReleaseEvent
import chimahon.releases.VolState
import chimahon.releases.VolumeRow
import chimahon.releases.WatchStatus
import chimahon.releases.WatchedSeries
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

class ReleasesPlanTest {

    private val out = ReleaseEvent(EventKind.OUT_TODAY, 1, 5)
    private val onSrc = ReleaseEvent(EventKind.ON_SOURCES, 2, 6, source = "X3DL")
    private val onSrcAuto = ReleaseEvent(EventKind.ON_SOURCES, 3, 7, source = "Excnn")
    private val moved = ReleaseEvent(EventKind.DATE_CHANGED, 1, 6, oldDate = LocalDate.parse("2026-12-01"), newDate = LocalDate.parse("2026-12-09"))
    private val all = listOf(out, onSrc, onSrcAuto, moved)

    @Test
    fun `kinds, fetch action and auto-fetch silence`() {
        val p = ReleasesPlan.plan(all, seeded = true) { it.anilistId == 3L }
        p.notes.map { it.kind to it.fetch } shouldBe listOf(
            NoteKind.OUT_TODAY to false, NoteKind.ON_SOURCE to true, NoteKind.MOVED to false,
        )
        p.silent shouldBe listOf(onSrcAuto)
        p.notes.last().quiet shouldBe true
        p.notes.first().quiet shouldBe false
    }

    @Test
    fun `the first run notifies nothing and clears everything`() {
        val p = ReleasesPlan.plan(all, seeded = false) { false }
        p.notes shouldBe emptyList()
        p.silent shouldBe all
    }

    @Test
    fun `only what was handled is cleared, a failed post stays pending`() {
        val p = ReleasesPlan.plan(all, seeded = true) { it.anilistId == 3L }
        val posted = p.notes.take(2).map { it.event } // posting threw on the third
        ReleasesPlan.remaining(all, p.silent + posted) shouldBe listOf(moved)
    }

    @Test
    fun `more than three countable notes collapse into a summary`() {
        val notes = ReleasesPlan.plan(List(4) { ReleaseEvent(EventKind.OUT_TODAY, it.toLong(), 1) }, true) { false }.notes
        ReleasesPlan.split(notes).first shouldBe notes
        ReleasesPlan.split(notes).second shouldBe emptyList()
        ReleasesPlan.split(notes.take(3)) shouldBe (emptyList<Note>() to notes.take(3))
    }

    @Test
    fun `on-source notes stay single and do not count toward the summary`() {
        val outs = List(3) { Note(ReleaseEvent(EventKind.OUT_TODAY, it.toLong(), 1), NoteKind.OUT_TODAY, false) }
        val srcs = List(3) { Note(ReleaseEvent(EventKind.ON_SOURCES, 10L + it, 2, source = "X"), NoteKind.ON_SOURCE, true) }
        ReleasesPlan.split(outs + srcs) shouldBe (emptyList<Note>() to outs + srcs) // 3 countable: no summary
        val four = outs + Note(ReleaseEvent(EventKind.OUT_TODAY, 9, 1), NoteKind.OUT_TODAY, false)
        val (summed, singles) = ReleasesPlan.split(four + srcs)
        summed shouldBe four
        singles shouldBe srcs
    }

    @Test
    fun `the seeded flag needs rows in the store`() {
        ReleasesPlan.seedNow(seeded = false, storeHasRows = false) shouldBe false
        ReleasesPlan.seedNow(seeded = false, storeHasRows = true) shouldBe true
        ReleasesPlan.seedNow(seeded = true, storeHasRows = false) shouldBe true
    }

    @Test
    fun `auto-fetch explicit choice wins, plan to read is always off`() {
        ReleasesPlan.autoFetchOn(null) shouldBe false
        ReleasesPlan.autoFetchOn(WatchedSeries(1, "t", autoFetch = true)) shouldBe true
        ReleasesPlan.autoFetchOn(WatchedSeries(1, "t", autoFetch = false)) shouldBe false
        // even when he is on the latest, even with an explicit true
        ReleasesPlan.autoFetchOn(WatchedSeries(1, "t", status = WatchStatus.PLAN)) shouldBe false
        ReleasesPlan.autoFetchOn(WatchedSeries(1, "t", status = WatchStatus.PLAN, autoFetch = true)) shouldBe false
        // explicit false wins over "on the latest"
        ReleasesPlan.autoFetchOn(WatchedSeries(1, "t", lastKnownVol = 5, autoFetch = false), listOf(row(6, VolState.ON_SOURCES))) shouldBe false
    }

    private val day = LocalDate.parse("2026-11-25")
    private fun row(vol: Int, state: VolState, id: Long = 1) = VolumeRow(id, vol, day, state, null, day, day)

    @Test
    fun `default is on only when he has everything up to the newest minus one`() {
        val s = WatchedSeries(1, "t", lastKnownVol = 5)
        // no row yet: on
        ReleasesPlan.autoFetchOn(s) shouldBe true
        // newest 6 on sources, he has 5: behind 0
        ReleasesPlan.autoFetchOn(s, listOf(row(6, VolState.ON_SOURCES))) shouldBe true
        // newest 7: behind 1 (6 is missing)
        ReleasesPlan.autoFetchOn(s, listOf(row(7, VolState.ON_SOURCES))) shouldBe false
        // has 1-3, newest 20: the back catalogue is not fetched
        ReleasesPlan.autoFetchOn(WatchedSeries(1, "t", lastKnownVol = 3), listOf(row(20, VolState.ON_SOURCES))) shouldBe false
        // another series' rows do not count
        ReleasesPlan.autoFetchOn(s, listOf(row(9, VolState.ON_SOURCES, id = 2))) shouldBe true
    }

    @Test
    fun `no known place means auto-fetch off and no behind note`() {
        ReleasesPlan.autoFetchOn(WatchedSeries(1, "t", lastKnownVol = 0), listOf(row(6, VolState.ON_SOURCES))) shouldBe false
        ReleasesPlan.autoFetchOn(WatchedSeries(1, "t", lastKnownVol = 0, autoFetch = true)) shouldBe true // explicit still wins
        WatchList.behind(0, 20) shouldBe 0
        WatchList.behind(5, 6) shouldBe 0
        WatchList.behind(5, 20) shouldBe 14
        WatchList.behind(25, 20) shouldBe 0
    }

    @Test
    fun `only events with an auto-fetch in flight are silent, the others keep their Fetch note`() {
        val running = setOf(2L to 6) // the pending attempts after AutoFetch.run
        val p = ReleasesPlan.plan(listOf(onSrc, onSrcAuto), seeded = true) { e -> (e.anilistId to e.vol) in running }
        p.silent shouldBe listOf(onSrc)
        p.notes.map { it.event to it.fetch } shouldBe listOf(onSrcAuto to true)
    }
}
