package eu.kanade.tachiyomi.data.dictionary

import android.app.Application
import android.content.Context
import eu.kanade.presentation.more.settings.screen.importDictionaryFromStream
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.ProgressListener
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * One dictionary import at a time in the whole process: [importDictionaryFromStream] (setup, Settings, the downloads
 * below) and [DictionaryUpdateJob] take it, since they write the same folders and the profile's dictionaryOrder.
 */
val DictionaryImportMutex = Mutex()

/**
 * Downloads [entry] to the cache (with resume), imports it with the classic import (a kanji dictionary also gets
 * the kanji stroke font there), deletes the zip. [onProgress] gets (bytes so far, total); [onImporting] once the
 * download is done. Returns (message, ok); a failed download returns (null, false).
 */
suspend fun downloadAndImportDictionary(
    context: Context,
    entry: RecommendedDictionary,
    onProgress: (Long, Long) -> Unit,
    onImporting: () -> Unit = {},
): Pair<String?, Boolean> = withContext(Dispatchers.IO) {
    val zip = File(context.cacheDir, "dict_download_${entry.id}.zip")
    try {
        // a part left by a killed process may be from an older release: start over
        zip.delete()
        try {
            Injekt.get<NetworkHelper>().downloadFileWithResume(
                url = entry.url,
                outputFile = zip,
                progressListener = object : ProgressListener {
                    override fun update(bytesRead: Long, contentLength: Long, done: Boolean) {
                        // the file, not bytesRead: that restarts at 0 on each resumed attempt
                        val got = zip.length()
                        onProgress(got, maxOf(entry.bytes, got))
                    }
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return@withContext null to false
        }
        onImporting()
        val profile = Injekt.get<DictionaryPreferences>().profileStore.getActiveProfile()
        importDictionaryFromStream(context, zip.inputStream(), profile)
    } finally {
        zip.delete()
    }
}

/**
 * The usual dictionaries' downloads, app-scoped so they go on when the setup step or Settings is left (into the
 * Summary, Home, anywhere). Queued and run one after another. Not in the map = idle (not installed, not asked).
 */
object DictionaryDownloads {

    sealed interface State {
        data object Waiting : State
        data class Downloading(val bytes: Long, val total: Long) : State
        data object Importing : State
        data object Installed : State

        /** [message] null = the download failed (connection); else the import's message. */
        data class Failed(val message: String?) : State
    }

    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())
    val states: StateFlow<Map<String, State>> = _states.asStateFlow()

    /** The person's own installed dictionaries (folder names), from the last [refresh]. */
    private val _others = MutableStateFlow<List<String>>(emptyList())
    val others: StateFlow<List<String>> = _others.asStateFlow()

    // ponytail: in-process only; a process death mid-download leaves the row idle again (tap Download again).
    // A WorkManager job (like RealDebridImportJob) if that turns out to happen on the tablet.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<RecommendedDictionary>(Channel.UNLIMITED)
    private val app: Application get() = Injekt.get()

    init {
        scope.launch { for (entry in queue) run(entry) }
    }

    fun isActive(state: State?): Boolean =
        state is State.Waiting || state is State.Downloading || state is State.Importing

    /** Reads what is installed (on screen open and after each install); running downloads keep their state. */
    fun refresh() {
        scope.launch {
            val found = RecommendedDictionaries.scan(File(app.getExternalFilesDir(null), "dictionaries"))
            val ids = RecommendedDictionaries.installedIds(found)
            val order = Injekt.get<DictionaryPreferences>().profileStore.getActiveProfile().dictionaryOrder
            _others.value = RecommendedDictionaries.others(found, order)
            _states.update { m ->
                RecommendedDictionaries.all.mapNotNull { e ->
                    val s = m[e.id]
                    when {
                        isActive(s) -> s
                        e.id in ids -> State.Installed
                        s is State.Failed -> s
                        else -> null
                    }?.let { e.id to it }
                }.toMap()
            }
        }
    }

    /** Queues [selection]; ones already installed, queued or running are left alone. */
    fun start(selection: List<RecommendedDictionary>) {
        val m = _states.value
        val todo = selection.filter { !isActive(m[it.id]) && m[it.id] != State.Installed }
        if (todo.isEmpty()) return
        _states.update { s -> s + todo.associate { it.id to State.Waiting } }
        todo.forEach { queue.trySend(it) }
    }

    fun retry(id: String) {
        RecommendedDictionaries.byId(id)?.let { start(listOf(it)) }
    }

    private fun set(id: String, state: State) = _states.update { it + (id to state) }

    private suspend fun run(entry: RecommendedDictionary) {
        set(entry.id, State.Downloading(0, entry.bytes))
        var shown = 0L
        val (message, ok) = downloadAndImportDictionary(
            app,
            entry,
            onProgress = { got, total ->
                // a state change every 256 KB, not every 8 KB read
                if (got - shown >= 262_144) {
                    shown = got
                    set(entry.id, State.Downloading(got, total))
                }
            },
            onImporting = { set(entry.id, State.Importing) },
        )
        if (ok) {
            set(entry.id, State.Installed)
            turnOnAutoUpdate()
        } else {
            set(entry.id, State.Failed(message))
        }
        refresh()
    }

    /** Pedro 2026-11-22: a downloaded usual dictionary turns "Auto-update dictionaries" on (as its Settings row does). */
    private fun turnOnAutoUpdate() {
        val prefs = Injekt.get<DictionaryPreferences>()
        if (prefs.autoUpdateEnabled().get()) return
        prefs.autoUpdateEnabled().set(true)
        DictionaryUpdateJob.setupTask(app, true, prefs.autoUpdateInterval().get())
    }
}
