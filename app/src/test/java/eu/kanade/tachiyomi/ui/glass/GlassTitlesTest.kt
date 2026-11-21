package eu.kanade.tachiyomi.ui.glass

import eu.kanade.tachiyomi.data.anilist.AniListLibrary
import eu.kanade.tachiyomi.ui.glass.discover.Length
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

    // Browse-style groups: 1 Action+Romance finished 6 vols, tags A(80) B(70); 2 Romance releasing 14 vols, tags A(90)
    // C(65); 3 Comedy releasing 3 vols, tag B(60) + spoiler S(95) + low-rank L(40); 4 Action, volumes unknown;
    // 5 Action 40 vols, finished, tag C(99)
    private fun AniListLibrary.Entry.faceted(genres: List<String>, mediaStatus: String?, vararg tags: AniListLibrary.Tag) =
        copy(genres = genres, mediaStatus = mediaStatus, tags = tags.toList())

    private fun tag(name: String, rank: Int, spoiler: Boolean = false) = AniListLibrary.Tag(name, rank, spoiler)

    private val facets = listOf(
        list[0].copy(entry = list[0].entry.faceted(listOf("Action", "Romance"), "FINISHED", tag("A", 80), tag("B", 70))),
        list[1].copy(entry = list[1].entry.faceted(listOf("Romance"), "RELEASING", tag("A", 90), tag("C", 65))),
        list[2].copy(entry = list[2].entry.faceted(listOf("Comedy"), "RELEASING", tag("B", 60), tag("S", 95, spoiler = true), tag("L", 40))),
        list[3].copy(entry = list[3].entry.faceted(listOf("Action"), null)),
        list[4].copy(entry = list[4].entry.copy(volumes = 40).faceted(listOf("Action"), "FINISHED", tag("C", 99))),
    )

    private fun shown(f: TitleFilters, query: String = "") = ids(GlassTitles.filter(facets, query, f))

    @Test
    fun `genres need every selected one`() {
        shown(TitleFilters(genres = setOf("Action"))) shouldBe listOf(1L, 4L, 5L)
        shown(TitleFilters(genres = setOf("Action", "Romance"))) shouldBe listOf(1L)
        shown(TitleFilters(genres = setOf("Action")), query = "oshimai") shouldBe listOf(1L)
        TitleFilters(genres = setOf("Action")).toggleGenre("Action") shouldBe TitleFilters()
    }

    @Test
    fun `publishing status`() {
        shown(TitleFilters(published = "FINISHED")) shouldBe listOf(1L, 5L)
        shown(TitleFilters(published = "RELEASING")) shouldBe listOf(2L, 3L)
    }

    @Test
    fun `length buckets, unknown volumes match none`() {
        shown(TitleFilters(length = Length.SHORT)) shouldBe listOf(3L) // 3 vols
        shown(TitleFilters(length = Length.MEDIUM)) shouldBe listOf(1L, 2L) // 6, 14
        shown(TitleFilters(length = Length.LONG)) shouldBe emptyList()
        shown(TitleFilters(length = Length.VERY_LONG)) shouldBe listOf(5L) // 40
        Length.entries.none { GlassTitles.inLength(null, it) } shouldBe true
        GlassTitles.inLength(5, Length.SHORT) shouldBe true
        GlassTitles.inLength(16, Length.LONG) shouldBe true
        GlassTitles.inLength(30, Length.LONG) shouldBe true
        GlassTitles.inLength(31, Length.VERY_LONG) shouldBe true
    }

    @Test
    fun `tags include, exclude and the three-tap cycle`() {
        shown(TitleFilters(tagsIn = setOf("A"))) shouldBe listOf(1L, 2L)
        shown(TitleFilters(tagsIn = setOf("A", "B"))) shouldBe listOf(1L)
        shown(TitleFilters(tagsOut = setOf("A"))) shouldBe listOf(3L, 4L, 5L)
        shown(TitleFilters(tagsIn = setOf("A"), tagsOut = setOf("C"))) shouldBe listOf(1L)
        val f = TitleFilters().cycleTag("A")
        f shouldBe TitleFilters(tagsIn = setOf("A"))
        f.cycleTag("A") shouldBe TitleFilters(tagsOut = setOf("A"))
        f.cycleTag("A").cycleTag("A") shouldBe TitleFilters()
    }

    @Test
    fun `spoiler and low-rank tags are never offered`() {
        val v = GlassTitles.view(facets, "", TitleFilters(), GlassSort.LAST_READ)
        v.tags.map { it.first }.toSet() shouldBe setOf("A", "B", "C")
        v.tags.first() shouldBe ("A" to 2) // ties broken A–Z
        GlassTitles.view(facets, "", TitleFilters(), GlassSort.LAST_READ, topTags = 1).tags shouldBe listOf("A" to 2)
        // a selected tag stays offered even when it falls out of the top list
        GlassTitles.view(facets, "", TitleFilters(tagsOut = setOf("C")), GlassSort.LAST_READ, topTags = 1).tags shouldBe
            listOf("C" to 2, "A" to 2)
    }

    @Test
    fun `faceted counts apply every other group`() {
        val f = TitleFilters(status = ListFilter.READING, genres = setOf("Action"), published = "FINISHED")
        val v = GlassTitles.view(facets, "", f, GlassSort.TITLE)
        ids(v.shown) shouldBe listOf(5L, 1L) // title sort: t5 before お兄ちゃん…
        // AniList counts ignore READING: Action + finished = 1, 5
        v.statusCounts[ListFilter.ALL] shouldBe 2
        v.statusCounts[ListFilter.READING] shouldBe 2
        v.statusCounts[ListFilter.COMPLETED] shouldBe 0
        // genre counts ignore Action: reading + finished = 1, 5; chips = the list's genres, Browse order
        v.genres shouldBe listOf("Action" to 2, "Comedy" to 0, "Romance" to 1)
        // status counts ignore FINISHED: reading + Action = 1, 5 (both finished), releasing none
        v.publishedCounts["FINISHED"] shouldBe 2
        v.publishedCounts["RELEASING"] shouldBe null
        v.lengthCounts shouldBe mapOf(Length.SHORT to 0, Length.MEDIUM to 1, Length.LONG to 0, Length.VERY_LONG to 1)
        v.hereCounts shouldBe mapOf(Here.EVERY to 1, Here.NOTHING to 1)
        // the count line = shown; Clear = the default filters show everything
        ids(GlassTitles.view(facets, "", TitleFilters(), GlassSort.TITLE).shown).size shouldBe 5
    }

    @Test
    fun `volume label`() {
        GlassTitles.volumeLabel(next) shouldBe "3"
        GlassTitles.volumeLabel(next.copy(number = 3.5)) shouldBe "3.5"
        GlassTitles.volumeLabel(next.copy(number = -1.0)) shouldBe "Vol. 03"
    }
}
