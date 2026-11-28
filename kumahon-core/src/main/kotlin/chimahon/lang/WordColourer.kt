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
    private class Index(val cards: Map<String, List<String>>, val ids: Map<String, Long>, val marks: Set<String>, val time: Long)
    @Volatile private var index: Index? = null

    /** After a sync or a rating: the next [rows] reads the store again. */
    fun reload() { index = null }

    @Synchronized private fun index(srs: Srs): Index = index?.takeIf { System.currentTimeMillis() - it.time < MAX_AGE_MS } ?: run {
        val now = System.currentTimeMillis()
        val cards = HashMap<String, MutableList<String>>()
        val ids = HashMap<String, Long>() // the lowest card id of each key: the card the popup shows and rates
        val ready = split("") != null
        for (c in srs.cards()) {
            val state = Srs.state(c.due, now)
            for (k in keysOf(c, ready)) {
                cards.getOrPut(k.lowercase()) { ArrayList() } += state
                ids[k.lowercase()] = minOf(ids[k.lowercase()] ?: c.id, c.id)
            }
        }
        // Words carded on another device (the hub's `carded`): mined, blue until a card here says more. One already in kumapie's list
        // as its `spelling<TAB>reading` was reviewed there: known, so it colours nothing itself (the list decides) and its forms are known
        // too. An unreviewed reading gives the bare spelling no blue while another reading of it was reviewed (an imported or marked spelling is not that: it stays blue).
        val listed = srs.listedWords()
        val reviewedBare = listed.filter { '	' in it }.mapTo(HashSet()) { it.substringBefore('	') } // tab entries come only from replayed reviews
        for ((k, forms) in srs.carded()) {
            val bare = k.substringBefore('\t')
            if (k in listed) for (w in forms) cards.getOrPut(w.lowercase()) { ArrayList() } += "k"
            else for (w in listOf(k) + (if (bare in reviewedBare) emptyList() else listOf(bare)) + forms) cards.getOrPut(w.lowercase()) { ArrayList() } += "b"
        }
        if (keys.size > 20_000) keys.clear()
        Index(cards, ids, srs.markedWords(), now).also { index = it }
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
        val toks = words.filter { it.isWord }
        val states = toks.mapTo(ArrayList()) { shown(srs, ix, it.base, it.surface) }
        // A card whose word the splitter cuts in pieces (怪奇現象 = 怪奇 + 現象): the pieces written together take the card's
        // colour, the longest run first. Not in kumapie's `WordColours` (yet).
        var i = 0
        while (i < toks.size) {
            var step = 1
            for (n in minOf(MAX_RUN, toks.size - i) downTo 2) {
                val state = if (touching(toks, i, n)) run(ix, text, toks, i, n) else null
                if (state != null) {
                    for (k in i until i + n) states[k] = state
                    step = n
                    break
                }
            }
            i += step
        }
        return toks.mapIndexed { k, it -> Row(it.begin, it.surface.length, states[k]) }
    }

    private fun touching(toks: List<JapaneseModel.Token>, i: Int, n: Int): Boolean =
        (i until i + n - 1).all { toks[it].begin + toks[it].surface.length == toks[it + 1].begin }

    /** The colour of the card (or mark) whose word is [n] pieces from [i] written together; null = there is none. */
    private fun run(ix: Index, text: String, toks: List<JapaneseModel.Token>, i: Int, n: Int): String? {
        val last = toks[i + n - 1]
        val head = text.substring(toks[i].begin, last.begin)
        // as written, then with the last piece in its dictionary form (a 英気を養う card colours 英気を養って)
        return listOf(head + last.surface, head + last.base).distinct().firstNotNullOfOrNull { joined ->
            cardState(ix, joined)?.let { if (joined in ix.marks) "k" else it }
        }
    }

    /** The colour the page gives a word (dictionary form [base], written [surface]): the lookup popup shows the same. Null = no store. */
    fun state(base: String, surface: String = base): String? = store()?.let { shown(it, index(it), base, surface) }

    /** The own card that colours [base] (else [surface]) on the page, by any of its keys (another spelling, the tapped form); null = none. */
    fun cardId(base: String, surface: String = base): Long? =
        store()?.let { index(it) }?.let { it.ids[base.lowercase()] ?: it.ids[surface.lowercase()] }

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
        private const val MAX_RUN = 4 // pieces of one card's word
        private val BOLD = Regex("""<b>([^<\[]+)""")
    }
}
