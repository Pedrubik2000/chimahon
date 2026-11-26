package eu.kanade.tachiyomi.ui.glass.downloads

import eu.kanade.tachiyomi.data.download.RealDebridImportJob
import eu.kanade.tachiyomi.data.download.RealDebridQueue
import eu.kanade.tachiyomi.data.download.RealDebridQueue.State
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

class GlassDownloadsTest {

    private fun row(
        mediaId: Long?,
        state: State,
        vararg volumes: Int,
        reason: String? = null,
        missing: List<Int> = emptyList(),
        delivered: List<Int> = emptyList(),
    ) = RealDebridQueue.Row(
        workId = UUID.randomUUID(),
        mediaId = mediaId,
        volumes = volumes.toList(),
        sourceId = 1L,
        state = state,
        step = null,
        bytes = 0,
        total = 0,
        speed = 0,
        reason = reason ?: if (state == State.FAILED) "not on Excnn" else null,
        servedBy = null,
        failures = emptyList(),
        delivered = delivered,
        missing = missing,
    )

    @Test
    fun `rows group by title, running first, shared links last, cancelled dropped`() {
        val groups = GlassDownloads.group(
            listOf(
                row(1, State.QUEUED, 6),
                row(null, State.QUEUED),
                row(2, State.FAILED, 22),
                row(3, State.DONE, 1),
                row(1, State.RUNNING, 5),
                row(4, State.RUNNING, 2),
                row(5, State.FAILED, 1, reason = "Cancelled"),
            ),
        )
        groups.map { it.mediaId } shouldBe listOf(1L, 4L, 2L, 3L, null)
        groups.first().rows.size shouldBe 2
        groups.first().active shouldBe true
    }

    @Test
    fun `a failed volume queued again is no longer failed`() {
        val g = TitleGroup(7, listOf(row(7, State.FAILED, 3), row(7, State.QUEUED, 3), row(7, State.FAILED, 4)))
        g.failedVolumes shouldBe listOf(4)
    }

    @Test
    fun `bar cells from the queue, then read, then on the tablet`() {
        val rows = listOf(row(1, State.RUNNING, 5), row(1, State.QUEUED, 6, 7), row(1, State.FAILED, 9), row(1, State.DONE, 4))
        val n = GlassDownloads.volumeCount(null, read = 2, here = 3, rows = rows)
        n shouldBe 9
        GlassDownloads.cells(n, read = 2, here = setOf(1, 2, 3, 4), rows = rows) shouldBe listOf(
            Cell.READ, Cell.READ, Cell.HERE, Cell.HERE, Cell.DOWNLOADING, Cell.QUEUED, Cell.QUEUED, Cell.NONE, Cell.FAILED,
        )
        GlassDownloads.volumeCount(26, 2, 3, rows) shouldBe 26
        GlassDownloads.volumeCount(0, 2, 3, emptyList()) shouldBe 3
    }

    @Test
    fun `a done job is not on the tablet unless its volumes are`() {
        // MASHLE - the 1-5 pack held only 3-5, already here; 17-18 came
        val rows = listOf(row(1, State.DONE, 17, 18), row(1, State.DONE, 1, 2, 3, 4, 5))
        val here = setOf(3, 4, 5, 17, 18)
        GlassDownloads.cells(18, read = 0, here = here, rows = rows) shouldBe
            List(18) { i -> if (i + 1 in here) Cell.HERE else Cell.NONE }
    }

    @Test
    fun `a queued pack with volumes already here shows and counts only the new ones`() {
        val pack = row(1, State.QUEUED, 1, 2, 3, 4, 5)
        GlassDownloads.cells(6, read = 0, here = setOf(3, 4, 5), rows = listOf(pack)) shouldBe listOf(
            Cell.QUEUED, Cell.QUEUED, Cell.HERE, Cell.HERE, Cell.HERE, Cell.NONE,
        )
        GlassDownloads.toAdd(listOf(pack), setOf(3, 4, 5)) shouldBe 2
        GlassDownloads.toAdd(listOf(row(null, State.QUEUED)), emptySet()) shouldBe 1 // a shared link
    }

    @Test
    fun `a row with missing volumes fails for those only`() {
        val pack = row(1, State.FAILED, 1, 2, 3, 4, 5, reason = "Vol. 1, 2 were not in the pack (X3DL)", missing = listOf(1, 2))
        GlassDownloads.cells(5, read = 0, here = setOf(3, 4, 5), rows = listOf(pack)) shouldBe listOf(
            Cell.FAILED, Cell.FAILED, Cell.HERE, Cell.HERE, Cell.HERE,
        )
        val g = TitleGroup(1, listOf(pack))
        g.failed shouldBe listOf(pack)
        g.failedVolumes shouldBe listOf(1, 2)
        // queued again for 1 and 2 from another source: no longer failed
        TitleGroup(1, listOf(pack, row(1, State.QUEUED, 1, 2))).failed shouldBe emptyList()
    }

