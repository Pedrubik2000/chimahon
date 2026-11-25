package chimahon.lang

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** One HTTP answer; [body] is streamed (the frequency CSV is large). */
class JitenReply(val code: Int, val body: InputStream) {
    constructor(code: Int, text: String) : this(code, ByteArrayInputStream(text.toByteArray()))
}

/** GET [url]; tests pass a fake. */
fun interface JitenTransport {
    @Throws(IOException::class)
    fun get(url: String, userAgent: String): JitenReply
}

/** The real thing, over HttpURLConnection (kumapie `JitenHttp`). */
object JitenHttp : JitenTransport {
    override fun get(url: String, userAgent: String): JitenReply {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        c.setRequestProperty("User-Agent", userAgent)
        val code = c.responseCode
        return JitenReply(code, if (code in 200..299) c.inputStream else (c.errorStream ?: ByteArrayInputStream(ByteArray(0))))
    }
}

/** An imported known-words list as kumapie syncs it: `known.ja = {time, words}`. */
data class KnownImport(val time: Long, val words: Set<String>)

/**
 * kumapie's Jiten known-words import, rule for rule (kumapie-tv `lang/Jiten.kt`, `KnownWords.importJiten`, `ImportedLists.use`),
 * so `known.ja` means the same in both apps: the top N words of jiten.moe's global frequency list plus the picked sets of kumapie's
 * `wordlists/ja.tsv`; one import replaces the previous whole; the list with the newer time wins whole.
 * Network calls block: run them off the main thread.
 */
class JitenKnown(
    private val transport: JitenTransport = JitenHttp,
    private val clock: () -> Long = System::currentTimeMillis,
    private val userAgent: String = "kumahon (https://github.com/Pedrubik2000/kumahon)",
) {
    @Volatile private var blockedUntil = 0L

    /** True while jiten.moe's last answer was 403/418 and 6 hours have not passed. */
    val blocked: Boolean get() = clock() < blockedUntil

    /** The import: top [n] words and [sets] (slugs of [SETS]); [KnownImport.time] is now. Throws on HTTP errors (and while [blocked]). */
    fun import(n: Int, sets: Collection<String>): KnownImport {
        val words = top(n) + if (sets.isEmpty()) emptySet() else get(LISTS).use { lists(it.bufferedReader().lineSequence(), sets.toSet()) }
        return KnownImport(clock(), words)
    }

    private fun top(n: Int): Set<String> =
        if (n <= 0) emptySet() else get("$API/frequency-list/download?downloadType=csv").use { topFromCsv(it.bufferedReader().lineSequence(), n) }

    private fun get(url: String): InputStream {
        val api = url.startsWith(API) // the backoff is jiten.moe's; the GitHub list is never blocked and never blocks
        if (api && blocked) throw IOException("api.jiten.moe refused the request (418/403): no more asks for 6 hours")
        val r = transport.get(url, userAgent)
        if (api && (r.code == 403 || r.code == 418)) blockedUntil = clock() + BACKOFF_MS
        if (r.code != 200) { r.body.close(); throw IOException("${URL(url).host}: HTTP ${r.code} for $url") }
        return r.body
    }

    companion object {
        const val API = "https://api.jiten.moe/api"
        const val LISTS = "https://raw.githubusercontent.com/Pedrubik2000/kumapie-tv/main/wordlists/ja.tsv"
        const val BACKOFF_MS = 6 * 3_600_000L

        /** The word lists: slug (the file's first column) -> name. */
        val SETS = linkedMapOf(
            "particles" to "Particles",
            "counters" to "Counters",
            "onomatopoeia" to "Onomatopoeia",
            "grammar" to "Grammar",
            "honorific" to "Honorifics",
            "interjections" to "Interjections",
        )

        /** Words ranked 1..[n] in the CSV "Word,Form,Rank" (header first, ordered by rank): column 0, rank = last column; line by line. */
        fun topFromCsv(lines: Sequence<String>, n: Int): Set<String> {
            val out = HashSet<String>()
            if (n <= 0) return out
            for (line in lines.drop(1)) {
                val cols = line.split(',')
                val rank = cols.lastOrNull()?.toIntOrNull() ?: continue
                if (rank > n) break
                cols[0].takeIf { it.isNotBlank() }?.let(out::add)
            }
            return out
        }

        /** The words of [sets] in the lists file ("slug<TAB>form" lines, "#" comments). */
        fun lists(lines: Sequence<String>, sets: Set<String>): Set<String> =
            lines.filter { !it.startsWith("#") }.map { it.split('\t') }
                .filter { it.size == 2 && it[0] in sets && it[1].isNotBlank() }.map { it[1] }.toSet()

        /** The newer list wins, whole; an equal time keeps [mine] (kumapie never applies an equal time again). */
        fun merge(mine: KnownImport, theirs: KnownImport?): KnownImport = if (theirs != null && theirs.time > mine.time) theirs else mine
    }
}

