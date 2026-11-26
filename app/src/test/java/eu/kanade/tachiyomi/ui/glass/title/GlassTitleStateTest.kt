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

    @Test
    fun `primary does not resume a chapter-range file above the total`() {
        val all = (1..18).map { vol(it, read = true) } + vol(146)
        GlassTitleState.primary(GlassTitleState.inRange(all, 18), 0, 18, emptySet()) shouldBe Primary.None
        // unknown total: nothing is hidden
        GlassTitleState.inRange(all, 0) shouldBe all
        GlassTitleState.primary(GlassTitleState.inRange(all, 0), 0, 0, emptySet()) shouldBe Primary.Resume(vol(146))
    }

    @Test
    fun `nextUnread skips a chapter-range file and volumes above the total`() {
        // file-name parse as the title page: "Vol. 13.cbz" is volume 13, "ch146-153.rar" is 146 (capped by the total)
        fun file(name: String, id: Long, read: Boolean = false, page: Long = 0) = tachiyomi.domain.chapter.model.Chapter.create()
            .copy(id = id, read = read, lastPageRead = page, url = "MASHLE/$name")
        fun vol(n: Int, read: Boolean = false, page: Long = 0) = file("Vol. $n.cbz", n.toLong(), read, page)
        val range = file("ch146-153.rar", 999)
        val chapters = (11..18).map { vol(it, read = it <= 12) } + range
        GlassTitleState.nextUnread(chapters, emptySet(), 18, 0)?.id shouldBe 13L
        GlassTitleState.nextUnread(chapters, setOf("MASHLE/Vol. 13.cbz"), 18, 0)?.id shouldBe 14L
        GlassTitleState.nextUnread(listOf(range), emptySet(), 18, 0) shouldBe null
        // an in-progress volume wins over the lowest unread one
        val partway = chapters.map { if (it.id == 15L) it.copy(lastPageRead = 7) else it }
        GlassTitleState.nextUnread(partway, emptySet(), 18, 0)?.id shouldBe 15L
        // AniList progress 12, nothing read here, files 1..18: resume at 13; total unknown (0) = no cap
        val all = (1..18).map { vol(it) }
        GlassTitleState.nextUnread(all, emptySet(), 18, 12)?.id shouldBe 13L
        GlassTitleState.nextUnread(all, emptySet(), 0, 12)?.id shouldBe 13L
        GlassTitleState.nextUnread(all + range, emptySet(), 0, 18)?.id shouldBe 999L
    }

    // Mashle: 1-2 read on AniList only, 3-12 read here, 13 reading, 14 unread here, 15 not here
    private fun selectCells() = GlassTitleState.cells(
        15,
        (3..12).map { vol(it, read = true) } + vol(13, page = 4) + vol(14),
        emptyMap(),
        readUpTo = 12,
        totalKnown = true,
    )

    @Test
    fun `select read picks read volumes on the tablet, not the reading one`() {
        val cells = selectCells()
        GlassTitleState.selectRead(cells, 12, 13) shouldBe (3..12).toSet()
        GlassTitleState.selectable(cells, 13) shouldBe ((3..12) + 14).toSet()
        GlassTitleState.readNotHere(cells) shouldBe listOf(1, 2)
    }

    @Test
    fun `quick picks, range, get and delete targets`() {
        // 1-2 read on AniList only, 3-12 read here, 13 reading, 14 unread here, 15 not here, 16 queued, 17 failed
        val cells = GlassTitleState.cells(
            17,
            (3..12).map { vol(it, read = true) } + vol(13, page = 4) + vol(14),
            mapOf(16 to Active(1L, true, 0.5f)),
            readUpTo = 12,
            totalKnown = true,
            failed = setOf(17),
        )
        GlassTitleState.pick(cells, GlassTitleState.Pick.ALL) shouldBe (1..17).toSet()
        GlassTitleState.pick(cells, GlassTitleState.Pick.READ) shouldBe (1..12).toSet()
        GlassTitleState.pick(cells, GlassTitleState.Pick.UNREAD_HERE) shouldBe setOf(13, 14)
        GlassTitleState.pick(cells, GlassTitleState.Pick.NOT_FETCHED) shouldBe setOf(15, 17)
        GlassTitleState.range(cells, 5, 3) shouldBe setOf(3, 4, 5)
        GlassTitleState.range(cells, 16, 40) shouldBe setOf(16, 17)
        // Get: no file and not queued (read on AniList too); Delete: a file, never the one being read
        GlassTitleState.gettable(cells, (1..17).toSet()) shouldBe listOf(1, 2, 15, 17)
        GlassTitleState.deletable(cells, setOf(2, 3, 13, 14, 16), 13) shouldBe setOf(3, 14)
        GlassTitleState.upTo(cells, 4) shouldBe setOf(3, 4)
    }

    @Test
    fun `quick picks and range never include the locked reading volume`() {
        val cells = selectCells() // 13 is being read
        val locked = GlassTitleState.locked(cells, 13)
        locked shouldBe setOf(13)
        GlassTitleState.Pick.entries.forEach { GlassTitleState.pick(cells, it, locked).contains(13) shouldBe false }
        GlassTitleState.pick(cells, GlassTitleState.Pick.UNREAD_HERE, locked) shouldBe setOf(14)
        GlassTitleState.range(cells, 12, 15, locked) shouldBe setOf(12, 14, 15)
    }

    @Test
    fun `delete read goes for read volumes on the tablet, the sheet says what stays`() {
        val cells = selectCells()
        val goes = GlassTitleState.selectRead(cells, 12, 13)
        goes shouldBe (3..12).toSet()
        val sum = GlassTitleState.deleteSummary(cells, goes, 13)
        sum.goes shouldBe (3..12).toList()
        sum.reading shouldBe listOf(13)
        sum.stays shouldBe listOf(14)
        sum.queued shouldBe emptyList()
        // nothing read on the tablet: no button
        GlassTitleState.selectRead(GlassTitleState.cells(3, listOf(vol(1)), emptyMap()), 0, null) shouldBe emptySet()
        // the one being read is never in a delete, even when asked for
        GlassTitleState.deleteSummary(cells, setOf(13, 14), 13).goes shouldBe listOf(14)
    }

    @Test
    fun `a volume under AniList's count on the tablet counts as read, the reading one never selectable`() {
        val cells = GlassTitleState.cells(5, listOf(vol(2), vol(3, page = 7), vol(5)), emptyMap(), readUpTo = 3)
        GlassTitleState.selectRead(cells, 3, 3) shouldBe setOf(2)
        GlassTitleState.selectable(cells, 3) shouldBe setOf(2, 5)
        // read AND on the device = READ with its file (the tile shows check + phone); not here = READ without; unread here = HERE
        cells[1].state shouldBe VolState.READ // vol 2, within AniList's 3, file here
        cells[1].local?.number shouldBe 2
        cells[2].state shouldBe VolState.HERE // vol 3 is being read (page 7): AniList's count does not make it READ
        cells[4].state shouldBe VolState.HERE // vol 5 unread
        cells[3].state shouldBe VolState.NONE // vol 4 not here, not read
        cells[0].state shouldBe VolState.READ // vol 1 not here, within the count: read, no file
        cells[0].local shouldBe null
    }

    @Test
    fun `read row beyond AniList's count is READ too, deleted read volume is a plain check`() {
        val cells = GlassTitleState.cells(4, listOf(vol(4, read = true)), emptyMap(), readUpTo = 2)
        cells[3].state shouldBe VolState.READ
        cells[3].local?.number shouldBe 4
        cells[1].state shouldBe VolState.READ // vol 2: AniList only, file deleted
        cells[1].local shouldBe null
        GlassTitleState.readNotHere(cells) shouldBe listOf(1, 2)
    }

    @Test
    fun `boxes only when the source is found with volumes`() {
        GlassTitleState.showBoxes(x3dl) shouldBe true
        GlassTitleState.showBoxes(null) shouldBe false // checking
        GlassTitleState.showBoxes(rawxt) shouldBe false // not on source
        GlassTitleState.showBoxes(Coverage(4, "Nyaa", Coverage.State.NEEDS_VISIT)) shouldBe false
        GlassTitleState.showBoxes(Coverage(5, "Err", Coverage.State.ERROR, message = "x")) shouldBe false
        GlassTitleState.showBoxes(Coverage(6, "NoNum", Coverage.State.FOUND)) shouldBe false // found, no volume numbers
    }

    @Test
    fun `summary line for a source with a gap, and the volumes no source has`() {
        GlassTitleState.missingLine((1..24).toSet(), 25) shouldBe "24 of 25 · vol. 25 missing"
        GlassTitleState.missingLine((1..25).toSet(), 25) shouldBe null // the screen says "All 25"
        val sources = listOf(1L, 2L, 3L)
        val cov = mapOf(1L to x3dl, 2L to excnn, 3L to rawxt)
        GlassTitleState.unavailable(cov, sources, 13) shouldBe setOf(13)
        GlassTitleState.unavailable(cov - 3L, sources, 13) shouldBe emptySet() // one still loading: unknown
        GlassTitleState.unavailable(cov + (3L to Coverage(3, "Rawxt", Coverage.State.NEEDS_VISIT)), sources, 13) shouldBe emptySet()
        // found with no volume numbers = not answered: it might have vol 13
        GlassTitleState.unavailable(cov + (3L to Coverage(3, "Rawxt", Coverage.State.FOUND)), sources, 13) shouldBe emptySet()
    }

    @Test
    fun `source bar rows count the gaps, 15 volumes at 280 dp take two rows with numbers`() {
        val items = GlassTitleState.sourceBoxes(List(15) { BoxState.HAVE })
        val (rows, numbers) = boxLayout(items, 280f)
        rows.size shouldBe 2
        numbers shouldBe true
        boxLayout(items, 400f).first.size shouldBe 1 // 15 * 18 + 14 * 2 = 298 fits in 400
        boxLayout(items, 400f).second shouldBe true
    }

    @Test
    fun `source rows split by weight`() {
        val items = GlassTitleState.sourceBoxes(List(30) { BoxState.HAVE })
        splitRows(items, 1).size shouldBe 1
        splitRows(items, 3).map { it.size } shouldBe listOf(10, 10, 10)
    }

    @Test
    fun `read only on the tablet is not picked by select read and warns when picked by hand`() {
        // AniList read 3; vol 4 read here only; vol 6 half-read but not the Resume volume; vol 7 is
        val cells = GlassTitleState.cells(
            8,
            listOf(vol(2, read = true), vol(4, read = true), vol(5), vol(6, page = 9), vol(7, page = 2)),
            emptyMap(),
            readUpTo = 3,
            totalKnown = true,
        )
        GlassTitleState.selectRead(cells, 3, 7) shouldBe setOf(2)
        GlassTitleState.locked(cells, 7) shouldBe setOf(6, 7)
        GlassTitleState.selectable(cells, 7) shouldBe setOf(2, 4, 5)
        GlassTitleState.readOnlyHere(cells, setOf(2, 4, 5), 3) shouldBe listOf(4) // 5 is unread: no mark lost
        GlassTitleState.readOnlyHere(cells, setOf(2), 3) shouldBe emptyList()
    }

    @Test
    fun `size text`() {
        GlassTitleState.sizeText(1_430_000_000) shouldBe "1.4 GB"
        GlassTitleState.sizeText(320_000_000) shouldBe "320 MB"
        GlassTitleState.sizeText(0) shouldBe "0 MB"
        GlassTitleState.sizeText(310_000) shouldBe "310 KB"
        GlassTitleState.sizeText(200) shouldBe "1 KB"
    }

    @Test
    fun `volume slider max - AniList count, else the highest known plus five, never under progress`() {
        GlassTitleState.volumeSliderMax(18, 12, emptyList()) shouldBe 18
        GlassTitleState.volumeSliderMax(18, 20, emptyList()) shouldBe 20
        GlassTitleState.volumeSliderMax(null, 3, listOf(vol(7), vol(5))) shouldBe 12
        GlassTitleState.volumeSliderMax(0, 9, listOf(vol(7))) shouldBe 14
        GlassTitleState.volumeSliderMax(null, 0, emptyList()) shouldBe 5
    }

    @Test
    fun `missing runs merge into one gap, a single missing volume keeps its box`() {
        // Nyaa: 26 volumes, 17 and 20-24 missing
        val has = (1..26).filter { it != 17 && it !in 20..24 }.toSet()
        val boxes = GlassTitleState.boxes(Coverage(1, "Nyaa", Coverage.State.FOUND, volumes = has.associateWith { "v$it" }), 26, emptySet(), emptyMap())
        val merged = GlassTitleState.sourceBoxes(boxes)
        merged.filterIsInstance<SourceBox.Gap>() shouldBe listOf(SourceBox.Gap(20, 24))
        merged.filterIsInstance<SourceBox.Vol>().filter { it.state == BoxState.NONE } shouldBe listOf(SourceBox.Vol(17, BoxState.NONE))
        merged.size shouldBe 26 - 6 + 1 + 1 // 20 volumes, the lone 17, the one gap
        GlassTitleState.missingLine(has, 26) shouldBe "20 of 26 · vol. 17, 20–24 missing"
        GlassTitleState.missingLine((1..26).toSet(), 26) shouldBe null
    }
}
