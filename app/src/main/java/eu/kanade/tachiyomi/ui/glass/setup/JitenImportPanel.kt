package eu.kanade.tachiyomi.ui.glass.setup

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chimahon.lang.JitenKnown
import chimahon.ocr.KumapieColours
import chimahon.srs.Cards
import chimahon.srs.JitenState
import chimahon.srs.KnownLayers
import chimahon.srs.importJiten
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassChip
import eu.kanade.presentation.glass.Pill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import java.text.NumberFormat
import kotlin.math.roundToInt

private val STOPS = listOf(1_000, 2_000, 5_000, 10_000, 20_000, 50_000)

private fun n(v: Int) = NumberFormat.getIntegerInstance().format(v)

/**
 * Jiten import (setup mockup B): big count + stop slider, the word sets as chips, Import, a status line. In Glass tokens and shared
 * components. [withTally] adds the stacked bar of what adds up (Jiten, kumapie, marks).
 */
@Composable
internal fun JitenImportPanel(withTally: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var last by remember { mutableStateOf<JitenState?>(null) }
    var layers by remember { mutableStateOf<KnownLayers?>(null) }
    var stop by remember { mutableIntStateOf(STOPS.indexOf(10_000)) }
    var sets by remember { mutableStateOf(JitenKnown.SETS.keys.toSet()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun reload() = withContext(Dispatchers.IO) {
        val srs = Cards.store(context)
        last = srs.jitenImport()
        layers = srs.knownLayers()
    }
    LaunchedEffect(Unit) {
        reload()
        last?.takeIf { it.time > 0 }?.let { st ->
            stop = STOPS.indices.minBy { kotlin.math.abs(STOPS[it] - st.top) }
            sets = st.sets.toSet()
        }
    }
    val top = STOPS[stop]

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(n(top), fontSize = Glass.hero, lineHeight = 44.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(MR.strings.jiten_count_label), color = Glass.secondary, fontSize = Glass.body, modifier = Modifier.padding(bottom = 6.dp))
        }
        StopSlider(stop, STOPS.size, { stop = it }, enabled = !busy)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            STOPS.forEach { Text("${it / 1000}k", color = Glass.secondary, fontSize = Glass.caption) }
        }
        Text(
            stringResource(MR.strings.jiten_also_known),
            Modifier.padding(top = 6.dp),
            color = Glass.secondary,
            fontSize = Glass.caption,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 2.sp,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            JitenKnown.SETS.forEach { (slug, name) ->
                val on = slug in sets
                GlassChip(
                    name,
                    selected = on,
                    onClick = { if (!busy) sets = if (on) sets - slug else sets + slug },
                    leading = if (on) "check" else null,
                )
            }
        }
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Pill(
                stringResource(MR.strings.jiten_import),
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            val picked = JitenKnown.SETS.keys.filter { it in sets }
                            withContext(Dispatchers.IO) { importJiten(Cards.store(context), top, picked) }
                            KumapieColours.clear()
                            reload()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.message ?: e.toString()
                        }
                        busy = false
                    }
                },
                primary = true,
                icon = "download",
                enabled = !busy,
            )
            val st = last
            val line = when {
                busy -> stringResource(MR.strings.jiten_downloading)
                error != null -> stringResource(MR.strings.jiten_failed, error!!)
                st == null || st.time == 0L -> stringResource(MR.strings.jiten_never)
                else -> stringResource(
                    MR.strings.jiten_last,
                    DateUtils.getRelativeTimeSpanString(st.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString().lowercase(),
                    n(st.top),
                    st.sets.size,
                )
            }
            Text(line, color = if (error != null) Glass.red else Glass.secondary, fontSize = Glass.body, modifier = Modifier.weight(1f))
        }
        if (withTally) layers?.let { Tally(it) }
    }
}

/**
 * The stop picker of mockup B: 4dp track (ice up to the thumb), a tick per stop, a 24dp white thumb; tap or drag snaps to a stop.
 * The row is 48dp high so it is easy to hit.
 */
@Composable
private fun StopSlider(index: Int, count: Int, onChange: (Int) -> Unit, enabled: Boolean) {
    var width by remember { mutableIntStateOf(0) }
    fun at(x: Float) = (x / width.coerceAtLeast(1) * (count - 1)).roundToInt().coerceIn(0, count - 1)
    val thumb = with(androidx.compose.ui.platform.LocalDensity.current) { 24.dp.toPx() }
    val fraction = index / (count - 1f)
    Box(
        Modifier
            .fillMaxWidth()
            .height(Glass.button)
            .onSizeChanged { width = it.width }
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(index.toFloat(), 0f..(count - 1).toFloat(), count - 2)
                setProgress { v -> onChange(v.roundToInt().coerceIn(0, count - 1)); true }
            }
            .pointerInput(enabled, count, width) { if (enabled) detectTapGestures { onChange(at(it.x)) } }
            .pointerInput(enabled, count, width) { if (enabled) detectHorizontalDragGestures { change, _ -> onChange(at(change.position.x)) } },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Glass.line))
        Box(Modifier.fillMaxWidth(fraction).height(4.dp).clip(CircleShape).background(Glass.ice))
        for (i in 0 until count) {
            if (i == index) continue
            Box(
                Modifier
                    .offset { IntOffset((width * i / (count - 1f) - 3.dp.toPx()).roundToInt(), 0) }
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (i < index) Glass.page else Color.White.copy(alpha = 0.45f)),
            )
        }
        Box(
            Modifier
                .offset { IntOffset((width * fraction - thumb / 2).roundToInt(), 0) }
                .size(24.dp)
                .clip(CircleShape)
                .background(if (enabled) Color.White else Glass.secondary),
        )
    }
}

/** Number, stacked bar and one line per layer; KnownLayers counts each word once (kumapie, then Jiten, then marks, then kuma3 / AnkiDroid), so the lines add up to the total. */
@Composable
private fun Tally(l: KnownLayers) {
    val rows = listOf(
        Triple(Glass.ice, stringResource(MR.strings.jiten_layer_jiten), l.jiten),
        Triple(Color.White, stringResource(MR.strings.jiten_layer_kumapie), l.kumapie),
        Triple(Glass.green, stringResource(MR.strings.jiten_layer_marks), l.marks),
        Triple(Glass.secondary, stringResource(MR.strings.jiten_layer_anki), l.anki),
    )
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(n(l.total), fontSize = Glass.heading, fontWeight = FontWeight.Bold)
            Text(stringResource(MR.strings.jiten_known_sub), color = Glass.secondary, fontSize = Glass.body, modifier = Modifier.padding(bottom = 2.dp))
        }
        Row(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(Glass.line)) {
            rows.forEach { (c, _, v) -> if (v > 0) Box(Modifier.weight(v.toFloat()).height(8.dp).background(c)) }
        }
        rows.forEach { (c, name, v) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(c))
                Text(name, Modifier.weight(1f), color = Glass.secondary, fontSize = Glass.body)
                Text("+ " + n(v), fontSize = Glass.body, fontWeight = FontWeight.SemiBold)
            }
        }
        Row {
            Text(stringResource(MR.strings.jiten_layer_total), Modifier.weight(1f), fontSize = Glass.body, fontWeight = FontWeight.SemiBold)
            Text(n(l.total), fontSize = Glass.body, fontWeight = FontWeight.SemiBold)
        }
    }
}
