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

    /** Without Jiten's files too, 感じました is the known word 感じる (Sudachi alone says 感ずる), as the lookup popup shows it. */
    @Test fun sudachiJoinKeepsTheModernVerb() {
        assertEquals("感じる", JapaneseModel.modern("感じ", "感ずる"))
        assertEquals("感ずる", JapaneseModel.modern("感ずる", "感ずる"))
        assertEquals("感ずる", JapaneseModel.modern("感ぜ", "感ずる"))
        assertEquals("する", JapaneseModel.modern("し", "する"))
        assumeTrue(model?.isReady == true)
        fun bases(t: String) = JapaneseModel.join(model!!.parse(listOf(t)).first()).filter { it.isWord }.joinToString(" ") { it.base }
        assertEquals("初めて 芳醇 を 感じる", bases("初めて芳醇を感じました！"))
        assertEquals("信じる", bases("信じています"))
        assertEquals("勉強 する", bases("勉強しました"))
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

    /** A release marked dropped by 1.4.25 or older (where one text that failed to split dropped it) is fetched again; one that doesn't load is not. */
    @Test fun oldDroppedMarkIsTriedAgain(@org.junit.jupiter.api.io.TempDir tmp: File) {
        assumeTrue(model?.isReady == true)
        model!!.dic.copyTo(File(tmp, model!!.dic.name))
        val m = JapaneseModel(tmp)
        val now = System.currentTimeMillis()
        File(tmp, "jiten-splitter.version").writeText("dropped:2026-10-11\n$now")
        assertEquals(true, m.splitterWanted())
        File(tmp, "jiten-splitter.version").writeText("unloadable:2026-10-11\n$now")
        assertEquals(false, m.splitterWanted())
    }

    /** One text Jiten can't split leaves its two files alone (until 1.4.25 it deleted them for good); files that don't load are thrown away. */
    @Test fun oneBadTextKeepsJitensFiles(@org.junit.jupiter.api.io.TempDir tmp: File) {
        val m = JapaneseModel(tmp)
        m.splitterFiles.forEach { it.writeBytes(ByteArray(10)) }
        val version = File(tmp, "jiten-splitter.version").apply { writeText("2026-10-11\n0") }
        fun fake(loads: Boolean) = object : JitenSplitter(m.splitterFiles[0], m.dic, m.splitterFiles[1]) {
            override fun load() { if (!loads) throw java.io.IOException("bad file") }
            override fun split(text: String): List<JapaneseModel.Token> = throw IllegalStateException("can't split $text")
        }
        assertEquals(null, m.jitenWords("困った文") { fake(loads = true) })
        assertEquals(listOf(true, true), m.splitterFiles.map { it.exists() })
        assertEquals("2026-10-11", version.readLines()[0])
        assertEquals(null, m.jitenWords("困った文") { fake(loads = false) })
        assertEquals(listOf(false, false), m.splitterFiles.map { it.exists() })
        assertEquals("unloadable:2026-10-11", version.readLines()[0])
    }

    /** A server answering with an error page or a cut file: nothing kept, no leftovers, the failure reported. */
    @Test fun badDownloadsAreRefused(@org.junit.jupiter.api.io.TempDir tmp: File) {
        File(tmp, "sudachi-core-${JapaneseModel.VERSION}.dic").writeBytes(ByteArray(10)) // "downloaded" already
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex -> val b = "<html>error</html>".toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
        server.start()
        try {
            val offline = { u: String, f: File -> if (u.startsWith("http://127.")) JapaneseModel.httpGet(u, f) else throw java.io.IOException("offline") }
            val m = JapaneseModel(tmp, { "http://127.0.0.1:${server.address.port}" }, offline)
            m.download()
            assertEquals(true, m.failure!!.contains("not a dictionary file"))
            assertEquals(emptyList<String>(), tmp.list()!!.filter { it.endsWith(".part") || it.endsWith(".tmp") || it.endsWith(".whl") })
            assertEquals(false, m.splitterFiles.any { it.exists() })
        } finally { server.stop(0) }
    }

    // The public release: a fake fetcher serves the manifest and the files, no network.
    private class Release(var version: String = "v1") {
        val gz = byteArrayOf(0x1f, 0x8b.toByte()) + ByteArray(1_100_000) { 1 }
        val dic = ByteArray(1_100_000) { 2 }
        var gzServed = gz
        val calls = ArrayList<String>()
        private fun sha(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
        fun manifest(gzSize: Long = gz.size.toLong()) = """{"version":"$version","base":"https://x/r/","files":[
            {"name":"jiten-lexicon.tsv.gz","size":$gzSize,"sha256":"${sha(gz)}"},
            {"name":"jiten_user_core.dic","size":${dic.size},"sha256":"${sha(dic)}"}]}"""
        var manifestText = manifest()
        val fetcher = { u: String, f: File ->
            calls += u
            when (u) {
                "https://x/m.json" -> f.writeText(manifestText)
                "https://x/r/jiten-lexicon.tsv.gz" -> f.writeBytes(gzServed)
                "https://x/r/jiten_user_core.dic" -> f.writeBytes(dic)
                else -> throw java.io.IOException("404 $u")
            }
        }
    }

    /** The stored last check moves 25 hours back. */
    private fun aDayLater(tmp: File) = File(tmp, "jiten-splitter.version").let { it.writeText(it.readText().lines()[0] + "\n" + (System.currentTimeMillis() - 25 * 3_600_000L)) }

    private fun readyDir(tmp: File) = tmp.also { File(it, "sudachi-core-${JapaneseModel.VERSION}.dic").writeBytes(ByteArray(10)) }

    @Test fun manifestParses() {
        val (v, base, files) = JapaneseModel.parseManifest(Release().manifest())
        assertEquals("v1", v)
        assertEquals("https://x/r/", base)
        assertEquals(listOf("jiten-lexicon.tsv.gz", "jiten_user_core.dic"), files.map { it.name })
        assertEquals(1_100_000L, files[1].size)
    }

    @Test fun releaseFilesAreFetchedOnceAndNotAgainWhileTheVersionHolds(@org.junit.jupiter.api.io.TempDir tmp: File) {
        val r = Release()
        val m = JapaneseModel(readyDir(tmp), fetcher = r.fetcher, manifestUrl = "https://x/m.json")
        assertEquals(true, m.splitterWanted())
        m.download()
        assertEquals(null, m.failure)
        assertEquals(true, m.splitterReady)
        assertEquals(false, m.splitterWanted()) // checked just now
        assertEquals(3, r.calls.size)
        // A day later, same version: only the manifest is read.
        aDayLater(tmp)
        assertEquals(true, m.splitterWanted())
        r.calls.clear()
        m.download()
        assertEquals(listOf("https://x/m.json"), r.calls)
        assertEquals(false, m.splitterWanted())
        // A new version replaces both files.
        r.version = "v2"; r.manifestText = r.manifest()
        aDayLater(tmp)
        r.calls.clear()
        m.download()
        assertEquals(3, r.calls.size)
        assertEquals("v2", File(tmp, "jiten-splitter.version").readText().lines()[0])
    }

    @Test fun wrongSizeOrShaIsRejectedAndDeleted(@org.junit.jupiter.api.io.TempDir tmp: File) {
        val r = Release()
        val m = JapaneseModel(readyDir(tmp), fetcher = r.fetcher, manifestUrl = "https://x/m.json")
        r.gzServed = r.gz.copyOf().also { it[100] = 9 } // same size, other bytes
        m.download()
        assertEquals(true, m.failure!!.contains("sha256"))
        assertEquals(false, m.splitterFiles.any { it.exists() })
        r.gzServed = r.gz + 0.toByte() // other size
        m.download()
        assertEquals(true, m.failure!!.contains("manifest says"))
        assertEquals(emptyList<String>(), tmp.list()!!.filter { it.endsWith(".part") || it.endsWith(".new") })
        assertEquals(false, m.splitterFiles.any { it.exists() })
        r.gzServed = r.gz
        m.download()
        assertEquals(true, m.splitterReady)
    }

    /** A release that matches its sha256 but doesn't load here: dropped once, not fetched again until its version changes. */
    @Test fun droppedReleaseIsNotFetchedAgain(@org.junit.jupiter.api.io.TempDir tmp: File) {
        val r = Release()
        val m = JapaneseModel(readyDir(tmp), fetcher = r.fetcher, manifestUrl = "https://x/m.json")
        m.download()
        assertEquals(true, m.splitterReady)
        m.dropSplitter(RuntimeException("doesn't load"))
        assertEquals(false, m.splitterReady)
        assertEquals(false, m.splitterWanted()) // dropped, checked just now: no manifest read per text either
        aDayLater(tmp)
        assertEquals(true, m.splitterWanted())
        r.calls.clear()
        m.download() // same version: the manifest only
        assertEquals(listOf("https://x/m.json"), r.calls)
        assertEquals(false, m.splitterReady)
        assertEquals(false, m.splitterWanted())
        aDayLater(tmp)
        r.version = "v2"; r.manifestText = r.manifest()
        r.calls.clear()
        m.download() // new version: fetched
        assertEquals(3, r.calls.size)
        assertEquals(true, m.splitterReady)
    }

    @Test fun baseWithoutSlashIsJoined(@org.junit.jupiter.api.io.TempDir tmp: File) {
        val r = Release()
        r.manifestText = r.manifestText.replace("https://x/r/", "https://x/r")
        JapaneseModel(readyDir(tmp), fetcher = r.fetcher, manifestUrl = "https://x/m.json").download()
        assertEquals(true, r.calls.contains("https://x/r/jiten_user_core.dic"))
    }

    @Test fun serverFirstThenRelease(@org.junit.jupiter.api.io.TempDir tmp: File) {
        val r = Release()
        val m = JapaneseModel(readyDir(tmp), { "https://srv/" }, r.fetcher, "https://x/m.json")
        m.download() // https://srv/api/models/... is a 404 in the fake: the release serves
        assertEquals(null, m.failure)
        assertEquals(true, r.calls.first().startsWith("https://srv/api/models/"))
        assertEquals(true, m.splitterReady)
    }

    @Test fun notDownloadedGivesNoWords() = assertEquals(null, JapaneseModel(File("no-such-dir")).words("猫"))
}
