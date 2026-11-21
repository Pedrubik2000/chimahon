package eu.kanade.tachiyomi.ui.glass

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassPage
import eu.kanade.presentation.glass.Pill
import eu.kanade.presentation.glass.glassPanel
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.anilist.AniListLibrary
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Every glass screen opens a title through here. Phase 4 swaps both branches for the glass title page. */
fun openTitle(navigator: Navigator, title: GlassTitle) {
    val mangaId = title.localMangaId
    navigator.push(if (mangaId != null) MangaScreen(mangaId) else GlassTitleStubScreen(title.mediaId))
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

/** Until the title page exists: what the cache knows about a title with nothing on the tablet. */
class GlassTitleStubScreen(private val mediaId: Long) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val entry = Injekt.get<AniListLibrary>().get(mediaId)
        GlassPage {
            Pill(
                stringResource(MR.strings.glass_back),
                onClick = { navigator.pop() },
                icon = "chevLeft",
                small = true,
                modifier = Modifier.padding(start = 32.dp, top = 24.dp),
            )
            val shape = RoundedCornerShape(Glass.r10)
            if (entry != null) Row(
                Modifier
                    .align(Alignment.Center)
                    .widthIn(max = 640.dp)
                    .glassPanel()
                    .padding(24.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                AsyncImage(
                    model = entry.coverLarge ?: entry.cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(160.dp, 240.dp).clip(shape).border(1.dp, Glass.line, shape),
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        entry.native ?: entry.romaji ?: entry.english ?: "",
                        fontSize = Glass.heading,
                        fontWeight = FontWeight.SemiBold,
                    )
                    entry.romaji?.takeIf { entry.native != null }?.let {
                        Text(it, color = Glass.secondary, fontSize = Glass.body)
                    }
                    Text(statusLabel(entry.status), fontSize = Glass.label)
                    entry.volumes?.let {
                        Text(stringResource(MR.strings.glass_n_volumes, it), color = Glass.secondary, fontSize = Glass.body)
                    }
                    Text(
                        stringResource(MR.strings.glass_title_later),
                        color = Glass.secondary,
                        fontSize = Glass.body,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
        }
    }
}
