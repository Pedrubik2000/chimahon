package eu.kanade.tachiyomi.ui.glass.dictionary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassIcon
import eu.kanade.presentation.glass.GlassPage
import eu.kanade.presentation.glass.Meter
import eu.kanade.presentation.glass.Pill
import eu.kanade.presentation.glass.glassPanel
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.dictionary.DictionaryDownloads
import eu.kanade.tachiyomi.data.dictionary.DictionaryDownloads.State
import eu.kanade.tachiyomi.data.dictionary.RecommendedDictionaries
import eu.kanade.tachiyomi.data.dictionary.RecommendedDictionary
import eu.kanade.tachiyomi.ui.glass.setup.SetupButton
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

// The usual dictionaries (build plan phase 12, canvas Dict-1): the rows shared by the setup's Dictionary step and
// Settings › Japanese, and the licences screen.

/**
 * One panel: the person's own dictionaries (installed rows), then one row per [RecommendedDictionaries.all]. A row
 * with no state shows [idle] (the setup's switch, Settings' Download button). Scrolls inside when it does not fit.
 */
@Composable
internal fun DictionaryPanel(
    states: Map<String, State>,
    others: List<String>,
    modifier: Modifier = Modifier,
    idle: @Composable (RecommendedDictionary) -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .glassPanel(Glass.r16)
            .heightIn(max = 336.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        others.forEachIndexed { i, name ->
            if (i > 0) Line()
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(name, fontSize = Glass.label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(MR.strings.glass_dict_own), color = Glass.secondary, fontSize = Glass.body, maxLines = 1)
                }
                Installed()
            }
        }
        RecommendedDictionaries.all.forEachIndexed { i, entry ->
            if (i > 0 || others.isNotEmpty()) Line()
            DictionaryRow(entry, states[entry.id], idle)
        }
    }
}

@Composable
private fun DictionaryRow(entry: RecommendedDictionary, state: State?, idle: @Composable (RecommendedDictionary) -> Unit) {
    val mb = RecommendedDictionaries.mb(entry.bytes)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(entry.name, fontSize = Glass.label, fontWeight = FontWeight.SemiBold, maxLines = 1)
                if (entry.main) {
                    Text(
                        stringResource(MR.strings.glass_dict_main),
                        color = Glass.secondary,
                        fontSize = Glass.caption,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 2.sp,
                    )
                }
            }
            if (state is State.Failed) {
                Row(
                    Modifier.padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    GlassIcon("alert", size = 18.dp)
                    Text(
                        state.message?.let { stringResource(MR.strings.glass_dict_failed_install, entry.name, it) }
                            ?: stringResource(MR.strings.glass_dict_failed_download, entry.name),
                        color = Color.White,
                        fontSize = Glass.body,
                    )
                }
            } else {
                Text(
                    stringResource(MR.strings.glass_dict_what_size, entry.what, mb),
                    color = Glass.secondary,
                    fontSize = Glass.body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when (state) {
            null -> idle(entry)
            State.Waiting -> Status(stringResource(MR.strings.glass_dict_waiting))
            State.Importing -> Status(stringResource(MR.strings.glass_dict_installing))
            State.Installed -> Installed()
            is State.Downloading -> Column(Modifier.width(132.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Meter(
                    if (state.total > 0) state.bytes.toFloat() / state.total else 0f,
                    Modifier.width(132.dp),
                    color = Glass.ice,
                )
                Text(
                    stringResource(MR.strings.glass_dict_progress, RecommendedDictionaries.mb(state.bytes), RecommendedDictionaries.mb(state.total)),
                    color = Glass.secondary,
                    fontSize = Glass.body,
                    maxLines = 1,
                )
            }
            is State.Failed -> SetupButton(
                stringResource(MR.strings.glass_dict_try_again),
                { DictionaryDownloads.retry(entry.id) },
                small = true,
            )
        }
    }
}

/** A quiet right-aligned word in the 132dp status column ("Waiting", "Not chosen"). */
@Composable
internal fun Status(text: String) {
    Text(text, Modifier.width(132.dp), color = Glass.secondary, fontSize = Glass.body, textAlign = TextAlign.End, maxLines = 1)
}

@Composable
private fun Installed() {
    Row(
        Modifier.widthIn(min = 132.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
    ) {
        GlassIcon("check", size = 18.dp, tint = Glass.ice)
        Text(stringResource(MR.strings.glass_dict_installed), fontSize = Glass.body, maxLines = 1)
    }
}

@Composable
private fun Line() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Glass.line))
}

/** Settings › Japanese › Usual dictionaries: the same rows, a Download button per row (queued one after another). */
@Composable
internal fun UsualDictionariesSettings() {
    LaunchedEffect(Unit) { DictionaryDownloads.refresh() }
    val states by DictionaryDownloads.states.collectAsStateWithLifecycle()
    val others by DictionaryDownloads.others.collectAsStateWithLifecycle()
    DictionaryPanel(states, others, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { entry ->
        SetupButton(
            stringResource(MR.strings.glass_dict_download),
            { DictionaryDownloads.start(listOf(entry)) },
            primary = false,
            leading = "download",
            small = true,
        )
    }
}

/**
 * Each usual dictionary's name, licence and full acknowledgement. The EDRDG licence wants the acknowledgement on its
 * own screen reached from a menu (Settings › Japanese › Dictionary licences).
 */
class GlassDictionaryLicencesScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        GlassPage {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(start = 80.dp, end = 80.dp, top = 32.dp, bottom = 48.dp)
                    .widthIn(max = 760.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Pill(stringResource(MR.strings.glass_back), onClick = { navigator.pop() }, icon = "chevLeft", small = true)
                Text(stringResource(MR.strings.glass_dict_licences), fontSize = Glass.title, fontWeight = FontWeight.SemiBold)
                RecommendedDictionaries.all.forEach { d ->
                    Column(
                        Modifier.fillMaxWidth().glassPanel(Glass.r16).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(d.name, fontSize = Glass.label, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(MR.strings.glass_dict_licence_line, d.licence), color = Glass.secondary, fontSize = Glass.body)
                        Text(d.attribution, fontSize = Glass.body, lineHeight = 20.sp)
                    }
                }
            }
        }
    }
}
