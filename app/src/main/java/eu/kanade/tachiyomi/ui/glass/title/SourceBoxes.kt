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
import androidx.compose.foundation.layout.Box
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
import eu.kanade.presentation.glass.StatusPalette
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/** A box narrower than this cannot show its number: the bar wraps into two rows. */
private val MIN_BOX = 18.dp

/** A gap box is as wide as this many volume boxes (it carries a label). */
private fun gapWeight(from: Int, to: Int) = (to - from + 1).coerceIn(2, 4).toFloat()

/** A source card's bar: [GlassTitleState.sourceBoxes], numbered, one row (two when the boxes would be too small). */
@Composable
internal fun SourceBoxes(boxes: List<BoxState>) {
    val colours = StatusPalette.current()
    val items = GlassTitleState.sourceBoxes(boxes)
    fun weight(b: SourceBox) = if (b is SourceBox.Gap) gapWeight(b.from, b.to) else 1f
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val total = items.sumOf { weight(it).toDouble() }.toFloat()
        val rows = if (maxWidth / total < MIN_BOX) {
            // two rows split at half the weight
            var acc = 0f
            val half = total / 2
            val (a, b) = items.partition { acc += weight(it); acc - weight(it) < half }
            listOf(a, b)
        } else {
            listOf(items)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            rows.forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    row.forEach { b ->
                        when (b) {
                            is SourceBox.Vol -> SourceBoxCell(b.number, b.state, colours, Modifier.weight(1f))
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
 * One box: [number] inside, or for a merged run its [gapRange] ("20–24 missing" when it is wide enough, else just the
 * range). A missing box is dashed (or solid) per the colour set.
 */
@Composable
internal fun SourceBoxCell(number: Int?, state: BoxState, colours: StatusColours, modifier: Modifier, gapRange: String? = null) {
    val shape = RoundedCornerShape(3.dp)
    val (fill, ink) = when (state) {
        BoxState.HAVE -> colours.have to colours.haveInk
        BoxState.HERE -> colours.have.copy(alpha = 0.35f) to Color.White
        BoxState.QUEUED -> Glass.ice to Color.Black
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
        val label = when {
            gapRange == null -> number?.toString().orEmpty()
            maxWidth >= 84.dp -> stringResource(MR.strings.glass_title_gap, gapRange)
            else -> gapRange
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
