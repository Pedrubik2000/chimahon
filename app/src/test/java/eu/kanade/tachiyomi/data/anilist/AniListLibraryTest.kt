package eu.kanade.tachiyomi.data.anilist

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AniListLibraryTest {

    private val entries = AniListLibrary.parseList(javaClass.getResource("/anilist_list.json")!!.readText())

    @Test
    fun `list collection response`() {
        // six entries; the custom list repeats Nozaki-kun and is skipped
        entries.map { it.mediaId } shouldBe listOf(59211L, 58533L, 100664L, 116266L, 33082L, 108556L)
        entries.map { it.status }.distinct() shouldBe listOf("CURRENT", "COMPLETED", "PLANNING")

        val nozaki = entries[0]
        nozaki.progress shouldBe 138
        nozaki.progressVolumes shouldBe 17
        nozaki.score shouldBe 80
        nozaki.updatedAt shouldBe 1790542514L
        nozaki.native shouldBe "月刊少女野崎くん"
        nozaki.romaji shouldBe "Gekkan Shoujo Nozaki-kun"
        nozaki.english shouldBe "Monthly Girls' Nozaki-kun"
        nozaki.volumes shouldBe null
        nozaki.mediaStatus shouldBe "RELEASING"
        nozaki.format shouldBe "MANGA"
        nozaki.genres shouldBe listOf("Comedy", "Romance")
        nozaki.tags.first() shouldBe AniListLibrary.Tag("Drawing", 95, false)
        nozaki.averageScore shouldBe 82
        nozaki.popularity shouldBe 26395
        nozaki.year shouldBe 2011
        nozaki.coverLarge!!.endsWith("/cover/large/nx59211-ltc4FW9inrms.jpg") shouldBe true

        entries[1].tags[2].spoiler shouldBe true // "Rehabilitation", a media spoiler
        entries[2].volumes shouldBe 20
        entries[2].chapters shouldBe 189
        entries.first { it.mediaId == 33082L }.banner shouldBe null
    }

    @Test
    fun `local series match by seriesKey on native, romaji or english`() {
        fun match(vararg names: String) = AniListLibrary.match(names.toList(), entries)?.mediaId

        match("[Group] Kanojo mo Kanojo v01-03 (Digital)") shouldBe 116266L // romaji, volume marks dropped
        match("日常") shouldBe 33082L // native folder name
        match("Spy x Family") shouldBe 108556L // english, case and punctuation ignored
        match("SPY×FAMILY 第05巻") shouldBe 108556L
        match("Some Title", "ijiranaide nagatoro-san") shouldBe 100664L // second name (folder) matches
        match("Yotsuba&!") shouldBe null
        match("", "  ") shouldBe null
    }
}
