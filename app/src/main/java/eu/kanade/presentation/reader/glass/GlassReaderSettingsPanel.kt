package eu.kanade.presentation.reader.glass

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassChip
import eu.kanade.presentation.glass.GlassRoundButton
import eu.kanade.presentation.glass.glassPanel
import eu.kanade.presentation.reader.settings.ColorFilterPage
import eu.kanade.presentation.reader.settings.GeneralPage
import eu.kanade.presentation.reader.settings.OcrItems
import eu.kanade.presentation.reader.settings.ReadingModePage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/** The sections of the Glass settings panel (A: the tabs of the classic dialog, with OCR pulled out of General). */
enum class GlassSettingsSection { Mode, Display, Filters, Ocr }

/** The panel is this wide on a tablet (over the page); on a phone it covers the screen width. */
private val PanelWidth = 360.dp
private val PanelGap = 12.dp

/** Clearance above/below the panel: the top bar and the two-line page bar, which a side panel never covers. */
/** Header height: one row on a tablet, plus the tools row on a phone. */
private val BarTopWide = 72.dp
private val BarTopNarrow = 124.dp
private val BarBottom = 136.dp

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

/** Title row of a side panel: [title], optional [trailing] chip, and the close button. */
@Composable
internal fun GlassPanelTitle(title: String, onClose: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.weight(1f), fontSize = Glass.heading)
        trailing()
        GlassRoundButton("close", onClose, outlined = true)
    }
}

/** The Glass settings panel: every option of the classic dialog's tabs, in four sections. Filters hides the menus, like the classic tab. */
@Composable
fun GlassReaderSettingsPanel(
    screenModel: ReaderSettingsScreenModel,
    initial: GlassSettingsSection,
    onClose: () -> Unit,
    onShowMenus: () -> Unit,
    onHideMenus: () -> Unit,
) {
    var section by remember { mutableStateOf(initial) }
    LaunchedEffect(section) { if (section == GlassSettingsSection.Filters) onHideMenus() else onShowMenus() }

    GlassSidePanel(onLeft = false, onClose = onClose) {
        GlassPanelTitle(stringResource(MR.strings.glass_rset_title), onClose) {
            Text(stringResource(MR.strings.glass_rset_scope), color = Glass.secondary, fontSize = Glass.body)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassSettingsSection.entries.forEach {
                GlassChip(stringResource(it.label()), selected = it == section, onClick = { section = it })
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            when (section) {
                GlassSettingsSection.Mode -> ReadingModePage(screenModel)
                GlassSettingsSection.Display -> GeneralPage(screenModel, showOcr = false)
                GlassSettingsSection.Filters -> ColorFilterPage(screenModel)
                GlassSettingsSection.Ocr -> OcrItems(screenModel)
            }
        }
    }
}

private fun GlassSettingsSection.label() = when (this) {
    GlassSettingsSection.Mode -> MR.strings.pref_category_reading_mode
    GlassSettingsSection.Display -> MR.strings.pref_category_general
    GlassSettingsSection.Filters -> MR.strings.custom_filter
    GlassSettingsSection.Ocr -> MR.strings.action_ocr
}
