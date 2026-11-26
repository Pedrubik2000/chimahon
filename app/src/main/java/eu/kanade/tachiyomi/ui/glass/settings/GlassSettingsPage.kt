package eu.kanade.tachiyomi.ui.glass.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.Navigator
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassRoundButton
import eu.kanade.presentation.glass.GlassIcon
import eu.kanade.presentation.glass.GlassToggle
import eu.kanade.presentation.glass.glassPanel
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.PreferenceScreen
import eu.kanade.presentation.more.settings.screen.SettingsAnkiScreen
import eu.kanade.presentation.more.settings.screen.SettingsBrowseScreen
import eu.kanade.presentation.more.settings.screen.SettingsDataScreen
import eu.kanade.presentation.more.settings.screen.SettingsDictionaryPopupScreen
import eu.kanade.presentation.more.settings.screen.SettingsDictionaryScreen
import eu.kanade.presentation.more.settings.screen.SettingsDownloadScreen
import eu.kanade.presentation.more.settings.screen.SettingsLibraryScreen
import eu.kanade.presentation.more.settings.screen.SettingsReaderScreen
import eu.kanade.presentation.more.settings.screen.SettingsSecurityScreen
import eu.kanade.presentation.more.settings.screen.SettingsTrackingScreen
import eu.kanade.presentation.more.settings.screen.about.AboutScreen
import eu.kanade.presentation.reader.glass.GlassReaderTheme
import eu.kanade.tachiyomi.data.coverage.SourceCoverage
import eu.kanade.tachiyomi.data.dictionary.RecommendedDictionaries
import eu.kanade.tachiyomi.ui.glass.dictionary.GlassDictionaryLicencesScreen
import eu.kanade.tachiyomi.ui.glass.dictionary.UsualDictionariesSettings
import eu.kanade.tachiyomi.ui.glass.downloads.GlassDownloads
import eu.kanade.tachiyomi.ui.glass.setup.GlassSetupScreen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.setting.SettingsScreen
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableMap
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.StatusColourSet
import kotlinx.collections.immutable.toImmutableList
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

internal class Section(val id: String, val name: StringResource, val summary: StringResource)

internal val Sections = listOf(
    Section("accounts", MR.strings.glass_settings_accounts, MR.strings.glass_settings_accounts_sub),
    Section("sources", MR.strings.glass_settings_sources, MR.strings.glass_settings_sources_sub),
    Section("downloads", MR.strings.glass_settings_downloads, MR.strings.glass_settings_downloads_sub),
    Section("reader", MR.strings.glass_settings_reader, MR.strings.glass_settings_reader_sub),
    Section("japanese", MR.strings.glass_settings_japanese, MR.strings.glass_settings_japanese_sub),
    Section("mining", MR.strings.glass_settings_mining, MR.strings.glass_settings_mining_sub),
    Section("library", MR.strings.glass_settings_library, MR.strings.glass_settings_library_sub),
    Section("privacy", MR.strings.glass_settings_privacy, MR.strings.glass_settings_privacy_sub),
    Section("about", MR.strings.glass_settings_about, MR.strings.glass_settings_about_sub),
)

/**
 * Canvas `Settings-1.dc.html`: the 9 sections on the left, the section on the right. Every pane re-hosts the
 * classic screens' own [Preference] items (their getPreferences()), regrouped; the only new UI is the source order.
 */
/** A section to open next (the Needs attention panel's "Change key" sets "sources"); taken once, then null. */
val glassSettingsSection = mutableStateOf<String?>(null)

@Composable
fun GlassSettingsPage(navigator: Navigator) {
    var section by rememberSaveable { mutableStateOf(Sections.first().id) }
    val asked by glassSettingsSection
    LaunchedEffect(asked) {
        asked?.let { id ->
            if (Sections.any { it.id == id }) section = id
            glassSettingsSection.value = null
        }
    }
    val narrow = LocalConfiguration.current.screenWidthDp < 900
    val sourceLine = sourceOrderLine()
    val current = Sections.first { it.id == section }

    Row(Modifier.fillMaxSize()) {
        Spacer(Modifier.width(100.dp)) // the rail
        Column(
            Modifier
                .width(if (narrow) 220.dp else 300.dp)
                .fillMaxHeight()
                .background(Glass.panel)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                stringResource(MR.strings.glass_settings_title),
                Modifier.padding(start = 12.dp, end = 12.dp, bottom = 14.dp),
                fontSize = Glass.title,
                fontWeight = FontWeight.SemiBold,
            )
            Sections.forEach { s ->
                val on = s.id == section
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 60.dp)
                        .clip(RoundedCornerShape(Glass.r12))
                        .background(if (on) Color.White else Color.Transparent)
                        .clickable { section = s.id }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                ) {
                    Text(
                        stringResource(s.name),
                        color = if (on) Color.Black else Color.White,
                        fontSize = Glass.label,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (s.id == "sources" && sourceLine.isNotEmpty()) sourceLine else stringResource(s.summary),
                        color = if (on) Color.Black.copy(alpha = 0.62f) else Glass.secondary,
                        fontSize = Glass.caption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(Glass.line))
        Column(
            Modifier.weight(1f).fillMaxHeight().padding(start = if (narrow) 24.dp else 40.dp, top = 32.dp, end = if (narrow) 16.dp else 40.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(current.name), fontSize = Glass.heading, fontWeight = FontWeight.SemiBold)
            key(section) {
                GlassReaderTheme {
                    PreferenceScreen(
                        items = sectionItems(section, navigator),
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(bottom = 24.dp),
                    )
                }
            }
        }
    }
}

