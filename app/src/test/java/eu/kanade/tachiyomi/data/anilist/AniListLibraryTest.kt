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

    // The tablet's 21 series folders (2026-11-22). Natives given are AniList's; the other entries are made up by
    // romaji. "Tadade wa daka remasen" gets a romaji unlike its folder: only the ComicInfo native finds it.
    private val folderList = listOf(
        e(1, "Ao no Hako"), e(2, "BECK"), e(3, "Batsuichi de Nakimushi na Otonari-san"), e(4, "Chainsaw Man"),
        e(5, "Doujima-kun wa Doujinai"), e(6, "Grand Blue"), e(7, "Hagure Idol Jigoku-hen"),
        e(8, "Joshikousei no Mudazukai", "女子高生の無駄づかい"), e(9, "Kill Me Baby"), e(10, "Kuso Onna ni Sachi Are"),
        e(114960, "MASHLE", "マッシュル-MASHLE-", "Mashle: Magic and Muscles"), e(12, "Mahou Shoujo ni Akogarete"),
        e(13, "Recorder to Randoseru"), e(14, "Sasha-chan to Classmate Otaku-kun"), e(15, "Skip to Loafer"),
        e(16, "Super no Ura de Yani Suu Futari", "スーパーの裏でヤニ吸うふたり"),
        e(17, "Tada de wa Dakaremasen yo", "タダでは抱かれません"), e(18, "Tobaku Mokushiroku Kaiji", "賭博黙示録カイジ"),
        e(19, "Watashi no Kobushi wo Uketomete!"), e(20, "Yaiteru Futari"), e(21, "Zeikin de Katta Hon", "税金で買った本"),
        // a second entry by the same romaji (a spin-off, a remake): the native title decides
        e(99, "Zeikin de Katta Hon", "税金で買った本 外伝"),
    )

    private fun e(id: Long, romaji: String, native: String? = null, english: String? = null) =
        AniListLibrary.Entry(mediaId = id, status = "CURRENT", romaji = romaji, native = native, english = english)

    /** A folder as adoption reads it: its ComicInfo `<Summary>` ("native\n\nenglish\n\n...") and `<Web>`. */
    private fun folder(name: String, summary: String? = null, web: String? = null): Long? {
        val (native, english) = AniListLibrary.summaryTitles(summary)
        val id = AniListLibrary.aniListId(web)
        return AniListLibrary.matchFolder(id, native, listOfNotNull(name, english), folderList)?.mediaId
    }

    @Test
    fun `every series folder on the tablet finds its title`() {
        val names = listOf(
            "Ao no Hako", "BECK", "Batsuichidenakimushinaotonarisan", "Chainsaw Man", "Doujimakun wa Doujinai",
            "Grand Blue", "Hagure Idol Jigokuhen", "Joshikousei no Mudazukai", "Kill Me Baby", "Kuso Onna ni Sachi Are",
            "MASHLE", "Mahou Shoujo ni Akogarete", "Recorder to Randoseru", "Sasha-chan to Classmate Otaku-kun",
            "Skip to Loafer", "Super no Ura de Yani Suu Futari", "Tadade wa daka remasen", "Tobaku Mokushiroku Kaiji",
            "Watashi no Kobushi wo Uketomete!", "Yaiteru Futari", "Zeikin de Katta Hon",
        )
        val natives = mapOf(
            "Joshikousei no Mudazukai" to "女子高生の無駄づかい",
            "MASHLE" to "マッシュル-MASHLE-\n\nMashle: Magic and Muscles\n\nThis is a world of magic...",
            "Super no Ura de Yani Suu Futari" to "スーパーの裏でヤニ吸うふたり",
            "Tadade wa daka remasen" to "タダでは抱かれません\n\nI Won't Sleep With You for Free\n\n...",
            "Tobaku Mokushiroku Kaiji" to "賭博黙示録カイジ",
            "Zeikin de Katta Hon" to "税金で買った本",
        )
        val ids = names.map { folder(it, natives[it]) }
        ids shouldBe listOf<Long>(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 114960, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21)

        folder("Tadade wa daka remasen") shouldBe null // by name alone it can't be found
        folder("Zeikin de Katta Hon") shouldBe 21L // by name alone: the first entry by that romaji
        folder("Zeikin de Katta Hon", "税金で買った本 外伝") shouldBe 99L // the exact native wins over the name
    }

    @Test
    fun `an AniList id in the folder decides alone`() {
        folder("whatever", web = "https://anilist.co/manga/114960") shouldBe 114960L
        folder("MASHLE", web = "https://anilist.co/manga/555") shouldBe null // not on the list: no name guess
        AniListLibrary.aniListId("https://anilist.co/manga/114960/MASHLE/") shouldBe 114960L
        AniListLibrary.aniListId("https://mangadex.org/title/abc") shouldBe null
        AniListLibrary.aniListId(null) shouldBe null
    }

    @Test
    fun `the AniList id is added to a ComicInfo, nothing else changes`() {
        val xml = "<?xml version='1.0' encoding='UTF-8' ?>\n<ComicInfo>\n  <Series>MASHLE</Series>\n</ComicInfo>"
        AniListLibrary.withWeb(xml, "https://anilist.co/manga/114960") shouldBe
            "<?xml version='1.0' encoding='UTF-8' ?>\n<ComicInfo>\n  <Series>MASHLE</Series>\n" +
            "  <Web>https://anilist.co/manga/114960</Web>\n</ComicInfo>"
        AniListLibrary.withWeb("<ComicInfo><Web>x</Web></ComicInfo>", "y") shouldBe null
        AniListLibrary.withWeb("not xml", "y") shouldBe null
        AniListLibrary.summaryTitles("マッシュル-MASHLE-\n\nMashle: Magic and Muscles\n\nThis is") shouldBe
            ("マッシュル-MASHLE-" to "Mashle: Magic and Muscles")
        AniListLibrary.summaryTitles(null) shouldBe (null to null)
    }
}
