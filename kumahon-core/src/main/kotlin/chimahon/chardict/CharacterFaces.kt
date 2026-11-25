package chimahon.chardict

import chimahon.lang.JapaneseModel
import org.json.JSONArray
import org.json.JSONObject

/** One dictionary's glossary (raw structured-content JSON) for a word. */
class Glossary(val dict: String, val raw: String)

/** The character dictionary alone, as a lookup session sees it (Android: kumahon's HoshiDicts over that one dictionary). */
interface CharacterDict {
    /** Glossaries of the exact word, empty when no character has it. */
    fun glossaries(word: String): List<Glossary>
    fun media(dict: String, path: String): ByteArray?
}

/** Reading the entries kumahon's own builder writes (the same ids and "From:" line kumapie's Hoshidicts reads). Port of its `character`, `fromWork`, `portraitPath`. */
object CharacterEntry {
    /** An entry's name in Latin letters and the honorific it adds (壬氏様: "Jinshi", "様"; 壬氏: "Jinshi", null); null for any other entry. */
    fun character(glossary: String): Pair<String, String?>? = runCatching {
        if ("\"name-romaji\"" !in glossary) return null
        // the native layer hands the glossary as an array of one object (kumapie reads it so), tolerated as the object itself
        val root = org.json.JSONTokener(glossary).nextValue().let { if (it is JSONArray) it.optJSONObject(0) else it as? JSONObject }
        val top = root?.optJSONArray("content") ?: return null
        val parts = (0 until top.length()).mapNotNull { top.optJSONObject(it) }
        val romaji = parts.firstOrNull { it.optJSONObject("data")?.optString("id") == "name-romaji" }?.optString("content")
        // An honorific's entry starts with a banner of its own: [様, " — Very formal/respectful"].
        romaji?.let { it to parts.firstOrNull()?.takeIf { p -> !p.has("data") }?.optJSONArray("content")?.optJSONObject(0)?.optString("content") }
    }.getOrNull()

    /**
     * The entry is from [work]: its "From: <title>" and the work's title match once spaces, punctuation and a volume number
     * are gone ("くま クマ 熊 ベアー17" is くまクマ熊ベアー's). One holds the other, so a sequel finds its first season's title.
     */
    fun fromWork(glossary: String, work: String): Boolean {
        val w = workKey(work).trimEnd { it.isDigit() }
        if (w.length < 2) return false
        // every "From: <title>" text of the parsed JSON (the single card's block or a multi-media row's span), unescaped
        return nodes(glossary).filterIsInstance<String>().filter { "From: " in it }.any { s ->
            workKey(s.substringAfter("From: ")).let { f -> f.length >= 2 && (f in w || w in f) }
        }
    }

    /** Every value of the glossary's JSON, depth first (empty when it is not JSON). */
    private fun nodes(json: String): Sequence<Any?> = nodes(runCatching { org.json.JSONTokener(json).nextValue() }.getOrNull())

    private fun nodes(v: Any?): Sequence<Any?> = sequence {
        yield(v)
        when (v) {
            is JSONArray -> for (i in 0 until v.length()) yieldAll(nodes(v.opt(i)))
            is JSONObject -> for (k in v.keys()) yieldAll(nodes(v.opt(k)))
        }
    }

    fun workKey(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC).lowercase().filter { it.isLetterOrDigit() }

    /** The file of a character entry's portrait (the img inside its "portrait-container", not the voice actor's later one), or null. */
    fun portraitPath(glossary: String): String? =
        nodes(glossary).filterIsInstance<JSONObject>().firstOrNull { it.optJSONObject("data")?.optString("id") == "portrait-container" }
            ?.let { box -> nodes(box).filterIsInstance<JSONObject>().firstOrNull { it.has("path") }?.optString("path")?.ifEmpty { null } }
}

/**
 * Finds character names in a text, as kumapie's subtitles do (port of `WordColours.faces`): the words are joined up to 5 pieces
 * at a time, longest first (壬|氏|様 is 壬氏様, named 壬氏); a join of several pieces must be a name only the character
 * dictionary has, a single word is a name when it is a headword (2+ characters, not only kana), and with no work also when [autoKnown] accepts it; the honorific is stripped. With a work, only its characters.
 * [cache] stores a portrait by key (Android: a file in the cache dir); it is asked for every name, found or not.
 */
