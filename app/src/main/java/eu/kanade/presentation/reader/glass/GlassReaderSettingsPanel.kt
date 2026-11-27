package eu.kanade.presentation.reader.glass

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassChip
import eu.kanade.presentation.glass.GlassRoundButton
import eu.kanade.presentation.glass.glassPanel
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/** The four tabs of the Glass settings panel (mockup A: Four tabs). */
enum class GlassSettingsSection { Reading, Display, Taps, Ocr }

/** What the top bar's old "more" menu held, now at the end of the settings: auto-scroll (More) and the web view / browser / share row. */
class GlassSettingsExtras(
    val onAutoScroll: (() -> Unit)? = null,
    val onWebView: (() -> Unit)? = null,
    val onBrowser: (() -> Unit)? = null,
    val onShare: (() -> Unit)? = null,
)

/** The panel is this wide on a tablet (over the page); on a phone it covers the screen width. */
private val PanelWidth = 380.dp
private val PanelGap = 12.dp

/** Clearance above/below the panel: the top bar and the two-line page bar, which a side panel never covers. */
/** Header height: one row on a tablet, plus the tools row on a phone. */
private val BarTopWide = 72.dp
private val BarTopNarrow = 124.dp
// the two-line page bar is ~90 dp (28 + 48 + paddings, border, 4 dp gap) above the navigation bar; 8 dp clear of it
private val BarBottom = 98.dp

/**
 * A tall Glass panel on one side, between the top bar and the bottom page bar. It overlays the page: the viewer is never resized (a
 * resize left it stale).
 */
@Composable
internal fun GlassSidePanel(
    onClose: () -> Unit,
    onLeft: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onClose)
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(start = PanelGap, end = PanelGap, top = if (LocalConfiguration.current.screenWidthDp >= 640) BarTopWide else BarTopNarrow, bottom = BarBottom),
    ) {
        Column(
            Modifier
                .align(if (onLeft) Alignment.CenterStart else Alignment.CenterEnd)
                .then(if (wide) Modifier.width(PanelWidth) else Modifier.fillMaxWidth())
                .fillMaxHeight()
                .glassPanel()
                .pointerInput(Unit) {}
                .padding(16.dp),
            content = content,
        )
    }
}

/** Title row of a side panel: serif [title], optional [trailing] label, and the close button. */
@Composable
internal fun GlassPanelTitle(title: String, onClose: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.weight(1f), fontSize = Glass.heading, fontFamily = FontFamily.Serif)
        trailing()
        GlassRoundButton("close", onClose, outlined = true)
    }
}

/** The Glass settings panel (mockup A, four tabs): serif title, one row of four tabs, then the rows of the tab. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GlassReaderSettingsPanel(
    screenModel: ReaderSettingsScreenModel,
    initial: GlassSettingsSection,
    onClose: () -> Unit,
    onShowMenus: () -> Unit,
    onHideMenus: () -> Unit,
    extras: GlassSettingsExtras = GlassSettingsExtras(),
) {
    var section by remember { mutableStateOf(initial) }
    // The bars stay on every tab (Filters too): the page beside the panel changes live.
    LaunchedEffect(Unit) { onShowMenus() }

    GlassSidePanel(onLeft = false, onClose = onClose) {
        GlassPanelTitle(stringResource(MR.strings.glass_rset_title), onClose)
        Text(stringResource(MR.strings.glass_rset_subline), Modifier.padding(bottom = 10.dp), color = Glass.secondary, fontSize = Glass.caption)
        GlassSegmented(GlassSettingsSection.entries.map { stringResource(it.label()) to it }, section) { section = it }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            when (section) {
                GlassSettingsSection.Reading -> GlassReadingSection(screenModel, extras, onClose)
                GlassSettingsSection.Display -> GlassDisplaySection(screenModel)
                GlassSettingsSection.Taps -> GlassTapsSection(screenModel)
                GlassSettingsSection.Ocr -> GlassOcrSection(screenModel)
            }
        }
        if (extras.onWebView != null || extras.onBrowser != null || extras.onShare != null) {
            // web view / browser / share: the actions of the old "more" menu, only those this source has
            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                extras.onWebView?.let { GlassChip(stringResource(MR.strings.action_open_in_web_view), selected = false, onClick = { onClose(); it() }, leading = "external") }
                extras.onBrowser?.let { GlassChip(stringResource(MR.strings.action_open_in_browser), selected = false, onClick = { onClose(); it() }, leading = "external") }
                extras.onShare?.let { GlassChip(stringResource(MR.strings.action_share), selected = false, onClick = { onClose(); it() }, leading = "external") }
            }
        }
    }
}

private fun GlassSettingsSection.label() = when (this) {
    GlassSettingsSection.Reading -> MR.strings.glass_rset_tab_reading
    GlassSettingsSection.Display -> MR.strings.glass_rset_sec_display
    GlassSettingsSection.Taps -> MR.strings.glass_rset_sec_taps
    GlassSettingsSection.Ocr -> MR.strings.glass_rset_sec_ocr
}
