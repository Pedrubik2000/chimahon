package eu.kanade.tachiyomi.ui.glass.setup

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import chimahon.ocr.KumapieColours
import chimahon.srs.Cards
import chimahon.srs.HubScan
import chimahon.srs.PeerSync
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.hippo.unifile.UniFile
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.more.settings.screen.getFileNameFromUri
import eu.kanade.presentation.more.settings.screen.importDictionaryFromStream
import eu.kanade.presentation.more.settings.screen.openStreamWithFallback
import eu.kanade.tachiyomi.data.anilist.AniListLibrary
import eu.kanade.tachiyomi.data.dictionary.DictionaryDownloads
import eu.kanade.tachiyomi.data.dictionary.RecommendedDictionaries
import eu.kanade.tachiyomi.data.download.RealDebridImportJob
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences
import eu.kanade.tachiyomi.util.storage.DiskUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.core.common.storage.displayablePath
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.storage.service.StoragePreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * State of the first-run setup (canvas `Setup-2`); lives with the screen, so rotating keeps the step and the pasted
 * key. [fromSettings]: opened from Settings › About (pops at the end, a stored key counts without a new check).
 */
class GlassSetupModel(private val fromSettings: Boolean) : ScreenModel {

    private val app: Application = Injekt.get()
    private val trackerManager: TrackerManager = Injekt.get()
    private val aniList: AniListLibrary = Injekt.get()
    private val downloadPreferences: DownloadPreferences = Injekt.get()
    private val storageManager: StorageManager = Injekt.get()
    val storagePreferences: StoragePreferences = Injekt.get()

    enum class Rd { NONE, CHECKING, PREMIUM, FREE, REJECTED, OFFLINE }

    // AniList
    val signedIn: StateFlow<Boolean> = trackerManager.aniList.isLoggedInFlow
        .stateIn(screenModelScope, SharingStarted.Eagerly, trackerManager.aniList.isLoggedIn)

    /** The viewer's name; null until known ("Signed in"). */
    var name by mutableStateOf<String?>(null)
        private set

    // first access reads the cache file: off the main thread
    val entries: StateFlow<List<AniListLibrary.Entry>> = flow { emitAll(aniList.entries) }
        .flowOn(Dispatchers.IO)
        .stateIn(screenModelScope, SharingStarted.Eagerly, emptyList())

    // Real-Debrid
    private val storedKey = downloadPreferences.realDebridToken().get().trim()

    /** The key in the field; never logged, shown masked. */
    var key by mutableStateOf(storedKey)
        private set
    var rd by mutableStateOf(Rd.NONE)
        private set
    var daysLeft by mutableIntStateOf(0)
        private set

    // Storage
    var storageOk by mutableStateOf(false)
        private set
    var storagePath by mutableStateOf<String?>(null)
        private set
    var freeBytes by mutableStateOf<Long?>(null)
        private set
    var seriesCount by mutableStateOf<Int?>(null)
        private set

    // Real-Debrid "Not now" (kept in memory only): volumes also come by hand and as torrents, so a key can wait
    var realDebridSkipped by mutableStateOf(false)
        private set

    // Notifications ("Not now" is kept in memory only)
    var notificationsAllowed by mutableStateOf(false)
    var notificationsSkipped by mutableStateOf(false)
        private set

    // Dictionary
    var dictionary by mutableStateOf<String?>(null)
        private set
    var dictionarySkipped by mutableStateOf(false)
        private set
    var importing by mutableStateOf(false)
        private set
    var importError by mutableStateOf<String?>(null)
        private set

    /** The usual dictionaries switched on (canvas Dict-1: on by default); kept across rotation with the model. */
    var picked by mutableStateOf(RecommendedDictionaries.all.filter { it.onByDefault }.map { it.id }.toSet())

    /** [DictionaryDownloads.states], app-scoped: the downloads go on when the setup is left. */
    var downloads by mutableStateOf<Map<String, DictionaryDownloads.State>>(emptyMap())
        private set

    val dictionaryDownloading: Boolean get() = downloads.values.any(DictionaryDownloads::isActive)

    /** "Jitendex, JMnedict and KANJIDIC" (the installed usual ones), else the newest folder's name. */
    val dictionaryLine: String
        get() = RecommendedDictionaries.names(
            RecommendedDictionaries.all.filter { downloads[it.id] == DictionaryDownloads.State.Installed }.map { it.name },
        ).ifEmpty { dictionary.orEmpty() }

    /** Step 0-6, or [GlassSetupLogic.SUMMARY]. From Settings: step 1 (AniList); a first run: the first missing one. */
    var step by mutableIntStateOf(
        if (fromSettings) {
            0
        } else {
            GlassSetupLogic.firstStep(
                GlassSetupLogic.Facts(
                    aniList = trackerManager.aniList.isLoggedIn,
                    // a first run: a stored key counts only after a check here
                    realDebrid = false,
                    // ponytail: one SAF stat on the main thread at open, as the MainActivity gate does
                    storage = StorageManager.directoryAccessible(app, storagePreferences.baseStorageDirectory().get()),
                ),
            )
        },
    )

