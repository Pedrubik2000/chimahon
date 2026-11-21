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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassPage
import eu.kanade.presentation.glass.GlassRail
import eu.kanade.presentation.glass.GlassRailItems
import eu.kanade.presentation.glass.Pill
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.download.LocalSeriesSync
import eu.kanade.tachiyomi.ui.glass.discover.GlassDiscoverModel
import eu.kanade.tachiyomi.ui.glass.discover.GlassDiscoverPage
import eu.kanade.tachiyomi.ui.glass.downloads.GlassDownloadsPage
import eu.kanade.tachiyomi.ui.glass.home.GlassHomePage
import eu.kanade.tachiyomi.ui.glass.library.GlassLibraryPage
import eu.kanade.tachiyomi.ui.glass.settings.GlassSettingsPage
import eu.kanade.tachiyomi.ui.home.HomeScreen

// The rail's page. Top level, not rememberSaveable (rotating the tablet sent the shell back to Home) and not a
// field of the screen object (Voyager serializes screens).
// ponytail: lost when Android kills the process; a preference if that ever matters
private val glassPage = mutableStateOf("home")

/** The new root on tablets (pref glassUi). Discover and Downloads are placeholders until phase 5. */
object GlassHomeScreen : Screen() {
    private fun readResolve(): Any = GlassHomeScreen

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model = rememberScreenModel { GlassTitlesModel() }
        var page by glassPage
        BackHandler(enabled = page != "home") { page = "home" }
        // volumes put in a series folder outside the app (or by an older import) get their chapter rows
        LaunchedEffect(Unit) { LocalSeriesSync.syncAll() }

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
            // last, so it draws over the pages (Home's banner scrolls under it) and keeps its touches
            GlassRail(
                active = page,
                onSelect = { page = it },
                onBearLongClick = { navigator.push(GlassKitScreen()) },
                modifier = Modifier.padding(start = 20.dp, top = 24.dp),
            )
        }
    }
}
