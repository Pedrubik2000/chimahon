package eu.kanade.presentation.reader.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chimahon.DictionaryPaths
import chimahon.DictionaryRepository
import chimahon.LookupResult
import chimahon.MediaInfo
import chimahon.anki.AnkiCardCreator
import chimahon.anki.AnkiProfile
import chimahon.anki.AnkiResult
import chimahon.ocr.KumapieColours
import chimahon.util.ImageEncoder
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassIcon
import eu.kanade.tachiyomi.ui.dictionary.orderLookupResultsForDisplay
import eu.kanade.tachiyomi.ui.reader.viewer.OcrTextBlock
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.extractOcrLookupString
import eu.kanade.tachiyomi.ui.reader.viewer.fullText
import eu.kanade.tachiyomi.ui.reader.viewer.orderedDisplayText
import eu.kanade.tachiyomi.ui.reader.viewer.toOrderedOffset
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/** One word of the page that kumapie colours: [status] "u" new, "d" due, "b" mined; [start] indexes the block's fullText. */
class GlassPageWord(
    val text: String,
    val status: String,
    val block: OcrTextBlock,
    val start: Int,
    val view: ReaderPageImageView,
)

/** The pages on screen now: their new and due words in reading order, and how many mined ones. */
class GlassPageWordsData(val words: List<GlassPageWord>, val mined: Int)

private val NEW = Color(0xFFFF6B6B)
private val DUE = Color(0xFF7CC47F)
private val MINED = Color(0xFF6FA8FF)

