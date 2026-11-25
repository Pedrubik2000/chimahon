package eu.kanade.tachiyomi.ui.glass.title

import eu.kanade.tachiyomi.data.coverage.Coverage
import eu.kanade.tachiyomi.data.download.RealDebridQueue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

class GlassTitleStateTest {

    private val x3dl = Coverage(
        1,
        "X3DL",
        Coverage.State.FOUND,
        // vol. 1-8 in one pack, then singles 9-10
        volumes = (1..8).associateWith { "pack-1-8" } + (9..10).associateWith { "x$it" },
    )
    private val excnn = Coverage(2, "Excnn", Coverage.State.FOUND, volumes = (1..12).associateWith { "e$it" })
    private val rawxt = Coverage(3, "Rawxt", Coverage.State.NOT_FOUND)

    private fun vol(n: Int, read: Boolean = false, page: Long = 0) = LocalVolume(n * 100L, n, read, page)

    private fun row(vols: List<Int>, state: RealDebridQueue.State, source: Long, bytes: Long = 0, total: Long = 0) =
        RealDebridQueue.Row(
            workId = UUID.randomUUID(),
            mediaId = 7,
            volumes = vols,
            sourceId = source,
            state = state,
            step = null,
            bytes = bytes,
            total = total,
            speed = 0,
            reason = null,
            servedBy = null,
            failures = emptyList(),
        )

    @Test
    fun `boxes per source from coverage, tablet and queue`() {
        val active = GlassTitleState.active(listOf(row(listOf(11), RealDebridQueue.State.RUNNING, 2, 50, 100)))
        active.getValue(11) shouldBe Active(2, running = true, fraction = 0.5f)

        val b = GlassTitleState.boxes(excnn, 13, here = setOf(1, 2), active = active)
        b.take(3) shouldBe listOf(BoxState.HERE, BoxState.HERE, BoxState.HAVE)
        b[10] shouldBe BoxState.QUEUED // vol. 11 is coming from Excnn
        b[12] shouldBe BoxState.NONE // vol. 13: nowhere

        // X3DL doesn't hold vol. 11's job: plain coverage there
        GlassTitleState.boxes(x3dl, 13, emptySet(), active)[10] shouldBe BoxState.NONE
        GlassTitleState.boxes(rawxt, 3, emptySet(), active) shouldBe List(3) { BoxState.NONE }
        GlassTitleState.boxes(null, 2, emptySet(), active) shouldBe List(2) { BoxState.NONE }

        // finished and failed rows don't hold a volume
        GlassTitleState.active(listOf(row(listOf(3), RealDebridQueue.State.FAILED, 1))) shouldBe emptyMap()
    }

    @Test
    fun `fetch K covers the missing volumes, packs once`() {
        val order = GlassTitleState.fetchOrder(listOf(x3dl, excnn, rawxt), chosen = 1, fillGaps = true)
        val local = listOf(vol(1, read = true), vol(2))
        val active = mapOf(9 to Active(1, running = false, fraction = null))
        val missing = GlassTitleState.missing(13, local, active)
        missing shouldBe listOf(3, 4, 5, 6, 7, 8, 10, 11, 12, 13)

        // 3-8 come in X3DL's 1-8 pack; 10 from X3DL; 11-12 filled from Excnn; 13 from nobody
        GlassTitleState.plan(missing, order) shouldBe listOf(
            Pack(1, (1..8).toList()),
            Pack(1, listOf(10)),
            Pack(2, listOf(11)),
            Pack(2, listOf(12)),
        )
        GlassTitleState.fetchable(missing, order) shouldBe listOf(3, 4, 5, 6, 7, 8, 10, 11, 12)

        // fill gaps off: only the chosen source
        val only = GlassTitleState.fetchOrder(listOf(x3dl, excnn), chosen = 1, fillGaps = false)
        GlassTitleState.fetchable(missing, only) shouldBe listOf(3, 4, 5, 6, 7, 8, 10)
        // Excnn chosen: singles first, X3DL behind it
        GlassTitleState.plan(listOf(3), GlassTitleState.fetchOrder(listOf(x3dl, excnn), 2, true)) shouldBe
            listOf(Pack(2, listOf(3)))

        GlassTitleState.defaultSource(listOf(rawxt, excnn, x3dl)) shouldBe 2
    }

