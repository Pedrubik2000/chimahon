package chimahon.srs

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import kotlin.random.Random

/** kumahon's one flashcard store (two [Srs] on the same file break) and its sync with kumapie's device sync ([PeerSync]). */
object Cards {
    private var store: Srs? = null

    @Synchronized
    fun store(context: Context): Srs = store ?: Srs(AndroidSqlDb.open(context.applicationContext), Build.MODEL).also { store = it }

    private var peer: PeerSync? = null

    /** The sync with kumapie (pairing, hub, status, [PeerSync.sync]) over this app's store and its kumahon_peer prefs. */
    @Synchronized
    fun peer(context: Context): PeerSync = peer ?: context.applicationContext.let { app -> PeerSync(AndroidPeerPrefs(app), { store(app) }, Build.MODEL.orEmpty()) { Log.i("kumahon", it) } }.also { peer = it }

    /** One exchange of review events with kumapie ([PeerSync.sync]): false = not paired or it failed (never throws). Blocking: call it off the main thread. */
    fun sync(context: Context): Boolean = peer(context).sync().also { chimahon.ocr.KumapieColours.clear() } // new known words, marks and reviews: page colours change

    /** The key kumapie matches this card by (`ja|spelling<TAB>reading`, from its `word` field and the `Reading` field), or null. */
    fun keyOf(card: Srs.Card): String? = PeerSync.keyOf(card)

    /**
     * The card of this store (not deleted) for a Japanese word and its reading: the lowest id whose key is the word's, as kumapie's
     * sync picks the card a review event goes to. Blocking (SQLite).
     */
    fun find(context: Context, word: String, reading: String): Srs.Card? {
        val want = WordKey.lookupKey(word, reading) ?: return null
        return store(context).cards().sortedBy { it.id }.firstOrNull { keyOf(it) == want }
    }

    /** A card of this store and the interval label of each answer (Again, Hard, Good, Easy) it would get now: the step [Srs.rate] takes, without writing. */
    class Preview(val card: Srs.Card, val labels: List<String>)

    /** [find]'s card with its four answer intervals from the real FSRS state; null when kumahon's own store has no card of the word. Blocking (SQLite). */
    fun preview(context: Context, word: String, reading: String, now: Long = System.currentTimeMillis()): Preview? {
        val card = find(context, word, reading) ?: return null
        val fsrs = store(context).fsrs
        val before = card.stability?.let { Fsrs6.State(it, card.difficulty!!) }
        val days = card.lastReview?.let { Srs.elapsedDays(it, now) } ?: 0
        return Preview(card, (1..4).map { eu.kanade.tachiyomi.ui.glass.review.ReviewLogic.intervalLabel(fsrs.interval(fsrs.next(before, days, it).stability)) })
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
     * A word met here: its card in this store (made unless [find] has it) and a context when there is a sentence.
     * One at a time, so two quick taps make one card. Blocking.
     */
    @Synchronized
    fun addMined(context: Context, word: String, reading: String, sentence: String, meaning: String, show: String?, episode: String?,
                 page: Int? = null, image: ByteArray? = null): Srs.Card {
        val card = find(context, word, reading)
            ?: store(context).addCard(furigana(word, reading), sentence, meaning, show, episode, null,
                JSONObject().put("Reading", reading).toString(), "kumahon")
        addContext(context, card.id, show, episode, page, sentence, image)
        // the card is in the store: recolour the page now (red -> blue), not after the sync that follows (5 s + network)
        chimahon.ocr.KumapieColours.clear()
        return card
    }

    /** The `word` field for a simple word, see [WordKey.furigana]. */
    fun furigana(word: String, reading: String): String = WordKey.furigana(word, reading)
}