    @Test
    fun `a cancelled or covered job is not failed in the bar`() {
        val cancelled = row(1, State.FAILED, 2, reason = "Cancelled")
        GlassDownloads.cells(3, read = 1, here = emptySet(), rows = listOf(cancelled)) shouldBe
            listOf(Cell.READ, Cell.NONE, Cell.NONE)
        TitleGroup(1, listOf(cancelled)).failed shouldBe emptyList()
        // failed, then done by a later job but not on the tablet: failed (the title page's rule); on it: not failed
        val rows = listOf(row(1, State.FAILED, 2), row(1, State.DONE, 2))
        GlassDownloads.cells(2, read = 0, here = emptySet(), rows = rows) shouldBe listOf(Cell.NONE, Cell.FAILED)
        GlassDownloads.cells(2, read = 0, here = setOf(2), rows = rows) shouldBe listOf(Cell.NONE, Cell.HERE)
    }

    @Test
    fun `with the tablet's volumes known a failed row counts for those not here, old done rows cover nothing`() {
        // MASHLE - the done 1-5 row is from before jobs reported what they delivered; 1-2 never came
        val rows = listOf(
            row(1, State.DONE, 13, 14, 15, 16),
            row(1, State.DONE, 17, 18),
            row(1, State.DONE, 1, 2, 3, 4, 5),
            row(1, State.FAILED, 1, 2, 3, 4, reason = "Vol. 1, 2, 3, 4 were not found; dead link: Rawxt"),
        )
        val g = GlassDownloads.group(rows, mapOf(1L to (3..18).toSet())).single()
        g.failed shouldBe listOf(rows[3])
        g.failedVolumes shouldBe listOf(1, 2)
        g.failedVolumesOf(rows[3]) shouldBe listOf(1, 2) // the expanded row's label, not the pack's 1-4
        GlassDownloads.cells(18, read = 0, here = (3..18).toSet(), rows = rows).take(3) shouldBe
            listOf(Cell.FAILED, Cell.FAILED, Cell.HERE)
        // AniList says 1-3 were read, but 1-2 are not here and their fetch failed: failed, not read
        GlassDownloads.cells(18, read = 3, here = (3..18).toSet(), rows = rows).take(3) shouldBe
            listOf(Cell.FAILED, Cell.FAILED, Cell.READ)
        // all on the tablet: nothing failed
        GlassDownloads.group(rows, mapOf(1L to (1..18).toSet())).single().let {
            it.failed shouldBe emptyList()
            it.failedVolumes shouldBe emptyList()
        }
        // queued again elsewhere: covered
        TitleGroup(1, rows + row(1, State.QUEUED, 1, 2), (3..18).toSet()).failed shouldBe emptyList()
        // the tablet's volumes not known: the old rule (the done 1-5 row covers them)
        TitleGroup(1, rows).failed shouldBe emptyList()
    }

    @Test
    fun `a done job covers a failed volume only if it delivered it`() {
        // MASHLE - the done 1-5 pack delivered only 3-5; the 1-4 fallback was a dead link
        val done = listOf(
            row(1, State.DONE, 1, 2, 3, 4, 5, delivered = listOf(3, 4, 5), missing = listOf(1, 2)),
            row(1, State.DONE, 13, 14, 15, 16),
            row(1, State.DONE, 17, 18),
        )
        val dead = row(1, State.FAILED, 1, 2, 3, 4, reason = "Vol. 1, 2, 3, 4 were not found; dead link: Rawxt")
        val g = TitleGroup(1, done + dead)
        g.failed shouldBe listOf(dead)
        g.failedVolumes shouldBe listOf(1, 2)
        GlassDownloads.group(done + dead).single().failed.isEmpty() shouldBe false
        // newer builds name only the missing ones
        TitleGroup(1, done + dead.copy(missing = listOf(1, 2))).failedVolumes shouldBe listOf(1, 2)
        // a done row from before jobs reported what they delivered still covers what it was queued for
        TitleGroup(1, listOf(row(1, State.DONE, 1, 2), dead)).failedVolumes shouldBe listOf(3, 4)
    }

    @Test
    fun `a stored reason without the clause that restates the volumes`() {
        val shown = GlassDownloads::shownReason
        // every shape of RealDebridImportJob.missingReason
        shown("Vol. 1, 2, 3, 4 were not found; dead link: Rawxt") shouldBe "Dead link: Rawxt"
        shown("Vol. 1, 2 were not found; dead link: X3DL, Rawxt") shouldBe "Dead link: X3DL, Rawxt"
        shown("Vol. 1, 2 were not in the pack (X3DL); dead link: Rawxt") shouldBe "Not in the pack (X3DL); dead link: Rawxt"
        shown("Vol. 3 was not in the pack (X3DL, Excnn)") shouldBe "Not in the pack (X3DL, Excnn)"
        shown("Vol. 1, 2 were not in the pack") shouldBe ""
        shown("Vol. 1 was not in the pack; dead link: Rawxt") shouldBe "Dead link: Rawxt"
        // anything else as it is, capitalized
        shown("dead link: Rawxt") shouldBe "Dead link: Rawxt"
        shown("Failed: Real-Debrid /unrestrict/link: HTTP 503 busy") shouldBe "Failed: Real-Debrid /unrestrict/link: HTTP 503 busy"
        shown("Nothing added") shouldBe "Nothing added"
        shown(null) shouldBe ""
    }

