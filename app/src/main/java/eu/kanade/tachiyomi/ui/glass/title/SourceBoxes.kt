package eu.kanade.tachiyomi.ui.glass.title

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.StatusColours
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.ceil

/** A box narrower than this cannot show its number: the bar wraps into more rows, and below it the number is hidden. */
private val MIN_BOX = 18.dp

/** A gap box is as wide as this many volume boxes (it carries a label). */
private fun gapWeight(from: Int, to: Int) = (to - from + 1).coerceIn(2, 4).toFloat()

private fun weight(b: SourceBox) = if (b is SourceBox.Gap) gapWeight(b.from, b.to) else 1f

/** [items] cut into [rows] runs of about equal weight, in order. */
internal fun splitRows(items: List<SourceBox>, rows: Int): List<List<SourceBox>> {
    if (rows <= 1) return listOf(items)
    val cap = items.sumOf { weight(it).toDouble() } / rows
    val out = mutableListOf<MutableList<SourceBox>>(mutableListOf())
    var acc = 0.0
    items.forEach { b ->
        if (acc >= cap * out.size - 1e-6 && out.size < rows && out.last().isNotEmpty()) out.add(mutableListOf())
        out.last().add(b)
        acc += weight(b)
    }
    return out
}

/** A source card's bar: [GlassTitleState.sourceBoxes], numbered, in as many rows as keep each box at [MIN_BOX]. */
@Composable
internal fun SourceBoxes(boxes: List<BoxState>, colours: StatusColours) {
    val items = GlassTitleState.sourceBoxes(boxes)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val total = items.sumOf { weight(it).toDouble() }.toFloat()
        val rowCount = ceil(total * MIN_BOX.value / maxWidth.value).toInt().coerceIn(1, items.size.coerceAtLeast(1))
        val rows = splitRows(items, rowCount)
        val widest = rows.maxOf { r -> r.sumOf { weight(it).toDouble() } }.toFloat()
        // a unit box's width in the fullest row; narrower than MIN_BOX = numbers off (still the colours)
        val numbers = (maxWidth - 2.dp * (rows.maxOf { it.size } - 1)) / widest >= MIN_BOX
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            rows.forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    row.forEach { b ->
                        when (b) {
                            is SourceBox.Vol -> SourceBoxCell(b.number.takeIf { numbers }, b.state, colours, Modifier.weight(1f))
                            is SourceBox.Gap -> SourceBoxCell(
                                null,
                                BoxState.NONE,
                                colours,
                                Modifier.weight(gapWeight(b.from, b.to)),
                                gapRange = if (b.from == b.to) "${b.from}" else "${b.from}–${b.to}",
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * One box: [number] inside (null on a volume box = too narrow for it), or for a merged run its [gapRange]
 * ("20–24 missing" when it fits, else just the range when that fits, else nothing). A missing box is dashed (or solid)
 * per the colour set; the on-device box is dim, the queued one takes the palette's queued colour.
 */
@Composable
internal fun SourceBoxCell(number: Int?, state: BoxState, colours: StatusColours, modifier: Modifier, gapRange: String? = null) {
    val shape = RoundedCornerShape(3.dp)
    val queued = colours.of(VolState.QUEUED).border ?: Glass.ice
    val (fill, ink) = when (state) {
        BoxState.HAVE -> colours.have to colours.haveInk
        BoxState.HERE -> colours.have.copy(alpha = 0.35f) to Color.White
        BoxState.QUEUED -> queued to colours.haveInk
        BoxState.NONE -> colours.missingFill to colours.missing.copy(alpha = 1f)
    }
    BoxWithConstraints(
        modifier
            .height(22.dp)
            .clip(shape)
            .background(fill)
            .dashedBorder(if (state == BoxState.NONE) colours.missing else null, 1.5.dp, colours.missingDashed, shape),
        contentAlignment = Alignment.Center,
    ) {
        // ~6 dp a character at 10 sp bold, plus the padding
        fun fits(s: String) = maxWidth >= (6 * s.length + 6).dp
        val label = if (gapRange == null) {
            number?.toString().orEmpty()
        } else {
            stringResource(MR.strings.glass_title_gap, gapRange).takeIf { fits(it) } ?: gapRange.takeIf { fits(it) }.orEmpty()
        }
        Text(
            label,
            color = ink,
            fontSize = if (gapRange != null) 10.sp else 9.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            modifier = Modifier.padding(horizontal = 1.dp),
        )
    }
}
