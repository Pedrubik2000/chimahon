package chimahon.srs

import android.content.Context
import android.util.Log
import chimahon.ocr.KumapieColours
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.random.Random

/** kumahon's one flashcard store (two [Srs] on the same file break) and its sync with kumapie's. */
object Cards {
    private var store: Srs? = null
    private val syncing = Any()
    private const val MAX_ROUNDS = 40

    /** [ok]: every call made succeeded. [read]: kumapie's card index was readable (first call), so "no adopted card arrived" means kumapie has none. */
    data class Result(val ok: Boolean, val read: Boolean, val adopted: Map<Pair<String, String>, List<Long>> = emptyMap())

    @Synchronized
    fun store(context: Context): Srs = store ?: Srs(context.applicationContext).also { store = it }

    /**
     * Syncs the store with kumapie in rounds (at most [MAX_ROUNDS], each sends rows up to [Srs.BUDGET] characters) while kumapie has
     * more to give or rows wait in the outbox; a round that changes nothing (no rows taken, same `have` and outbox)
     * ends the loop. Every request carries `have` (the cards held, deleted ones too); the
     * first one also the [adopt] words (word, reading) kumapie may already have a card for. ok = false: kumapie didn't
     * answer (or answered an error) or the store failed. Blocking: call it off the main thread. Overlapping calls run
     * one after the other.
     */
    fun sync(context: Context, adopt: List<Pair<String, String>> = emptyList()): Result = synchronized(syncing) {
        try {
            val store = store(context)
            var read = false
            var adopted: Map<Pair<String, String>, List<Long>> = emptyMap()
            var round = 0
            // What a round must change to count as progress: rows taken, the cards held, the outbox.
            var haveBefore = store.have().toString()
            var pendingBefore = store.pending()
            while (true) {
                val words = JSONArray()
                if (round == 0) for ((word, reading) in adopt) words.put(JSONObject().put("word", word).put("reading", reading))
                val s = store.sync { body -> KumapieColours.srs(context, body.put("have", store.have()).put("words", words)) }
                if (round == 0) { read = s.read; adopted = s.adopted }
                if (!s.ok) return@synchronized Result(false, read, adopted)
                if (!s.more && s.pending == 0) break
                val haveAfter = store.have().toString()
                if (s.got == 0 && haveAfter == haveBefore && s.pending >= pendingBefore) {
                    Log.w("kumahon", "cards sync: round $round changed nothing (more=${s.more}, pending=${s.pending}): stopping, the rest waits for the next sync")
                    break
                }
                haveBefore = haveAfter; pendingBefore = s.pending
                if (++round >= MAX_ROUNDS) { Log.w("kumahon", "cards sync: still more after $MAX_ROUNDS rounds, the rest waits for the next sync"); break }
            }
            Result(true, read, adopted)
        } catch (e: Exception) {
            Log.w("kumahon", "cards sync failed: $e")
            Result(false, false)
        }
    }