/** "X3DL → Excnn → Manga Zone → …": the Sources section's summary line (empty with no sources). */
@Composable
internal fun sourceOrderLine(): String {
    val prefs = remember { Injekt.get<DownloadPreferences>() }
    val coverage = remember { Injekt.get<SourceCoverage>() }
    val order by prefs.realDebridSourceOrder().collectAsState()
    val off by prefs.realDebridSourcesOff().collectAsState()
    return remember(order, off) {
        coverage.sources().map { it.name }.let { n -> n.take(3).joinToString(" → ") + if (n.size > 3) " → …" else "" }
    }
}

/** The pane's items, from the classic screens (see the mapping in plans/chimahon_build_plan.md, phase 5). */
@Composable
internal fun sectionItems(id: String, navigator: Navigator): List<Preference> = when (id) {
    "accounts" -> {
        val dl = splitDownloads()
        var other by rememberSaveable { mutableStateOf(false) }
        val trackers = SettingsTrackingScreen.getPreferences() // composed always: its dialogs live in it
        listOf<Preference>(
            Preference.PreferenceItem.CustomPreference(title = "AniList") { AniListCard() },
            Preference.PreferenceItem.CustomPreference(title = stringResource(MR.strings.glass_acc_other)) {
                OtherTrackersFold(other) { other = !other }
            },
        ) + (if (other) trackers else emptyList()) + listOfNotNull(group(stringResource(MR.strings.glass_dl_rd), dl.realDebrid))
    }
    "sources" -> {
        val dl = splitDownloads()
        listOfNotNull(group(stringResource(MR.strings.glass_dl_rd), dl.realDebrid), sourceOrderGroup()) +
            SettingsBrowseScreen.getPreferences()
    }
    "downloads" -> splitDownloads().rest
    "reader" -> {
        val ocr = stringResource(MR.strings.pref_category_ocr)
        SettingsReaderScreen.getPreferences().filter { it.title != ocr }
    }
    "japanese" -> {
        val ocr = stringResource(MR.strings.pref_category_ocr)
        SettingsReaderScreen.getPreferences().filter { it.title == ocr } +
            usualDictionariesGroup(navigator) +
            SettingsDictionaryScreen.getPreferences() +
            SettingsDictionaryPopupScreen.getPreferences()
    }
    "mining" -> SettingsAnkiScreen.getPreferences()
    "library" -> SettingsLibraryScreen.getPreferences() + statusColoursGroup() + SettingsDataScreen.getPreferences()
    "privacy" -> SettingsSecurityScreen.getPreferences()
    else -> {
        val dl = splitDownloads()
        listOfNotNull(
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.glass_settings_about_app),
                onClick = { navigator.push(AboutScreen()) },
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.glass_settings_all),
                subtitle = stringResource(MR.strings.glass_settings_all_sub),
                onClick = { navigator.push(SettingsScreen()) },
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.glass_classic_view),
                subtitle = stringResource(MR.strings.glass_settings_classic_sub),
                onClick = { navigator.push(HomeScreen) },
            ),
            // chimahon: the first-run setup again (it only reads until something is tapped)
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.glass_setup_run_again),
                subtitle = stringResource(MR.strings.glass_setup_run_again_sub),
                onClick = { navigator.push(GlassSetupScreen(fromSettings = true)) },
            ),
            group(stringResource(MR.strings.glass_settings_developer), dl.coverageTest),
        )
    }
}

private class DownloadSplit(val realDebrid: List<Preference>, val coverageTest: List<Preference>, val rest: List<Preference>)

// ponytail: the token row is found by its literal title (SettingsDownloadScreen has no string id for it) and the
// account row is the one right after it; if that ever moves, both just stay in Downloads (nothing is lost)
private const val RD_TOKEN_TITLE = "Real-Debrid API token"

