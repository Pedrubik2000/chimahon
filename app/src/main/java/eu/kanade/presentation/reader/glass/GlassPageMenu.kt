package eu.kanade.presentation.reader.glass

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassChip
import eu.kanade.presentation.glass.GlassIcon
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/** Which page of a double spread the actions of [GlassPageMenu] act on. */
private enum class PageChoice { First, Second, Both }

/**
 * Page long-press (A4): "Page 84", a small preview, a p. 84 / p. 85 / Both switch for double spreads, then the
 * actions of the classic ReaderPageActionsDialog (same callbacks).
 */
@Composable
fun GlassPageMenu(
    page: ReaderPage,
    extraPage: ReaderPage?,
    onClose: () -> Unit,
    onSetAsCover: (useExtraPage: Boolean) -> Unit,
    onShare: (copyToClipboard: Boolean, useExtraPage: Boolean) -> Unit,
    onSave: (useExtraPage: Boolean) -> Unit,
    onShareCombined: (copyToClipboard: Boolean) -> Unit,
    onSaveCombined: () -> Unit,
) {
    var choice by remember { mutableStateOf(PageChoice.First) }
    var confirmCover by remember { mutableStateOf(false) }
    val shown = if (choice == PageChoice.Second && extraPage != null) extraPage else page
    val both = choice == PageChoice.Both && extraPage != null
    val second = choice == PageChoice.Second

    GlassSidePanel(onLeft = false, onClose = onClose) {
        GlassPanelTitle(stringResource(MR.strings.glass_rset_page, page.number), onClose)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val preview by produceState<ImageBitmap?>(null, shown) {
                value = withContext(Dispatchers.IO) { runCatching { shown.stream?.let { open -> decodeSmall(open) } }.getOrNull() }
            }
            preview?.let {
                Image(it, null, Modifier.heightIn(max = 160.dp), contentScale = ContentScale.Fit, alignment = Alignment.CenterStart)
            }
            if (extraPage != null) {
                GlassCap(stringResource(MR.strings.glass_rset_which))
                GlassSegmented(
                    listOf(
                        stringResource(MR.strings.glass_rset_p, page.number) to PageChoice.First,
                        stringResource(MR.strings.glass_rset_p, extraPage.number) to PageChoice.Second,
                        stringResource(MR.strings.glass_rset_both) to PageChoice.Both,
                    ),
                    choice,
                ) { choice = it }
            }
            // a combined image has no cover action: Both sets the first page
            PageAction("book", stringResource(MR.strings.set_as_cover)) { confirmCover = true }
            PageAction("cards", stringResource(MR.strings.action_copy_to_clipboard)) {
                if (both) onShareCombined(true) else onShare(true, second)
                onClose()
            }
            PageAction("external", stringResource(MR.strings.action_share)) {
                if (both) onShareCombined(false) else onShare(false, second)
                onClose()
            }
            PageAction("download", stringResource(MR.strings.action_save)) {
                if (both) onSaveCombined() else onSave(second)
                onClose()
            }
        }
    }

    if (confirmCover) {
        AlertDialog(
            text = { Text(stringResource(MR.strings.confirm_set_image_as_cover)) },
            confirmButton = {
                TextButton(onClick = { onSetAsCover(second); confirmCover = false }) { Text(stringResource(MR.strings.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmCover = false }) { Text(stringResource(MR.strings.action_cancel)) }
            },
            onDismissRequest = { confirmCover = false },
        )
    }
}

@Composable
private fun PageAction(icon: String, text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GlassIcon(icon, Modifier.size(20.dp))
        Text(text, fontSize = Glass.label)
    }
}

/** A thumbnail: the page decoded at 1/4 or so (the panel shows it at most 160dp high). */
private fun decodeSmall(open: () -> java.io.InputStream): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    open().use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outHeight / (sample * 2) >= 480) sample *= 2
    return open().use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        ?.asImageBitmap()
}
