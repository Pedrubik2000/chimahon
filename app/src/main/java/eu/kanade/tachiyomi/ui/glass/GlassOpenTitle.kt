package eu.kanade.tachiyomi.ui.glass

import androidx.compose.runtime.Composable
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.data.anilist.AniListLibrary
import eu.kanade.tachiyomi.ui.glass.title.GlassTitleScreen
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Every glass screen opens a title through here: the glass title page, local series or not. */
fun openTitle(navigator: Navigator, title: GlassTitle) {
    // a title off the list (Discover) stays findable by media id
    val library = Injekt.get<AniListLibrary>()
    if (library.entries.value.none { it.mediaId == title.mediaId }) library.remember(title.entry)
    navigator.push(GlassTitleScreen(title.mediaId))
}

/** AniList list status → its label. */
@Composable
fun statusLabel(status: String): String = when (status) {
    "CURRENT" -> stringResource(MR.strings.glass_status_current)
    "PLANNING" -> stringResource(MR.strings.glass_status_planning)
    "COMPLETED" -> stringResource(MR.strings.glass_status_completed)
    "PAUSED" -> stringResource(MR.strings.glass_status_paused)
    "DROPPED" -> stringResource(MR.strings.glass_status_dropped)
    "REPEATING" -> stringResource(MR.strings.glass_status_repeating)
    else -> status
}
