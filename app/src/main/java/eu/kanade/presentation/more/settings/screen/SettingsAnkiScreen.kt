package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import chimahon.chardict.CharacterDictionary
import chimahon.srs.Cards
import eu.kanade.tachiyomi.data.dictionary.CharacterDictionaryJob
import chimahon.srs.PeerSync
import eu.kanade.presentation.more.settings.Preference
import chimahon.ocr.KumapieColours
import eu.kanade.presentation.more.settings.widget.EditTextPreferenceWidget
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.glass.setup.AnkiKnownRow
import eu.kanade.tachiyomi.ui.glass.setup.JitenImportPanel
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Anki mining + dictionary profiles (per-profile fields), then the sync of kumahon's own cards with kumapie.
 * Preference widgets are reused from [SettingsDictionaryScreen]; the sync texts are English literals, like the Anki texts there.
 */
object SettingsAnkiScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = KMR.strings.pref_category_anki

    @Composable
    override fun getPreferences(): List<Preference> =
        SettingsDictionaryScreen.ankiPreferences() + getKumapieSyncGroup()

    /** Tap = pair (until kumapie has allowed this device), then sync; the subtitle is [PeerSync.status]. */
    @Composable
    private fun getKumapieSyncGroup(): Preference.PreferenceGroup {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var status by remember { mutableStateOf(Cards.peer(context).status()) }
        var hub by remember { mutableStateOf(Cards.peer(context).hub()) }
        var characters by remember { mutableStateOf("") }
        var faces by remember { mutableStateOf(CharacterDictionary.facesEnabled(context)) }
        var colouring by remember { mutableStateOf(KumapieColours.enabled(context)) }
        var colouringServer by remember { mutableStateOf(KumapieColours.server(context)) }
        // Background syncs (Review, retries) change the line while this page is open: read it again every few seconds.
        LaunchedEffect(Unit) {
            while (true) {
                characters = withContext(Dispatchers.IO) { CharacterDictionary.verify(context); CharacterDictionary.status(context) }
                delay(5_000)
                if (status != "Working...") status = Cards.peer(context).status()
            }
        }
        return Preference.PreferenceGroup(
            title = "kumapie",
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.CustomPreference(
                    title = "Show character faces",
                    content = {
                        SwitchPreferenceWidget(
                            title = "Show character faces",
                            subtitle = "A character's picture before their name on the page, from kumahon's own character dictionary",
                            checked = faces,
                            onCheckedChanged = { faces = it; CharacterDictionary.setFacesEnabled(context, it); KumapieColours.clear() },
                        )
                    },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = "Character dictionary: rebuild now",
                    subtitle = characters,
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            CharacterDictionaryJob.rebuildNow(context)
                            characters = CharacterDictionary.status(context)
                        }
                    },
                ),
                Preference.PreferenceItem.CustomPreference(
                    title = "Colour words",
                    content = {
                        SwitchPreferenceWidget(
                            title = "Colour words",
                            subtitle = "Red new, green due, blue mined, known plain: kumahon's own colours (downloads the Japanese dictionary, 80 MB, once)",
                            checked = colouring,
                            onCheckedChanged = { colouring = it; KumapieColours.setEnabled(context, it) },
                        )
                    },
                ),
                Preference.PreferenceItem.CustomPreference(
                    title = "Known words",
                    content = {
                        Column {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(stringResource(MR.strings.jiten_known_words), style = MaterialTheme.typography.titleMedium)
                            JitenImportPanel(withTally = true)
                        }
                        AnkiKnownRow()
                        }
                    },
                ),
                Preference.PreferenceItem.CustomPreference(
                    title = "Jiten data server",
                    content = {
                        EditTextPreferenceWidget(
                            title = "Jiten data server (optional)",
                            subtitle = colouringServer.ifBlank { "none: the files come from the public kumapie release" },
                            dialogSubtitle = "Leave empty: Jiten's two splitter files (45 MB) come from the public kumapie release. Or the dojo server's address (https://...:8445), tried first",
                            icon = null,
                            value = colouringServer,
                            formatSubtitle = false,
                            onConfirm = {
                                KumapieColours.setServer(context, it)
                                colouringServer = KumapieColours.server(context)
                                true
                            },
                        )
                    },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = "Sync reviews with kumapie",
                    subtitle = status,
                    onClick = {
                        status = "Working..."
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                if (!Cards.peer(context).paired()) Cards.peer(context).pair()
                                Cards.sync(context)
                            }
                            status = Cards.peer(context).status()
                        }
                    },
                ),
                Preference.PreferenceItem.CustomPreference(
                    title = "Hub address",
                    content = {
                        EditTextPreferenceWidget(
                            title = "Hub address",
                            subtitle = hub,
                            dialogSubtitle = "kumapie's address and port (on this tablet: ${PeerSync.DEFAULT_HUB})",
                            icon = null,
                            value = hub,
                            formatSubtitle = false,
                            onConfirm = {
                                Cards.peer(context).setHub(it)
                                hub = Cards.peer(context).hub()
                                true
                            },
                        )
                    },
                ),
            ),
        )
    }
}
