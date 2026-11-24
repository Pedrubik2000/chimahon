package chimahon.srs

import org.json.JSONArray
import org.json.JSONObject

/**
 * The Japanese words kumapie counts as known, rebuilt from its sync answer (kumapie `KnownWords`), so a device without a kumapie of its
 * own colours words like the tablet. Entries, as [Srs.replaceKnown] keeps them:
 *  - a bare spelling (no tab): imported words (`known.ja.words`, Jiten) and words marked known (`progress.*.marked`, newest mark wins);
 *    known whatever the reading;
 *  - `spelling<TAB>reading` ([WordKey.japaneseKey]): a word with a card reviewed at least once (events replay to a standing rating), plus its bare spelling: kumapie's index holds both keys of a card and colours by spelling.
 * Not in the answer, so left out: words known by part of speech alone (`autoKnown`, Sudachi), and kuma3's Anki cards.
 */
object KnownWords {

    /** The entries for [answer] (kumapie's `/sync` JSON); events are replayed with [fsrs] (its parameters as kumapie sends them). */
    fun of(answer: JSONObject, fsrs: Fsrs6): Set<String> {
        val out = HashSet<String>()
        answer.optJSONObject("known")?.optJSONObject(Srs.LANG)?.optJSONArray("words")?.let { w ->
            for (i in 0 until w.length()) w.optString(i).takeIf { it.isNotEmpty() }?.let { out += it }
        }
        out += marks(answer.optJSONObject("progress"))
        val byKey = HashMap<String, MutableList<Pair<Long, Int>>>()
        answer.optJSONArray("events")?.let { ev ->
            for (i in 0 until ev.length()) ev.optJSONObject(i)?.let { e ->
                e.optString("key").takeIf { it.startsWith("ja|") }?.let { byKey.getOrPut(it.substring(3)) { mutableListOf() }.add(e.optLong("time") to e.optInt("ease")) }
            }
        }
        for ((key, rows) in byKey) {
            // kumapie (CardIndex.byCard): any card that is not new is known, whatever its stability; its index holds both keys of a card.
            Srs.replay(rows.sortedBy { it.first }, fsrs) ?: continue
            out += key; out += key.substringBefore('	')
        }
        return out
    }

    /** `progress` is {device: {marked: {word: [known, at]}}}: the words whose newest mark says known, Japanese ones only (kumapie `markWord`). */
    fun marks(progress: JSONObject?, mine: Map<String, Pair<Boolean, Long>> = emptyMap()): Set<String> {
        val latest = HashMap<String, Pair<Boolean, Long>>(mine) // marks made here that the hub may not have yet; a newer mark elsewhere wins
        if (progress != null) for (d in progress.keys()) progress.optJSONObject(d)?.optJSONObject("marked")?.let { m ->
            for (k in m.keys()) {
                val a: JSONArray = m.optJSONArray(k) ?: continue
                if ((latest[k]?.second ?: -1) <= a.optLong(1)) latest[k] = a.optBoolean(0) to a.optLong(1)
            }
        }
        return latest.filter { it.value.first && japaneseMark(it.key) }.keys
    }

    private val JAPANESE = Regex("[぀-ヿ㐀-鿿]")
    private val LATIN = Regex("[A-Za-zÀ-ɏ]")
    private val PREFIXED = Regex("^[a-z]{2}:")

    private fun japaneseMark(key: String) = !PREFIXED.containsMatchIn(key) && (JAPANESE.containsMatchIn(key) || !LATIN.containsMatchIn(key))

    /** Is (word, reading) in [entries]: the spelling alone, or its `spelling<TAB>reading` key. */
    fun isKnown(entries: Set<String>, word: String, reading: String): Boolean = word in entries || WordKey.japaneseKey(word, reading) in entries
}
