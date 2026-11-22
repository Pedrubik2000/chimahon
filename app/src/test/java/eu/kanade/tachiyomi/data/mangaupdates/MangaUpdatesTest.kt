package eu.kanade.tachiyomi.data.mangaupdates

import eu.kanade.tachiyomi.data.anilist.AniListLibrary
import eu.kanade.tachiyomi.data.mangaupdates.MangaUpdates.Candidate
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MangaUpdatesTest {

    private fun resource(name: String) = javaClass.getResource(name)!!.readText()

    private val loafer = AniListLibrary.Entry(
        mediaId = 98478,
        status = "CURRENT",
        native = "スキップとローファー",
        romaji = "Skip to Loafer",
        english = "Skip and Loafer",
        mediaStatus = "RELEASING",
        format = "MANGA",
        year = 2018,
    )

    @Test
    fun `search hits carry the title and the matched name`() {
        val hits = MangaUpdates.parseSearch(resource("/mangaupdates/search.json"))
        hits.size shouldBe 3
        hits[0] shouldBe Candidate(4563152379, listOf("Skip to Loafer", "スキップとローファー"), "Manga", 2018)
        MangaUpdates.best(hits, loafer) shouldBe hits[0]
    }

    @Test
    fun `series record gives associated names, status and completed`() {
        val s = MangaUpdates.parseSeries(resource("/mangaupdates/series.json"))
        s.candidate.seriesId shouldBe 4563152379
        s.candidate.year shouldBe 2018
        ("スキップとローファー" in s.candidate.titles) shouldBe true
        s.status shouldBe "13 Volumes (Ongoing)"
        s.completed shouldBe false
        MangaUpdates.confident(s.candidate, loafer) shouldBe true
        MangaUpdates.volumeCount(s.status) shouldBe 13
    }

    @Test
    fun `confident match needs exact name key, a manga type and the year within one`() {
        val right = Candidate(1, listOf("Skip to Loafer"), "Manga", 2018)
        MangaUpdates.confident(right, loafer) shouldBe true
        MangaUpdates.confident(right.copy(type = "Novel"), loafer) shouldBe false
        MangaUpdates.confident(right.copy(type = "Artbook"), loafer) shouldBe false
        MangaUpdates.confident(right.copy(year = 2023), loafer) shouldBe false
        MangaUpdates.confident(right.copy(year = 2019), loafer) shouldBe true
        MangaUpdates.confident(right.copy(year = null), loafer) shouldBe true
        // no contains / fuzzy matching
        MangaUpdates.confident(right.copy(titles = listOf("Skip to Loafer Anthology")), loafer) shouldBe false
        MangaUpdates.confident(right.copy(titles = listOf("Skip")), loafer) shouldBe false
        // an associated name (here the english one, punctuation aside) is enough
        MangaUpdates.confident(right.copy(titles = listOf("Sukippu", "Skip & Loafer!")), loafer.copy(english = "Skip & Loafer")) shouldBe true
        // a light novel only takes a Novel record
        MangaUpdates.confident(right, loafer.copy(format = "NOVEL")) shouldBe false
        MangaUpdates.confident(right.copy(type = "Novel"), loafer.copy(format = "NOVEL")) shouldBe true
    }

    @Test
    fun `two confident candidates, the exact year or none`() {
        val a = Candidate(1, listOf("Skip to Loafer"), "Manga", 2018)
        val b = Candidate(2, listOf("Skip to Loafer"), "Manga", 2019)
        MangaUpdates.best(listOf(a, b), loafer) shouldBe a
        MangaUpdates.best(listOf(a, b.copy(year = 2018)), loafer) shouldBe null
        MangaUpdates.best(listOf(a, b), loafer.copy(year = null)) shouldBe null
        MangaUpdates.best(listOf(a, a), loafer) shouldBe a
        MangaUpdates.best(emptyList(), loafer) shouldBe null
    }

    @Test
    fun `volume count from the status text`() {
        MangaUpdates.volumeCount("13 Volumes (Ongoing)") shouldBe 13
        MangaUpdates.volumeCount("26 Volumes (Complete)") shouldBe 26
        MangaUpdates.volumeCount("1 Volume (Complete)") shouldBe 1
        MangaUpdates.volumeCount("18 Volumes (Complete)\n9 Volumes (Complete; Bunko)") shouldBe 18
        // real text of Rurouni Kenshin
        MangaUpdates.volumeCount(
            "28 Tankoubon Volumes (Complete)  \n22 Kanzenban Volumes (Complete)  \n14 Bunkoban Volumes (Complete)",
        ) shouldBe 28
        MangaUpdates.volumeCount("12 Volumes (Ongoing)<BR>6 Volumes (Hiatus; Bunko)") shouldBe 12
        MangaUpdates.volumeCount("84 Chapters (Ongoing)") shouldBe null
        MangaUpdates.volumeCount("84 Chapters (Ongoing)\n3 Volumes (Complete; Bunko)") shouldBe null
        MangaUpdates.volumeCount("Oneshot (Complete)") shouldBe null
        MangaUpdates.volumeCount("") shouldBe null
        MangaUpdates.volumeCount(null) shouldBe null
    }
}