    /**
     * The card of this store (not deleted) for a Japanese word and its reading (empty = any reading), or null. Blocking (SQLite).
     * First what kumapie adopted or we linked (`words` table; with an empty reading only when the word has exactly one card);
     * else among the cards tagged `kumahon` (made here, `word` in the [furigana] form), same rule for an empty reading. Cards in
     * kumapie's formats are never guessed from their text.
     */
    fun find(context: Context, word: String, reading: String): Srs.Card? {
        val s = store(context)
        val linked = s.wordCards(word, reading)
        if (linked.isNotEmpty()) return if (reading.isEmpty() && linked.size > 1) null else s.card(linked.first())
        val want = CardWord.hira(reading)
        val own = s.cards().filter { c ->
            c.tags.split(' ').contains("kumahon") && CardWord.key(c.word).let { (spelling, read) ->
                spelling == word && (want.isEmpty() || CardWord.hira(read) == want)
            }
        }
        return if (want.isEmpty() && own.size != 1) null else own.firstOrNull()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Runs [block] in the background (never delays the caller); an exception is logged, never thrown. */
    fun later(block: () -> Unit) {
        scope.launch {
            try { block() } catch (e: Exception) { Log.w("kumahon", "cards background step failed: $e") }
        }
    }

    /** Saves a screenshot as it is (no re-encoding) under `srs/contexts/` and answers its path. Blocking. */
    fun saveImage(context: Context, bytes: ByteArray): String {
        val dir = File(context.filesDir, "srs/contexts").apply { mkdirs() }
        // The bytes are what the reader encodes (WebP), so the extension says so.
        return File(dir, "${System.currentTimeMillis()}-${Random.nextInt(1000)}.webp").apply { writeBytes(bytes) }.path
    }

    /** A new context (where the word was met) on [card]; nothing when [sentence] is blank (R4: no sentence, no context). Blocking. */
    fun addContext(context: Context, card: Long, title: String?, volume: String?, page: Int?, sentence: String?, image: ByteArray? = null) {
        if (sentence.isNullOrBlank()) return
        store(context).addContext(card, title, volume, page, sentence, image?.let { saveImage(context, it) })
    }

    /**
     * A word mined here: its card in this store (made unless [find] has it) and a context when there is a sentence.
     * One at a time, so two quick taps make one card. Blocking.
     */
    @Synchronized
    fun addMined(context: Context, word: String, reading: String, sentence: String, meaning: String, show: String?, episode: String?,
                 page: Int? = null, image: ByteArray? = null): Srs.Card {
        val card = find(context, word, reading)
            ?: store(context).addCard(furigana(word, reading), sentence, meaning, show, episode, null, "{}", "kumahon")
                .also { store(context).linkWord(word, reading, it.id) }
        addContext(context, card.id, show, episode, page, sentence, image)
        return card
    }

    /** (spelling, reading) of a card's `word` field, see [CardWord.key]. */
    fun key(wordField: String): Pair<String, String> = CardWord.key(wordField)

    /** The `word` field for a simple word, see [CardWord.furigana]. */
    fun furigana(word: String, reading: String): String = CardWord.furigana(word, reading)
}

/** Pure text helpers of [Cards] (no Android classes, so a unit test can run them). */
object CardWord {
    private val BRACKET = Regex("\\[[^\\]]*]")
    private val RUBY = Regex("[^\\s\\[\\]]+\\[([^\\]]*)]")

    /**
     * A card's `word` field in Anki furigana form (`魔法[まほう]`, ` 日本[にほん] 語[ご]`, `食[た]べる`, plain kana as is,
     * maybe `:0-` pitch marker after the word) -> (spelling, reading): spelling without brackets and spaces, reading
     * with each `kanji[kana]` replaced by the kana.
     */
    fun key(wordField: String): Pair<String, String> {
        val s = wordField.substringBefore(':')
        val spelling = s.replace(BRACKET, "").filterNot { it.isWhitespace() }
        val reading = s.replace(RUBY) { it.groupValues[1] }.filterNot { it.isWhitespace() }
        return spelling to reading
    }

    /**
     * Inverse of [key] for a simple word: no kanji or no reading -> the word; else the kana shared at both ends stays
     * outside and the rest is bracketed (`食べる`,`たべる` -> `食[た]べる`; `お茶`,`おちゃ` -> `お 茶[ちゃ]`).
     */
    fun furigana(word: String, reading: String): String {
        if (reading.isEmpty() || word.none { it.isKanji() }) return word
        val w = hira(word)
        val r = hira(reading)
        var p = 0
        while (p < w.length && p < r.length && w[p] == r[p] && !word[p].isKanji()) p++
        var q = 0
        while (p + q < w.length && p + q < r.length && w[w.length - 1 - q] == r[r.length - 1 - q] && !word[word.length - 1 - q].isKanji()) q++
        val base = word.substring(p, word.length - q)
        val ruby = reading.substring(p, reading.length - q)
        if (base.isEmpty() || ruby.isEmpty()) return "$word[$reading]"
        return word.substring(0, p) + (if (p > 0) " " else "") + base + "[" + ruby + "]" + word.substring(word.length - q)
    }

    /** Katakana to hiragana (other characters as they are). */
    fun hira(s: String): String = s.map { if (it in 'ァ'..'ヶ') it - 0x60 else it }.joinToString("")

    private fun Char.isKanji() = this in '一'..'鿿' || this in '㐀'..'䶿' || this == '々'
}
