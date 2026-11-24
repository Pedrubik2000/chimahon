package kumahon.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import chimahon.srs.Fsrs6
import chimahon.srs.Srs
import eu.kanade.tachiyomi.ui.glass.review.ReviewLogic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One place a card was met: "Title · vol. 3 · p. 42" and the sentence. */
data class Face(val where: String, val sentence: String)

/**
 * The Review screen's state, as the Android GlassReviewModel: same queue ([ReviewLogic.Session]), same store steps. Compose
 * state is written by the caller's (UI) coroutine; every store call runs on Dispatchers.IO, one at a time.
 */
class ReviewModel(private val store: Srs, private val scope: CoroutineScope) {
    /** Runs after each answer or undo (the sync timer). */
    var onAnswer: () -> Unit = {}

    var session by mutableStateOf(ReviewLogic.Session())
        private set
    var loading by mutableStateOf(true)
        private set
    var storeEmpty by mutableStateOf(false)
        private set
    var nextDue by mutableStateOf<Long?>(null)
        private set
    var card by mutableStateOf<Srs.Card?>(null)
        private set
    var shown by mutableStateOf(false)
        private set
    var faces by mutableStateOf<List<Face>>(emptyList())
        private set
    var intervals by mutableStateOf<List<String>>(emptyList())
        private set

    val canUndo: Boolean get() = session.prev != null

    private val cards = HashMap<Long, Srs.Card>()
    private val io = Mutex()
    private var busy = false
    private var shownAt = 0L

    /** Reads the queue when nothing was answered yet (also after a sync brought cards). */
    suspend fun load() {
        var read: Long? = null
        // the session swap happens inside the lock: a waiting answer() must see the new session when it resumes
        io.withLock {
            val l = withContext(Dispatchers.IO) {
                runCatching {
                    val due = store.due(System.currentTimeMillis())
                    val all = if (due.isEmpty()) store.cards() else emptyList()
                    Triple(due, all.isEmpty(), all.mapNotNull { it.due }.minOrNull())
                }.getOrNull()
            }
            loading = false
            if (l == null) return
            if (l.first.isNotEmpty()) {
                if (session.canReload) {
                    l.first.forEach { cards[it.id] = it }
                    session = ReviewLogic.Session.start(l.first.map { it.id }, l.first.filter { it.due == null }.map { it.id }.toSet())
                    read = setCurrent()
                }
            } else if (session.current == null) {
                storeEmpty = l.second
                nextDue = l.third
            }
        }
        read?.let { readFace(it) }
    }

    fun show() {
        if (card != null) shown = true
    }

    /** Runs in the model's own scope, so leaving the screen cannot cancel it between the write and the queue. */
    fun answer(rating: Int): Job = scope.launch {
        val c = card ?: return@launch
        if (!shown || busy) return@launch
        busy = true
        try {
            val ms = System.currentTimeMillis() - shownAt
            val ok = io.withLock {
                // a sync's reload may have swapped the session while we waited for the lock: never rate a card twice
                if (session.current != c.id) false
                else withContext(Dispatchers.IO) { runCatching { store.rate(c.id, rating, answerMs = ms) }.isSuccess }
            }
            if (!ok || session.current != c.id) return@launch // a failed write leaves the card and Undo as they were
            session = session.answer(rating)
            onAnswer()
            showCurrent()
        } finally { busy = false }
    }

    fun undo(): Job = scope.launch {
        if (busy) return@launch
        val id = session.prev?.current ?: return@launch
        busy = true
        try {
            val before = session
            session = session.undo()
            // no card on screen while the write runs: a key press cannot rate the wrong one
            card = null; shown = false; faces = emptyList(); intervals = emptyList()
            val ok = io.withLock { withContext(Dispatchers.IO) { runCatching { store.undo(id) }.isSuccess } }
            if (!ok) session = before // the rating is still in the store: no silent revert, or the card would be answered twice
            onAnswer()
            showCurrent()
        } finally { busy = false }
    }

    /** The head of the queue becomes the current card, front side; its contexts and answer intervals are read. */
    private suspend fun showCurrent() {
        val id = setCurrent()
        if (id == null) load() else readFace(id) // the queue ran out: a sync may have brought more
    }

    /** The state half of [showCurrent] (no store access, so [load] can run it inside the lock); answers the current id. */
    private fun setCurrent(): Long? {
        val id = session.current
        card = id?.let { cards[it] }
        shown = false
        faces = emptyList()
        intervals = emptyList()
        shownAt = System.currentTimeMillis()
        return id
    }

    private suspend fun readFace(id: Long) {
        val read = io.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    val c = store.card(id) ?: return@runCatching null
                    val now = System.currentTimeMillis()
                    val before = c.stability?.let { Fsrs6.State(it, c.difficulty!!) }
                    val days = c.lastReview?.let { Srs.elapsedDays(it, now) } ?: 0
                    val fsrs = store.fsrs
                    val labels = (1..4).map { ReviewLogic.intervalLabel(fsrs.interval(fsrs.next(before, days, it).stability)) }
                    store.contexts(id).map { Face(ReviewLogic.whereLine(it.title, it.volume, it.page), it.sentence.orEmpty().ifBlank { ReviewLogic.cardSentence(c.sentence) }) } to labels
                }.getOrNull()
            }
        }
        if (read != null && card?.id == id) {
            faces = read.first
            intervals = read.second
        }
    }
}
