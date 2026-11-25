package eu.kanade.tachiyomi.ui.glass.setup

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chimahon.lang.JitenKnown
import chimahon.ocr.KumapieColours
import chimahon.srs.Cards
import chimahon.srs.JitenState
import chimahon.srs.KnownLayers
import chimahon.srs.importJiten
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import java.io.IOException
import java.text.NumberFormat

private val STOPS = listOf(1_000, 2_000, 5_000, 10_000, 20_000, 50_000)
private val Blue = Color(0xFF6EA8FF)
private val Orange = Color(0xFFC98B4E)
private val Green = Color(0xFF5BBD7B)

private fun n(v: Int) = NumberFormat.getIntegerInstance().format(v)

/**
 * Jiten import (mockup A "Slider + tally"): big count + slider, the word sets as switches, Import, a status line.
 * Colours come from the surrounding content colour, so it sits in the dark setup panel and in the Settings page alike.
 * [withTally] adds the stacked bar of what adds up (Jiten, kumapie, marks).
 */
@Composable
internal fun JitenImportPanel(withTally: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dim = LocalContentColor.current.copy(alpha = 0.62f)
    var last by remember { mutableStateOf<JitenState?>(null) }
    var layers by remember { mutableStateOf<KnownLayers?>(null) }
    var stop by remember { mutableStateOf(STOPS.indexOf(10_000).toFloat()) }
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
            stop = STOPS.indices.minBy { kotlin.math.abs(STOPS[it] - st.top) }.toFloat()
            sets = st.sets.toSet()
        }
    }
    val top = STOPS[stop.toInt()]

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(n(top), fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(MR.strings.jiten_count_label), color = dim, fontSize = 14.sp, modifier = Modifier.padding(bottom = 6.dp))
        }
        Slider(value = stop, onValueChange = { stop = Math.round(it).toFloat() }, valueRange = 0f..(STOPS.size - 1).toFloat(), steps = STOPS.size - 2, enabled = !busy)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            STOPS.forEach { Text("${it / 1000}k", color = dim, fontSize = 12.sp) }
        }
        Text(stringResource(MR.strings.jiten_also_known), color = dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            JitenKnown.SETS.forEach { (slug, name) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(slug in sets, { sets = if (it) sets + slug else sets - slug }, enabled = !busy)
                    Text(name, fontSize = 14.sp)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Button(
                enabled = !busy,
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
            ) { Text(stringResource(MR.strings.jiten_import)) }
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
            Text(line, color = if (error != null) Color(0xFFFF8A80) else dim, fontSize = 13.sp, modifier = Modifier.weight(1f))
        }
        if (withTally) layers?.let { Tally(it, dim) }
    }
}

/** Number, stacked bar and one line per layer; KnownLayers counts each word once (kumapie, then Jiten, then marks), so the lines add up to the total. */
@Composable
private fun Tally(l: KnownLayers, dim: Color) {
    val rows = listOf(
        Triple(Blue, stringResource(MR.strings.jiten_layer_jiten), l.jiten),
        Triple(Orange, stringResource(MR.strings.jiten_layer_kumapie), l.kumapie),
        Triple(Green, stringResource(MR.strings.jiten_layer_marks), l.marks),
    )
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(n(l.total), fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(MR.strings.jiten_known_sub), color = dim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
        }
        Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(dim.copy(alpha = 0.2f))) {
            rows.forEach { (c, _, v) -> if (v > 0) Box(Modifier.weight(v.toFloat()).height(10.dp).background(c)) }
        }
        rows.forEach { (c, name, v) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(c))
                Text(name, Modifier.weight(1f), color = dim, fontSize = 13.sp)
                Text("+ " + n(v), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Row {
            Text(stringResource(MR.strings.jiten_layer_total), Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(n(l.total), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