/** The classic Downloads screen cut in three: Real-Debrid token + account, the debug coverage test, the rest. */
@Composable
private fun splitDownloads(): DownloadSplit {
    val all = SettingsDownloadScreen.getPreferences()
    val coverageTitle = stringResource(MR.strings.rd_coverage_test)
    val token = all.indexOfFirst { it.title == RD_TOKEN_TITLE }
    val rd = if (token < 0) emptySet() else setOf(token, token + 1).filter { it < all.size }.toSet()
    return DownloadSplit(
        realDebrid = rd.sorted().map { all[it] },
        coverageTest = all.filter { it.title == coverageTitle },
        rest = all.filterIndexed { i, p -> i !in rd && p.title != coverageTitle },
    )
}

/** [items] (single items or whole groups) under one header; null when there is nothing to show. */
@Suppress("UNCHECKED_CAST")
private fun group(title: String, items: List<Preference>): Preference.PreferenceGroup? {
    val flat = items.flatMap {
        if (it is Preference.PreferenceGroup) it.preferenceItems else listOf(it as Preference.PreferenceItem<out Any, out Any>)
    }
    if (flat.isEmpty()) return null
    return Preference.PreferenceGroup(title = title, preferenceItems = flat.toImmutableList())
}

/** Build plan phase 12: the usual dictionaries to download, and their licences on a screen of their own (EDRDG). */
@Composable
private fun usualDictionariesGroup(navigator: Navigator): Preference.PreferenceGroup = Preference.PreferenceGroup(
    title = stringResource(MR.strings.glass_dict_usual),
    preferenceItems = persistentListOf(
        Preference.PreferenceItem.CustomPreference(title = stringResource(MR.strings.glass_dict_usual)) { UsualDictionariesSettings() },
        Preference.PreferenceItem.TextPreference(
            title = stringResource(MR.strings.glass_dict_licences),
            subtitle = RecommendedDictionaries.names(RecommendedDictionaries.all.map { it.name }),
            onClick = { navigator.push(GlassDictionaryLicencesScreen()) },
        ),
    ),
)

@Composable
private fun sourceOrderGroup(): Preference.PreferenceGroup = Preference.PreferenceGroup(
    title = stringResource(MR.strings.glass_settings_order),
    preferenceItems = persistentListOf(
        Preference.PreferenceItem.CustomPreference(title = stringResource(MR.strings.glass_settings_order)) { SourceOrder() },
    ),
)

/** Real-Debrid sources in the order they are tried: rank, name, on/off, move up / down (no drag). */
@Composable
internal fun SourceOrder() {
    val prefs = remember { Injekt.get<DownloadPreferences>() }
    val coverage = remember { Injekt.get<SourceCoverage>() }
    val order by prefs.realDebridSourceOrder().collectAsState()
    val off by prefs.realDebridSourcesOff().collectAsState()
    val sources = remember(order) { coverage.sources(includeOff = true) }
    val ids = sources.map { it.id }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (sources.isEmpty()) {
            Text(stringResource(MR.strings.glass_settings_no_sources), color = Glass.secondary, fontSize = Glass.body)
        }
        sources.forEachIndexed { i, s ->
            val on = s.id !in off
            Row(
                Modifier.fillMaxWidth().height(56.dp).glassPanel(Glass.r12).padding(start = 16.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("${i + 1}", Modifier.width(20.dp), color = Glass.secondary, fontSize = Glass.body, fontWeight = FontWeight.Bold)
                Text(
                    s.name,
                    Modifier.weight(1f).alpha(if (on) 1f else 0.62f),
                    color = Color.White,
                    fontSize = Glass.label,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                GlassToggle(on, onCheckedChange = { prefs.realDebridSourcesOff().set(GlassDownloads.toggle(off, s.id, it)) })
                GlassRoundButton("chevUp", {
                    prefs.realDebridSourceOrder().set(GlassDownloads.move(ids, s.id, -1))
                }, description = stringResource(MR.strings.glass_settings_move_up, s.name), enabled = i > 0, iconSize = 20.dp)
                GlassRoundButton("chevDown", {
                    prefs.realDebridSourceOrder().set(GlassDownloads.move(ids, s.id, 1))
                }, description = stringResource(MR.strings.glass_settings_move_down, s.name), enabled = i < ids.lastIndex, iconSize = 20.dp)
            }
        }
    }
}


/** Volume status colours: Default / Green-red / Ice-amber (the title grid, its legend, the source boxes). */
@Composable
private fun statusColoursGroup(): Preference.PreferenceGroup = Preference.PreferenceGroup(
    title = stringResource(MR.strings.glass_status_colours),
    preferenceItems = persistentListOf(
        Preference.PreferenceItem.ListPreference(
            preference = remember { Injekt.get<UiPreferences>().glassStatusColours() },
            entries = StatusColourSet.entries.associateWith { stringResource(it.titleRes) }.toImmutableMap(),
            title = stringResource(MR.strings.glass_status_colours_pick),
            subtitle = "%s",
        ),
    ),
)
