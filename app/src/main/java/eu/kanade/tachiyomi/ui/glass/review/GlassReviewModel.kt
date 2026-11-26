package eu.kanade.tachiyomi.ui.glass.review

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import android.media.MediaPlayer
import android.net.Uri
import android.os.ParcelFileDescriptor
import chimahon.anki.SentenceTts
import chimahon.audio.WordAudioService
import chimahon.srs.Cards
import chimahon.srs.Fsrs6
import chimahon.srs.Srs
import eu.kanade.presentation.glass.glassReviewDue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

private const val AUDIO_TAG = "KumahonAudio"

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
    private fun syncLater() {
        chimahon.srs.SyncTrigger.request(app, "review rating") // right after (coalesced over 5 s)
        ReviewSyncJob.later(app) // and once more after 60 s quiet, which survives a killed process
    }

    /** The page opened: read the queue, sync with kumapie, then read it again if nothing was answered meanwhile. */
    fun start() {
        screenModelScope.launch { phoneVoice = withContext(Dispatchers.IO) { phoneVoiceAvailable() } }
        if (session.canReload) load()
        screenModelScope.launch {
            withContext(Dispatchers.IO) { Cards.sync(app) }
            if (session.canReload) load()
        }
    }

    fun show() {
        if (card == null) return
        shown = true
        stopAudio()
        if (autoplay()) playWord()
    }

    private var visible = false // main thread: the Review page is on screen (the model outlives it)

    /** The page calls this when it enters / leaves the screen; leaving stops the sound and blocks autoplay. */
    fun pageVisible(on: Boolean) {
        visible = on
        if (!on) stopAudio()
    }

    // ---- audio: the Sound pill, the buttons and taps on the text all end up in play() ----

    /** What is sounding now; the Making* ones are the wait for the voice / the recording. */
    enum class Voice { None, MakingSentence, Sentence, MakingWord, Word }

    private val prefs by lazy { app.getSharedPreferences("glass_review", Context.MODE_PRIVATE) } // first use usually on IO

    /** "auto" (front: the sentence reads itself, Show: the word), "tap" (only on tap), "off" (taps on text do nothing special). Auto until the saved choice is read. */
    var sound by mutableStateOf("auto")
        private set
    private var soundTouched = false
    private var soundLoaded = false // no autoplay before the saved choice is known (it may be tap / off)

    private fun autoplay() = soundLoaded && sound == "auto" && visible

    init {
        screenModelScope.launch {
            val saved = withContext(Dispatchers.IO) { ReviewLogic.normalSound(prefs.getString("sound", null)) }
            if (!soundTouched) sound = saved
            soundLoaded = true
            // the first card may have been shown before the choice was known: give it its autoplay now
            if (autoplay() && card != null && !shown && voice == Voice.None) playSentence(currentSentence())
        }
    }

    /** A phone voice (Japanese TTS) is usable; read once per model on IO. False until known. */
    var phoneVoice by mutableStateOf(false)
        private set
    private var phoneVoiceCache: Boolean? = null
    private var phoneVoiceRetryAt = 0L // after a too-slow engine start: answer false until then, not 5 s per play
    private val phoneLock = Any()

    /** Blocking on the first call (one TTS init), cached after. IO thread. */
    private fun phoneVoiceAvailable(): Boolean =
        synchronized(phoneLock) {
            phoneVoiceCache ?: if (System.currentTimeMillis() < phoneVoiceRetryAt) false
            else SentenceTts.available(app)?.also { phoneVoiceCache = it } ?: false.also { phoneVoiceRetryAt = System.currentTimeMillis() + 60_000 }
        }
    var voice by mutableStateOf(Voice.None)
        private set

    /** The sentence text being made or spoken (the face that shows it gets the ice line). */
    var voiceText by mutableStateOf("")
        private set

    /** The current card's word has no recording (found out on the first try): the phone voice is used. */
    var wordNoRecording by mutableStateOf(false)
        private set

    private var playGen = 0 // bumped by every stop/play: a late result of an older one is dropped
    private var playJob: Job? = null

    fun cycleSound() {
        soundTouched = true
        sound = ReviewLogic.nextSound(sound)
        if (sound == "off") stopAudio()
        prefs.edit().putString("sound", sound).apply() // apply() doesn't block; on main, so fast taps save in order
    }

    /** A tap on the sentence text: plays it, unless Sound is off (then the front still means Show). */
    fun tapSentence(text: String) {
        if (sound != "off") playSentence(text) else if (!shown) show()
    }

    /** A tap on the word text (answer side); nothing when Sound is off. */
    fun tapWord() {
        if (sound != "off") playWord()
    }

    /** The sentence the buttons and the front's autoplay speak: the newest context, else the card's own. */
    fun currentSentence(): String =
        ReviewLogic.pickSentence(contexts.firstOrNull()?.sentence, card?.sentence.orEmpty())

    fun playSentence(text: String) {
        if (text.isBlank()) return
        play(Voice.MakingSentence, text)
    }

    fun playWord() {
        if (wordNoRecording && !phoneVoice) return // no recording and no phone voice: nothing to play
        play(Voice.MakingWord, "")
    }

    fun stopAudio() {
        playGen++
        playJob?.cancel()
        playJob = null
        voice = Voice.None
    }

    override fun onDispose() = stopAudio()

    private sealed class Src {
        class Url(val url: String) : Src()
        class Fd(val pfd: ParcelFileDescriptor) : Src()
        class Wav(val file: File) : Src()
    }

    private fun play(kind: Voice, text: String) {
        val c = card ?: return
        stopAudio()
        val gen = playGen
        val sentence = kind == Voice.MakingSentence
        voice = kind
        voiceText = text
        playJob = screenModelScope.launch {
            // set inside the IO block, so a cancellation after the open still leaves it to the finally to close
            var src: Src? = null
            var noRecording = false
            try {
                withContext(Dispatchers.IO) {
                    try {
                        if (sentence) {
                            src = ttsFile(text, gen)?.let { Src.Wav(it) }
                        } else {
                            val w = ReviewLogic.parseWord(c.word)
                            val reading = ReviewLogic.audioReading(c.fields, w)
                            src = wordSource(w.spelling, reading)
                            if (src == null) {
                                noRecording = true
                                src = ttsFile(reading.ifBlank { w.spelling }, gen)?.let { Src.Wav(it) } // phone voice
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.i(AUDIO_TAG, "audio lookup failed: $e")
                    }
                }
                if (gen != playGen || card?.id != c.id) return@launch
                if (!sentence) wordNoRecording = noRecording
                val s = src ?: return@launch
                playSource(s) { if (gen == playGen) voice = if (sentence) Voice.Sentence else Voice.Word }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i(AUDIO_TAG, "audio play failed: $e")
            } finally {
                (src as? Src.Fd)?.let { runCatching { it.pfd.close() } }
                if (gen == playGen) voice = Voice.None
            }
        }
    }

    /** The word's first recording (url or a local dictionary file), or null. Blocking: IO thread. */
    private suspend fun wordSource(word: String, reading: String): Src? {
        val r = Injekt.get<WordAudioService>().findWordAudio(word, reading).firstOrNull() ?: return null
        if (!r.url.startsWith("chimahon-local://")) return Src.Url(r.url)
        val uri = Uri.parse(r.url)
        val pfd = Injekt.get<WordAudioService>().getAudioDataFd(uri.path?.removePrefix("/") ?: return null, uri.host ?: return null)
        return pfd?.let { Src.Fd(it) }
    }

    private val ttsLock = Mutex() // one synthesis at a time

    /**
     * The phone voice for [text] as a cached wav (made once per text), or null. Waits its turn; a request that is
     * no longer current ([gen] != playGen) when its turn comes is dropped before the engine starts.
     */
    private suspend fun ttsFile(text: String, gen: Int): File? {
        val dir = File(app.cacheDir, "review_tts").apply { mkdirs() }
        val f = File(dir, ReviewLogic.ttsCacheName(text))
        if (f.length() > 44) return f
        if (!phoneVoiceAvailable()) return null
        return ttsLock.withLock {
            if (f.length() > 44) return@withLock f
            if (gen != playGen) return@withLock null
            val bytes = SentenceTts.say(app, text) ?: return@withLock null
            val tmp = File(dir, f.name + ".tmp")
            tmp.writeBytes(bytes)
            if (tmp.renameTo(f)) f else null.also { tmp.delete() }
        }
    }

    /** Plays [src] on a new MediaPlayer until it ends; cancelling releases it. Main thread. */
    private suspend fun playSource(src: Src, onStart: () -> Unit) = suspendCancellableCoroutine<Unit> { cont ->
        val p = MediaPlayer()
        val timeout = Runnable { runCatching { p.release() }; if (cont.isActive) cont.resume(Unit) }
        val handler = Handler(Looper.getMainLooper())
        cont.invokeOnCancellation { handler.removeCallbacks(timeout); runCatching { p.release() } }
        p.setOnPreparedListener { handler.removeCallbacks(timeout); if (cont.isActive) { onStart(); it.start() } }
        p.setOnCompletionListener { runCatching { it.release() }; if (cont.isActive) cont.resume(Unit) }
        p.setOnErrorListener { m, what, extra ->
            Log.i(AUDIO_TAG, "player error $what/$extra")
            runCatching { m.release() }
            if (cont.isActive) cont.resume(Unit)
            true
        }
        try {
            when (src) {
                is Src.Url -> p.setDataSource(src.url)
                is Src.Fd -> p.setDataSource(src.pfd.fileDescriptor)
                is Src.Wav -> p.setDataSource(src.file.absolutePath)
            }
            p.prepareAsync()
            if (src is Src.Url) handler.postDelayed(timeout, 10_000) // a dead server must not hold the spinner
        } catch (e: Exception) {
            runCatching { p.release() }
            cont.resumeWithException(e)
        }
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
        ratingBusy = true // no second undo or answer until this one is written
        session = session.undo()
        glassReviewDue.intValue = session.queue.size
        screenModelScope.launch {
            try {
                val ok = io.withLock {
                    val written = withContext(Dispatchers.IO) {
                        try {
                            Cards.store(app).undo(id)
                            true
                        } catch (e: Exception) {
                            Log.w("kumahon", "review undo failed: $e")
                            false
                        }
                    }
                    // the rating is still in the store: put the sitting back inside the lock, or a waiting answer() sees a half-undone session
                    if (!written) session = before
                    written
                }
                if (!ok) {
                    glassReviewDue.intValue = session.queue.size
                    showCurrent()
                }
                syncLater()
            } finally {
                ratingBusy = false
            }
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
                        val keep = ReviewLogic.keepFace(card?.id, l.due.first().id) // same card, same face: no restart
                        session = ReviewLogic.Session.start(l.due.map { it.id }, l.due.filter { it.due == null }.map { it.id }.toSet())
                        if (!keep) showCurrent() else card = cards[card!!.id] // same face, the synced copy of the card
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
        stopAudio()
        wordNoRecording = false
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
            if (card?.id != id) return@launch
            if (read != null) {
                contexts = read.first
                intervals = read.second
            }
            // auto: the front reads its sentence once the card opens (never over a Show that came first)
            if (autoplay() && !shown) playSentence(currentSentence())
        }
    }
}