/** The page views of [viewer] that are on screen (one in a pager, one per visible page in webtoon), top first. */
internal fun visiblePageViews(viewer: Viewer?): List<ReaderPageImageView> {
    val root = viewer?.getView() ?: return emptyList()
    val out = mutableListOf<ReaderPageImageView>()
    val rect = Rect()
    fun walk(v: View) {
        if (v is ReaderPageImageView) {
            if (v.isShown && v.getGlobalVisibleRect(rect)) out += v
            return
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
    }
    walk(root)
    return out.sortedBy { v -> IntArray(2).also { v.getLocationOnScreen(it) }[1] }
}

/** Redraws the page's text boxes (their word colours) after a mark or a new card. */
private fun redraw(v: View) {
    v.invalidate()
    if (v is ViewGroup) for (i in 0 until v.childCount) redraw(v.getChildAt(i))
}

/** Null: no OCR on any page on screen. Blocking (kumapie's provider): call it off the UI thread. */
private fun loadWords(context: Context, views: List<ReaderPageImageView>, blocks: List<List<OcrTextBlock>>): GlassPageWordsData? {
    if (blocks.all { it.isEmpty() }) return null
    val seen = HashSet<String>()
    val mined = HashSet<String>()
    val words = mutableListOf<GlassPageWord>()
    views.zip(blocks).forEach { (view, viewBlocks) ->
        // Reading order: right to left in columns for vertical text, else top to bottom.
        val vertical = viewBlocks.count { it.vertical } * 2 > viewBlocks.size
        val ordered = if (vertical) {
            viewBlocks.sortedWith(compareBy<OcrTextBlock>({ -it.xmax }, { it.ymin }))
        } else {
            viewBlocks.sortedWith(compareBy<OcrTextBlock>({ it.ymin }, { it.xmin }))
        }
        for (block in ordered) {
            val text = block.fullText
            val rows = KumapieColours.words(context, text) ?: continue
            for ((start, length, status) in rows.sortedBy { it.first }) {
                if (start < 0 || start + length > text.length || length <= 0) continue
                val word = text.substring(start, start + length)
                when (status) {
                    "b" -> mined += word
                    "u", "d" -> if (seen.add(word)) words += GlassPageWord(word, status, block, start, view)
                }
            }
        }
    }
    return GlassPageWordsData(words, mined.size)
}

/** The dictionary's best entry for [word]: its own form first, else the longest match. Blocking. */
private fun lookUp(
    context: Context,
    repository: DictionaryRepository,
    word: String,
    lookup: () -> Pair<AnkiProfile, DictionaryPaths>,
): LookupResult? = runCatching {
    val (profile, paths) = lookup()
    val results = orderLookupResultsForDisplay(repository.lookup(word, paths, profile.languageCode).results, profile, context)
    results.firstOrNull { it.matched == word } ?: results.firstOrNull()
}.getOrNull()

/** Plain text of a dictionary glossary (plain string, or Yomitan structured content as JSON); readings in <rt> dropped. */
internal fun glossaryText(raw: String): String {
    val parsed = runCatching { JSONTokener(raw).nextValue() }.getOrNull()
    if (parsed !is JSONArray && parsed !is JSONObject) return raw.trim()
    fun walk(x: Any?, sb: StringBuilder) {
        when (x) {
            is String -> sb.append(x)
            is JSONArray -> for (i in 0 until x.length()) walk(x.opt(i), sb)
            is JSONObject -> if (x.optString("tag") !in setOf("rt", "rp")) {
                walk(x.opt("content"), sb)
                walk(x.opt("text"), sb)
            }
        }
    }
    val parts = if (parsed is JSONArray) (0 until parsed.length()).map { parsed.opt(it) } else listOf(parsed)
    return parts.map { StringBuilder().also { sb -> walk(it, sb) }.toString().trim() }
        .filter { it.isNotEmpty() }
        .joinToString("; ")
        .replace(Regex("\\s+"), " ")
        .replace(Regex("^\\S*【[^】]*】\\s*"), "") // a dictionary's own headword line ("まちじかん[3]【待ち時間】")
}

/** Opens the normal lookup popup for [word] as a tap on it would (highlight + popup with the rating buttons). */
internal fun GlassPageWord.openLookup() {
    val lookupString = extractOcrLookupString(block.fullText, start).ifBlank { return }
    view.activeOcrBlock = block
    view.activeOcrCharOffset = start
    view.activeOcrMatchedCount = 0
    view.ocrLayoutCache = null
    view.ocrPopupLookupString = lookupString
    val rect = view.refineActiveOcrBlock(text.length) ?: run {
        val at = IntArray(2).also { view.getLocationOnScreen(it) }
        android.graphics.RectF(at[0].toFloat(), at[1].toFloat(), at[0] + view.width.toFloat(), at[1] + view.height.toFloat())
    }
    view.onShowOcrPopup?.invoke(
        lookupString, block.orderedDisplayText, block.toOrderedOffset(start),
        rect.left, rect.top, rect.width(), rect.height(), block.vertical, null, block,
    )
}

/**
 * A3 variant B: the pill "These pages · n new · n due · n mined · Words" and the list of the new and due words of the
 * pages on screen. Word only; tap = peek (reading · meaning); new words ✓ (kumapie mark known) and + (mine, as the
 * popup's +); due words Review (the lookup popup, which has the rating buttons). Kotlin calls only, no WebView.
 */
@Composable
fun GlassPageWords(
    viewer: Viewer?,
    pageKey: Any?,
    repository: DictionaryRepository,
    lookupPaths: () -> Pair<AnkiProfile, DictionaryPaths>,
    mediaInfo: MediaInfo?,
    titleId: String?,
    screenshot: () -> Bitmap?,
    onReview: (GlassPageWord) -> Unit,
    onRunOcr: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Loaded each time the controls open (this leaves composition with them) and when the page changes.
    val data by produceState<Result<GlassPageWordsData?>?>(null, viewer, pageKey) {
        val views = visiblePageViews(viewer)
        val blocks = views.map { it.ocrBlocks }
        value = Result.success(withContext(Dispatchers.IO) { loadWords(context, views, blocks) })
    }
    var expanded by remember { mutableStateOf(true) }
    val known = remember(pageKey) { mutableStateMapOf<String, Boolean>() }
    val minedNow = remember(pageKey) { mutableStateMapOf<String, Boolean>() }
    val peeks = remember { mutableStateMapOf<String, String>() }
    var peeking by remember(pageKey) { mutableStateOf<String?>(null) }

    val loaded = data?.getOrNull()
    val words = loaded?.words.orEmpty()
    val newCount = words.count { it.status == "u" && known[it.text] != true && minedNow[it.text] != true }
    val dueCount = words.count { it.status == "d" }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier
                .height(44.dp)
                .clip(CircleShape)
                .background(Glass.panel.copy(alpha = 0.94f))
                .border(1.dp, Glass.line, CircleShape)
                .padding(start = 16.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(MR.strings.glass_reader_these_pages), color = Glass.secondary, fontSize = Glass.body)
            if (data == null) {
                Text("…", color = Glass.secondary, fontSize = Glass.body)
            } else if (loaded == null) {
                Text(stringResource(MR.strings.glass_reader_no_ocr), color = Color.White, fontSize = Glass.body)
                onRunOcr?.let { run ->
                    Box(
                        Modifier.height(36.dp).clip(CircleShape).background(Color.White).clickable(onClick = run)
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(stringResource(MR.strings.action_ocr), color = Color.Black, fontSize = Glass.body, fontWeight = FontWeight.SemiBold)
                    }
                }
            } else {
                Count(NEW, stringResource(MR.strings.glass_reader_new, newCount))
                Count(DUE, stringResource(MR.strings.glass_reader_due, dueCount))
                Count(MINED, stringResource(MR.strings.glass_reader_mined, loaded.mined + minedNow.size))
                Row(
                    Modifier.height(40.dp).clip(CircleShape)
                        .background(if (expanded) Color.White else Color.Transparent)
                        .clickable { expanded = !expanded }
                        .padding(start = 14.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val ink = if (expanded) Color.Black else Color.White
                    Text(stringResource(MR.strings.glass_reader_words), color = ink, fontSize = Glass.body, fontWeight = FontWeight.SemiBold)
                    GlassIcon(if (expanded) "chevDown" else "chevUp", size = 16.dp, tint = ink)
                }
            }
        }

        if (loaded != null && expanded) {
            Column(
                Modifier
                    .width(440.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(Glass.r16))
                    .background(Glass.panel.copy(alpha = 0.97f))
                    .border(1.dp, Glass.line, androidx.compose.foundation.shape.RoundedCornerShape(Glass.r16))
                    .padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            ) {
                if (words.isEmpty()) {
                    Text(
                        stringResource(MR.strings.glass_reader_nothing_new),
                        color = Glass.secondary,
                        fontSize = Glass.body,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                } else {
                    LazyColumn(Modifier.heightIn(max = 400.dp)) {
                        itemsIndexed(words, key = { _, w -> w.text }) { i, w ->
                            if (i > 0) HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                            val isKnown = known[w.text] == true
                            val isMined = minedNow[w.text] == true
                            Row(
                                Modifier.heightIn(min = 56.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                val dot = when {
                                    isKnown -> Color.White
                                    isMined -> MINED
                                    w.status == "u" -> NEW
                                    else -> DUE
                                }
                                Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                                Column(
                                    Modifier
                                        .weight(1f)
                                        .clickable {
                                            peeking = if (peeking == w.text) null else w.text
                                            if (w.text !in peeks) {
                                                scope.launch {
                                                    val entry = withContext(Dispatchers.Default) {
                                                        lookUp(context, repository, w.text, lookupPaths)
                                                    }
                                                    peeks[w.text] = entry?.let { r ->
                                                        val meaning = r.term.glossaries.firstOrNull()?.let { glossaryText(it.glossary) }
                                                            .orEmpty().take(80)
                                                        listOf(r.term.reading.ifBlank { r.term.expression }, meaning)
                                                            .filter { it.isNotBlank() }.joinToString(" · ")
                                                    } ?: "—"
                                                }
                                            }
                                        }
                                        .padding(vertical = 6.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(w.text, color = Color.White, fontSize = Glass.heading, fontFamily = FontFamily.Serif)
                                    if (peeking == w.text) {
                                        Text(
                                            peeks[w.text] ?: "…",
                                            color = Glass.secondary,
                                            fontSize = Glass.body,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                if (w.status == "u" && !isKnown && !isMined) {
                                    CircleAction("check", stringResource(MR.strings.glass_reader_mark_known)) {
                                        scope.launch {
                                            val entry = withContext(Dispatchers.Default) { lookUp(context, repository, w.text, lookupPaths) }
                                            val body = JSONObject()
                                                .put("action", "mark")
                                                .put("word", entry?.term?.expression ?: w.text)
                                                .put("reading", entry?.term?.reading.orEmpty())
                                                .put("text", w.text)
                                                .put("known", true)
                                                .toString()
                                            val answer = withContext(Dispatchers.IO) { KumapieColours.call(context, body) }
                                            if (runCatching { JSONObject(answer).getBoolean("marked") }.getOrDefault(false)) {
                                                known[w.text] = true
                                                redraw(w.view)
                                            }
                                        }
                                    }
                                    CircleAction("plus", stringResource(MR.strings.glass_reader_mine)) {
                                        val shot = screenshot()
                                        scope.launch {
                                            val ok = mine(context, repository, w, lookupPaths, mediaInfo, titleId, shot)
                                            if (ok) {
                                                minedNow[w.text] = true
                                                redraw(w.view)
                                            }
                                        }
                                    }
                                } else if (w.status == "d") {
                                    Box(
                                        Modifier
                                            .height(44.dp)
                                            .clip(CircleShape)
                                            .border(1.dp, Glass.line, CircleShape)
                                            .clickable { onReview(w) }
                                            .padding(horizontal = 16.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            stringResource(MR.strings.glass_reader_review),
                                            color = Color.White,
                                            fontSize = Glass.body,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                    Text(
                        stringResource(MR.strings.glass_reader_peek_hint),
                        color = Glass.secondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Count(colour: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(colour))
        Text(text, color = Color.White, fontSize = Glass.body)
    }
}

@Composable
private fun CircleAction(icon: String, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).border(1.dp, Glass.line, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        GlassIcon(icon, size = 20.dp, contentDescription = description)
    }
}

/** The popup's + for a page word: the same [AnkiCardCreator.addToAnki] (kumapie's Mined decks first), sentence = the OCR block. */
private suspend fun mine(
    context: Context,
    repository: DictionaryRepository,
    word: GlassPageWord,
    lookupPaths: () -> Pair<AnkiProfile, DictionaryPaths>,
    mediaInfo: MediaInfo?,
    titleId: String?,
    screenshot: Bitmap?,
): Boolean {
    val profile = lookupPaths().first
    if (!profile.ankiEnabled) {
        context.toast(MR.strings.anki_not_configured)
        return false
    }
    val entry = withContext(Dispatchers.Default) { lookUp(context, repository, word.text, lookupPaths) }
    if (entry == null) {
        context.toast(context.stringResource(MR.strings.glass_reader_no_entry, word.text))
        return false
    }
    val sentence = word.block.orderedDisplayText
    val result = withContext(Dispatchers.IO) {
        AnkiCardCreator.addToAnki(
            context = context,
            result = entry,
            deck = profile.ankiDeck,
            model = profile.ankiModel,
            fieldMapJson = profile.ankiFieldMap,
            tags = profile.ankiTags,
            dupCheck = profile.ankiDupCheck,
            dupScope = profile.ankiDupScope,
            dupAction = profile.ankiDupAction,
            sentence = sentence,
            offset = sentence.indexOf(entry.matched).takeIf { it >= 0 } ?: word.block.toOrderedOffset(word.start),
            media = mediaInfo,
            screenshotBytes = screenshot?.takeIf { profile.ankiCropMode != "no_screenshot" }?.let { ImageEncoder.encode(it).bytes },
            selection = entry.matched,
            type = "manga",
            syncOnCreate = profile.ankiSyncOnCreate,
            profileId = profile.id,
            titleId = titleId,
        )
    }
    when (result) {
        is AnkiResult.Success -> context.toast(MR.strings.anki_card_added)
        is AnkiResult.CardExists -> context.toast(MR.strings.anki_card_exists)
        is AnkiResult.OpenCard -> chimahon.anki.AnkiDroidBridge(context).guiEditNote(result.noteId)
        is AnkiResult.PermissionDenied -> context.toast(MR.strings.pref_anki_permission_denied)
        is AnkiResult.Error -> context.toast(context.stringResource(MR.strings.anki_card_error, result.message))
        is AnkiResult.NotConfigured -> context.toast(MR.strings.anki_not_configured)
    }
    return result is AnkiResult.Success || result is AnkiResult.CardExists
}
