package chimahon.lang

import com.worksap.nlp.sudachi.Config
import com.worksap.nlp.sudachi.Dictionary
import com.worksap.nlp.sudachi.Tokenizer
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.security.MessageDigest
import org.json.JSONObject
import java.util.zip.ZipInputStream

/**
 * Japanese words for kumahon's own word colours: the port of kumapie's `JapaneseModel` (Sudachi Java 0.8.2, mode B, plus Jiten's
 * splitter once its two files are here), pure JVM so Android and the desktop app share it. [dir] is the data folder (Android
 * `filesDir/sudachi`, desktop `%APPDATA%\kumahon\sudachi`). The Sudachi core dictionary (~80 MB, 200 MB unpacked) comes from the
 * sudachidict-core wheel on PyPI, exactly as kumapie does; Jiten's files (`jiten-lexicon.tsv.gz` 25 MB, `jiten_user_core.dic`
 * 20 MB) from `<server>/api/models/<file>` (dojo-feed) when a [server] is set, else from kumapie's public release ([MANIFEST]:
 * size and sha256 checked, replaced when its `version` changes, looked at once a day); without them Sudachi + [join] split alone.
 * [fetcher] writes a URL's bytes to a file (tests pass a fake).
 */
class JapaneseModel(
    private val dir: File,
    private val server: () -> String = { "" },
    private val fetcher: (String, File) -> Unit = { u, f -> httpGet(u, f) },
    private val manifestUrl: String = MANIFEST,
) {
    val dic = File(dir, "sudachi-core-$VERSION.dic")
    val isReady: Boolean get() = dic.exists()
    val splitterFiles = SPLITTER_FILES.map { File(dir, it) }
    val splitterReady: Boolean get() = isReady && splitterFiles.all { it.exists() }
    @Volatile var failure: String? = null
        private set
    /**
     * `<version>` or `dropped:<version>` (files of that release that don't load here), a newline, the time of the last manifest
     * check (ms). Absent: files from a server, or none.
     */
    private val versionFile = File(dir, "jiten-splitter.version")
    private fun state(): Triple<String, Boolean, Long>? = runCatching {
        val l = versionFile.readText().trim().lines()
        val v = l[0].trim()
        // an old file holds only the version: check time 0 = the next daily check runs at once
        Triple(v.removePrefix("dropped:"), v.startsWith("dropped:"), l.getOrNull(1)?.trim()?.toLongOrNull() ?: 0L)
    }.getOrNull()
    private fun writeState(version: String, dropped: Boolean) = versionFile.writeText("${if (dropped) "dropped:" else ""}$version\n${System.currentTimeMillis()}")
    /** Jiten's files are missing (and not dropped from this release), or the release manifest wasn't read for a day: [download] has something to do. */
    fun splitterWanted(): Boolean {
        if (!isReady) return false
        val st = state() ?: return !splitterReady
        return System.currentTimeMillis() - st.third > DAY || (!splitterReady && !st.second)
    }
    /** When Jiten's files were last thrown away as unusable ([words]); the caller waits before fetching them again. */
    @Volatile var droppedAt = 0L
        private set

    /**
     * Fetches what is missing (blocking, off the main thread): the Sudachi dictionary, then Jiten's files from the server.
     * One download at a time; a failure is kept in [failure] and tried again at the next call.
     */
    fun download(): Unit = synchronized(DOWNLOAD) {
        try {
            dir.mkdirs()
            if (!isReady) sudachi()
            fetchJiten()
            failure = null
        } catch (e: Exception) {
            failure = e.message ?: e.toString()
            leftovers()
        }
    }

    /** Jiten's files: the server's when set (missing ones only), then the release for what is missing or out of date. */
    private fun fetchJiten() {
        val s = server().trim().trimEnd('/')
        var serverError: Exception? = null
        if (s.isNotEmpty()) try {
            for (f in splitterFiles) if (!f.exists()) fetch("$s/api/models/${f.name}", f, MIN_SPLITTER_FILE, gzip = f.name.endsWith(".gz"))
        } catch (e: Exception) { serverError = e }
        if (!splitterWanted()) { serverError?.let { throw it }; return }
        try { release() } catch (e: Exception) { throw if (serverError == null) e else IOException("server: ${serverError.message}; release: ${e.message}") }
    }

    /** One file of the release manifest. */
    data class Entry(val name: String, val size: Long, val sha256: String)

    /** Reads the release manifest; replaces the files only when its version differs from the stored one (or a file is missing). */
    private fun release() {
        val tmp = File(dir, "jiten-splitter.json.part")
        val (version, base, entries) = try { fetcher(manifestUrl, tmp); parseManifest(tmp.readText()) } finally { tmp.delete() }
        val wanted = SPLITTER_FILES.map { n -> entries.firstOrNull { it.name == n } ?: throw IOException("the release manifest has no $n") }
        val st = state()
        // Same version: nothing to fetch (and, when its files didn't load here, nothing until the version changes).
        if (st != null && st.first == version && (st.second || splitterReady)) { writeState(version, st.second); return }
        // Both files whole and checked before either is replaced.
        val staged = wanted.map { e -> File(dir, e.name + ".new").also { fetch(base.trimEnd('/') + "/" + e.name, it, MIN_SPLITTER_FILE, e.name.endsWith(".gz"), e) } }
        // No splitter in use or loading while the pair changes (lock order as in [words]: SPLIT, then Companion).
        synchronized(SPLIT) {
            synchronized(Companion) {
                jiten = null
                for ((f, to) in staged.zip(splitterFiles)) java.nio.file.Files.move(f.toPath(), to.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        }
        writeState(version, false)
    }

    /** Half-downloaded files of a failed attempt (the wheel, the unpacked dictionary, `.part` and `.new` files): never kept. */
    private fun leftovers() {
        val staged = splitterFiles.map { File(it.path + ".new") }
        for (f in listOf(File(dir, "sudachidict-core.whl"), File(dir, "${dic.name}.tmp"), File(dir, "jiten-splitter.json.part")) + staged +
            (listOf(dic, File(dir, "sudachidict-core.whl")) + splitterFiles + staged).map { File(it.path + ".part") }) f.delete()
    }

    /** A downloaded file is used only when whole (the size the server announced; with [expect], its size and sha256) and of the right kind: a bad one must not kill the colours. */
    private fun fetch(url: String, to: File, minSize: Long, gzip: Boolean = false, expect: Entry? = null) {
        val part = File(to.path + ".part")
        try {
            fetcher(url, part)
            if (expect != null) {
                if (part.length() != expect.size) throw IOException("${expect.name}: ${part.length()} bytes, the manifest says ${expect.size}")
                if (sha256(part) != expect.sha256.lowercase()) throw IOException("${expect.name}: sha256 differs from the manifest")
            }
            if (part.length() < minSize) throw IOException("${to.name}: only ${part.length()} bytes, not a dictionary file")
            if (gzip) part.inputStream().use { if (it.read() != 0x1f || it.read() != 0x8b) throw IOException("${to.name}: not a gzip file") }
            if (!part.renameTo(to)) throw IOException("couldn't move ${to.name} into place")
        } finally { part.delete() }
    }

    /** The sudachidict-core wheel's system.dic, kept; the wheel is deleted (kumapie `JapaneseModelWorker.sudachi`). */
    private fun sudachi() {
        val wheel = File(dir, "sudachidict-core.whl")
        fetch(WHEEL, wheel, 10_000_000)
        val tmp = File(dir, "${dic.name}.tmp").apply { delete() }
        ZipInputStream(wheel.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "sudachidict_core/resources/system.dic") {
                    tmp.outputStream().use { zip.copyTo(it) }
                    break
                }
            }
        }
        if (tmp.length() < 10_000_000) throw IOException("the download has no Sudachi dictionary")
        if (!tmp.renameTo(dic)) throw IOException("couldn't move the dictionary into place")
        wheel.delete()
    }

    /** A word as Sudachi splits it: [base] is its dictionary form (the known-word key), [reading] in katakana. */
    data class Token(val surface: String, val base: String, val reading: String, val pos: String, val begin: Int, val sub: String = "") {
        /** Punctuation, symbols and blanks: not words. */
        val isWord: Boolean get() = pos != "補助記号" && pos != "空白"
    }

    /**
     * Each text's tokens (mode B), in order. Loads the dictionary on first use. One at a time: Sudachi's tokenizer isn't
     * thread-safe.
     */
    fun parse(texts: List<String>): List<List<Token>> = synchronized(Companion) {
        val tok = tokenizer()
        texts.map { tokens(tok, it) }
    }

    /** [text]'s words as a learner sees them (kumapie `words`: Jiten's splitter when its files are here, else Sudachi + [join]); null while the dictionary isn't downloaded. */
    fun words(text: String): List<Token>? {
        if (!isReady) return null
        val s = try { splitter() } catch (e: Exception) { dropSplitter(e); null }
        if (s != null) try { return synchronized(SPLIT) { s.split(text) } } catch (e: Exception) { dropSplitter(e) }
        return join(parse(listOf(text)).first())
    }

    /** Jiten's files don't load (a bad download): deleted, and Sudachi + [join] split alone, as without them. */
    internal fun dropSplitter(e: Exception) {
        synchronized(Companion) { jiten = null }
        for (f in splitterFiles) f.delete()
        state()?.let { writeState(it.first, true) } // this release doesn't load here: not fetched again until its version changes
        droppedAt = System.currentTimeMillis()
        failure = "Jiten's files dropped: ${e.message ?: e}"
    }

    /** The other spellings of a card's word ([JitenSplitter.spellings]); empty without Jiten's files or when they fail. */
    fun spellings(spelling: String, reading: String): Set<String> =
        runCatching { splitter()?.spellings(spelling, reading) }.getOrNull().orEmpty()

    /** Jiten's splitter once its files are here (one per process: ~95 MB), else null. */
    fun splitter(): JitenSplitter? = if (!splitterReady) null else synchronized(Companion) {
        jiten?.takeIf { it.first == dic.path }?.second ?: JitenSplitter(splitterFiles[0], dic, splitterFiles[1]).also { jiten = dic.path to it }
    }

    private val auto = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * kumapie `Japanese.autoKnown` (without its characters' dictionary, which kumahon has none of): the word alone is a name
     * (固有名詞) or an interjection/filler (感動詞), by Sudachi's part of speech.
     */
    fun autoKnown(word: String): Boolean = isReady && auto.getOrPut(word) {
        runCatching { parse(listOf(word)).first() }.getOrNull()?.singleOrNull()?.let { it.pos == "感動詞" || it.sub == "固有名詞" } ?: false
    }

    /**
     * Pieces that are an ordinary word, so a name PART (マッシュ of マッシュ・バーンデッド) must not be taken for it on a page: a piece that is not a
     * noun (名詞), or the joined word in the top 20 000 of Jiten's frequency rank. Without Jiten's files only the part of speech decides.
     */
    fun commonWord(pieces: List<Token>): Boolean {
        if (pieces.any { it.pos != "名詞" }) return true
        val rank = runCatching { splitter()?.frequencyRank(pieces.joinToString("") { it.surface }) }.getOrNull()
        return rank != null && rank <= COMMON_RANK
    }

    private val autoName = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** [autoKnown] for character faces: only a proper noun (固有名詞) is a name; an interjection (感動詞) is not. */
    fun autoKnownName(word: String): Boolean = isReady && autoName.getOrPut(word) {
        runCatching { parse(listOf(word)).first() }.getOrNull()?.singleOrNull()?.let { it.sub == "固有名詞" } ?: false
    }

    private fun tokenizer(): Tokenizer = synchronized(Companion) {
        loaded?.takeIf { it.first == dic.path }?.second ?: run {
            check(isReady) { "The Japanese dictionary isn't downloaded yet." }
            val d: Dictionary = Dictionary.load(Config.defaultConfig().systemDictionary(SudachiSystem.of(dic.toPath())))
            d.tokenizer().also { loaded = dic.path to it }
        }
    }

    companion object {
        private const val COMMON_RANK = 20_000
        private val SPLIT = Any()
        private val DOWNLOAD = Any()
        /** Jiten's smaller file is 20 MB: less than this is an error page or a cut download. */
        private const val MIN_SPLITTER_FILE = 1_000_000L

        private val HEADS = setOf("動詞", "形容詞")
        val HONORIFIC = setOf("さん", "ちゃん", "くん", "君", "様", "さま", "たち", "達", "殿", "氏")
        private val CONNECTIVE = setOf("て", "で", "ちゃ", "じゃ")

        /** Sudachi's mode-B tokens of [text]. */
        fun tokens(tok: Tokenizer, text: String): List<Token> = tok.tokenize(Tokenizer.SplitMode.B, text).map {
            Token(it.surface(), it.dictionaryForm(), it.readingForm(), it.partOfSpeech()[0], it.begin(), it.partOfSpeech()[1])
        }

        /**
         * Sudachi's mode-B pieces joined into learner words (prototype and checks: C:\dojo\work\ja-parser\chunks.py):
         * a verb or adjective takes its auxiliaries, て/で and the helper verbs after them (届い+た, 教え+て+い+ませ+ん,
         * し+なさい), keyed by its dictionary form; a noun takes its suffix (防御+力, 素早+さ; not さん/ちゃん), keyed by the
         * whole; だ/で + particle opening a phrase is a conjunction (でも, だって, だけど). Particles stay words of their own.
         */
        fun join(tokens: List<Token>): List<Token> {
            val out = ArrayList<Token>()
            var last = ""          // part of speech of the piece just joined
            var opens = true       // the current word opens a phrase (start, after a space or punctuation)
            var boundary = true
            for (t in tokens) {
                val cur = out.lastOrNull()
                // お/ご (a prefix) + noun or verb is one word keyed by it (お風呂 → 風呂, ご丁寧 → 丁寧, お待ちください → 待つ; item 31).
                if (cur != null && !boundary && cur.pos == "接頭辞" && t.pos in setOf("名詞", "形状詞", "動詞")) {
                    out[out.lastIndex] = cur.copy(surface = cur.surface + t.surface, base = t.base,
                        reading = cur.reading + t.reading, pos = t.pos, sub = t.sub)
                    last = t.pos
                    continue
                }
                var joins: String? = null
                if (cur != null && !boundary) {
                    val head = cur.pos
                    joins = when {
                        t.pos == "接尾辞" && t.surface !in HONORIFIC && head != "助詞" && head != "助動詞" -> "noun"
                        head in HEADS && (t.pos == "助動詞" ||
                            (t.pos == "助詞" && t.sub == "接続助詞" && t.surface in CONNECTIVE && last != "助詞") ||
                            (t.sub == "非自立可能" && t.pos in HEADS && last in setOf("助詞", "助動詞", "動詞"))) -> "inflect"
                        head == "助動詞" && (t.pos == "助動詞" || (t.pos == "形容詞" && t.sub == "非自立可能")) -> "inflect"
                        opens && cur.surface in setOf("で", "だ") && t.pos == "助詞" && t.surface in setOf("も", "って", "けど", "から", "けれど") -> "conj"
                        else -> null
                    }
                }
                when (joins) {
                    "noun" -> out[out.lastIndex] = cur!!.copy(surface = cur.surface + t.surface, base = cur.surface + t.surface,
                        reading = cur.reading + t.reading, pos = "名詞", sub = "")
                    "conj" -> out[out.lastIndex] = cur!!.copy(surface = cur.surface + t.surface, base = cur.surface + t.surface,
                        reading = cur.reading + t.reading, pos = "接続詞", sub = "")
                    "inflect" -> out[out.lastIndex] = cur!!.copy(surface = cur.surface + t.surface, reading = cur.reading + t.reading)
                    else -> { out += t; opens = boundary }
                }
                if (joins == "conj") opens = false
                last = t.pos
                boundary = t.pos == "補助記号" || t.pos == "空白"
            }
            return out
        }

        /** kumapie's public release: the manifest names the files (base URL, size, sha256) and their version. */
        const val MANIFEST = "https://github.com/Pedrubik2000/kumapie-tv/releases/download/jiten-splitter/jiten-splitter.json"
        private const val DAY = 24 * 3_600_000L

        /** `{version, base, files:[{name,size,sha256}]}`. */
        fun parseManifest(json: String): Triple<String, String, List<Entry>> {
            val o = JSONObject(json)
            val files = o.getJSONArray("files")
            return Triple(o.getString("version"), o.getString("base"), (0 until files.length()).map {
                files.getJSONObject(it).let { f -> Entry(f.getString("name"), f.getLong("size"), f.getString("sha256")) }
            })
        }

        private fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { i -> val b = ByteArray(1 shl 16); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        /** Writes [url]'s body to [to]; HttpURLConnection follows GitHub's https->https redirects (cross-host too) itself. */
        fun httpGet(url: String, to: File) {
            (java.net.URL(url).openConnection() as HttpURLConnection).run {
                connectTimeout = 15_000
                readTimeout = 60_000
                try {
                    if (responseCode != 200) throw IOException("$url: HTTP $responseCode")
                    val announced = contentLengthLong
                    inputStream.use { i -> to.outputStream().use { i.copyTo(it) } }
                    if (announced > 0 && to.length() != announced) throw IOException("${to.name}: got ${to.length()} of $announced bytes")
                } finally { disconnect() }
            }
        }

        const val VERSION = "20260723.1"
        const val WHEEL = "https://files.pythonhosted.org/packages/85/af/ba8419f684865b8cca587e01cedb41ba83fbdc985d75ab9e6ff38fdedf1a/" +
            "sudachidict_core-$VERSION-py3-none-any.whl"
        @Volatile private var loaded: Pair<String, Tokenizer>? = null
        @Volatile private var jiten: Pair<String, JitenSplitter>? = null
        /** On the PC: state\dicts, served by dojo-feed's GET /api/models/<file>. */
        val SPLITTER_FILES = listOf("jiten-lexicon.tsv.gz", "jiten_user_core.dic")
    }
}

/** Sudachi's system dictionaries, loaded once per path (kumapie `SudachiSystem`). */
object SudachiSystem {
    private val loaded = HashMap<String, com.worksap.nlp.sudachi.dictionary.BinaryDictionary>()

    @Synchronized
    fun of(path: java.nio.file.Path): com.worksap.nlp.sudachi.dictionary.BinaryDictionary =
        loaded.getOrPut(path.toString()) { com.worksap.nlp.sudachi.dictionary.BinaryDictionary.loadSystem(path.toString()) }
}
