package chimahon.releases

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

internal fun fixture(name: String): String =
    (SourcesTest::class.java.getResourceAsStream("/releases/$name") ?: error("missing fixture $name")).use { String(it.readBytes(), Charsets.UTF_8) }

/** Each parser against the pages saved on 2026-11-25 (python urllib, browser User-Agent). */
class SourcesTest {
    private fun d(s: String) = LocalDate.parse(s)

    // ---- books.or.jp ----

    @Test fun tokenFromHome() {
        val t = BooksOrJp.parseToken(fixture("booksorjp-home.html"))
        assertEquals(40, t.length)
        assertThrows(ReleaseParseException::class.java) { BooksOrJp.parseToken("<html></html>") }
    }

    @Test fun booksDandadan() {
        val rows = BooksOrJp.parse(fixture("booksorjp-dandadan.html"))
        val v27 = rows.first { it.title == "ダンダダン 27" }
        assertEquals(d("2027-01-04"), v27.date)
        assertEquals("集英社", v27.publisher)
        assertFalse(v27.ebook)
        assertEquals(d("2026-11-04"), rows.first { it.title == "ダンダダン 26" }.date)
        assertEquals(d("2026-09-04"), rows.first { it.title == "ダンダダン 25" }.date)
        // 24 is listed twice with the same date: paper and e-book
        val v24 = rows.filter { it.title == "ダンダダン 24" }
        assertEquals(listOf(false, true), v24.map { it.ebook })
        assertEquals(setOf(d("2026-06-04")), v24.map { it.date }.toSet())
        assertTrue(rows.all { it.source == BooksOrJp.ID && it.title.isNotEmpty() })
    }

    @Test fun booksSakamotoKingdomFrieren() {
        val sk = BooksOrJp.parse(fixture("booksorjp-sakamoto.html"))
        assertEquals(d("2027-01-04"), sk.first { it.title == "SAKAMOTO DAYS 30" }.date)
        assertEquals(d("2026-11-04"), sk.first { it.title == "SAKAMOTO DAYS 29" }.date)
        assertEquals(d("2027-04-30"), sk.first { it.title.startsWith("SAKAMOTO DAYS 31巻") }.date)

        val ki = BooksOrJp.parse(fixture("booksorjp-kingdom.html"))
        assertEquals(d("2026-11-18"), ki.first { it.title == "キングダム 81" }.date)
        assertEquals(d("2026-08-19"), ki.first { it.title == "キングダム 80" }.date)
        // double-encoded entity in a BOX title is decoded once more
        assertTrue(ki.any { it.title.startsWith("キングダム BOXセット 4 成") && "&" !in it.title })

        val fr = BooksOrJp.parse(fixture("booksorjp-frieren.html"))
        assertEquals(d("2025-12-18"), fr.first { it.title == "葬送のフリーレン １５" }.date)
        assertEquals("小学館", fr.first { it.title == "葬送のフリーレン １５" }.publisher)
    }

    @Test fun booksNotAResultsPage() {
        assertThrows(ReleaseParseException::class.java) { BooksOrJp.parse("<html>Just a moment...</html>") }
    }

    private class FakeHttp(val posts: MutableList<Map<String, String>> = mutableListOf(), val fail419Once: Boolean = false) : ReleaseHttp {
        val gets = mutableListOf<String>()
        private var failed = false
        override fun get(url: String): String { gets += url; return fixture(if ("books.or.jp" in url) "booksorjp-home.html" else error(url)) }
        override fun postForm(url: String, form: Map<String, String>): String {
            if (fail419Once && !failed) { failed = true; throw HttpStatusException(419, url) }
            posts += form
            return fixture("booksorjp-dandadan.html")
        }
    }

    @Test fun booksSearchUsesTokenOnce() {
        val http = FakeHttp()
        val src = BooksOrJp(http)
        src.listings("ダンダダン", d("2026-11-25"))
        src.listings("ダンダダン", d("2026-11-25"))
        assertEquals(1, http.gets.size)
        assertEquals(2, http.posts.size)
        assertEquals("ダンダダン", http.posts[0]["searchforbooks_title"])
        assertEquals(40, http.posts[0]["_token"]!!.length)
    }

    @Test fun booksRetriesExpiredToken() {
        val http = FakeHttp(fail419Once = true)
        val src = BooksOrJp(http)
        assertTrue(src.listings("ダンダダン", d("2026-11-25")).isNotEmpty())
        assertEquals(2, http.gets.size) // home fetched again for a fresh token
    }

    // ---- NDL ----

    @Test fun ndlDandadan() {
        val rows = NdlSearch.parse(fixture("ndl-dandadan.xml"))
        assertEquals(58, rows.size)
        val jpro = rows.first { it.title == "ダンダダン 27" }
        assertEquals(d("2027-01-04"), jpro.date)
        assertEquals("集英社", jpro.publisher)
        assertNotNull(jpro.source)
        // NDL's own records: the series title, the number apart, a month only (no exact day)
        val own26 = rows.first { it.title == "ダンダダン" && it.vol == 26 }
        assertEquals(null, own26.date)
        assertEquals("集英社", own26.publisher)
        assertEquals(26, rows.filter { it.title == "ダンダダン" && it.vol != null }.size)
    }

