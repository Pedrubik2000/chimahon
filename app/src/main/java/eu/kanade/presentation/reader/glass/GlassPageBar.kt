package eu.kanade.presentation.reader.glass

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import eu.kanade.presentation.glass.Glass
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.roundToInt

// Pure logic of the Glass page bar (unit-tested in GlassPageBarTest).

/** Pages after [page] (1-based) in a chapter of [total] pages. */
internal fun pagesLeft(page: Int, total: Int) = (total - page).coerceAtLeast(0)

/** Where the handle sits on the track as 0..1 from the LEFT edge; the R2L viewer fills from the right. */
internal fun trackPosition(page: Int, total: Int, rtl: Boolean): Float {
    if (total <= 1) return if (rtl) 1f else 0f
    val f = ((page - 1).toFloat() / (total - 1)).coerceIn(0f, 1f)
    return if (rtl) 1f - f else f
}

/** The page under a finger at [fraction] (0..1 from the left edge of the track). */
internal fun pageAt(fraction: Float, total: Int, rtl: Boolean): Int {
    val f = fraction.coerceIn(0f, 1f).let { if (rtl) 1f - it else it }
    return (f * (total - 1).coerceAtLeast(0)).roundToInt() + 1
}

/** Chapter boundaries as track positions; none unless the volume really has several chapters. */
internal fun tickPositions(chapterStarts: List<Int>, total: Int, rtl: Boolean): List<Float> {
    if (chapterStarts.size < 2 || total <= 1) return emptyList()
    return chapterStarts.filter { it in 2..total }.map { trackPosition(it, total, rtl) }
}

/** Last page of a double spread shown as "195-196" (or just the page); falls back to [current]. */
internal fun spreadLastPage(text: String, current: Int) =
    maxOf(current, Regex("\\d+").findAll(text).mapNotNull { it.value.toIntOrNull() }.maxOrNull() ?: current)

private const val BACK_READ_ON = 3

/** "Back to p. N" stays while you are still near where the jump landed ([landed]) and not already on [origin]. */
internal fun showBackPill(origin: Int?, landed: Int, current: Int, arrived: Boolean = true) =
    origin != null && current != origin && (!arrived || abs(current - landed) < BACK_READ_ON)


/** The pages of the chapter on screen, from whichever viewer is active. */
private fun chapterPages(viewer: Viewer?): List<ReaderPage>? {
    val current = when (viewer) {
        is PagerViewer -> viewer.currentPage
        is WebtoonViewer -> viewer.currentPage
        else -> null
    }
    return (current as? ReaderPage)?.chapter?.pages
}

/** A small decode (about 340 px tall) of a page for the drag preview; null when the page has no stream yet. */
private suspend fun decodeThumb(open: (() -> InputStream)?): ImageBitmap? = withContext(Dispatchers.IO) {
    if (open == null) return@withContext null
    runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outHeight / (sample * 2) >= 340) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        open().use { BitmapFactory.decodeStream(it, null, opts) }?.asImageBitmap()
    }.getOrNull()
}

private val barShape = RoundedCornerShape(Glass.r16)
private val trackLine = Color.White.copy(alpha = 0.2f)

/**
 * Glass bottom page bar as in the round-2 mockup A: a floating panel, "12 left" / "84 / 196" / chapter on the top
 * line, round skip buttons and the track below, a preview above the finger while dragging and a "Back to p. N" pill.
 * ponytail: no volume skip, bookmark flags or chapter names under the track (the reader has no volume/bookmark data
 * here); a chapter skip has no back pill (the old chapter's page is not reachable from here).
 */
