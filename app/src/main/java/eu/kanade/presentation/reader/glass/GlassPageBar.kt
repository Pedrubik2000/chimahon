package eu.kanade.presentation.reader.glass

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import eu.kanade.presentation.glass.Glass
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
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

/**
 * Glass bottom page bar: "p. 84 / 196", pages left, a thin track that fills in the reading direction, chapter skip
 * buttons, a bubble above the finger while dragging and a "Back to p. N" pill after a jump.
 * ponytail: the bubble shows page + chapter, not the page image (the viewer exposes no cheap thumbnail);
 * a chapter skip has no back pill (the old chapter's page is not reachable from here).
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
        Column(
            Modifier
                .fillMaxWidth()
                .background(barColor)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .pointerInput(Unit) {},
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(Modifier.height(32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (spreadText != null) "$spreadText / $totalPages" else "p. $currentPage / $totalPages", color = Color.White, fontSize = Glass.label, fontWeight = FontWeight.SemiBold)
                Text(
                    if (chapterLabel != null) stringResource(MR.strings.glass_rbar_left_in, left, chapterLabel) else stringResource(MR.strings.glass_rbar_left, left),
                    color = Glass.secondary,
                    fontSize = Glass.caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (showBackPill(origin, landed, currentPage, arrived)) {
                    val back = origin ?: 1
                    Box(
                        Modifier
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
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SkipButton(
                    next = false,
                    enabled = if (isRtl) enabledNext else enabledPrevious,
                    onClick = if (isRtl) onNextChapter else onPreviousChapter,
                    description = stringResource(if (isRtl) MR.strings.action_next_chapter else MR.strings.action_previous_chapter),
                )
                if (totalPages > 1) {
                    PageTrack(
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
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                SkipButton(
                    next = true,
                    enabled = if (isRtl) enabledPrevious else enabledNext,
                    onClick = if (isRtl) onPreviousChapter else onNextChapter,
                    description = stringResource(if (isRtl) MR.strings.action_previous_chapter else MR.strings.action_next_chapter),
                )
            }
        }
    }
}

@Composable
private fun SkipButton(next: Boolean, enabled: Boolean, onClick: () -> Unit, description: String) {
    Box(
        Modifier.size(Glass.chip).clip(CircleShape).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (next) Icons.Outlined.SkipNext else Icons.Outlined.SkipPrevious,
            contentDescription = description,
            tint = if (enabled) Color.White else Color.White.copy(alpha = 0.18f),
        )
    }
}

@Composable
private fun PageTrack(
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
    val density = LocalDensity.current
    var widthPx by remember { mutableIntStateOf(0) }
    Box(
        modifier
            .height(Glass.chip)
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
        Canvas(Modifier.fillMaxWidth().height(Glass.chip)) {
            widthPx = size.width.roundToInt()
            val y = size.height / 2
            val w = size.width
            val stroke = 3.dp.toPx()
            drawLine(Glass.line, Offset(0f, y), Offset(w, y), stroke)
            val edge = if (rtl) w else 0f
            drawLine(Color.White, Offset(edge, y), Offset(pos * w, y), stroke)
            ticks.forEach { drawLine(Color.White.copy(alpha = 0.4f), Offset(it * w, y - 6.dp.toPx()), Offset(it * w, y + 6.dp.toPx()), 1.5.dp.toPx()) }
            drawCircle(Color.White, if (dragging) 10.dp.toPx() else 7.dp.toPx(), Offset(pos * w, y))
        }
        if (dragging) {
            val bubbleW = 112.dp
            val bubbleWpx = with(density) { bubbleW.roundToPx() }
            val x = ((pos * widthPx) - bubbleWpx / 2f).roundToInt().coerceIn(0, (widthPx - bubbleWpx).coerceAtLeast(0))
            Popup(alignment = Alignment.TopStart, offset = IntOffset(x, with(density) { -(76.dp).roundToPx() })) {
                Column(
                    Modifier
                        .width(bubbleW)
                        .clip(RoundedCornerShape(Glass.r12))
                        .background(Glass.panel)
                        .border(1.dp, Glass.line, RoundedCornerShape(Glass.r12))
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("p. $page", color = Color.White, fontSize = Glass.heading, fontWeight = FontWeight.SemiBold)
                    chapterTitle?.let { Text(it, color = Glass.secondary, fontSize = Glass.caption, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp)) }
                }
            }
        }
    }
}
