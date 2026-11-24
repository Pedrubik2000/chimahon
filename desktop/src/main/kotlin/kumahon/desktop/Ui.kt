package kumahon.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ProvideTextStyle
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chimahon.srs.HubScan
import chimahon.srs.PeerSync
import eu.kanade.tachiyomi.ui.glass.review.ReviewLogic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The Android Glass palette, so the PC looks like the tablet. */
private object K {
    val page = Color(0xFF07090D)
    val panel = Color(0xFF14171D)
    val line = Color.White.copy(alpha = 0.18f)
    val secondary = Color.White.copy(alpha = 0.62f)
    val blue = Color(0xFF6FA8FF)
    val red = Color(0xFFFF6B6B)
    val green = Color(0xFF7CC47F)
    val ice = Color(0xFF9CC9FF)
}

/** Windows' Yu Gothic for the Japanese glyphs; another machine falls back to the system font. */
private val jp: FontFamily = run {
    val r = File("C:/Windows/Fonts/YuGothR.ttc")
    val b = File("C:/Windows/Fonts/YuGothB.ttc")
    if (r.isFile && b.isFile) FontFamily(Font(r, FontWeight.Normal), Font(b, FontWeight.Bold), Font(b, FontWeight.SemiBold)) else FontFamily.Default
}

enum class Tab { Review, Settings }

@Composable
fun App(review: ReviewModel, sync: PeerSync, syncNow: suspend () -> Boolean, initialTab: Tab = Tab.Review, scan: () -> List<String> = { HubScan.find() }) {
    var tab by remember { mutableStateOf(initialTab) }
    ProvideTextStyle(TextStyle(fontFamily = jp, color = Color.White, fontSize = 16.sp)) {
        Row(Modifier.fillMaxSize().background(K.page)) {
            Rail(tab, review.session.queue.size) { tab = it }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (tab) {
                    Tab.Review -> ReviewScreen(review)
                    Tab.Settings -> SettingsScreen(sync, syncNow, scan)
                }
            }
        }
    }
}

@Composable
private fun Rail(tab: Tab, due: Int, pick: (Tab) -> Unit) {
    Column(Modifier.width(200.dp).fillMaxHeight().background(Color(0xFF0B0D12)).border(BorderStroke(1.dp, K.line)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("kumahon", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 20.dp))
        for ((t, label) in listOf(Tab.Review to "Review", Tab.Settings to "Settings")) {
            val on = t == tab
            val shape = RoundedCornerShape(14.dp)
            Row(
                Modifier.fillMaxWidth().height(48.dp).clip(shape)
                    .then(if (on) Modifier.background(Color.White) else Modifier.border(1.dp, K.line, shape))
                    .clickable { pick(t) }.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, color = if (on) Color.Black else Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (t == Tab.Review && due > 0) Text("$due", color = if (on) Color.Black.copy(alpha = 0.6f) else K.secondary, fontSize = 14.sp)
            }
        }
    }
}

// ---- Review ----

private val answerKeys = listOf(Key.One, Key.Two, Key.Three, Key.Four)

@Composable
fun ReviewScreen(m: ReviewModel) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(
        Modifier.fillMaxSize().focusRequester(focus).focusable().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when {
                e.isCtrlPressed && e.key == Key.Z -> { m.undo(); true }
                e.key == Key.Spacebar && !m.shown -> { m.show(); true }
                m.shown && e.key in answerKeys -> { m.answer(answerKeys.indexOf(e.key) + 1); true }
                else -> false
            }
        }.padding(32.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("Review", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            val s = m.session
            if (s.queue.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("${s.newCount} new", color = K.blue, fontWeight = FontWeight.SemiBold)
                    Text("${s.againCount} again", color = K.red, fontWeight = FontWeight.SemiBold)
                    Text("${s.dueCount} due", color = K.green, fontWeight = FontWeight.SemiBold)
                }
            }
            Box(Modifier.weight(1f))
            PillButton("Undo  Ctrl+Z", enabled = m.canUndo) { m.undo() }
        }
        val card = m.card
        if (card == null) {
            if (!m.loading) EmptyReview(m, Modifier.fillMaxWidth().weight(1f).padding(top = 24.dp)) else Box(Modifier.weight(1f))
        } else {
            Box(Modifier.fillMaxWidth().weight(1f).padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                CardPanel(m, card.word, card.sentence, card.meaning)
            }
            if (!m.shown) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val shape = RoundedCornerShape(16.dp)
                    Box(Modifier.width(420.dp).height(72.dp).clip(shape).background(Color.White).clickable { m.show() }, contentAlignment = Alignment.Center) {
                        Text("Show answer   Space", color = Color.Black, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)) {
                    for (r in 1..4) AnswerButton(m, r)
                }
            }
        }
    }
}