@Composable
internal fun GlassPageBar(
    isRtl: Boolean,
    onNextChapter: () -> Unit,
    enabledNext: Boolean,
    onPreviousChapter: () -> Unit,
    enabledPrevious: Boolean,
    currentPage: Int,
    totalPages: Int,
    chapterTitle: String?,
    onPageIndexChange: (Int) -> Unit,
    viewer: Viewer? = null,
    chapterStarts: List<Int> = emptyList(),
    chapterLabel: String? = null,
    currentPageText: String = "",
) {
    // Keyed on the chapter only: totalPages can change while pages load and must not reset the pill.
    var origin by remember(chapterTitle) { mutableStateOf<Int?>(null) }
    var landed by remember(chapterTitle) { mutableIntStateOf(currentPage) }
    // The viewer scrolls through other pages on its way to [landed]; only count reading on once it got there.
    var arrived by remember(chapterTitle) { mutableStateOf(true) }
    LaunchedEffect(currentPage, landed) { if (abs(currentPage - landed) <= 1) arrived = true }
    var dragPage by remember { mutableStateOf<Int?>(null) }
    fun jump(to: Int) {
        if (to == currentPage) return
        if (!showBackPill(origin, landed, currentPage, arrived)) origin = currentPage
        landed = to
        arrived = false
        onPageIndexChange(to - 1)
    }

    val spreadText = currentPageText.takeIf { it.isNotBlank() && it != currentPage.toString() }
    val left = pagesLeft(spreadLastPage(currentPageText, currentPage), totalPages)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(barShape)
                    .background(barColor)
                    .border(1.dp, Glass.line, barShape)
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp)
                    .pointerInput(Unit) {},
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.fillMaxWidth().height(34.dp)) {
                    val leftText = if (chapterLabel != null) stringResource(MR.strings.glass_rbar_left_in, left, chapterLabel) else stringResource(MR.strings.glass_rbar_left, left)
                    Text(
                        buildAnnotatedString {
                            val i = leftText.indexOf(left.toString())
                            if (i < 0) append(leftText) else {
                                append(leftText.substring(0, i))
                                withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.Medium)) { append(left.toString()) }
                                append(leftText.substring(i + left.toString().length))
                            }
                        },
                        color = Glass.secondary,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.CenterStart).fillMaxWidth(0.34f),
                    )
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(fontFamily = FontFamily.Serif, fontSize = 26.sp, color = Color.White)) { append(spreadText ?: currentPage.toString()) }
                            withStyle(SpanStyle(fontSize = 15.sp, color = Glass.secondary)) { append(" / $totalPages") }
                        },
                        modifier = Modifier.align(Alignment.Center),
                    )
                    if (showBackPill(origin, landed, currentPage, arrived)) {
                        val back = origin ?: 1
                        Box(
                            Modifier
                                .align(Alignment.CenterEnd)
                                .height(28.dp)
                                .clip(CircleShape)
                                .border(1.dp, Glass.ice, CircleShape)
                                .clickable {
                                    origin = null
                                    landed = back
                                    arrived = false
                                    onPageIndexChange(back - 1)
                                }
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(stringResource(MR.strings.glass_rbar_back, back), color = Glass.ice, fontSize = Glass.caption, fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        chapterTitle?.let {
                            Text(
                                it,
                                color = Color.White,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.align(Alignment.CenterEnd).fillMaxWidth(0.34f),
                                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            )
                        }
                    }
                }
                Row(Modifier.height(74.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                    SkipButton(
                        next = false,
                        enabled = if (isRtl) enabledNext else enabledPrevious,
                        onClick = if (isRtl) onNextChapter else onPreviousChapter,
                        label = stringResource(if (isRtl) MR.strings.glass_rbar_next else MR.strings.glass_rbar_prev),
                        description = stringResource(if (isRtl) MR.strings.action_next_chapter else MR.strings.action_previous_chapter),
                    )
                    if (totalPages > 1) {
                        PageTrack(
                            viewer = viewer,
                            rtl = isRtl,
                            page = dragPage ?: currentPage,
                            total = totalPages,
                            ticks = tickPositions(chapterStarts, totalPages, isRtl),
                            dragging = dragPage != null,
                            chapterTitle = chapterTitle,
                            onDrag = { dragPage = it },
                            onCommit = {
                                dragPage = null
                                jump(it)
                            },
                            modifier = Modifier.weight(1f).padding(horizontal = 18.dp),
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    SkipButton(
                        next = true,
                        enabled = if (isRtl) enabledPrevious else enabledNext,
                        onClick = if (isRtl) onPreviousChapter else onNextChapter,
                        label = stringResource(if (isRtl) MR.strings.glass_rbar_prev else MR.strings.glass_rbar_next),
                        description = stringResource(if (isRtl) MR.strings.action_previous_chapter else MR.strings.action_next_chapter),
                    )
                }
            }
        }
    }
}

