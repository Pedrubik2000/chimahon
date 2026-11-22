package eu.kanade.tachiyomi.ui.glass.problems

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.anilist.AniListLibrary
import eu.kanade.tachiyomi.data.download.RealDebridImportJob
import eu.kanade.tachiyomi.data.download.RealDebridQueue
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.ui.glass.GlassTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import tachiyomi.domain.download.service.DownloadPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** The Needs attention list; lives with [eu.kanade.tachiyomi.ui.glass.GlassHomeScreen] (one per app run). */
class GlassProblemsModel(
    aniList: AniListLibrary = Injekt.get(),
    queue: RealDebridQueue = Injekt.get(),
    prefs: DownloadPreferences = Injekt.get(),
    trackerManager: TrackerManager = Injekt.get(),
) : ScreenModel {

    // ponytail: memory only, so a hidden problem comes back after a restart (the design asks for that)
    private val hidden = MutableStateFlow(emptySet<String>())

    // one /user call at shell start and one per new token; a network error says nothing (null)
    private val account = prefs.realDebridToken().changes()
        .distinctUntilChanged()
        .onEach { hidden.update { it - GlassProblems.RD } } // a new key shows its own answer
        .map { token -> runCatching { RealDebridImportJob.account(token) }.getOrNull() }
        .onStart { emit(null) }

    val problems: StateFlow<List<Problem>> = combine(
        combine(account, queue.rows, hidden) { a, r, h -> Triple(a, r, h) },
        combine(
            flow { emitAll(aniList.entries) }, // first access reads the cache file: keep it off the main thread
            trackerManager.aniList.isLoggedInFlow,
            aniList.error,
        ) { e, s, err -> Triple(e, s, err) },
    ) { (acc, rows, hid), (entries, signedIn, error) ->
        GlassProblems.build(acc, rows, entries.associate { it.mediaId to GlassTitle(it).name }, signedIn, error, hid)
    }
        .flowOn(Dispatchers.IO)
        .stateIn(screenModelScope, SharingStarted.Eagerly, emptyList())

    fun hide(problem: Problem) = hidden.update { it + problem.id }
}
