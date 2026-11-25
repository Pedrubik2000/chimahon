package chimahon.releases

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class VolumeMatcherTest {
    private fun d(s: String) = LocalDate.parse(s)
    private fun l(title: String, date: String? = "2027-01-04", ebook: Boolean = false, vol: Int? = null, source: String = "s", publisher: String? = null) =
        Listing(title, vol, date?.let(::d), publisher, ebook, source)

    private fun vols(jp: String, last: Int, rows: List<Listing>, publisher: String? = null) =
        VolumeMatcher.match(jp, last, rows, publisher).map { it.vol }

    @Test fun numberForms() {
        val rows = listOf(
            l("ブルーロック（４１）"), // full-width brackets and digits
            l("ブルーロック(042)"), // zero padded
            l("ブルーロック ４３"), // full-width digit after a space
            l("ブルーロック　44巻"), // ideographic space, 巻
            l("ブルーロック45"), // no separator
        )
        assertEquals(listOf(41, 42, 43, 44, 45), vols("ブルーロック", 40, rows))
    }

    @Test fun fullWidthJpTitleMatchesToo() {
        assertEquals(listOf(81), vols("ＫＩＮＧＤＯＭ", 80, listOf(l("KINGDOM 81"))))
    }

    @Test fun onlyAfterLastKnownAndLowestFirst() {
        val rows = listOf(l("ダンダダン 27"), l("ダンダダン 25"), l("ダンダダン 26"))
        assertEquals(listOf(26, 27), vols("ダンダダン", 25, rows))
        assertEquals(26, VolumeMatcher.match("ダンダダン", 25, rows).first().vol)
        assertEquals(emptyList<Int>(), vols("ダンダダン", 27, rows))
    }

    @Test fun droppedKinds() {
        val rows = listOf(
            l("マッシュル-MASHLE- 第155話"),
            l("小説 ブルーロック 15"),
            l("ブルーロック ノベライズ 3"),
            l("TVアニメ『葬送のフリーレン』副読本 1 フリーレン＆ヒンメルがわかる魔法"),
            l("キングダム BOXセット 4 成瞬の変"),
            l("キングダム magazine 2026"),
            l("葬送のフリーレン ぬり絵BOOK"),
            l("キングダム 話 81"),
        )
        assertEquals(emptyList<Int>(), vols("ブルーロック", 0, rows))
        assertEquals(emptyList<Int>(), vols("キングダム", 0, rows))
        assertEquals(emptyList<Int>(), vols("葬送のフリーレン", 0, rows))
        assertEquals(emptyList<Int>(), vols("マッシュル-MASHLE-", 0, rows))
    }

    @Test fun specialEditionAttachedToTheNumberIsASpecial() {
        val m = VolumeMatcher.match("ゆびさきと恋々", 14, listOf(l("ゆびさきと恋々（１５）特装版", "2027-01-15")))
        assertEquals(listOf(15), m.map { it.vol })
        assertTrue(m.single().special)
        assertEquals(emptyList<Int>(), vols("ゆびさきと恋々", 14, listOf(l("ゆびさきと恋々（１５）小説"))))
    }

    @Test fun seriesWhoseOwnNameHasADropWordKeepTheirVolumes() {
        assertEquals(listOf(5), vols("小説の神様", 4, listOf(l("小説の神様 5"))))
        assertEquals(listOf(3), vols("話がしたいよ", 2, listOf(l("話がしたいよ 3"))))
        assertEquals(listOf(2), vols("BOX～箱の中に何かいる～", 1, listOf(l("BOX～箱の中に何かいる～ 2"))))
        // a non-special tail is still rejected by the regex
        assertEquals(emptyList<Int>(), vols("小説の神様", 4, listOf(l("小説の神様 5 小説版"))))
    }

    @Test fun spinOffsDoNotMatch() {
        val rows = listOf(
            l("うちがキングダム ３"), l("ストリート・キングダム 最終版"), l("キングダム 魂の決戦 映画ノベライズ"),
            l("ダンダダン ダイズカン"), l("ダンダダン流 自分を好きになる言葉"), l("ダンダダン 2026 カレンダー"),
            l("キングダム 81"),
        )
        assertEquals(listOf(81), vols("キングダム", 0, rows))
        assertEquals(emptyList<Int>(), vols("ダンダダン", 0, rows))
    }

    @Test fun specialEditionOnlyGivesTheDate() {
        val onlySpecial = listOf(l("SAKAMOTO DAYS 31巻 鈴木祐斗描き下ろしイラストグッズ付き同梱版", "2027-04-30"))
        val c = VolumeMatcher.match("SAKAMOTO DAYS", 30, onlySpecial).single()
        assertEquals(31, c.vol)
        assertEquals(d("2027-04-30"), c.date)
        assertTrue(c.special)
        // a regular row of the same source wins over the special one
        val both = listOf(l("葬送のフリーレン 15 特別短編小説付き特装版", "2025-12-20"), l("葬送のフリーレン １５", "2025-12-18"))
        val b = VolumeMatcher.match("葬送のフリーレン", 14, both).single()
        assertEquals(d("2025-12-18"), b.date)
        assertEquals(false, b.special)
        // an unknown suffix is not a volume
        assertEquals(emptyList<Int>(), vols("SAKAMOTO DAYS", 0, listOf(l("SAKAMOTO DAYS 31 ファンブック"))))
    }

    @Test fun ebookDuplicateDroppedButOnlyEbookKept() {
        val rows = listOf(l("ダンダダン 24", "2026-06-04"), l("ダンダダン 24", "2026-06-04", ebook = true), l("ダンダダン 25", "2026-09-04", ebook = true))
        val c = VolumeMatcher.match("ダンダダン", 0, rows)
        assertEquals(listOf(24, 25), c.map { it.vol })
        assertEquals(d("2026-09-04"), c[1].date) // no paper row for 25: the e-book date is all we have
    }

    @Test fun ndlOwnRecordsUseTheSeparateNumber() {
        val c = VolumeMatcher.match("ダンダダン", 25, listOf(l("ダンダダン", null, vol = 26, source = "ndl"), l("ダンダダン", null, source = "ndl")))
        assertEquals(26, c.single().vol)
        assertEquals(null, c.single().date)
    }

    @Test fun publisherPin() {
        val rows = listOf(l("ブルーロック 41", publisher = "講談社"), l("ブルーロック 42", publisher = "ある出版社"), l("ブルーロック 43", publisher = null))
        assertEquals(listOf(41, 43), vols("ブルーロック", 0, rows, publisher = "講談社"))
    }

    @Test fun oneCandidatePerVolAndSource() {
        val rows = listOf(l("ダンダダン 26", "2026-11-04", source = "a"), l("ダンダダン 26", "2026-11-05", source = "b"), l("ダンダダン 26", "2026-11-03", source = "a"))
        val c = VolumeMatcher.match("ダンダダン", 0, rows)
        assertEquals(listOf("a" to d("2026-11-03"), "b" to d("2026-11-05")), c.map { it.source to it.date })
    }

    // the saved pages
    @Test fun realPages() {
        val dd = BooksOrJp.parse(fixture("booksorjp-dandadan.html"))
        assertEquals(listOf(26 to d("2026-11-04"), 27 to d("2027-01-04")), VolumeMatcher.match("ダンダダン", 25, dd).map { it.vol to it.date })
        val sk = BooksOrJp.parse(fixture("booksorjp-sakamoto.html"))
        val skm = VolumeMatcher.match("SAKAMOTO DAYS", 28, sk)
        assertEquals(listOf(29, 30, 31), skm.map { it.vol })
        assertEquals(listOf(false, false, true), skm.map { it.special })
        assertEquals(d("2027-04-30"), skm.last().date)
        val ki = BooksOrJp.parse(fixture("booksorjp-kingdom.html"))
        assertEquals(listOf(80 to d("2026-08-19"), 81 to d("2026-11-18")), VolumeMatcher.match("キングダム", 79, ki).map { it.vol to it.date })
        val fr = BooksOrJp.parse(fixture("booksorjp-frieren.html"))
        assertEquals(listOf(15 to d("2025-12-18")), VolumeMatcher.match("葬送のフリーレン", 14, fr).map { it.vol to it.date })
        assertEquals(emptyList<Int>(), vols("葬送のフリーレン", 15, fr))
        val kodansha = KodanshaCalendar.parse(fixture("kodansha-202611.html")) + KodanshaCalendar.parse(fixture("kodansha-202701.html"))
        assertEquals(listOf(41 to d("2026-11-17"), 42 to d("2027-01-15")), VolumeMatcher.match("ブルーロック", 40, kodansha, "講談社").map { it.vol to it.date })
        val ndl = NdlSearch.parse(fixture("ndl-dandadan.xml"))
        val n = VolumeMatcher.match("ダンダダン", 25, ndl)
        assertEquals(listOf(26 to null, 27 to d("2027-01-04")), n.map { it.vol to it.date })
    }
}
