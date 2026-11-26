package eu.kanade.tachiyomi.ui.glass.popup

import android.os.SystemClock
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chimahon.LookupResult
import chimahon.ocr.KumapieColours
import chimahon.srs.Cards
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassChip
import eu.kanade.presentation.glass.GlassIcon
import eu.kanade.presentation.glass.GlassRoundButton
import eu.kanade.presentation.glass.Pill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** What the popup knows of the looked-up word: its colour state, whether it is marked known, and (when kumahon has its card) the card with its answer intervals. */
internal class PopupWordState(val state: String, val marked: Boolean, val preview: Cards.Preview?)

private fun readWordState(context: android.content.Context, word: String, reading: String, matched: String): PopupWordState {
    val body = JSONObject().put("action", "state").put("word", word).put("reading", reading).put("text", matched)
    val answer = runCatching { JSONObject(KumapieColours.callOwn(context, body.toString())) }.getOrNull()
    val state = PopupLogic.stateName(answer?.optString("state"))
    val preview = if (state == "u") null else runCatching { Cards.preview(context, word, reading) }.getOrNull()
    return PopupWordState(state, answer?.optBoolean("marked") ?: false, preview)
}

private fun stateColour(state: String?): Color = when (state) {
    "u" -> Glass.red
    "d" -> Glass.green
    "b" -> Glass.blue
    else -> Color.White
}

/**
 * Reader lookup popup, option D "Thumb bar": headword (coloured by the word's state, furigana), pitch, status line, dictionary
 * jump chips, then [content] (the dictionary WebView) and the actions at the bottom. [webView] is told what to do through its
 * DictionaryRenderer (audio, mine, scroll, open the card), so selection, dictionary choice and duplicates act as in the old popup.
 */