/** The mockup's `.tool`: a 44dp outlined round button with a 12sp label under it. */
@Composable
private fun SkipButton(next: Boolean, enabled: Boolean, onClick: () -> Unit, label: String, description: String) {
    Column(Modifier.width(58.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Glass.page)
                .border(1.dp, Glass.line, CircleShape)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (next) Icons.Outlined.SkipNext else Icons.Outlined.SkipPrevious,
                contentDescription = description,
                modifier = Modifier.size(18.dp),
                tint = if (enabled) Color.White else Color.White.copy(alpha = 0.3f),
            )
        }
        Text(label, color = Color.White.copy(alpha = if (enabled) 0.85f else 0.3f), fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun PageTrack(
    viewer: Viewer?,
    rtl: Boolean,
    page: Int,
    total: Int,
    ticks: List<Float>,
    dragging: Boolean,
    chapterTitle: String?,
    onDrag: (Int) -> Unit,
    onCommit: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val drag by rememberUpdatedState(onDrag)
    val commit by rememberUpdatedState(onCommit)
    LaunchedEffect(page) { if (dragging) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    var widthPx by remember { mutableIntStateOf(0) }
    // Previews by page number; a few pages at most, cleared when it grows. Missing = number bubble only.
    val thumbs = remember { mutableStateMapOf<Int, ImageBitmap?>() }
    LaunchedEffect(page, dragging) {
        if (!dragging || thumbs.containsKey(page)) return@LaunchedEffect
        delay(60) // not for every page the finger flies over
        val open = chapterPages(viewer)?.getOrNull(page - 1)?.stream
        val bmp = decodeThumb(open)
        if (thumbs.size > 16) thumbs.clear()
        thumbs[page] = bmp
    }
    Box(
        modifier
            .height(46.dp)
            .pointerInput(total, rtl) {
                detectTapGestures { commit(pageAt(it.x / size.width, total, rtl)) }
            }
            .pointerInput(total, rtl) {
                var last = 1
                detectDragGestures(
                    onDragStart = {
                        last = pageAt(it.x / size.width, total, rtl)
                        drag(last)
                    },
                    onDragEnd = { commit(last) },
                    onDragCancel = { commit(last) },
                ) { change, _ ->
                    change.consume()
                    last = pageAt(change.position.x / size.width, total, rtl)
                    drag(last)
                }
            },
    ) {
        val pos = trackPosition(page, total, rtl)
        Canvas(Modifier.fillMaxWidth().height(46.dp)) {
            widthPx = size.width.roundToInt()
            val y = 23.dp.toPx()
            val w = size.width
            val stroke = 6.dp.toPx()
            drawLine(trackLine, Offset(0f, y), Offset(w, y), stroke, StrokeCap.Round)
            val edge = if (rtl) w else 0f
            drawLine(Color.White, Offset(edge, y), Offset(pos * w, y), stroke, StrokeCap.Round)
            ticks.forEach { drawLine(Color.White.copy(alpha = 0.75f), Offset(it * w, y - 13.dp.toPx()), Offset(it * w, y + 7.dp.toPx()), 2.dp.toPx()) }
            drawCircle(Color.White, if (dragging) 13.dp.toPx() else 11.dp.toPx(), Offset(pos * w, y))
        }
        if (dragging) {
            val fingerX = pos * widthPx
            val above = object : PopupPositionProvider {
                override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                    val x = (anchorBounds.left + fingerX - popupContentSize.width / 2f).roundToInt()
                        .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                    return IntOffset(x, anchorBounds.top - popupContentSize.height - 8)
                }
            }
            Popup(popupPositionProvider = above) {
                Column(
                    Modifier
                        .width(168.dp)
                        .clip(RoundedCornerShape(Glass.r12))
                        .background(Glass.panel)
                        .border(1.dp, Glass.line, RoundedCornerShape(Glass.r12))
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    thumbs[page]?.let {
                        Image(it, null, Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(Glass.r8)), contentScale = ContentScale.Fit)
                    }
                    Text("p. $page", color = Color.White, fontSize = Glass.label, fontWeight = FontWeight.SemiBold)
                    chapterTitle?.let { Text(it, color = Glass.secondary, fontSize = Glass.caption, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}