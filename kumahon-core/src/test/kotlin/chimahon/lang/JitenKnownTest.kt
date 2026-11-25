package chimahon.lang

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class JitenKnownTest {
    private val csv = "Word,Form,Rank\n猫,ねこ,1\n犬,いぬ,2\n,x,3\n走る,はしる,4\nbad,row,notanumber\n鳥,とり,5\n"
    private val tsv = "# comment\nparticles\tは\nparticles\tが\ncounters\t個\ngrammar\t \nbroken line\n"

    @Test fun topTakesColumn0UntilRankExceedsN() {
        assertEquals(setOf("猫", "犬", "走る"), JitenKnown.topFromCsv(csv.lineSequence(), 4))
        assertEquals(setOf("猫"), JitenKnown.topFromCsv(csv.lineSequence(), 1))
        assertEquals(emptySet<String>(), JitenKnown.topFromCsv(csv.lineSequence(), 0))
    }

    @Test fun setsFilterBySlugSkipCommentsAndBlanks() {
        assertEquals(setOf("は", "が"), JitenKnown.lists(tsv.lineSequence(), setOf("particles", "grammar")))
    }

    @Test fun mergeNewerWinsEqualKeepsMine() {
        val a = KnownImport(5, setOf("a")); val b = KnownImport(9, setOf("b")); val c = KnownImport(5, setOf("c"))
        assertSame(b, JitenKnown.merge(a, b))
        assertSame(a, JitenKnown.merge(a, c))
        assertSame(a, JitenKnown.merge(a, null))
    }

    @Test fun importReplacesWithTopPlusSetsAndBacksOff6hAfter418() {
        var now = 1000L
        var code = 200
        val urls = mutableListOf<String>()
        val k = JitenKnown({ url, _ -> urls += url; if (code != 200) JitenReply(code, "") else JitenReply(200, if ("frequency" in url) csv else tsv) }, { now })
        val r = k.import(2, listOf("counters"))
        assertEquals(KnownImport(1000, setOf("猫", "犬", "個")), r)
        assertTrue("downloadType=csv" in urls[0] && urls[1] == JitenKnown.LISTS)
        code = 418
        assertThrows(IOException::class.java) { k.import(2, emptyList()) }
        assertTrue(k.blocked)
        code = 200; val calls = urls.size
        assertThrows(IOException::class.java) { k.import(2, emptyList()) }
        assertEquals(calls, urls.size)
        now += JitenKnown.BACKOFF_MS
        assertFalse(k.blocked)
        assertEquals(setOf("猫", "犬"), k.import(2, emptyList()).words)
    }

    private fun fake(code: () -> Int, urls: MutableList<String> = mutableListOf(), text: (String) -> String = { if ("frequency" in it) csv else tsv }) =
        JitenKnown({ url, _ -> urls += url; code().let { c -> JitenReply(c, if (c == 200) text(url) else "") } }, { 1000L })

    @Test fun http403BlocksTheApiAndTheMessageNamesIt() {
        val urls = mutableListOf<String>()
        val k = fake({ 403 }, urls)
        assertTrue(assertThrows(IOException::class.java) { k.import(2, emptyList()) }.message!!.contains("HTTP 403"))
        assertTrue(k.blocked)
        assertThrows(IOException::class.java) { k.import(2, emptyList()) }
        assertEquals(1, urls.size)
    }

    @Test fun transportIOExceptionPropagatesAndDoesNotBlock() {
        val k = JitenKnown({ _, _ -> throw IOException("no network") }, { 1000L })
        assertEquals("no network", assertThrows(IOException::class.java) { k.import(2, listOf("counters")) }.message)
        assertFalse(k.blocked)
    }

    @Test fun zeroTopWithSetsAsksOnlyForTheLists() {
        val urls = mutableListOf<String>()
        val r = fake({ 200 }, urls).import(0, listOf("particles"))
        assertEquals(listOf(JitenKnown.LISTS), urls)
        assertEquals(setOf("は", "が"), r.words)
    }

    @Test fun aRefusedListsDownloadDoesNotBlockTheApi() {
        val k = fake({ 403 }, text = { "" })
        assertThrows(IOException::class.java) { k.import(0, listOf("particles")) }
        assertFalse(k.blocked)
    }

    @Test fun crlfFilesWork() {
        val r = fake({ 200 }) { if ("frequency" in it) csv.replace("\n", "\r\n") else tsv.replace("\n", "\r\n") }.import(2, listOf("particles", "counters"))
        assertEquals(setOf("猫", "犬", "は", "が", "個"), r.words)
    }

    @Test fun readingStopsAtTheFirstRankPastTheCutOff() {
        // the CSV is ordered by rank: a smaller rank after the cut-off (a broken file) is not read
        assertEquals(setOf("a", "b"), JitenKnown.topFromCsv("Word,Form,Rank\na,x,1\nb,x,2\nc,x,9\nd,x,3\n".lineSequence(), 3))
    }
}