@Composable
internal fun GlassLookupCard(
    result: LookupResult?,
    fallbackTitle: String,
    existingExpressions: Set<String>,
    webView: WebView,
    showPitchDiagram: Boolean,
    showPitchNumber: Boolean,
    showPitchText: Boolean,
    audioEnabled: Boolean,
    arrowsEnabled: Boolean,
    ankiEnabled: Boolean,
    phone: Boolean,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val expression = result?.term?.expression.orEmpty()
    val reading = result?.term?.reading.orEmpty()
    val matched = result?.matched.orEmpty()
    val added = expression.isNotEmpty() && expression in existingExpressions

    var tick by remember { mutableIntStateOf(0) }
    var word by remember(expression, reading) { mutableStateOf<PopupWordState?>(null) }
    LaunchedEffect(added) {
        if (added) {
                kotlinx.coroutines.delay(1500) // the own card of a just mined word is made in the background
            tick++
        }
    }
    LaunchedEffect(expression, reading, added, tick) {
        if (expression.isNotEmpty()) word = withContext(Dispatchers.IO) { readWordState(context, expression, reading, matched) }
    }
    // a rating answered in this popup: its label and the token Undo needs
    var answered by remember(expression, reading) { mutableStateOf<Pair<String, String>?>(null) }
    var note by remember(expression, reading) { mutableStateOf<String?>(null) }
    val shownAt = remember(expression, reading) { SystemClock.elapsedRealtime() }

    val state = word?.state
    val hasCard = state != null && state != "u" && (state != "k" || word?.preview != null)
    val bar = if (state == null) PopupLogic.Bar.MINE else PopupLogic.bar(state, hasCard)
    var dictionary by remember(expression) { mutableIntStateOf(0) }

    fun press(which: String) { webView.evaluateJavascript("window.DictionaryRenderer && window.DictionaryRenderer.press('$which');", null) }
    fun step(delta: Int) { webView.evaluateJavascript("window.DictionaryRenderer && window.DictionaryRenderer.step($delta);", null) }
    fun mark() {
        val known = word?.marked != true
        scope.launch {
            val answer = withContext(Dispatchers.IO) {
                KumapieColours.callOwn(context, JSONObject().put("action", "mark").put("word", expression).put("reading", reading).put("text", matched).put("known", known).toString())
            }
            val marked = runCatching { JSONObject(answer).getBoolean("marked") }.getOrNull()
            if (marked != null) word = word?.let { PopupWordState(it.state, marked, it.preview) } ?: PopupWordState("u", marked, null)
            tick++
        }
    }
    fun rate(ease: Int) {
        scope.launch {
            val ms = SystemClock.elapsedRealtime() - shownAt
            val token = withContext(Dispatchers.IO) { PopupRating.ratePopupCard(context, expression, reading, ease, ms) }
            if (token != null) { answered = PopupLogic.RATINGS[ease - 1] to token; note = null; tick++ } else note = "The rating was not saved."
        }
    }
    fun undo() {
        val (_, token) = answered ?: return
        scope.launch {
            val undone = withContext(Dispatchers.IO) { PopupRating.undoPopupCard(context, token) }
            if (undone) { answered = null; tick++ } else note = "Undo did not work."
        }
    }

    Column(Modifier.fillMaxWidth()) {
        // ── header ──
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    val pieces = if (result == null) listOf(fallbackTitle to "") else PopupLogic.furigana(expression.ifEmpty { matched }, reading)
                    for ((text, ruby) in pieces) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(ruby.ifEmpty { " " }, color = Glass.secondary, fontSize = Glass.caption, maxLines = 1)
                            Text(text, color = stateColour(state), fontSize = if (phone) Glass.title else Glass.hero, fontWeight = FontWeight.Bold, maxLines = 1, lineHeight = if (phone) 36.sp else 44.sp)
                        }
                    }
                }
                if (result != null) {
                    PitchRows(reading, result, showPitchDiagram, showPitchNumber, showPitchText)
                    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(stateColour(state)))
                        val p = word?.preview?.card
                        Text(
                            note ?: PopupLogic.statusLine(state ?: "u", p?.created, p?.due, System.currentTimeMillis()),
                            color = Glass.secondary, fontSize = Glass.body, maxLines = 2,
                        )
                    }
                }
            }
            if (hasCard) {
                GlassRoundButton("check", ::mark, outlined = true, description = "Marked known", tint = if (word?.marked == true) Glass.green else null)
                val openable = ankiEnabled && added
                Box(
                    Modifier.size(Glass.button).clip(CircleShape).border(1.dp, Glass.blue, CircleShape).clickable(enabled = openable) { press("open") },
                    contentAlignment = Alignment.Center,
                ) { GlassIcon("cards", size = 22.dp, tint = Glass.blue, contentDescription = "Card") }
            }
            if (audioEnabled && result != null) GlassRoundButton("speaker", { press("audio") }, Modifier.size(56.dp), outlined = true, description = "Play audio", iconSize = 28.dp)
        }

        // ── dictionary jump chips ──
        val names = result?.term?.glossaries?.map { it.dictName }?.distinct().orEmpty()
        if (names.size > 1) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                names.forEachIndexed { i, name ->
                    GlassChip(
                        name, dictionary == i,
                        { dictionary = i; webView.evaluateJavascript("window.DictionaryRenderer && window.DictionaryRenderer.scrollToDictionary(${JSONObject.quote(name)});", null) },
                        Modifier.widthIn(max = 200.dp),
                    )
                }
            }
        } else Box(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Glass.line))
        Box(Modifier.weight(1f)) { content() }

        // ── the bar at the thumb ──
        Box(Modifier.fillMaxWidth().height(1.dp).background(Glass.line))
        Column(Modifier.fillMaxWidth().background(Glass.panel).padding(horizontal = 14.dp, vertical = 12.dp)) {
            when {
                result == null -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { Arrows(arrowsEnabled, ::step) }
                bar == PopupLogic.Bar.MINE -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val marked = word?.marked == true
                    Pill(if (marked) "Known" else "Mark known", ::mark, Modifier.weight(1f), primary = !marked, icon = "check", centered = true)
                    if (phone) GlassRoundButton("plus", { press("mine") }, outlined = true, description = "Mine")
                    else Pill(if (added) "Mined" else "Mine", { press("mine") }, icon = if (added) "check" else "plus")
                    Arrows(arrowsEnabled, ::step)
                }
                else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassIcon("cards", size = 16.dp, tint = if (bar == PopupLogic.Bar.RATE_DUE) Glass.green else Glass.blue)
                        val done = answered
                        Text(if (done != null) "Answered: ${done.first}" else PopupLogic.barLine(bar), color = Glass.secondary, fontSize = Glass.caption, modifier = Modifier.weight(1f))
                        if (done != null) Pill("Undo", ::undo, small = true, icon = "undo")
                        Arrows(arrowsEnabled, ::step, small = true)
                    }
                    if (answered == null) Row(Modifier.fillMaxWidth().height(60.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (i in 1..4) RateButton(PopupLogic.RATINGS[i - 1], word?.preview?.labels?.getOrNull(i - 1), i == 3, i == 1, Modifier.weight(1f)) { rate(i) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Arrows(enabled: Boolean, step: (Int) -> Unit, small: Boolean = false) {
    if (!enabled) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val m = if (small) Modifier.size(36.dp) else Modifier
        GlassRoundButton("arrowUp", { step(-1) }, m, outlined = true, description = "Previous")
        GlassRoundButton("arrowDown", { step(1) }, m, outlined = true, description = "Next")
    }
}

/** One of Again / Hard / Good / Easy with its interval (when kumahon has the card's FSRS state); Good is the white one. */
@Composable
private fun RateButton(label: String, interval: String?, good: Boolean, again: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Glass.r16)
    Column(
        modifier.fillMaxWidth().height(60.dp).clip(shape)
            .then(if (good) Modifier.background(Color.White) else Modifier.border(1.dp, Glass.line, shape))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        if (interval != null) Text(interval, color = if (good) Color.Black else Color.White, fontSize = Glass.heading, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(
            label, fontSize = if (interval != null) Glass.body else Glass.label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Clip,
            color = if (good) Color.Black.copy(alpha = 0.6f) else if (again) Glass.red else Glass.secondary,
        )
    }
}

/** The pitch under the headword: the reading with the line over its high morae and the drop, [n] and the pattern name, per distinct downstep number (the three settings decide what shows). */
@Composable
private fun PitchRows(reading: String, result: LookupResult, diagram: Boolean, number: Boolean, text: Boolean) {
    if (reading.isEmpty() || !(diagram || number || text)) return
    val positions = PopupLogic.positions(result.term.pitches.map { it.pitchPositions.toList() }).take(3)
    if (positions.isEmpty()) return
    for (n in positions) {
        val pitch = PopupLogic.pitch(reading, n) ?: continue
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row {
                pitch.morae.forEachIndexed { i, mora ->
                    val high = diagram && pitch.high[i]
                    val drop = diagram && pitch.drop == i
                    Text(
                        mora, color = Color.White, fontSize = Glass.label, maxLines = 1,
                        modifier = Modifier
                            .drawBehind {
                                val w = 2.dp.toPx()
                                if (high) drawLine(Color.White.copy(alpha = 0.85f), Offset(0f, w / 2), Offset(size.width, w / 2), w)
                                if (drop) drawLine(Color.White.copy(alpha = 0.85f), Offset(size.width - w / 2, 0f), Offset(size.width - w / 2, size.height), w)
                            }
                            .padding(start = 1.dp, end = if (drop) 3.dp else 1.dp, top = 3.dp),
                    )
                }
            }
            if (number) Text("[$n]", color = Color.White.copy(alpha = 0.85f), fontSize = Glass.caption, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(Glass.r8)).background(Color.White.copy(alpha = 0.12f)).padding(horizontal = 7.dp, vertical = 2.dp))
            if (text) Text(pitch.name, color = Glass.secondary, fontSize = Glass.caption)
        }
    }
}

