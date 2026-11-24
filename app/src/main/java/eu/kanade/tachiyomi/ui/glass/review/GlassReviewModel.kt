package eu.kanade.tachiyomi.ui.glass.review

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import chimahon.srs.Cards
import chimahon.srs.Fsrs6
import chimahon.srs.Srs
import eu.kanade.presentation.glass.glassReviewDue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/** One place a card was met, ready to draw: [where] is "Title · vol. 3 · p. 42", [image] only when the file exists. */
data class ReviewFace(val where: String, val sentence: String, val image: File?)

/** Reads the due count from the store (off the main thread) into [glassReviewDue]. Call from the main thread. */
suspend fun refreshReviewDue(context: Context) {
    val n = withContext(Dispatchers.IO) {
        try {
            Cards.store(context).due(System.currentTimeMillis()).size
        } catch (e: Exception) {
            Log.w("kumahon", "review due count failed: $e")
            0
        }
    }
    glassReviewDue.intValue = n
}

/**
 * The Review screen's state. Compose state is written on the main thread only; every store call runs on
 * Dispatchers.IO, one at a time (a rating is always written before the next card's state is read).
 */
class GlassReviewModel(private val app: Application = Injekt.get()) : ScreenModel {

    private class Loaded(val due: List<Srs.Card>, val noCards: Boolean, val next: Long?)

    var session by mutableStateOf(ReviewLogic.Session())
        private set
    var loading by mutableStateOf(true)
        private set

    /** The store holds no card at all (empty state: "No cards yet"). */
    var storeEmpty by mutableStateOf(false)
        private set

    /** The earliest due time among the cards, when nothing is due. */
    var nextDue by mutableStateOf<Long?>(null)
        private set
    var card by mutableStateOf<Srs.Card?>(null)
        private set
    var shown by mutableStateOf(false)
        private set

    /** Where the current card was met, newest first; empty until read (or none). */
    var contexts by mutableStateOf<List<ReviewFace>>(emptyList())
        private set

    /** The interval label of Again, Hard, Good, Easy for the current card; empty until read. */
    var intervals by mutableStateOf<List<String>>(emptyList())
        private set

    val canUndo: Boolean get() = session.prev != null

    private val cards = HashMap<Long, Srs.Card>() // main thread only
    private val io = Mutex()
    private var shownAt = 0L
    private var ratingBusy = false // a rating is being written (main thread only)
    private var loadJob: Job? = null

    /** Sync once 60 s pass without a new answer or undo: each one restarts the wait (one sync per burst, not per answer). */
    private fun syncLater() = ReviewSyncJob.later(app)

    /** The page opened: read the queue, sync with kumapie, then read it again if nothing was answered meanwhile. */
    fun start() {
        if (session.canReload) load()
        screenModelScope.launch {
            withContext(Dispatchers.IO) { Cards.sync(app) }
            if (session.canReload) load()
        }
    }

    fun show() {
        if (card != null) shown = true
    }

    fun answer(rating: Int) {
        val c = card ?: return
        if (!shown || ratingBusy) return
        ratingBusy = true
        val ms = SystemClock.elapsedRealtime() - shownAt
        screenModelScope.launch {
            val ok = io.withLock {
                // a sync's reload may have swapped the session while we waited for the lock: never rate a card twice
                if (session.current != c.id) return@withLock false
                withContext(Dispatchers.IO) {
                    try {
                        Cards.store(app).rate(c.id, rating, answerMs = ms)
                        true
                    } catch (e: Exception) {
                        Log.w("kumahon", "review rate failed, card stays: $e")
                        false
                    }
                }
            }
            ratingBusy = false
            // a failed rating leaves the session and Undo as they were; the card stays on screen
            if (!ok || session.current != c.id) return@launch
            session = session.answer(rating)
            glassReviewDue.intValue = session.queue.size
            syncLater()
            showCurrent()
        }
    }

    fun undo() {
        if (ratingBusy) return
        val id = session.prev?.current ?: return
        val before = session
        session = session.undo()
        glassReviewDue.intValue = session.queue.size
        screenModelScope.launch {
            val ok = io.withLock {
                withContext(Dispatchers.IO) {
                    try {
                        Cards.store(app).undo(id)
                        true
                    } catch (e: Exception) {
                        Log.w("kumahon", "review undo failed: $e")
                        false
                    }
                }
            }
            if (!ok) {
                // the rating is still in the store: put the sitting back, or the card would be answered twice
                session = before
                glassReviewDue.intValue = session.queue.size
                showCurrent()
            }
            syncLater()
        }
        showCurrent()
    }

    private fun load() {
        loadJob?.cancel()
        loadJob = screenModelScope.launch {
            // the session swap happens inside the lock: a waiting answer() must see the new session when it resumes
            io.withLock {
                val l = withContext(Dispatchers.IO) {
                    try {
                        val s = Cards.store(app)
                        val due = s.due(System.currentTimeMillis())
                        val all = if (due.isEmpty()) s.cards() else emptyList()
                        Loaded(due, all.isEmpty(), all.mapNotNull { it.due }.minOrNull())
                    } catch (e: Exception) {
                        Log.w("kumahon", "review load failed: $e")
                        null
                    }
                }
                loading = false
                if (l == null) return@withLock
                if (l.due.isNotEmpty()) {
                    if (session.canReload) {
                        l.due.forEach { cards[it.id] = it }
                        session = ReviewLogic.Session.start(l.due.map { it.id }, l.due.filter { it.due == null }.map { it.id }.toSet())
                        showCurrent()
                    }
                } else {
                    storeEmpty = l.noCards
                    nextDue = l.next
                }
                glassReviewDue.intValue = session.queue.size
            }
        }
    }

    /** The head of the queue becomes the current card, front side; its contexts and answer intervals are read next. */
    private fun showCurrent() {
        val id = session.current
        card = id?.let { cards[it] }
        shown = false
        contexts = emptyList()
        intervals = emptyList()
        shownAt = SystemClock.elapsedRealtime()
        if (id == null) {
            load() // the queue ran out: sync may have brought more
            return
        }
        screenModelScope.launch {
            val read = io.withLock {
                withContext(Dispatchers.IO) {
                    try {
                        val s = Cards.store(app)
                        val c = s.card(id) ?: return@withContext null
                        val now = System.currentTimeMillis()
                        // the same step Srs.rate takes, for each answer, without writing
                        val before = c.stability?.let { Fsrs6.State(it, c.difficulty!!) }
                        val days = c.lastReview?.let { Srs.elapsedDays(it, now) } ?: 0
                        val fsrs = s.fsrs
                        val labels = (1..4).map { ReviewLogic.intervalLabel(fsrs.interval(fsrs.next(before, days, it).stability)) }
                        val faces = s.contexts(id).map {
                            ReviewFace(
                                ReviewLogic.whereLine(it.title, it.volume, it.page),
                                it.sentence.orEmpty().ifBlank { ReviewLogic.cardSentence(c.sentence) },
                                it.image?.let(::File)?.takeIf { f -> f.exists() },
                            )
                        }
                        faces to labels
                    } catch (e: Exception) {
                        Log.w("kumahon", "review card read failed: $e")
                        null
                    }
                }
            }
            if (read != null && card?.id == id) {
                contexts = read.first
                intervals = read.second
            }
        }
    }
}
