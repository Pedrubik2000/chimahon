package chimahon.srs

/**
 * The kuma3 / AnkiDroid layer: which words of the Mined deck count as known. Pure (no Android): the app reads notes and cards from
 * the AnkiDroid provider and hands them here.
 */
object AnkiKnown {
    /** A word is known from this many days of FSRS stability (Anki's own "mature-ish" rule; kumapie used it for kuma3). */
    const val KNOWN_DAYS = 7.0
    const val TAG = "known-manually"

    /** [word] = the configured word field as stored (HTML, furigana brackets and all). */
    data class Note(val id: Long, val word: String, val tags: String)

    /** [stabilityDays] = FSRS stability (null: no memory state or a provider without the column); [intervalDays] = the scheduler's ivl. */
    data class Card(val noteId: Long, val stabilityDays: Double?, val intervalDays: Int?)

    /** ponytail: a field this long or with sentence punctuation is a sentence, not a word; no tokenizer here. */
    private const val MAX_WORD = 15
    private val RT = Regex("""(?is)<(rt|rp)\b[^>]*>.*?</\1>""")
    private val SENTENCE = Regex("""[。、！？!?「」『』…,;]""")

    /** The spelling a word field means (no HTML, no furigana brackets, no spaces), or null: empty, or a sentence. */
    fun surface(field: String): String? {
        val bare = field.replace(RT, "") // <ruby>漢字<rt>かんじ</rt></ruby>: the reading is not part of the word
        val s = WordKey.japaneseKeys(bare).getOrNull(1) ?: return null
        return s.takeIf { it.length <= MAX_WORD && !SENTENCE.containsMatchIn(it) }
    }

    /** One card's days: its stability, else (provider without it) its interval; a new or learning card (ivl <= 0) counts 0. */
    private fun days(c: Card): Double = c.stabilityDays ?: c.intervalDays?.toDouble()?.coerceAtLeast(0.0) ?: 0.0

    /** Known = its best card has >= [minDays] days, or the note has the tag [TAG]; notes without a usable word are skipped. */
    fun words(notes: List<Note>, cards: List<Card>, minDays: Double = KNOWN_DAYS): Set<String> {
        val best = HashMap<Long, Double>()
        for (c in cards) best.merge(c.noteId, days(c), ::maxOf)
        val out = HashSet<String>()
        for (n in notes) {
            val w = surface(n.word) ?: continue
            if (n.tags.split(' ').any { it.equals(TAG, ignoreCase = true) } || (best[n.id] ?: 0.0) >= minDays) out += w
        }
        return out
    }
}
