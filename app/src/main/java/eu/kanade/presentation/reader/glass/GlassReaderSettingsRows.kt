package eu.kanade.presentation.reader.glass

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassChip
import eu.kanade.presentation.glass.GlassToggle
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import tachiyomi.core.common.preference.Preference
import tachiyomi.presentation.core.util.collectAsState

// The Glass rows of the settings panel (mockup A): captions, pills, a segmented pill, toggles, sliders, mode tiles.

/** 12sp uppercase caption above a group. */
@Composable
internal fun GlassCap(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier.padding(top = 14.dp, bottom = 8.dp),
        color = Glass.secondary,
        fontSize = Glass.caption,
        letterSpacing = 1.2.sp,
    )
}

/** Wrapping pills (selected = white); [options] are (label, value) pairs. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> GlassPills(options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (label, value) -> GlassChip(label, selected = value == selected, onClick = { onSelect(value) }) }
    }
}

/** [GlassPills] bound to a preference. */
@Composable
internal fun <T> GlassPrefPills(pref: Preference<T>, options: List<Pair<String, T>>) {
    val value by pref.collectAsState()
    GlassPills(options, value) { pref.set(it) }
}

/** The segmented pill (Single / Double / Auto). */
@Composable
internal fun <T> GlassSegmented(options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(CircleShape).background(Glass.page).border(1.dp, Glass.line, CircleShape).padding(3.dp),
    ) {
        options.forEach { (label, value) ->
            val on = value == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(CircleShape)
                    .then(if (on) Modifier.background(Color.White) else Modifier)
                    .clickable { onSelect(value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on) Color.Black else Color.White, fontSize = Glass.body, maxLines = 1)
            }
        }
    }
}

@Composable
internal fun <T> GlassPrefSegmented(pref: Preference<T>, options: List<Pair<String, T>>) {
    val value by pref.collectAsState()
    GlassSegmented(options, value) { pref.set(it) }
}

/** Label (+ optional 12sp hint) and a Glass toggle on the right. */
@Composable
internal fun GlassToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, hint: String? = null) {
    Row(Modifier.fillMaxWidth().height(if (hint == null) 44.dp else 56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 15.sp)
            hint?.let { Text(it, color = Glass.secondary, fontSize = Glass.caption) }
        }
        GlassToggle(checked, onChange)
    }
}

@Composable
internal fun GlassPrefToggle(label: String, pref: Preference<Boolean>, hint: String? = null) {
    val checked by pref.collectAsState()
    GlassToggleRow(label, checked, { pref.set(it) }, hint)
}

/** label | slider | value, as in the mockup's filter rows. */
@Composable
internal fun GlassSliderRow(label: String, value: Int, range: IntRange, valueText: String, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.width(92.dp), fontSize = Glass.body, maxLines = 2)
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Glass.line,
            ),
        )
        Text(valueText, Modifier.width(44.dp), fontSize = Glass.caption, color = Glass.secondary, textAlign = TextAlign.End, maxLines = 1)
    }
}

@Composable
internal fun GlassDivider() {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).height(1.dp).background(Glass.line))
}

/** The 3x2 grid of reading-mode tiles (selected = white). */
@Composable
internal fun GlassModeTiles(selected: ReadingMode, onSelect: (ReadingMode) -> Unit, label: @Composable (ReadingMode) -> String) {
    val order = listOf(
        ReadingMode.DEFAULT, ReadingMode.RIGHT_TO_LEFT, ReadingMode.LEFT_TO_RIGHT,
        ReadingMode.VERTICAL, ReadingMode.WEBTOON, ReadingMode.CONTINUOUS_VERTICAL,
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        order.chunked(3).forEach { rowModes ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowModes.forEach { mode ->
                    val on = mode == selected
                    val ink = if (on) Color.Black else Color.White
                    Column(
                        Modifier
                            .weight(1f)
                            .height(78.dp)
                            .clip(RoundedCornerShape(Glass.r12))
                            .then(if (on) Modifier.background(Color.White) else Modifier.border(1.dp, Glass.line, RoundedCornerShape(Glass.r12)))
                            .clickable { onSelect(mode) }
                            .padding(horizontal = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        ModeGlyph(mode, ink)
                        Text(label(mode), color = ink, fontSize = 12.sp, maxLines = 2, textAlign = TextAlign.Center, lineHeight = 14.sp)
                    }
                }
            }
        }
    }
}

/** A tiny line drawing of the mode: pages (one, two, a strip) and the reading direction arrow. */
@Composable
private fun ModeGlyph(mode: ReadingMode, ink: Color) {
    Canvas(Modifier.size(56.dp, 34.dp)) {
        val stroke = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round)
        fun page(x: Float, y: Float, w: Float, h: Float) = drawRoundRect(ink, Offset(x.dp.toPx(), y.dp.toPx()), Size(w.dp.toPx(), h.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()), stroke)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(ink, Offset(x1.dp.toPx(), y1.dp.toPx()), Offset(x2.dp.toPx(), y2.dp.toPx()), 1.6.dp.toPx(), StrokeCap.Round)
        when (mode) {
            ReadingMode.RIGHT_TO_LEFT, ReadingMode.LEFT_TO_RIGHT -> {
                page(10f, 2f, 17f, 24f); page(30f, 2f, 17f, 24f)
                val rtl = mode == ReadingMode.RIGHT_TO_LEFT
                val (a, b) = if (rtl) 46f to 12f else 12f to 46f
                line(a, 31f, b, 31f)
                val d = if (rtl) 3f else -3f
                line(b, 31f, b + d, 28.5f); line(b, 31f, b + d, 33.5f)
            }
            ReadingMode.VERTICAL -> {
                page(18f, 1f, 20f, 30f)
                line(46f, 6f, 46f, 28f); line(46f, 28f, 43.5f, 25f); line(46f, 28f, 48.5f, 25f)
            }
            ReadingMode.WEBTOON -> {
                page(18f, 0f, 20f, 34f)
                line(23f, 9f, 33f, 9f); line(23f, 17f, 33f, 17f); line(23f, 25f, 33f, 25f)
            }
            ReadingMode.CONTINUOUS_VERTICAL -> {
                page(18f, 0f, 20f, 15f); page(18f, 19f, 20f, 15f)
            }
            else -> page(18f, 2f, 20f, 30f)
        }
    }
}
