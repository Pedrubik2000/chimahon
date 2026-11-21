package eu.kanade.tachiyomi.ui.glass

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassPage
import eu.kanade.presentation.glass.GlassRail
import eu.kanade.presentation.glass.GlassRailItems
import eu.kanade.presentation.glass.Pill
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.setting.SettingsScreen

/** The new root on tablets (pref glassUi). Pages are placeholders until phases 3-5. */
object GlassHomeScreen : Screen() {
    private fun readResolve(): Any = GlassHomeScreen

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        var page by rememberSaveable { mutableStateOf("home") }
        BackHandler(enabled = page != "home") { page = "home" }

        GlassPage {
            GlassRail(
                active = page,
                onSelect = { if (it == "settings") navigator.push(SettingsScreen()) else page = it },
                onBearLongClick = { navigator.push(GlassKitScreen()) },
                modifier = Modifier.padding(start = 20.dp, top = 24.dp),
            )
            Column(
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
    }
}
