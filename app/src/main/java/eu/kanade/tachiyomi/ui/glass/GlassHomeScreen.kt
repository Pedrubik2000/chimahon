package eu.kanade.tachiyomi.ui.glass

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassPage
import eu.kanade.presentation.glass.GlassRail
import eu.kanade.presentation.glass.GlassRailButton
import eu.kanade.presentation.glass.GlassRailItems
import eu.kanade.presentation.glass.Pill
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.anilist.AniListLibrary
import eu.kanade.tachiyomi.data.download.LocalSeriesSync
import eu.kanade.tachiyomi.data.download.RealDebridQueue
import eu.kanade.tachiyomi.data.track.anilist.AnilistApi
import eu.kanade.tachiyomi.ui.glass.discover.GlassDiscoverModel
import eu.kanade.tachiyomi.ui.glass.discover.GlassDiscoverPage
import eu.kanade.tachiyomi.ui.glass.downloads.GlassDownloadsPage
import eu.kanade.tachiyomi.ui.glass.downloads.cachedCoverage
import eu.kanade.tachiyomi.ui.glass.home.GlassHomePage
import eu.kanade.tachiyomi.ui.glass.library.GlassLibraryPage
import eu.kanade.tachiyomi.ui.glass.problems.GlassProblems
import eu.kanade.tachiyomi.ui.glass.problems.GlassProblemsModel
import eu.kanade.tachiyomi.ui.glass.problems.GlassProblemsPanel
import eu.kanade.tachiyomi.ui.glass.problems.ProblemAction
import eu.kanade.tachiyomi.ui.glass.settings.GlassSettingsPage
import eu.kanade.tachiyomi.ui.glass.settings.glassSettingsSection
import eu.kanade.tachiyomi.ui.glass.title.GlassTitleScreen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.util.system.openInBrowser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

// The rail's page. Top level, not rememberSaveable (rotating the tablet sent the shell back to Home) and not a
// field of the screen object (Voyager serializes screens).
// ponytail: lost when Android kills the process; a preference if that ever matters
private val glassPage = mutableStateOf("home")

/** The new root on tablets (pref glassUi). Discover and Downloads are placeholders until phase 5. */
object GlassHomeScreen : Screen() {
    private fun readResolve(): Any = GlassHomeScreen

    /** Switches the shell to [page] ("home", "library", "discover", "downloads"), e.g. from a title page's rail. */
    fun show(page: String) {
        glassPage.value = page
    }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model = rememberScreenModel { GlassTitlesModel() }
        var page by glassPage
        BackHandler(enabled = page != "home") { page = "home" }
        // volumes put in a series folder outside the app (or by an older import) get their chapter rows; series
        // folders keep being linked to their AniList titles while the shell is up (the list can arrive later)
        LaunchedEffect(Unit) { LocalSeriesSync.syncAll() }
        val problemsModel = rememberScreenModel { GlassProblemsModel() }
        val problems by problemsModel.problems.collectAsStateWithLifecycle()
        var problemsOpen by problemsModel.open // the model outlives a rotation
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        GlassPage {
            when (page) {
                "home" -> GlassHomePage(model, navigator)
                "library" -> GlassLibraryPage(model, navigator)
                // created on the first Discover visit, then kept with the shell (no AniList call before that)
                "discover" -> GlassDiscoverPage(rememberScreenModel { GlassDiscoverModel() }, navigator)
                "downloads" -> GlassDownloadsPage(model, navigator)
                "settings" -> GlassSettingsPage(navigator)
                else -> Column(
                    Modifier.padding(start = 108.dp, top = 24.dp, end = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        GlassRailItems.first { it.first == page }.second,
                        color = Color.White,
                        fontSize = Glass.title,
                        fontWeight = FontWeight.SemiBold,
                    )
                    // ponytail: way back to Novels/Anime/Dictionary until the new screens exist
                    Pill("Classic view", onClick = { navigator.push(HomeScreen) }, small = true)
                }
            }
            if (problemsOpen) {
                // composed after the pages' own BackHandlers, so it wins while the panel is open
                BackHandler { problemsOpen = false }
                GlassProblemsPanel(problems, onClose = { problemsOpen = false }) { p, action ->
                    when (action) {
                        ProblemAction.CHANGE_KEY -> {
                            glassSettingsSection.value = "sources"
                            page = "settings"
                            problemsOpen = false
                        }
                        ProblemAction.HIDE -> problemsModel.hide(p)
                        ProblemAction.OPEN_TITLE -> p.mediaId?.let {
                            problemsOpen = false // else it is still open when the title page is left
                            navigator.push(GlassTitleScreen(it))
                        }
                        ProblemAction.TRY_AGAIN -> p.mediaId?.let { id ->
                            // the Downloads card's Try again: cached coverage, else the title page fetches it
                            scope.launch {
                                val entry = Injekt.get<AniListLibrary>().get(id)
                                val queued = if (entry == null) {
                                    emptySet()
                                } else {
                                    withContext(Dispatchers.IO) {
                                        val cached = cachedCoverage(context, id)
                                        if (cached.isEmpty()) emptySet() else Injekt.get<RealDebridQueue>().enqueueAll(entry, p.volumes, cached)
                                    }
                                }
                                if (queued.isEmpty()) navigator.push(GlassTitleScreen(id))
                            }
                        }
                        ProblemAction.RETRY_ANILIST -> model.refresh()
                        ProblemAction.OPEN_LIBRARY -> {
                            page = "library"
                            problemsOpen = false
                        }
                        ProblemAction.SIGN_IN -> context.openInBrowser(AnilistApi.authUrl(), forceDefaultBrowser = true)
                    }
                }
            }
            // last, so it draws over the pages (Home's banner scrolls under it) and keeps its touches
            GlassRail(
                active = page,
                onSelect = {
                    page = it
                    problemsOpen = false
                },
                onBearLongClick = { navigator.push(GlassKitScreen()) },
                modifier = Modifier.padding(start = 20.dp, top = 24.dp),
                dots = GlassProblems.areas(problems),
            )
            // canvas Err-3: only while something needs attention (or the panel is still open on an empty list)
            if (problems.isNotEmpty() || problemsOpen) {
                GlassRailButton(
                    icon = "alert",
                    label = stringResource(MR.strings.glass_problems_alert, problems.size),
                    on = problemsOpen,
                    onClick = { problemsOpen = !problemsOpen },
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 24.dp),
                    badge = problems.size.takeIf { it > 0 }?.toString(),
                )
            }
        }
    }
}