    init {
        readNotifications()
        screenModelScope.launch {
            signedIn.collect { on ->
                if (!on) {
                    name = null
                    return@collect
                }
                name = runCatching { trackerManager.aniList.viewerName() }.getOrNull()?.ifBlank { null }
                // the covers and counts; a list already cached is not fetched again here
                if (entries.value.isEmpty()) aniList.refresh()
            }
        }
        screenModelScope.launch {
            merge(storagePreferences.baseStorageDirectory().changes().map { }, storageManager.changes).collect { measureStorage() }
        }
        screenModelScope.launch { dictionary = withContext(Dispatchers.IO) { installedDictionary() } }
        DictionaryDownloads.refresh()
        screenModelScope.launch {
            var installed = emptySet<String>()
            DictionaryDownloads.states.collect { s ->
                downloads = s
                // one folder scan per newly installed dictionary, not per progress step
                val now = s.filterValues { it == DictionaryDownloads.State.Installed }.keys
                if (now != installed) {
                    installed = now
                    dictionary = withContext(Dispatchers.IO) { installedDictionary() }
                }
            }
        }
    }

    // Word colouring. The switch is the real preference (KumapieColours, the one Settings > Anki > kumapie shows); the rest is
    // kept in memory like the other steps' skips.
    var colouringOn by mutableStateOf(KumapieColours.enabled(app))
        private set
    var colouringSkipped by mutableStateOf(false)
        private set
    var colouringVisited by mutableStateOf(false)

    fun setColouring(on: Boolean) {
        colouringOn = on
        KumapieColours.setEnabled(app, on)
    }

    fun skipColouring() {
        colouringSkipped = true
        step = 6
    }

    /** Continue on the step: it was looked at, so it is not "skipped" any more. */
    fun colouringContinue() {
        colouringSkipped = false
        step = 6
    }

    // Known words (step 7): the sources themselves keep their own state (Jiten import, kumapie pairing, kuma3 layer).
    var knownSkipped by mutableStateOf(false)
        private set
    var knownVisited by mutableStateOf(false)

    fun skipKnown() {
        knownSkipped = true
        step = GlassSetupLogic.SUMMARY
    }

    fun knownContinue() {
        knownSkipped = false
        step = GlassSetupLogic.SUMMARY
    }

    // kumapie pairing (the same PeerSync as Settings > Anki > kumapie). Every network call runs on IO, never on the main thread.
    private val peer: PeerSync get() = Cards.peer(app)

    var paired by mutableStateOf(peer.paired())
        private set
    var pairedName by mutableStateOf<String?>(null)
        private set
    var hub by mutableStateOf(peer.hub())
        private set

    /** The 4-digit code kumapie shows while it waits for Allow; null before the first tap. */
    var pairCode by mutableStateOf<String?>(null)
        private set
    var pairNote by mutableStateOf<String?>(null)
        private set
    var pairBusy by mutableStateOf(false)
        private set
    var scanning by mutableStateOf(false)
        private set

    /** What the last scan found, "ip:port" each; null before a scan. */
    var found by mutableStateOf<List<String>?>(null)
        private set

    fun useHub(address: String) {
        if (address.trim() == hub) return
        peer.setHub(address)
        hub = peer.hub()
        pairCode = null
        pairNote = null
    }

    /** Scans this network's /24s for kumapie ([HubScan]); one hit becomes the address. */
    fun scan() {
        if (scanning) return
        scanning = true
        found = null
        screenModelScope.launch {
            val hits = withContext(Dispatchers.IO) { HubScan.find() }
            found = hits
            if (hits.size == 1) useHub(hits[0])
            scanning = false
        }
    }

    /**
     * Tap = pair; tapped again after Allow on kumapie it answers Paired, and the first sync brings the known words
     * (page colours update). PeerSync.pair asks kumapie, so it runs on IO.
     */
    fun pair() {
        if (pairBusy) return
        pairBusy = true
        pairNote = null
        screenModelScope.launch {
            val state = withContext(Dispatchers.IO) {
                val s = peer.pair()
                if (s is PeerSync.PairState.Paired) Cards.sync(app)
                s
            }
            when (state) {
                is PeerSync.PairState.Paired -> { paired = true; pairedName = state.name; pairCode = null }
                is PeerSync.PairState.Waiting -> pairCode = state.code
                is PeerSync.PairState.Failed -> pairNote = state.why
            }
            pairBusy = false
        }
    }

    fun downloadPicked() {
        DictionaryDownloads.start(RecommendedDictionaries.all.filter { it.id in picked })
        dictionarySkipped = false
    }

    // Real-Debrid: done only after a check answered Premium, or (from Settings) the stored key unchanged
    val realDebridDone: Boolean
        get() = rd == Rd.PREMIUM ||
            (fromSettings && storedKey.isNotEmpty() && key.trim() == storedKey && rd !in setOf(Rd.REJECTED, Rd.FREE))

