package eu.kanade.tachiyomi.ui.glass.downloads

import eu.kanade.tachiyomi.data.download.RealDebridQueue
import eu.kanade.tachiyomi.data.download.RealDebridQueue.State
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

class GlassDownloadsTest {

    private fun row(mediaId: Long?, state: State, vararg volumes: Int, reason: String? = null) = RealDebridQueue.Row(
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
        GlassDownloads.cells(n, read = 2, here = 3, rows = rows) shouldBe listOf(
            Cell.READ, Cell.READ, Cell.HERE, Cell.HERE, Cell.DOWNLOADING, Cell.QUEUED, Cell.QUEUED, Cell.NONE, Cell.FAILED,
        )
        GlassDownloads.volumeCount(26, 2, 3, rows) shouldBe 26
        GlassDownloads.volumeCount(0, 2, 3, emptyList()) shouldBe 3
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
}
