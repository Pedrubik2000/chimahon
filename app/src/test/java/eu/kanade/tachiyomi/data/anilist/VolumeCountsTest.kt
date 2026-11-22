package eu.kanade.tachiyomi.data.anilist

import eu.kanade.tachiyomi.data.anilist.VolumeCounts.Known
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class VolumeCountsTest {

    private val day = 24 * 60 * 60 * 1000L

    @Test
    fun `merge keeps the highest count and never erases one`() {
        VolumeCounts.merge(null, 8, "coverage", 1) shouldBe Known(8, "coverage", 1)
        VolumeCounts.merge(Known(8, "coverage", 1), 5, "mangadex", 2) shouldBe Known(8, "coverage", 1)
        VolumeCounts.merge(Known(8, "coverage", 1), 9, "mangadex", 2) shouldBe Known(9, "mangadex", 2)
        VolumeCounts.merge(Known(8, "coverage", 1), null, "mangadex", 2) shouldBe Known(8, "coverage", 1)
        // nothing found = "asked on", refreshed
        VolumeCounts.merge(null, null, "mangadex", 2) shouldBe Known(null, "mangadex", 2)
        VolumeCounts.merge(Known(null, "mangadex", 1), 0, "mangadex", 3) shouldBe Known(null, "mangadex", 3)
        VolumeCounts.merge(Known(null, "mangadex", 1), 4, "coverage", 3) shouldBe Known(4, "coverage", 3)
    }

    @Test
    fun `asked again only when nothing was found 30 days ago`() {
        val now = 100 * day
        VolumeCounts.shouldAsk(null, now) shouldBe true
        VolumeCounts.shouldAsk(Known(12, "mangadex", 0), now) shouldBe false
        VolumeCounts.shouldAsk(Known(null, "mangadex", now - 29 * day), now) shouldBe false
        VolumeCounts.shouldAsk(Known(null, "mangadex", now - 30 * day), now) shouldBe true
    }

    @Test
    fun `counts leave out asked-only titles`() {
        VolumeCounts.countsOf(mapOf(1L to Known(8), 2L to Known(null, "mangadex", 5))) shouldBe mapOf(1L to 8)
    }
}
