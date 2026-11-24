package chimahon.lang

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The real splitter, as kumapie's `JitenSplitterTest`: runs only with KUMAHON_SUDACHI_DIR set to a folder holding
 * `sudachi-core-<JapaneseModel.VERSION>.dic` (and, for the Jiten cases, `jiten-lexicon.tsv.gz` + `jiten_user_core.dic`).
 */
class JapaneseModelTest {
    private val model: JapaneseModel? by lazy { System.getenv("KUMAHON_SUDACHI_DIR")?.let(::File)?.let { JapaneseModel(it) }?.takeIf { it.isReady } }

    private fun show(text: String): String {
        val tokens = model!!.words(text)!!
        assertEquals(text, tokens.joinToString("") { it.surface }) // nothing dropped, in order
        return tokens.joinToString(" | ") { t -> if (!t.isWord) "[${t.surface}]" else if (t.surface == t.base) t.surface else "${t.surface}→${t.base}" }
    }

    /** kumapie's JitenSplitterTest.lines, word for word. */
    @Test fun jitenWordsAndBases() {
        assumeTrue(model?.splitterReady == true)
        assertEquals("黒板消し | は | 普通 | [。]", show("黒板消しは普通。"))
        assertEquals("それじゃ | 元も子もなくなる→元も子もない | ぞ", show("それじゃ元も子もなくなるぞ"))
        assertEquals("雨が降りそう→雨が降る | な | 気がします→気がする", show("雨が降りそうな気がします"))
        assertEquals("呼ばれちゃった→呼ぶ | [！]", show("呼ばれちゃった！"))
    }

    /** Sudachi mode B + join alone (no Jiten files): kumapie's `words` fallback. */
    @Test fun sudachiJoin() {
        assumeTrue(model?.isReady == true)
        val tokens = JapaneseModel.join(model!!.parse(listOf("届いた。でも、防御力が低い")).first())
        assertEquals(listOf("届いた→届く", "。", "でも", "、", "防御力", "が", "低い"), tokens.map { if (!it.isWord) it.surface else if (it.surface == it.base) it.surface else "${it.surface}→${it.base}" })
    }

    @Test fun autoKnownNamesAndFillers() {
        assumeTrue(model?.isReady == true)
        assertEquals(true, model!!.autoKnown("えっと"))
        assertEquals(false, model!!.autoKnown("猫"))
    }

    /** kumapie's JitenSplitterTest.spellings: the other forms of a card's word. */
    @Test fun spellingsOfAWord() {
        assumeTrue(model?.splitterReady == true)
        val m = model!!
        assertEquals(true, m.spellings("ガキ共", "がきども").containsAll(listOf("ガキども", "餓鬼ども")))
        assertEquals(true, "わかる" in m.spellings("分かる", "わかる"))
        assertEquals(false, "かみ" in m.spellings("紙", "かみ")) // 神, 髪 are かみ too
    }

    /** Jiten's files that don't load (right size and magic, wrong inside): thrown away, Sudachi + join split alone. */
    @Test fun brokenJitenFilesFallBackToSudachi(@org.junit.jupiter.api.io.TempDir tmp: File) {
        assumeTrue(model?.isReady == true)
        model!!.dic.copyTo(File(tmp, model!!.dic.name))
        val bad = JapaneseModel(tmp)
        File(tmp, "jiten-lexicon.tsv.gz").writeBytes(byteArrayOf(0x1f, 0x8b.toByte()) + ByteArray(2_000_000))
        File(tmp, "jiten_user_core.dic").writeBytes(ByteArray(2_000_000))
        assertEquals(true, bad.splitterReady)
        assertEquals(listOf("猫", "が"), bad.words("猫が")!!.map { it.surface })
        assertEquals(false, bad.splitterReady)
        assertEquals(true, bad.droppedAt > 0)
    }

    /** A server answering with an error page or a cut file: nothing kept, no leftovers, the failure reported. */
    @Test fun badDownloadsAreRefused(@org.junit.jupiter.api.io.TempDir tmp: File) {
        File(tmp, "sudachi-core-${JapaneseModel.VERSION}.dic").writeBytes(ByteArray(10)) // "downloaded" already
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex -> val b = "<html>error</html>".toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
        server.start()
        try {
            val m = JapaneseModel(tmp) { "http://127.0.0.1:${server.address.port}" }
            m.download()
            assertEquals(true, m.failure!!.contains("not a dictionary file"))
            assertEquals(emptyList<String>(), tmp.list()!!.filter { it.endsWith(".part") || it.endsWith(".tmp") || it.endsWith(".whl") })
            assertEquals(false, m.splitterFiles.any { it.exists() })
        } finally { server.stop(0) }
    }

    @Test fun notDownloadedGivesNoWords() = assertEquals(null, JapaneseModel(File("no-such-dir")).words("猫"))
}