    fun done(i: Int): Boolean = when (i) {
        0 -> signedIn.value
        1 -> realDebridDone || realDebridSkipped
        2 -> storageOk
        3 -> notificationsAllowed || notificationsSkipped
        // canvas Dict-1: Continue once one is installed, downloading or skipped
        4 -> dictionary != null || dictionarySkipped || dictionaryDownloading
        // optional and always allowed: it has been looked at, or skipped
        5 -> colouringVisited || colouringSkipped
        6 -> knownVisited || knownSkipped
        else -> false
    }

    /** "Not now" on 1, 3, 4, 5 or 6 and still not set: done for Continue, but not really done (dimmer bar). */
    fun skipped(i: Int): Boolean = when (i) {
        1 -> realDebridSkipped && !realDebridDone
        3 -> notificationsSkipped && !notificationsAllowed
        4 -> dictionarySkipped && dictionary == null
        5 -> colouringSkipped
        6 -> knownSkipped
        else -> false
    }

    val facts: GlassSetupLogic.Facts get() = GlassSetupLogic.Facts(done(0), done(1), done(2))

    fun onKey(text: String) {
        key = text.trim()
        rd = Rd.NONE
    }

    /** Checks the key in the field; a Premium or free account is saved, a refused key or no answer is not. */
    fun checkKey() {
        val k = key.trim()
        if (k.isEmpty() || rd == Rd.CHECKING) return
        rd = Rd.CHECKING
        screenModelScope.launch {
            val result = runCatching { RealDebridImportJob.account(k) }
            val account = result.getOrNull()
            rd = when {
                result.isFailure -> Rd.OFFLINE
                account == null -> Rd.NONE
                account.tokenRejected -> Rd.REJECTED
                else -> {
                    // Pedro 2026-11-22: a free account's key is saved too, but the step stays open
                    downloadPreferences.realDebridToken().set(k)
                    daysLeft = account.daysLeft
                    if (account.premium) Rd.PREMIUM else Rd.FREE
                }
            }
        }
    }

    /** At start and on every resume (the screen), so the bar and Summary know it before the step is visited. */
    fun readNotifications() {
        // from PermissionStep (onboarding): below API 33 there is nothing to ask
        notificationsAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    fun skipRealDebrid() {
        realDebridSkipped = true
        step = 2
    }

    fun skipNotifications() {
        notificationsSkipped = true
        step = 4
    }

    fun skipDictionary() {
        dictionarySkipped = true
        step = 5 // Word colouring comes after the Dictionary
    }

    fun importDictionary(uri: Uri) {
        if (importing) return
        importing = true
        importError = null
        screenModelScope.launch {
            val (message, ok) = withContext(Dispatchers.IO) {
                runCatching {
                    val stream = openStreamWithFallback(app, uri) ?: return@runCatching "Could not open file stream" to false
                    val profile = Injekt.get<DictionaryPreferences>().profileStore.getActiveProfile()
                    importDictionaryFromStream(app, stream, profile)
                }.getOrElse { (it.message ?: getFileNameFromUri(app, uri)) to false }
            }
            if (ok) {
                dictionary = withContext(Dispatchers.IO) { installedDictionary() } ?: getFileNameFromUri(app, uri)
                // the own-dictionary row (or a hand-imported Jitendex shown as installed)
                DictionaryDownloads.refresh()
            } else {
                importError = message
            }
            importing = false
        }
    }

    /** Sets the setup done (and the classic onboarding, so the classic look does not ask again). */
    fun finish() {
        Injekt.get<UiPreferences>().glassSetupDone().set(true)
        Injekt.get<BasePreferences>().shownOnboardingFlow().set(true)
    }

    private suspend fun measureStorage() = withContext(Dispatchers.IO) {
        val dir = storagePreferences.baseStorageDirectory().get()
        val ok = StorageManager.directoryAccessible(app, dir)
        storagePath = if (ok) UniFile.fromUri(app, dir.toUri())?.displayablePath?.let(GlassSetupLogic::shortPath) else null
        storageOk = ok
        val local = if (ok) storageManager.getLocalSourceDirectory() else null
        // the same filter as LocalSeriesSync.syncAll
        seriesCount = local?.listFiles()?.count { it.isDirectory && it.name?.startsWith(".") == false }
        freeBytes = local?.filePath?.let { DiskUtil.getAvailableStorageSpace(File(it)) }?.takeIf { it >= 0 }
    }

    /** The newest dictionary folder under dictionaries/<type>/, null when none is installed. */
    private fun installedDictionary(): String? =
        File(app.getExternalFilesDir(null), "dictionaries").listFiles().orEmpty()
            .filter { it.isDirectory }
            .flatMap { type -> type.listFiles().orEmpty().filter { it.isDirectory } }
            .maxByOrNull { it.lastModified() }
            ?.name
}
