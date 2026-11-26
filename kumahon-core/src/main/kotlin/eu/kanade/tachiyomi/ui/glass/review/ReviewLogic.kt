package eu.kanade.tachiyomi.ui.glass.review

import java.time.Instant
import java.time.ZoneId

/** The pure parts of the Review screen (no Android, no store): word parsing, text clean-up, labels and the session queue. */
object ReviewLogic {

    data class Word(val spelling: String, val reading: String)

    private val furigana = Regex(" ?([^ \\[\\]]+)\\[([^\\]]*)]")
    private val pitch = Regex(""":[0-9A-Za-z\-]*""")
    private val junk = Regex("""[/*\\-]""")

    /**
     * A card's `word` in Anki furigana form (`魔法[まほう]`, ` 日本[にほん] 語[ご]`, kumapie's `気[き]:0 に / 入[い]る:0`,
     * `人[*ひと]:2-`, `拾[ひろ]\う:0`, maybe with HTML) as its spelling (brackets and spaces gone) and its reading in
     * kana. Cleaned as kumapie's CardIndex.japaneseKeys does. No brackets = no reading (""): the word is spelled in
     * kana already.
     */
    fun parseWord(raw: String): Word {
        val s = plainText(raw).replace(pitch, "").replace(junk, "").trim()
        val spelling = s.replace(furigana) { it.groupValues[1] }.replace(" ", "")
        val reading = if (furigana.containsMatchIn(s)) s.replace(furigana) { it.groupValues[2] }.replace(" ", "") else ""
        return Word(spelling, reading)
    }

    /** The Sound pill's cycle: auto -> tap -> off -> auto. Anything unknown reads as auto. */
    fun nextSound(mode: String): String = when (mode) {
        "auto" -> "tap"
        "tap" -> "off"
        else -> "auto"
    }

    fun normalSound(mode: String?): String = if (mode == "tap" || mode == "off") mode else "auto"

    /** The sentence to speak: the context shown, else the card's own sentence (clean text). */
    fun pickSentence(shown: String?, cardSentenceRaw: String): String =
        shown?.takeIf { it.isNotBlank() } ?: cardSentence(cardSentenceRaw)

    /** A reloaded queue starts with the card already on screen: keep its face (no reset to front, no replay). */
    fun keepFace(shownId: Long?, newCurrent: Long?): Boolean = shownId != null && shownId == newCurrent

    /** Cache file name of a sentence's voice: sha1 of the text, so the same sentence is made once. */
    fun ttsCacheName(text: String): String =
        java.security.MessageDigest.getInstance("SHA-1").digest(text.trim().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) } + ".wav"

    /** The reading to look a word's recording up with: the card's Reading field, else the one in its furigana form. */
    fun audioReading(fieldsJson: String, word: Word): String {
        val r = try { org.json.JSONObject(fieldsJson).optString("Reading") } catch (_: Exception) { "" }
        return r.ifBlank { word.reading }
    }

    /** Dictionary meaning as plain text: line-break tags become new lines, other tags go, common entities decoded. */
    fun plainText(html: String): String {
        val s = html
            .replace(Regex("(?i)<br\\s*/?>|</(p|div|li|tr|h[1-6])>"), "\n")
            .replace(Regex("<[^>]*>"), "")
            .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&amp;", "&")
        return s.lines().joinToString("\n") { it.trim() }.replace(Regex("\n{3,}"), "\n\n").trim()
    }

    /** A card's own sentence as plain text without Anki furigana (` 日本[にほん] 語[ご]を` -> `日本語を`). */
    fun cardSentence(raw: String): String = plainText(raw).replace(furigana) { it.groupValues[1] }

    /** [text] cut in parts, each with whether it is [word] (literal matches only, no morphology). */
    fun segments(text: String, word: String): List<Pair<String, Boolean>> {
        if (word.isEmpty()) return if (text.isEmpty()) emptyList() else listOf(text to false)
        val out = ArrayList<Pair<String, Boolean>>()
        var i = 0
        while (true) {
            val j = text.indexOf(word, i)
            if (j < 0) break
            if (j > i) out += text.substring(i, j) to false
            out += word to true
            i = j + word.length
        }
        if (i < text.length) out += text.substring(i) to false
        return out
    }

    /** "Title · vol. 3 · p. 42", leaving out what is missing. */
    fun whereLine(title: String?, volume: String?, page: Int?): String =
        listOfNotNull(title?.takeIf { it.isNotBlank() }, volume?.takeIf { it.isNotBlank() }, page?.let { "p. $it" })
            .joinToString(" · ")

    /** The scheduler's intervals are whole days: "1 d", "3 d", "2 mo", "1 y". */
    fun intervalLabel(days: Int): String = when {
        days < 30 -> "$days d"
        days < 365 -> "${(days + 15) / 30} mo"
        else -> "${(days + 182) / 365} y"
    }

    /** "Next card today at 21:14", "... tomorrow at 4:00", else "... on 2026-12-01". */
    fun nextLabel(due: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val d = Instant.ofEpochMilli(due).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val time = "${d.hour}:${d.minute.toString().padStart(2, '0')}"
        return when (d.toLocalDate()) {
            today -> "Next card today at $time"
            today.plusDays(1) -> "Next card tomorrow at $time"
            else -> "Next card on ${d.toLocalDate()}"
        }
    }

    /**
     * One review sitting over card ids. The head of [queue] is the current card. An Again (1) puts it back at the end
     * and it stays in the sitting; any other answer finishes it. [prev] is the sitting before the last answer (one
     * level of undo).
     */
    data class Session(
        val queue: List<Long> = emptyList(),
        val total: Int = 0,
        val done: Int = 0,
        /** ids never rated when the sitting began */
        val newIds: Set<Long> = emptySet(),
        /** ids answered Again in this sitting */
        val again: Set<Long> = emptySet(),
        val prev: Session? = null,
    ) {
        val current: Long? get() = queue.firstOrNull()
        val untouched: Boolean get() = prev == null && done == 0 && again.isEmpty()

        /** Nothing answered yet, or nothing left: a fresh queue may take its place. */
        val canReload: Boolean get() = current == null || untouched

        val againCount: Int get() = queue.count { it in again }
        val newCount: Int get() = queue.count { it in newIds && it !in again }
        val dueCount: Int get() = queue.size - againCount - newCount

        fun answer(rating: Int): Session {
            val id = current ?: return this
            val rest = queue.drop(1)
            val before = copy(prev = null)
            return if (rating == 1) {
                copy(queue = rest + id, again = again + id, prev = before)
            } else {
                copy(queue = rest, done = done + 1, prev = before)
            }
        }

        /** Back to before the last answer (that card is current again); the same session when there is none. */
        fun undo(): Session = prev ?: this

        companion object {
            fun start(ids: List<Long>, newIds: Set<Long>) = Session(queue = ids, total = ids.size, newIds = newIds)
        }
    }
}
