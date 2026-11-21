package eu.kanade.tachiyomi.ui.glass

import eu.kanade.tachiyomi.data.anilist.AniListLibrary
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class GlassTitlesTest {

    private fun entry(id: Long, status: String, volumes: Int? = null, updatedAt: Long = 0, native: String = "t$id", romaji: String? = null) =
        AniListLibrary.Entry(mediaId = id, status = status, volumes = volumes, updatedAt = updatedAt, native = native, romaji = romaji)

    private val next = NextVolume(chapterId = 9, name = "Vol. 03", number = 3.0, lastPageRead = 40)

    // 1 every volume here, read yesterday, has a next volume; 2 some here, read today, no next; 3 nothing, planning;
    // 4 some here, volume count unknown, completed; 5 reading, nothing here, updated recently
    private val list = listOf(
        GlassTitle(entry(1, "CURRENT", volumes = 6, updatedAt = 100, native = "お兄ちゃんはおしまい！", romaji = "Onii-chan wa Oshimai!"), 11, 6, 2, lastRead = 1_000, next = next),
        GlassTitle(entry(2, "REPEATING", volumes = 14, updatedAt = 50), 12, 9, 9, lastRead = 2_000),
        GlassTitle(entry(3, "PLANNING", volumes = 3, updatedAt = 900)),
        GlassTitle(entry(4, "COMPLETED", volumes = null), 14, 5, 5, lastRead = 500),
        GlassTitle(entry(5, "CURRENT", volumes = 10, updatedAt = 3_000)),
    )

    private fun ids(l: List<GlassTitle>) = l.map { it.mediaId }

    @Test
    fun `on this tablet buckets`() {
        list.map { GlassTitles.here(it) } shouldBe listOf(Here.EVERY, Here.SOME, Here.NOTHING, Here.SOME, Here.NOTHING)
    }

    @Test
    fun `filter by search, AniList status and tablet`() {
        ids(GlassTitles.filter(list, "", ListFilter.READING, null)) shouldBe listOf(1L, 2L, 5L)
        ids(GlassTitles.filter(list, "", ListFilter.ALL, Here.NOTHING)) shouldBe listOf(3L, 5L)
        ids(GlassTitles.filter(list, "oshimai", ListFilter.ALL, null)) shouldBe listOf(1L) // romaji, any case
        ids(GlassTitles.filter(list, "おしまい", ListFilter.READING, Here.EVERY)) shouldBe listOf(1L)
        ids(GlassTitles.filter(list, "", ListFilter.COMPLETED, Here.SOME)) shouldBe listOf(4L)
    }

    @Test
    fun sort() {
        // last read on the tablet (millis), else AniList updatedAt (seconds → millis)
        ids(GlassTitles.sort(list, GlassSort.LAST_READ)) shouldBe listOf(5L, 3L, 2L, 1L, 4L)
        ids(GlassTitles.sort(list, GlassSort.VOLUMES_HERE)) shouldBe listOf(2L, 1L, 4L, 5L, 3L)
        ids(GlassTitles.sort(list, GlassSort.TITLE)) shouldBe listOf(2L, 3L, 4L, 5L, 1L)
    }

    @Test
    fun hero() {
        // title 2 was read later but has no next volume: title 1 resumes
        GlassTitles.hero(list) shouldBe Hero(list[0], resume = true)
        // nothing to resume → the most recently updated Reading title, no Resume
        GlassTitles.hero(list.map { it.copy(next = null) }) shouldBe Hero(list[4], resume = false)
        GlassTitles.hero(list.filter { it.entry.status == "PLANNING" }) shouldBe null
    }

    @Test
    fun `build joins local volumes and keeps AniList progress`() {
        val e = listOf(entry(1, "CURRENT", volumes = 6).copy(progressVolumes = 4), entry(2, "PLANNING"))
        val built = GlassTitles.build(e, mapOf(1L to GlassTitles.Local(mangaId = 11, volumes = 6, read = 2, lastRead = 0)))
        built[0] shouldBe GlassTitle(e[0], localMangaId = 11, volumesHere = 6, volumesRead = 4, lastRead = null)
        built[1] shouldBe GlassTitle(e[1])
        built[0].readFraction shouldBe 4f / 6f
        built[1].readFraction shouldBe null
    }

    @Test
    fun `volume label`() {
        GlassTitles.volumeLabel(next) shouldBe "3"
        GlassTitles.volumeLabel(next.copy(number = 3.5)) shouldBe "3.5"
        GlassTitles.volumeLabel(next.copy(number = -1.0)) shouldBe "Vol. 03"
    }
}
