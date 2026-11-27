package eu.kanade.presentation.reader.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassIcon
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOcrSource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/** The Glass OCR panel (mockup A2): the on/off toggle, then one row per source with a check on the selected one. */
@Composable
fun GlassOcrPanel(
    ocrEnabled: Boolean,
    ocrLoading: Boolean,
    onToggleOcr: () -> Unit,
    selected: ReaderOcrSource,
    mokuroAvailable: Boolean,
    onSelectSource: (ReaderOcrSource) -> Unit,
    onClose: () -> Unit,
) {
    GlassSidePanel(onLeft = false, onClose = onClose) {
        GlassPanelTitle(stringResource(MR.strings.action_ocr), onClose)
        // like the pill: no toggling while a scan runs and OCR is off
        GlassToggleRow(stringResource(MR.strings.glass_ocr_text_on_page), ocrEnabled, { if (ocrEnabled || !ocrLoading) onToggleOcr() })
        GlassDivider()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassCap(stringResource(MR.strings.glass_ocr_source))
            ReaderOcrSource.availableSources(BuildConfig.HAS_LOCAL_OCR, mokuroAvailable).forEach { source ->
                val on = source == selected
                val ink = if (on) Color.Black else Color.White
                val shape = RoundedCornerShape(Glass.r12)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(if (on) Modifier.background(Color.White, shape) else Modifier.border(1.dp, Glass.line, shape))
                        .clickable { onSelectSource(source) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(source.label()), color = ink, fontSize = Glass.body, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(source.description()), color = ink.copy(alpha = 0.62f), fontSize = Glass.caption)
                    }
                    if (on) GlassIcon("check", tint = ink)
                }
            }
            Text(
                stringResource(MR.strings.glass_ocr_hint, stringResource(selected.shortLabel())),
                Modifier.padding(top = 8.dp),
                color = Glass.secondary,
                fontSize = Glass.caption,
            )
        }
    }
}

private fun ReaderOcrSource.description() = when (this) {
    ReaderOcrSource.AUTOMATIC -> MR.strings.glass_ocr_desc_auto
    ReaderOcrSource.MOKURO -> MR.strings.glass_ocr_desc_mokuro
    ReaderOcrSource.GOOGLE_LENS -> MR.strings.glass_ocr_desc_lens
    ReaderOcrSource.LOCAL -> MR.strings.glass_ocr_desc_local
}