@Composable
private fun AnswerButton(m: ReviewModel, rating: Int) {
    val good = rating == 3
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.width(180.dp).height(84.dp).clip(shape)
            .then(if (good) Modifier.background(Color.White) else Modifier.background(K.panel).border(1.dp, K.line, shape))
            .clickable { m.answer(rating) },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(m.intervals.getOrNull(rating - 1).orEmpty().ifEmpty { " " }, color = if (good) Color.Black else Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(
            "${listOf("Again", "Hard", "Good", "Easy")[rating - 1]}   $rating",
            color = when { good -> Color.Black.copy(alpha = 0.6f); rating == 1 -> K.red; else -> K.secondary },
            fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun CardPanel(m: ReviewModel, wordRaw: String, sentenceRaw: String, meaningRaw: String) {
    val word = remember(wordRaw) { ReviewLogic.parseWord(wordRaw) }
    val face = m.faces.firstOrNull() ?: Face("", ReviewLogic.cardSentence(sentenceRaw))
    val meaning = remember(meaningRaw) { ReviewLogic.plainText(meaningRaw) }
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.widthIn(max = 960.dp).fillMaxWidth().fillMaxHeight().clip(shape).background(K.panel).border(1.dp, K.line, shape)
            .clickable(enabled = !m.shown) { m.show() }.padding(36.dp),
    ) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            if (face.where.isNotEmpty()) Text(face.where, color = K.secondary, fontSize = 15.sp, maxLines = 2)
            if (face.sentence.isBlank()) {
                Text(word.spelling, fontSize = 72.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            } else {
                Text(marked(face.sentence, word.spelling), fontSize = 34.sp, lineHeight = 50.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
            }
        }
        if (m.shown) {
            Box(Modifier.padding(vertical = 18.dp).fillMaxWidth().height(1.dp).background(K.line))
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                if (word.reading.isNotEmpty()) Text(word.reading, color = K.secondary, fontSize = 18.sp)
                if (face.sentence.isNotBlank()) Text(word.spelling, fontSize = 52.sp, fontWeight = FontWeight.Bold)
                if (meaning.isNotEmpty()) Text(meaning, fontSize = 20.sp, lineHeight = 30.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

private fun marked(text: String, word: String): AnnotatedString = buildAnnotatedString {
    for ((part, hit) in ReviewLogic.segments(text, word)) {
        if (hit) withStyle(SpanStyle(background = K.green.copy(alpha = 0.3f))) { append(part) } else append(part)
    }
}

@Composable
private fun EmptyReview(m: ReviewModel, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxWidth().drawBehind {
                drawRoundRect(Color.White.copy(alpha = 0.3f), cornerRadius = CornerRadius(16.dp.toPx()), style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f))))
            }.padding(36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("No cards due.", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("Cards you mine on this PC appear here; reviews of the same word on the tablet count too.", color = K.secondary, fontSize = 17.sp, lineHeight = 26.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            m.nextDue?.let { Text(ReviewLogic.nextLabel(it, System.currentTimeMillis()), color = K.ice, fontSize = 16.sp) }
        }
    }
}

// ---- Settings ----

@Composable
fun SettingsScreen(sync: PeerSync, syncNow: suspend () -> Boolean, scan: () -> List<String>) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(sync.status()) }
    var hub by remember { mutableStateOf(sync.hub()) }
    var found by remember { mutableStateOf<String?>(null) }
    var hits by remember { mutableStateOf(emptyList<String>()) }
    var pairNote by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun saveHub(v: String) { scope.launch(Dispatchers.IO) { sync.setHub(v) } } // file write: off the UI thread
    fun work(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch { try { block() } finally { busy = false; status = sync.status() } }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("Settings", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        val shape = RoundedCornerShape(16.dp)
        Column(Modifier.widthIn(max = 820.dp).fillMaxWidth().clip(shape).background(K.panel).border(1.dp, K.line, shape).padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("kumapie", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(if (busy) "Working..." else status.replace("then tap here again", "then press Pair again"), color = K.secondary, fontSize = 16.sp, lineHeight = 24.sp)
            Text("Hub address", color = K.secondary, fontSize = 14.sp)
            val fieldShape = RoundedCornerShape(12.dp)
            BasicTextField(
                hub, { hub = it }, singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 18.sp, fontFamily = jp), cursorBrush = SolidColor(Color.White),
                modifier = Modifier.fillMaxWidth().height(52.dp).onFocusChanged { if (!it.isFocused) saveHub(hub) }
                    .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.Enter) { saveHub(hub); true } else false }.clip(fieldShape).background(K.page).border(1.dp, K.line, fieldShape),
                decorationBox = { inner -> Box(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) { inner() } },
            )
            PillButton("Find kumapie on this network", enabled = !busy) {
                work {
                    found = "Looking on this network..."
                    val r = withContext(Dispatchers.IO) { scan() }
                    hits = r
                    found = if (r.isEmpty()) "Nothing answered on port ${HubScan.PORT}. Open kumapie on the tablet or phone (same Wi-Fi) with \"answer other devices\" on."
                    else "Found ${r.size}: ${r.joinToString()}"
                    if (r.size == 1) { hub = r[0]; saveHub(r[0]) }
                }
            }
            found?.let { Text(it, color = K.ice, fontSize = 15.sp, lineHeight = 22.sp) }
            if (hits.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (h in hits) PillButton("Use $h") { hub = h; saveHub(h) }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(K.line))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(if (sync.paired()) "Paired" else "Pair with kumapie", primary = !sync.paired(), enabled = !busy && !sync.paired()) {
                    work {
                        pairNote = when (val s = withContext(Dispatchers.IO) { sync.setHub(hub); sync.pair() }) {
                            is PeerSync.PairState.Waiting -> "Code ${s.code}. On kumapie tap Allow ${sync.deviceName()}, then press Pair again."
                            is PeerSync.PairState.Paired -> "Paired with ${s.name}."
                            is PeerSync.PairState.Failed -> null
                        }
                    }
                }
                PillButton("Sync now", primary = sync.paired(), enabled = !busy && sync.paired()) { work { withContext(Dispatchers.IO) { sync.setHub(hub) }; syncNow() } }
            }
            pairNote?.let { Text(it, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp) }
        }
    }
}

@Composable
private fun PillButton(label: String, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(999.dp)
    Box(
        Modifier.height(44.dp).alpha(if (enabled) 1f else 0.4f).clip(shape)
            .then(if (primary) Modifier.background(Color.White) else Modifier.background(K.panel).border(1.dp, K.line, shape))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (primary) Color.Black else Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
}
