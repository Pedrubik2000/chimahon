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
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.sp
import chimahon.ocr.KumapieColours
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
 * The long-press panel (mockup A4), one for both cases. "Page 84"; when the press hit an OCR bubble ([bubbleText]) its text
 * in the word colours first (tap a word = the lookup popup, [onLookupWord]); then the page: a thumbnail, "Vol. X . p. N", a
 * p. 84 / p. 85 / Both switch for double spreads and the actions of the classic ReaderPageActionsDialog (same callbacks).
 */
@Composable
fun GlassPageMenu(
    page: ReaderPage,
    extraPage: ReaderPage?,
    bubbleText: String?,
    onClose: () -> Unit,
    onLookupWord: (text: String, offset: Int, anchor: Rect) -> Unit,
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
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!bubbleText.isNullOrBlank()) {
                BubbleSection(bubbleText, onLookupWord)
                GlassDivider()
            }
            val preview by produceState<ImageBitmap?>(null, shown) {
                value = withContext(Dispatchers.IO) { runCatching { shown.stream?.let { open -> decodeSmall(open) } }.getOrNull() }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                preview?.let {
                    Image(it, null, Modifier.height(56.dp).clip(RoundedCornerShape(Glass.r8)), contentScale = ContentScale.Fit)
                }
                Text(
                    stringResource(MR.strings.glass_press_vol_page, shown.chapter.chapter.name, shown.number),
                    color = Glass.secondary,
                    fontSize = Glass.body,
                    maxLines = 2,
                )
            }
            if (extraPage != null) {
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
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GlassIcon(icon, Modifier.size(20.dp))
        Text(text, fontSize = Glass.label)
    }
}

/** "BUBBLE TEXT": the bubble's text in kumapie's word colours (the reader's own [KumapieColours]), a legend and Copy. */
@Composable
private fun BubbleSection(text: String, onLookupWord: (String, Int, Rect) -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    var redraw by remember { mutableIntStateOf(0) }
    val colours = remember(text, redraw) { KumapieColours.of(context, text) { redraw++ } }
    val annotated = remember(text, colours) { colouredText(text, colours) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val clipboard = LocalClipboardManager.current

    Row(verticalAlignment = Alignment.CenterVertically) {
        GlassCap(stringResource(MR.strings.glass_press_bubble), Modifier.weight(1f))
        GlassChip(stringResource(MR.strings.glass_press_copy), selected = false, onClick = { clipboard.setText(AnnotatedString(text)) })
    }
    Text(
        annotated,
        Modifier
            .fillMaxWidth()
            .heightIn(max = 112.dp)
            .verticalScroll(rememberScrollState())
            .onGloballyPositioned { origin = it.positionInRoot() }
            .pointerInput(text) {
                detectTapGestures { pos ->
                    val l = layout ?: return@detectTapGestures
                    val offset = l.getOffsetForPosition(pos)
                    val box = l.getBoundingBox(offset.coerceAtMost(text.lastIndex))
                    // compose root -> screen: the activity's popups place themselves in screen pixels
                    val loc = IntArray(2).also { view.getLocationOnScreen(it) }
                    onLookupWord(text, offset, box.translate(Offset(origin.x + loc[0], origin.y + loc[1])))
                }
            },
        fontSize = Glass.heading,
        lineHeight = 32.sp,
        onTextLayout = { layout = it },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Legend(Color.White, MR.strings.glass_press_known)
        Legend(Glass.red, MR.strings.glass_press_new)
        Legend(Glass.green, MR.strings.glass_press_due)
        Legend(Glass.blue, MR.strings.glass_press_mined)
    }
}

@Composable
private fun Legend(colour: Color, label: dev.icerock.moko.resources.StringResource) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(colour))
        Text(stringResource(label), color = Glass.secondary, fontSize = Glass.caption)
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

/** [text] with the word colours kumapie answered for it ([colours] per character, 0 = known/normal), in the Glass red/green/blue. */
internal fun colouredText(text: String, colours: IntArray?): AnnotatedString = buildAnnotatedString {
    append(text)
    colours?.forEachIndexed { i, c ->
        if (i < text.length) glassWordColour(c)?.let { addStyle(SpanStyle(color = it), i, i + 1) }
    }
}

/** kumapie's three word colours (made for the OCR boxes' white background) as the Glass ones for the dark panel. */
internal fun glassWordColour(argb: Int): Color? = when (argb) {
    KumapieColours.COLOURS["u"] -> Glass.red
    KumapieColours.COLOURS["d"] -> Glass.green
    KumapieColours.COLOURS["b"] -> Glass.blue
    else -> null
}
