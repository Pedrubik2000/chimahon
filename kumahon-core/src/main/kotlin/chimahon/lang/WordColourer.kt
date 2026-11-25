package chimahon.lang

import chimahon.srs.PeerSync
import chimahon.srs.Srs
import chimahon.srs.WordKey

/**
 * kumahon's own word colours, the same as kumapie's provider (`WordColours.query`): the text is split into words ([split]: the
 * port of `JapaneseModel.words`) and each word is drawn by `Word.shownFor`'s order:
 *  1. marked known (the newest mark of any device says known): "k", whatever its card says;
 *  2. its card in kumahon's own store (the dictionary form's, else the written form's; `CardIndex.state`): "d" due, "b" mined and
 *     never reviewed, "k" reviewed and not due;
 *  3. no card: "k" when on the known list (Jiten import, marks, reviewed cards' keys) or [auto] (names, interjections), else "u".
 * Rows are (start, length, state) per word, as the provider answers them. Without the dictionary ([split] null) there are no rows.
 */
class WordColourer(
    private val store: () -> Srs?,
    private val split: (String) -> List<JapaneseModel.Token>?,
    private val auto: (String) -> Boolean,
    /** The other spellings of a card's word ([JapaneseModel.spellings]); kumapie's `Language.cardKeys`. */
    private val spellings: (String, String) -> Set<String> = { _, _ -> emptySet() },
) {
    data class Row(val start: Int, val length: Int, val state: String)

    /** Card states by lower-cased spelling and "spelling\treading" key, and the marks, read from the store at most once a minute (kumapie's refresh). */
    private class Index(val cards: Map<String, List<String>>, val marks: Set<String>, val time: Long)
    @Volatile private var index: Index? = null

    /** After a sync or a rating: the next [rows] reads the store again. */
    fun reload() { index = null }

    @Synchronized private fun index(srs: Srs): Index = index?.takeIf { System.currentTimeMillis() - it.time < MAX_AGE_MS } ?: run {
        val now = System.currentTimeMillis()
        val cards = HashMap<String, MutableList<String>>()
        val ready = split("") != null
        for (c in srs.cards()) {
            val state = Srs.state(c.due, now)
            for (k in keysOf(c, ready)) cards.getOrPut(k.lowercase()) { ArrayList() } += state
        }
        // Words carded on another device (the hub's `carded`): mined, blue until a card here says more. One already in kumapie's list
        // as its `spelling<TAB>reading` was reviewed there: known, so it adds nothing (the known list decides).
        val listed = srs.listedWords()
        for ((k, forms) in srs.carded()) if (k !in listed) for (w in listOf(k, k.substringBefore('	')) + forms) cards.getOrPut(w.lowercase()) { ArrayList() } += "b"
        if (keys.size > 20_000) keys.clear()
        Index(cards, srs.markedWords(), now).also { index = it }
    }

    private val keys = HashMap<Triple<Long, Long, Boolean>, List<String>>()

    /**
     * Every key a card colours (kumapie `Language.cardKeys`): its own spelling and "spelling<TAB>reading", the word's other
     * spellings (Jiten's JMdict forms: ガキ共 colours ガキども, 分かる わかる), and the word bold in its sentence as it was tapped
     * (`<b>ワナ</b>` on a 罠 card, まひ on 麻痺) with its dictionary form when it splits into one word. Remembered per card and
     * state of the dictionary, so the index rebuilt every minute doesn't look every card up again.
     */
    private fun keysOf(c: Srs.Card, ready: Boolean): List<String> = keys.getOrPut(Triple(c.id, c.modified, ready)) {
        val own = PeerSync.keyOf(c)?.removePrefix("ja|")?.let { listOf(it, it.substringBefore('\t')) } ?: return@getOrPut emptyList()
        val (spelling, reading) = own[0].split('\t').let { it[0] to it.getOrElse(1) { "" } }
        val other = runCatching { spellings(spelling, reading) }.getOrNull().orEmpty()
        (own + other.flatMap { listOf(WordKey.japaneseKey(it, reading), it) } + tapped(c.sentence, spelling, ready)).distinct()
    }

    /** kumapie `Japanese.tapped`: nothing when the bold is the card's own spelling (its split can be another word: 覚え, 覚える). */
    private fun tapped(sentence: String, spelling: String, ready: Boolean): List<String> {
        val bold = BOLD.find(sentence)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() && it != spelling } ?: return emptyList()
        if (!ready) return listOf(bold)
        val word = runCatching { split(bold)?.filter { it.isWord }?.singleOrNull() }.getOrNull() ?: return emptyList()
        return listOf(bold, word.base)
    }

    /** The rows for [text]; empty while the dictionary isn't downloaded (or there is no store). */
    fun rows(text: String): List<Row> {
        val srs = store() ?: return emptyList()
        val words = split(text) ?: return emptyList()
        val ix = index(srs)
        return words.filter { it.isWord }.map { Row(it.begin, it.surface.length, shown(srs, ix, it.base, it.surface)) }
    }

    /** kumapie's `CardIndex.state`: due beats the rest, all never-reviewed is blue, else known; null = no card of its own. */
    private fun cardState(ix: Index, form: String): String? = ix.cards[form.lowercase()]?.let { s ->
        when {
            "d" in s -> "d"
            s.all { it == "b" } -> "b"
            else -> "k"
        }
    }

    private fun shown(srs: Srs, ix: Index, base: String, surface: String): String =
        if (base in ix.marks) "k"
        else cardState(ix, base) ?: cardState(ix, surface) ?: if (srs.isKnownSpelling(base) || auto(base)) "k" else "u"

    companion object {
        private const val MAX_AGE_MS = 60_000L
        private val BOLD = Regex("""<b>([^<\[]+)""")
    }
}
