package eu.kanade.tachiyomi.data.download

import eu.kanade.tachiyomi.data.coverage.Coverage
import eu.kanade.tachiyomi.data.coverage.Coverage.State
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ManualDownloadTest {

    private fun cov(id: Long, vararg v: Pair<Int, String>, state: State = State.FOUND) =
        Coverage(id, "S$id", state, volumes = v.toMap())

    private fun ids(wanted: Collection<Int>, here: Set<Int>, vararg c: Coverage) =
        ManualDownload.options(wanted, here, c.toList()).map { it.sourceId }

    @Test
    fun `a pack beats two singles, a partial source comes last`() {
        val singles = cov(1, 1 to "a", 2 to "b")
        val pack = cov(2, 1 to "p", 2 to "p")
        val partial = cov(3, 1 to "x")
        ids(listOf(1, 2), emptySet(), partial, singles, pack) shouldBe listOf(2L, 1L, 3L)
    }

    @Test
    fun `a Rapidgator pack comes after two files on another hoster, but before a partial source`() {
        val rg = cov(1, 1 to "https://rapidgator.net/file/p", 2 to "https://rapidgator.net/file/p")
        val rgShort = cov(2, 1 to "https://rg.to/file/p", 2 to "https://rg.to/file/p")
        val other = cov(3, 1 to "https://uploady.io/a", 2 to "https://katfile.com/b")
        val partial = cov(4, 1 to "https://hexload.com/x")
        ids(listOf(1, 2), emptySet(), rg, rgShort, partial, other) shouldBe listOf(3L, 1L, 2L, 4L)
    }

    private fun files(id: Long, vararg f: Pair<List<Int>, List<String>>) =
        Coverage(id, "S$id", State.FOUND, files = f.map { Coverage.File(it.first, it.second) })

    @Test
    fun `a file with the s mark comes after any other way that adds as much`() {
        fun c(id: Long, scans: Boolean, link: String) =
            Coverage(id, "S$id", State.FOUND, files = listOf(Coverage.File(listOf(1, 2, 3), listOf(link), scans = scans)))
        val marked = c(1, true, "https://sakurafile.com/a")
        val rapidgator = c(2, false, "https://rapidgator.net/file/b")
        val partial = cov(3, 1 to "https://katfile.com/c")
        ManualDownload.options(listOf(1, 2), emptySet(), listOf(marked, rapidgator, partial)).map { it.sourceId } shouldBe
            listOf(2L, 1L, 3L)
        // inside one source: the unmarked file wins the volume it also has
        val both = Coverage(
            4, "S4", State.FOUND,
            files = listOf(Coverage.File(listOf(1), listOf("s"), scans = true), Coverage.File(listOf(1, 2), listOf("p"))),
        )
        ManualDownload.options(listOf(1), emptySet(), listOf(both)).first().files.map { it.link } shouldBe listOf("p")
    }

    @Test
    fun `a source with packs and single volumes gives both ways, the pack first`() {
        val c = files(1, listOf(1) to listOf("a"), listOf(2) to listOf("b"), listOf(1, 2, 3) to listOf("p"))
        val options = ManualDownload.options(listOf(1, 2), emptySet(), listOf(c))
        options.map { o -> o.files.map { it.link } } shouldBe listOf(listOf("p"), listOf("a", "b"))
    }

    @Test
    fun `a file is taken from its hoster that is not slow, and from a mirror when that one is gone`() {
        val rg = "https://rapidgator.net/file/x"
        val kat = "https://katfile.com/x"
        val ddl = "https://ddownload.com/x"
        val c = files(1, listOf(1, 2) to listOf(rg, kat, ddl, "magnet:?xt=abc"))
        val options = ManualDownload.options(listOf(1), emptySet(), listOf(c))
        options.single().files shouldBe listOf(ManualDownload.File(kat, listOf(1, 2), listOf(ddl, rg)))
        ManualDownload.links(options) shouldBe listOf(kat, ddl, rg)
        ManualDownload.alive(options, setOf(kat)).single().files.single() shouldBe
            ManualDownload.File(ddl, listOf(1, 2), listOf(rg))
        // nothing left: the link stays, so the screen can mark the option as gone
        ManualDownload.alive(options, setOf(kat, ddl, rg)).single().files.single().link shouldBe kat
    }

    @Test
    fun `a source whose file had to fall back on Rapidgator goes after one that did not`() {
        val a = files(1, listOf(1) to listOf("https://rapidgator.net/file/a", "https://katfile.com/a"))
        val b = files(2, listOf(1) to listOf("https://uploady.io/b"))
        val options = ManualDownload.options(listOf(1), emptySet(), listOf(a, b))
        options.map { it.sourceId } shouldBe listOf(1L, 2L)
        ManualDownload.alive(options, setOf("https://katfile.com/a")).map { it.sourceId } shouldBe listOf(2L, 1L)
    }

    @Test
    fun `magnets are dropped and a source with nothing wanted gives no option`() {
        val torrent = cov(1, 1 to "magnet:?xt=abc")
        val other = cov(2, 5 to "z")
        ids(listOf(1), emptySet(), torrent, other) shouldBe emptyList()
    }

    @Test
    fun `a source that is not found gives no option`() {
        ids(listOf(1), emptySet(), cov(1, 1 to "a", state = State.NOT_FOUND)) shouldBe emptyList()
    }

    @Test
    fun `other volumes of a file split into already here and extra`() {
        val o = ManualDownload.options(listOf(2), setOf(1), listOf(cov(1, 1 to "p", 2 to "p", 3 to "p"))).single()
        o.files shouldBe listOf(ManualDownload.File("p", listOf(1, 2, 3)))
        o.adds shouldBe listOf(2)
        o.alreadyHere shouldBe listOf(1)
        o.extra shouldBe listOf(3)
    }

    @Test
    fun `less extra wins, then the order of the sources`() {
        val big = cov(1, 1 to "p", 2 to "p", 3 to "p")
        val exact = cov(2, 1 to "q", 2 to "q")
        val same = cov(3, 1 to "r", 2 to "r")
        ids(listOf(1, 2), emptySet(), big, same, exact) shouldBe listOf(3L, 2L, 1L)
    }

    @Test
    fun `among partial options the one adding more wins, even with more files`() {
        val one = cov(1, 1 to "a")
        val two = cov(2, 1 to "b", 2 to "c")
        ids(listOf(1, 2, 3), emptySet(), one, two) shouldBe listOf(2L, 1L)
    }

    @Test
    fun `a pack with extra volumes beats two exact single files`() {
        val singles = cov(1, 1 to "a", 2 to "b")
        val pack = cov(2, 1 to "p", 2 to "p", 3 to "p")
        ids(listOf(1, 2), emptySet(), singles, pack) shouldBe listOf(2L, 1L)
    }

    @Test
    fun `host drops www and the path`() {
        ManualDownload.host("https://www.rapidgator.net/file/abc/x.rar?x=1") shouldBe "rapidgator.net"
        ManualDownload.host("https://Nitroflare.com:443/view/X") shouldBe "nitroflare.com"
        ManualDownload.host("magnet:?xt=abc") shouldBe ""
        ManualDownload.host("") shouldBe ""
    }

    @Test
    fun `a picked file is named by its display name, else the end of its uri`() {
        RealDebridImportJob.localName("Vol 01.rar", "content://x/1") shouldBe "Vol 01.rar"
        RealDebridImportJob.localName(null, "content://x/document/primary%3ADownload%2FVol%2001.rar") shouldBe "Vol 01.rar"
    }

    @Test
    fun `a hoster page that says the file is gone`() {
        ManualDownload.gone(404, "https://uploady.io/abc") shouldBe true
        ManualDownload.gone(200, "https://rapidgator.net/article/premium") shouldBe true
        ManualDownload.gone(200, "https://rapidgator.net/file/abc/x.rar.html") shouldBe false
        ManualDownload.gone(200, "https://katfile.com/abc/x.rar.html") shouldBe false
        ManualDownload.gone(503, "https://uploady.io/abc") shouldBe false // down for now is not gone
    }
}