class FaceMatcher(
    private val dict: CharacterDict,
    private val autoKnown: (String) -> Boolean = { true },
    private val cache: (String, () -> ByteArray?) -> ByteArray? = { _, f -> f() },
) {
    class Hit(val start: Int, val length: Int, val name: String, val portrait: ByteArray)

    /** The name when only the character dictionary has [word] (壬氏様 -> 壬氏, 壬氏 -> 壬氏), else null. */
    fun characterName(word: String): String? {
        val found = dict.glossaries(word).map { CharacterEntry.character(it.raw) ?: return null }
        if (found.isEmpty()) return null
        return word.removeSuffix(found.firstNotNullOfOrNull { it.second }.orEmpty()).ifBlank { null }
    }

    /** [name]'s portrait: with [work], only that work's characters; without, the first character with that name. */
    fun portrait(name: String, work: String?): ByteArray? = cache("$name\n${work.orEmpty()}") {
        dict.glossaries(name).filter { work.isNullOrBlank() || CharacterEntry.fromWork(it.raw, work) }
            .firstNotNullOfOrNull { g -> CharacterEntry.portraitPath(g.raw)?.let { dict.media(g.dict, it) } }
    }

    fun match(text: String, words: List<JapaneseModel.Token>, work: String?): List<Hit> {
        val ws = words.filter { it.isWord }
        val out = ArrayList<Hit>()
        var i = 0
        while (i < ws.size) {
            val first = ws[i]
            var hit = 1
            if (first.surface.firstOrNull() !in 'ぁ'..'ゖ') for (n in minOf(5, ws.size - i) downTo 1) {
                val last = ws[i + n - 1]
                val t = text.substring(first.begin, last.begin + last.surface.length)
                // something between the pieces, except the middle dot of ソフィナ・ブリビア (a symbol, so not among [ws])
                if (t.replace("・", "").length != (i until i + n).sumOf { ws[it].surface.replace("・", "").length }) continue
                // a single word: the dictionary holds only characters, so its headword is enough (Sudachi's part of speech for a name is unreliable); no 1-character or kana-only words
                val name = (if (n > 1) characterName(t) ?: characterName(t.replace("・", ""))
                    else listOf(t, first.base).firstNotNullOfOrNull { w -> w.takeIf { it.length > 1 && !it.all { c -> c in 'ぁ'..'ゖ' } }?.let(::characterName) }
                        // without the work (its title not known yet) any character's name would do: then Sudachi must also take it for a name
                        ?.takeIf { !work.isNullOrBlank() || autoKnown(it) })
                    ?: continue
                val picture = portrait(name, work) ?: continue
                out += Hit(first.begin, t.length, name, picture)
                hit = n
                break
            }
            i += hit
        }
        return out
    }
}

/** The works the reader opened, and when the character dictionary has to be built again (kumapie's `reading_characters.py`: only when the set changes). */
object ReadWorks {
    fun add(works: List<String>, title: String): List<String> =
        if (title.isBlank() || works.any { CharacterEntry.workKey(it) == CharacterEntry.workKey(title) }) works else works + title

    const val NOT_FOUND_RETRY_MS = 7L * 24 * 3600 * 1000

    /** [read] without the works AniList did not find in the last 7 days ([notFound]: workKey to the time it was tried). */
    fun pending(read: List<String>, notFound: Map<String, Long> = emptyMap(), now: Long = 0): List<String> =
        read.filter { w -> notFound[CharacterEntry.workKey(w)]?.let { now - it >= NOT_FOUND_RETRY_MS } ?: true }

    /** Something was read and the set is not the one the built dictionary has (works AniList does not know are left out for a week). */
    fun needsRebuild(read: List<String>, built: List<String>, notFound: Map<String, Long> = emptyMap(), now: Long = 0): Boolean =
        read.isNotEmpty() && pending(read, notFound, now).map(CharacterEntry::workKey).toSet() != built.map(CharacterEntry::workKey).toSet()
}