    @Test
    fun `primary pill`() {
        // a volume half read wins
        GlassTitleState.primary(listOf(vol(1, true), vol(2), vol(3, page = 40)), 0, 26, emptySet()) shouldBe
            Primary.Resume(vol(3, page = 40))
        // next unread after the last read
        GlassTitleState.primary(listOf(vol(1, true), vol(2)), 0, 26, emptySet()) shouldBe Primary.Resume(vol(2))
        // AniList says 4 read: the unread vol. 2 here is behind, fetch vol. 5
        GlassTitleState.primary(listOf(vol(1, true), vol(2)), 4, 26, emptySet()) shouldBe Primary.Fetch(5)
        // nothing here: vol. 1; vol. 1 queued: the next one
        GlassTitleState.primary(emptyList(), 0, 26, emptySet()) shouldBe Primary.Fetch(1)
        GlassTitleState.primary(emptyList(), 0, 26, setOf(1)) shouldBe Primary.Fetch(2)
        // all read
        GlassTitleState.primary(listOf(vol(1, true), vol(2, true)), 2, 2, emptySet()) shouldBe Primary.None
        // volume count unknown
        GlassTitleState.primary(emptyList(), 3, 0, emptySet()) shouldBe Primary.Fetch(4)
    }

    @Test
    fun `an empty archive does not count as on the tablet`() {
        GlassTitleState.counts(isDirectory = false, length = 0) shouldBe false
        GlassTitleState.counts(isDirectory = false, length = 10) shouldBe true
        GlassTitleState.counts(isDirectory = true, length = 0) shouldBe true
    }

    @Test
    fun `volumes, cells and stars`() {
        GlassTitleState.volumeNumber(3.0) shouldBe 3
        GlassTitleState.volumeNumber(3.5) shouldBe null
        GlassTitleState.volumeNumber(-1.0) shouldBe null
        GlassTitleState.dedupe(listOf(vol(2, true), vol(1), LocalVolume(9, 2, false, 0))).map { it.chapterId } shouldBe
            listOf(100L, 9L)

        GlassTitleState.volumeCount(null, listOf(excnn), listOf(vol(14))) shouldBe 14
        GlassTitleState.volumeCount(26, listOf(excnn), emptyList()) shouldBe 26
        GlassTitleState.volumeCount(18, emptyList(), listOf(vol(3), vol(153))) shouldBe 18
        GlassTitleState.cells(2, listOf(vol(1), vol(153)), emptyMap()).map { it.number } shouldBe listOf(1, 2, 153)
        GlassTitleState.volumeCount(null, emptyList(), emptyList()) shouldBe 0

        val cells = GlassTitleState.cells(
            4,
            listOf(vol(1, true), vol(2)),
            mapOf(3 to Active(1, running = true, fraction = 0.25f)),
        )
        cells.map { it.state } shouldBe listOf(VolState.READ, VolState.HERE, VolState.DOWNLOADING, VolState.NONE)
        cells[2].fraction shouldBe 0.25f

        listOf(0, 10, 20, 50, 70, 90, 100).map(GlassTitleState::stars) shouldBe listOf(0, 1, 1, 3, 4, 5, 5)
        GlassTitleState.statuses.all { GlassTitleState.listStatus(GlassTitleState.trackerStatus(it)) == it } shouldBe true
    }

    @Test
    fun `no cell above a known total`() {
        // MASHLE: 18 on AniList, a stray "Vol. 153.rar" in the folder
        GlassTitleState.cells(18, listOf(vol(1), vol(153)), emptyMap(), totalKnown = true).map { it.number } shouldBe (1..18).toList()
    }

    @Test
    fun `a failed volume shows as failed until it is queued again or on the tablet`() {
        // MASHLE: 18 volumes, 3-18 on the tablet, AniList read 1, vol. 1-2 failed (dead link)
        val local = (3..18).map { vol(it) }
        val cells = GlassTitleState.cells(18, local, emptyMap(), readUpTo = 1, totalKnown = true, failed = setOf(1, 2))
        cells.take(3).map { it.state } shouldBe listOf(VolState.FAILED, VolState.FAILED, VolState.HERE)

        // queued again: downloading wins
        val queued = mapOf(1 to Active(1, running = true, fraction = null))
        GlassTitleState.cells(18, local, queued, readUpTo = 1, failed = setOf(1, 2))[0].state shouldBe VolState.DOWNLOADING
        // now on the tablet: on tablet wins
        GlassTitleState.cells(18, local + vol(2), emptyMap(), readUpTo = 1, failed = setOf(1, 2))[1].state shouldBe VolState.HERE
    }

    @Test
    fun `MangaDex only when a volume is missing or the count is unknown`() {
        GlassTitleState.needsMangaDex(3, listOf(vol(1), vol(2), vol(3))) shouldBe false
        GlassTitleState.needsMangaDex(3, listOf(vol(1), vol(3))) shouldBe true
        GlassTitleState.needsMangaDex(null, listOf(vol(1))) shouldBe true
        GlassTitleState.needsMangaDex(0, emptyList()) shouldBe true
    }
}