/** The tapped word's ice outline and the short connector line to the card (all in px of the reader's window, as the card's offset). */
@Composable
internal fun GlassAnchorOutline(anchorX: Float, anchorY: Float, anchorW: Float, anchorH: Float, cardX: Float, cardY: Float, cardW: Float, cardH: Float) {
    val d = androidx.compose.ui.platform.LocalDensity.current
    with(d) {
        val pad = 3.dp.toPx()
        val left = anchorX - pad
        val top = anchorY - pad
        val right = anchorX + anchorW + pad
        val bottom = anchorY + anchorH + pad
        Box(
            Modifier.offset(left.toDp(), top.toDp()).size((right - left).toDp(), (bottom - top).toDp())
                .border(2.dp, Glass.ice, RoundedCornerShape(Glass.r10)),
        )
        val t = 2.dp.toPx()
        val cx = (anchorX + anchorW / 2f)
        val cy = (anchorY + anchorH / 2f)
        val edge = 8.dp.toPx()
        val line: androidx.compose.ui.geometry.Rect? = when {
            cardX >= right -> { val y = cy.coerceIn(cardY + edge, cardY + cardH - edge); androidx.compose.ui.geometry.Rect(right, y - t / 2, cardX, y + t / 2) }
            cardX + cardW <= left -> { val y = cy.coerceIn(cardY + edge, cardY + cardH - edge); androidx.compose.ui.geometry.Rect(cardX + cardW, y - t / 2, left, y + t / 2) }
            cardY >= bottom -> { val x = cx.coerceIn(cardX + edge, cardX + cardW - edge); androidx.compose.ui.geometry.Rect(x - t / 2, bottom, x + t / 2, cardY) }
            cardY + cardH <= top -> { val x = cx.coerceIn(cardX + edge, cardX + cardW - edge); androidx.compose.ui.geometry.Rect(x - t / 2, cardY + cardH, x + t / 2, top) }
            else -> null
        }
        if (line != null && line.width > 0f && line.height > 0f) {
            Box(Modifier.offset(line.left.toDp(), line.top.toDp()).size(line.width.toDp(), line.height.toDp()).background(Glass.ice))
        }
    }
}
