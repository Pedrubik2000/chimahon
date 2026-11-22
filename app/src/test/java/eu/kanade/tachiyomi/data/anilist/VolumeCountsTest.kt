package eu.kanade.tachiyomi.data.anilist

import eu.kanade.tachiyomi.data.anilist.VolumeCounts.Known
import eu.kanade.tachiyomi.data.mangaupdates.MangaUpdates
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
    fun `merge keeps the MangaUpdates fields`() {
        VolumeCounts.merge(Known(8, "mangaupdates", 1, mu = 8, muAt = 1), 9, "coverage", 2) shouldBe
            Known(9, "coverage", 2, mu = 8, muAt = 1)
    }

    @Test
    fun `MangaUpdates wins over MangaDex both ways but not over higher coverage`() {
        val mu = MangaUpdates.Result(1, 13, completed = false)
        VolumeCounts.withMangaUpdates(Known(20, "mangadex", 1), mu, null, 5) shouldBe
            Known(13, "mangaupdates", 5, mu = 13, muAt = 5)
        VolumeCounts.withMangaUpdates(Known(9, "mangadex", 1), mu, null, 5) shouldBe
            Known(13, "mangaupdates", 5, mu = 13, muAt = 5)
        VolumeCounts.withMangaUpdates(null, mu, null, 5) shouldBe Known(13, "mangaupdates", 5, mu = 13, muAt = 5)
        // coverage above it is kept, from the seed or from the stored count
        VolumeCounts.withMangaUpdates(Known(20, "mangadex", 1), mu, 14, 5) shouldBe
            Known(14, "coverage", 5, mu = 13, muAt = 5)
        VolumeCounts.withMangaUpdates(Known(15, "coverage", 1), mu, null, 5) shouldBe
            Known(15, "coverage", 5, mu = 13, muAt = 5)
        VolumeCounts.withMangaUpdates(Known(10, "coverage", 1), mu, 12, 5) shouldBe
            Known(13, "mangaupdates", 5, mu = 13, muAt = 5)
        // no match / no number: only "asked on"
        VolumeCounts.withMangaUpdates(Known(9, "mangadex", 1), null, null, 5) shouldBe Known(9, "mangadex", 1, muAt = 5)
        VolumeCounts.withMangaUpdates(null, MangaUpdates.Result(1, null, completed = true), null, 5) shouldBe
            Known(muAt = 5, muDone = true)
        VolumeCounts.withMangaUpdates(Known(12, "mangaupdates", 1, mu = 12, muAt = 1), null, null, 5) shouldBe
            Known(12, "mangaupdates", 1, mu = 12, muAt = 5)
    }

    @Test
    fun `MangaUpdates re-ask rules`() {
        val now = 100 * day
        VolumeCounts.askMangaUpdates(null, releasing = true, now) shouldBe true
        // filled from MangaDex before MangaUpdates was a source: asked once
        VolumeCounts.askMangaUpdates(Known(12, "mangadex", 0), releasing = false, now) shouldBe true
        // a stated count: again after 14 days, only while releasing
        val stated = Known(13, "mangaupdates", now - 13 * day, mu = 13, muAt = now - 13 * day)
        VolumeCounts.askMangaUpdates(stated, releasing = true, now) shouldBe false
        VolumeCounts.askMangaUpdates(stated.copy(muAt = now - 14 * day), releasing = true, now) shouldBe true
        VolumeCounts.askMangaUpdates(stated.copy(muAt = now - 14 * day), releasing = false, now) shouldBe false
        // complete on MangaUpdates: never
        VolumeCounts.askMangaUpdates(stated.copy(muAt = 1, muDone = true), releasing = true, now) shouldBe false
        // no match: after 30 days
        VolumeCounts.askMangaUpdates(Known(muAt = now - 29 * day), releasing = true, now) shouldBe false
        VolumeCounts.askMangaUpdates(Known(muAt = now - 30 * day), releasing = true, now) shouldBe true
        // MangaDex is not asked once MangaUpdates gave a count, but is when it gave none
        VolumeCounts.shouldAsk(stated, now) shouldBe false
        VolumeCounts.shouldAsk(Known(muAt = now), now) shouldBe true
    }

    @Test
    fun `counts leave out asked-only titles`() {
        VolumeCounts.countsOf(mapOf(1L to Known(8), 2L to Known(null, "mangadex", 5))) shouldBe mapOf(1L to 8)
    }
}
