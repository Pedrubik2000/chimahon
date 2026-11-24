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
import chimahon.srs.Cards
import chimahon.srs.PeerSync
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.widget.EditTextPreferenceWidget
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.i18n.kmk.KMR

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
        // Background syncs (Review, retries) change the line while this page is open: read it again every few seconds.
        LaunchedEffect(Unit) {
            while (true) {
                delay(5_000)
                if (status != "Working...") status = Cards.peer(context).status()
            }
        }
        return Preference.PreferenceGroup(
            title = "kumapie",
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.TextPreference(
                    title = "Sync reviews with kumapie",
                    subtitle = status,
                    onClick = {
                        status = "Working..."
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                if (!Cards.peer(context).paired()) Cards.peer(context).pair()
                                Cards.peer(context).sync()
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
