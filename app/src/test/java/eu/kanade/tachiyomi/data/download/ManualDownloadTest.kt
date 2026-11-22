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
}