    @Test fun ndlKingdomIsNoisy() {
        val rows = NdlSearch.parse(fixture("ndl-kingdom.xml"))
        assertEquals(100, rows.size)
        assertTrue(rows.any { it.title == "うちがキングダム" && it.vol == 3 })
    }

    @Test fun ndlUrlAsksForTheNextVolume() {
        val base = "https://ndlsearch.ndl.go.jp/api/opensearch?title="
        assertEquals(base + "%E3%82%AD%E3%83%B3%E3%82%B0%E3%83%80%E3%83%A0+82&cnt=100", NdlSearch.url("キングダム", 81))
        assertEquals(base + "%E3%82%AD%E3%83%B3%E3%82%B0%E3%83%80%E3%83%A0&cnt=100", NdlSearch.url("キングダム", 0))
    }

    @Test fun booksBadDateSkipsTheRowNotThePage() {
        val html = """<div class="result_title_text"></div>
            <div class="result_list_item"><h4 class="result_list_discription_title">ダンダダン 28</h4>
            <input name="publication_date" hidden>2027-01-00</div>
            <div class="result_list_item"><h4 class="result_list_discription_title">ダンダダン 29</h4>
            <input name="publication_date" hidden>2027-03-04</div>"""
        assertEquals(listOf("ダンダダン 29"), BooksOrJp.parse(html).map { it.title })
    }

    @Test fun kodanshaBadDateSkipsTheRowNotThePage() {
        val html = "発売予定表<p>2027年2月30日発売</p><p>甲（１）</p><p>2027年2月26日発売</p><p>乙（２）</p>"
        assertEquals(listOf("乙（２）"), KodanshaCalendar.parse(html).map { it.title })
    }

    @Test fun kodanshaCacheIsForOneDay() {
        var gets = 0
        val http = object : ReleaseHttp {
            override fun get(url: String): String { gets++; return "発売予定表" }
            override fun postForm(url: String, form: Map<String, String>) = error("no post")
        }
        val src = KodanshaCalendar(http, monthsAhead = 0)
        src.listings("x", d("2026-11-25")); src.listings("y", d("2026-11-25"))
        assertEquals(1, gets)
        src.listings("x", d("2026-11-26"))
        assertEquals(2, gets)
    }

    @Test fun kodanshaFailedMonthIsFetchedOncePerRun() {
        var gets = 0
        val http = object : ReleaseHttp {
            override fun get(url: String): String { gets++; throw HttpStatusException(419, url) }
            override fun postForm(url: String, form: Map<String, String>) = error("no post")
        }
        val src = KodanshaCalendar(http, monthsAhead = 0)
        repeat(3) { assertThrows(HttpStatusException::class.java) { src.listings("x", d("2026-11-25")) } }
        assertEquals(1, gets)
        assertThrows(HttpStatusException::class.java) { src.listings("x", d("2026-11-26")) }
        assertEquals(2, gets) // a new day tries again
    }

    @Test fun ndlNotXml() {
        assertThrows(ReleaseParseException::class.java) { NdlSearch.parse("<html><body>blocked") }
    }

    // ---- Kodansha ----

    @Test fun kodanshaNovember() {
        val rows = KodanshaCalendar.parse(fixture("kodansha-202611.html"))
        assertEquals(190, rows.size)
        val bl = rows.first { it.title == "ブルーロック（４１）" }
        assertEquals(d("2026-11-17"), bl.date)
        assertEquals("講談社", bl.publisher)
        assertTrue(rows.any { it.title == "青のミブロー新選組編ー（１３）" && it.date == d("2026-11-17") })
    }

    @Test fun kodanshaJanuaryHasBlueLock42() {
        val rows = KodanshaCalendar.parse(fixture("kodansha-202701.html"))
        assertEquals(d("2027-01-15"), rows.first { it.title == "ブルーロック（４２）" }.date) // not on books.or.jp yet
    }

    @Test fun kodanshaDecemberAndNotACalendar() {
        assertEquals(191, KodanshaCalendar.parse(fixture("kodansha-202612.html")).size)
        assertThrows(ReleaseParseException::class.java) { KodanshaCalendar.parse("<html>x</html>") }
    }

    @Test fun kodanshaFetchesThreeMonthsOnceAnd404IsEmpty() {
        val asked = mutableListOf<String>()
        val http = object : ReleaseHttp {
            override fun get(url: String): String {
                asked += url.substringAfterLast('/')
                return when (url.substringAfterLast('/')) {
                    "202611" -> fixture("kodansha-202611.html")
                    "202612" -> fixture("kodansha-202612.html")
                    else -> throw HttpStatusException(404, url)
                }
            }
            override fun postForm(url: String, form: Map<String, String>) = error("no post")
        }
        val src = KodanshaCalendar(http)
        assertEquals(381, src.listings("x", d("2026-11-25")).size)
        src.listings("y", d("2026-11-25"))
        assertEquals(listOf("202611", "202612", "202701"), asked) // the second series reuses the months
    }
}