    @Test
    fun `move a source up or down`() {
        val shown = listOf(10L, 20L, 30L, 40L) // 40 = newly installed, not yet in the pref
        GlassDownloads.move(shown, 40, -1) shouldBe listOf(10L, 20L, 40L, 30L)
        GlassDownloads.move(shown, 10, 1) shouldBe listOf(20L, 10L, 30L, 40L)
        GlassDownloads.move(shown, 10, -1) shouldBe shown // already first
        GlassDownloads.move(shown, 40, 1) shouldBe shown // already last
        GlassDownloads.move(shown, 99, 1) shouldBe shown // unknown id
    }

    @Test
    fun `switch a source on and off`() {
        GlassDownloads.toggle(listOf(1L, 2L), 2, on = true) shouldBe listOf(1L)
        GlassDownloads.toggle(listOf(1L), 2, on = false) shouldBe listOf(1L, 2L)
        GlassDownloads.toggle(listOf(1L, 2L), 2, on = false) shouldBe listOf(1L, 2L)
    }

    @Test
    fun `a finished row is labelled with the volumes it put on the tablet`() {
        // MASHLE: an old row asked for 1-5 (no delivered/missing output), only 3-5 came; 3-18 on the tablet
        val old = row(7, State.DONE, 1, 2, 3, 4, 5)
        val pack = row(7, State.DONE, 13, 14, 15, 16)
        val g = TitleGroup(7, listOf(old, pack, row(7, State.FAILED, 1, 2, 3, 4)), here = (3..18).toSet())
        g.doneVolumesOf(old) shouldBe listOf(3, 4, 5)
        g.doneVolumesOf(pack) shouldBe (13..16).toList()

        // the tablet unknown: the requested volumes; a row's own output first
        TitleGroup(7, listOf(old)).doneVolumesOf(old) shouldBe listOf(1, 2, 3, 4, 5)
        TitleGroup(7, emptyList()).doneVolumesOf(row(7, State.DONE, 1, 2, 3, missing = listOf(1))) shouldBe listOf(2, 3)
        TitleGroup(7, emptyList()).doneVolumesOf(row(7, State.DONE, 1, 2, 3, delivered = listOf(3))) shouldBe listOf(3)
        // none of it on the tablet any more
        TitleGroup(7, emptyList(), here = setOf(9)).doneVolumesOf(old) shouldBe emptyList()
    }

    @Test
    fun `a crawling running download is slow only when others of its own chain wait behind it`() {
        val links = RealDebridImportJob.WORK_LINKS
        val crawl = row(1, State.RUNNING, 1).copy(step = RealDebridImportJob.STEP_DOWNLOADING, speed = 180_000, chain = links)
        val waiting = row(2, State.QUEUED, 3).copy(chain = links)
        GlassDownloads.isSlow(crawl, listOf(crawl, waiting)) shouldBe true
        GlassDownloads.isSlow(crawl, listOf(crawl)) shouldBe false
        // another chain runs in parallel: nothing waits behind it; a job from before the tag has no chain
        GlassDownloads.isSlow(crawl, listOf(crawl, waiting.copy(chain = RealDebridImportJob.WORK_TORRENTS))) shouldBe false
        GlassDownloads.isSlow(crawl.copy(chain = null), listOf(crawl, waiting.copy(chain = null))) shouldBe false
        GlassDownloads.isSlow(crawl.copy(speed = 9_000_000), listOf(crawl, waiting)) shouldBe false
        GlassDownloads.isSlow(crawl.copy(speed = 0), listOf(crawl, waiting)) shouldBe false
    }
    @Test
    fun `a queue moves a whole group to a place and keeps the inner order`() {
        // (manga, chapter)
        val q = listOf(1L to 1, 1L to 2, 2L to 1, 3L to 1, 3L to 2)
        GlassDownloads.moveGroup(q, { it.first }, 3, 0).map { it.first } shouldBe listOf(3L, 3L, 1L, 1L, 2L)
        GlassDownloads.moveGroup(q, { it.first }, 1, Int.MAX_VALUE).map { it.first } shouldBe listOf(2L, 3L, 3L, 1L, 1L)
        GlassDownloads.moveGroup(q, { it.first }, 3, 0).filter { it.first == 3L } shouldBe listOf(3L to 1, 3L to 2)
        GlassDownloads.moveGroup(q, { it.first }, 9, 0) shouldBe q
    }
}
