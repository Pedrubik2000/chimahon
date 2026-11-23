package eu.kanade.tachiyomi.source.online.all

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class VolumeLabelsTest {

    private fun fixture(name: String) = javaClass.getResource("/$name")!!.readText()

    private fun v(label: String) = VolumeLabels.volumes(label)

    @Test
    fun `labels the parsers make from the saved pages`() {
        // Excnn post: label lines; the archive name itself is a date
        ExcnnParser.parsePost(fixture("excnn/post.html")).volumes.map { v(it.label) } shouldBe listOf(
            setOf(1), setOf(2), setOf(1, 2), setOf(3), setOf(4, 5, 6),
        )
        v("excnn20-11-2026a07") shouldBe emptySet()

        // X3DL post: Uploady link text, a range with a second scan of part of it
        X3dlParser.parsePost(fixture("x3dl/post.html")).volumes.map { v(it.label) } shouldBe listOf(
            setOf(27), setOf(26), setOf(25), (22..24).toSet(), (15..21).toSet(), (1..14).toSet(),
        )
        v("Draft_King_27.rar (138.86 MB)") shouldBe setOf(27)
        v("[クロマツテツロウ] ドラフトキング 第01-27巻") shouldBe (1..27).toSet()

        // BsZip post: file names with a version letter
        WpRawParser.parsePost(WpRaw.BSZIP, fixture("wpraw/p16.html")).volumes.flatMap { v(it.label) }
            .containsAll(listOf(1, 2)) shouldBe true
        // A-Z Manga post: "_v01-02"
        WpRawParser.parsePost(WpRaw.A_ZMANGA, fixture("wpraw/p3.html")).volumes.flatMap { v(it.label) }
            .containsAll(listOf(1, 2)) shouldBe true
        // Manga Zone's Shonen Jump issues are year-issue, not volumes
        WpRawParser.parsePost(WpRaw.MANGA_ZONE, fixture("wpraw/p11.html")).volumes
            .filter { it.label.startsWith("Shonen_Jump_20") }
            .all { v(it.label).isEmpty() } shouldBe true

        // Nyaa: one torrent title each; every "第..巻" title gives volumes, magazines give none
        val titles = NyaaParser.parseRss(fixture("nyaa_rss.xml")).map { it.title }
        titles.filter { "巻" in it }.all { v(it).isNotEmpty() } shouldBe true
        titles.first { it.startsWith("DEAR BOYS ACT 4") }.let { v(it) } shouldBe (1..24).toSet()
        titles.first { it.startsWith("スタジオカバナ") }.let { v(it) } shouldBe (1..6).toSet()
        titles.first { it.startsWith("鋼の彼女と僕") }.let { v(it) } shouldBe setOf(1)
        titles.first { it.startsWith("二度目の恋は") }.let { v(it) } shouldBe setOf(2, 3)
        titles.filter { "号" in it || "No." in it }.all { v(it).isEmpty() } shouldBe true
    }

    @Test
    fun `shapes`() {
        v("04-06") shouldBe setOf(4, 5, 6)
        v("01-02b") shouldBe setOf(1, 2)
        v("03") shouldBe setOf(3)
        v("第01-27巻") shouldBe (1..27).toSet()
        v("[松田あお] 地下アイドルを影で支える俺 全03巻") shouldBe setOf(1, 2, 3)
        v("全３巻") shouldBe setOf(1, 2, 3)
        v("Vol. 12") shouldBe setOf(12)
        v("v12") shouldBe setOf(12)
        v("第12巻") shouldBe setOf(12)
        v("保健室の鍵閉めてっ_v01-02") shouldBe setOf(1, 2)
        v("Kaguharasan_no_fetish_02w") shouldBe setOf(2)
        v("15-21 20-21s") shouldBe (15..21).toSet()
        v("MASHLE_01s-05") shouldBe (1..5).toSet()
        v("MASHLE_06w-08w") shouldBe setOf(6, 7, 8)
        v("MASHLE_13s-16s+") shouldBe (13..16).toSet()
        v("Isekaisikuojisan_01b-08") shouldBe (1..8).toSet()
        v("01-14 01-04別スキャン") shouldBe (1..14).toSet()
        v("Shonen_Jump_2026-04-05") shouldBe emptySet()
        v("2024") shouldBe emptySet()
        v("(138.86 MB)") shouldBe emptySet()
        v("Daimajin Kanon | 大魔神カノン") shouldBe emptySet()
        v("[Hatakeyama_Tohya]_Koyoi,_Hitozuma_no_Yawahada_no_Naka_de") shouldBe emptySet()
    }
}
