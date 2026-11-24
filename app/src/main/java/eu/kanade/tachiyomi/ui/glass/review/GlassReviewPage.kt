package eu.kanade.tachiyomi.ui.glass.review

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassIcon
import eu.kanade.presentation.glass.GlassRailItems
import eu.kanade.presentation.glass.Meter
import eu.kanade.presentation.glass.Pill
import eu.kanade.presentation.glass.glassPanel

/**
 * Review, tablet (mockup option C, "Two thumbs, swipe stack"): Again/Hard under the left thumb, Good/Easy under the
 * right, the card in the middle as a stack whose contexts you swipe.
 */
@Composable
fun GlassReviewPage(model: GlassReviewModel) {
    LaunchedEffect(Unit) { model.start() }
    Box(Modifier.fillMaxSize()) {
        when {
            model.loading -> Unit
            model.card == null -> {
                ReviewTitle(Modifier.padding(start = 108.dp, top = 24.dp))
                if (model.canUndo) Pill("Undo", onClick = model::undo, modifier = Modifier.align(Alignment.TopEnd).padding(end = 24.dp, top = 24.dp), icon = "undo", small = true)
                ReviewEmpty(model, Modifier.align(Alignment.Center).widthIn(max = 520.dp).fillMaxWidth().height(300.dp))
            }
            else -> {
                Row(
                    Modifier.fillMaxWidth().padding(start = 108.dp, end = 24.dp, top = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text("${model.session.done} of ${model.session.total}", fontSize = Glass.body, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Meter(
                        fraction = if (model.session.total == 0) 0f else model.session.done.toFloat() / model.session.total,
                        modifier = Modifier.weight(1f),
                    )
                    ReviewCounts(model.session, Glass.body)
                    if (model.canUndo) Pill("Undo", onClick = model::undo, icon = "undo", small = true)
                }
                Row(
                    Modifier.fillMaxSize().padding(start = 104.dp, end = 24.dp, top = 88.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    ThumbColumn(model, left = true, Modifier.width(172.dp).fillMaxHeight())
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        ReviewCardStack(model, phone = false, Modifier.widthIn(max = 800.dp).fillMaxSize())
                    }
                    ThumbColumn(model, left = false, Modifier.width(172.dp).fillMaxHeight())
                }
            }
        }
    }
}

/** One thumb's buttons, bottom aligned: Show before the answer; after it Again over Hard (left), Good over Easy (right). */
@Composable
private fun ThumbColumn(model: GlassReviewModel, left: Boolean, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom)) {
        if (!model.shown) {
            ReviewShowButton(model::show, Modifier.fillMaxWidth().height(272.dp), compact = false)
        } else {
            val ratings = if (left) listOf(1, 2) else listOf(3, 4)
            ratings.forEach { ReviewAnswer(model, it, Modifier.fillMaxWidth().height(130.dp)) }
        }
    }
}

@Composable
internal fun ReviewTitle(modifier: Modifier = Modifier) {
    Text(
        GlassRailItems.first { it.first == "review" }.second,
        modifier,
        fontSize = Glass.title,
        fontWeight = FontWeight.SemiBold,
    )
}

/** "6 new  3 again  14 due" in the three count colours. */
@Composable
internal fun ReviewCounts(session: ReviewLogic.Session, size: androidx.compose.ui.unit.TextUnit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("${session.newCount} new", color = Glass.blue, fontSize = size, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text("${session.againCount} again", color = Glass.red, fontSize = size, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text("${session.dueCount} due", color = Glass.green, fontSize = size, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** The big white Show button; [compact] = one row (phone), else icon over text (the tablet thumbs). */
@Composable
internal fun ReviewShowButton(onClick: () -> Unit, modifier: Modifier, compact: Boolean) {
    Box(
        modifier.clip(RoundedCornerShape(Glass.r16)).background(Color.White).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (compact) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassIcon("eye", size = 24.dp, tint = Color.Black)
                Text("Show", color = Color.Black, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                GlassIcon("eye", size = 28.dp, tint = Color.Black)
                Text("Show", color = Color.Black, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** One of the four answers (1 Again, 2 Hard, 3 Good, 4 Easy) with the interval it gives; Good is the white one. */
@Composable
internal fun ReviewAnswer(model: GlassReviewModel, rating: Int, modifier: Modifier) {
    val good = rating == 3
    val shape = RoundedCornerShape(Glass.r16)
    Column(
        modifier
            .clip(shape)
            .then(if (good) Modifier.background(Color.White) else Modifier.background(Glass.panel).border(1.dp, Glass.line, shape))
            .clickable { model.answer(rating) },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            model.intervals.getOrNull(rating - 1).orEmpty(),
            color = if (good) Color.Black else Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        Text(
            listOf("Again", "Hard", "Good", "Easy")[rating - 1],
            color = when {
                good -> Color.Black.copy(alpha = 0.6f)
                rating == 1 -> Glass.red
                else -> Glass.secondary
            },
            fontSize = Glass.body,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** "Nothing due" with when the next card comes, or, with no card in the store at all, how to get some. */
@Composable
internal fun ReviewEmpty(model: GlassReviewModel, modifier: Modifier = Modifier) {
    Column(
        modifier
            .drawBehind {
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.3f),
                    cornerRadius = CornerRadius(16.dp.toPx()),
                    style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 12f))),
                )
            }
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (model.storeEmpty) {
            Text(
                "No cards yet. Mine a word while reading and it shows up here.",
                color = Glass.secondary,
                fontSize = Glass.label,
                textAlign = TextAlign.Center,
            )
        } else {
            Text("Nothing due", fontSize = Glass.heading, fontWeight = FontWeight.Bold)
            model.nextDue?.let {
                Text(
                    ReviewLogic.nextLabel(it, System.currentTimeMillis()),
                    color = Glass.secondary,
                    fontSize = Glass.label,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/** The current card as a stack (two edges behind it); tap it to show the answer. */
@Composable
internal fun ReviewCardStack(model: GlassReviewModel, phone: Boolean, modifier: Modifier) {
    val card = model.card ?: return
    Box(modifier.padding(end = 20.dp, bottom = 18.dp)) {
        StackEdge(24.dp, 16.dp, 2.2f, Color(0xFF0F1116))
        StackEdge(12.dp, 8.dp, 1.1f, Color(0xFF12151A))
        Column(
            Modifier
                .fillMaxSize()
                .glassPanel()
                .clickable(enabled = !model.shown) { model.show() }
                .padding(if (phone) 14.dp else 24.dp),
        ) {
            key(card.id) { ReviewCardBody(model, card.word, ReviewLogic.cardSentence(card.sentence), card.meaning, phone) }
        }
    }
}

@Composable
private fun BoxScope.StackEdge(dx: Dp, dy: Dp, rotation: Float, color: Color) {
    val shape = RoundedCornerShape(Glass.r16)
    Box(
        Modifier
            .matchParentSize()
            .offset(dx, dy)
            .graphicsLayer { rotationZ = rotation }
            .clip(shape)
            .background(color)
            .border(1.dp, Glass.line, shape),
    )
}

/** The contexts as swipeable pages, then (answer shown) the word, its reading and meaning, then the swipe hint. */
@Composable
private fun ColumnScope.ReviewCardBody(model: GlassReviewModel, wordRaw: String, sentence: String, meaningRaw: String, phone: Boolean) {
    val word = remember(wordRaw) { ReviewLogic.parseWord(wordRaw) }
    val faces = model.contexts.ifEmpty { listOf(ReviewFace("", sentence, null)) }
    val pager = rememberPagerState { faces.size }

    HorizontalPager(pager, Modifier.fillMaxWidth().weight(1f)) { i ->
        ReviewFaceView(faces[i], word.spelling, phone)
    }
    if (model.shown) {
        Box(Modifier.padding(vertical = 12.dp).fillMaxWidth().height(1.dp).background(Glass.line))
        val meaning = remember(meaningRaw) { ReviewLogic.plainText(meaningRaw) }
        Column(Modifier.fillMaxWidth().heightIn(max = if (phone) 200.dp else 300.dp).verticalScroll(rememberScrollState())) {
            if (word.reading.isNotEmpty()) Text(word.reading, color = Glass.secondary, fontSize = Glass.label)
            Text(word.spelling, fontSize = if (phone) 40.sp else 60.sp, fontWeight = FontWeight.Bold)
            if (meaning.isNotEmpty()) {
                Text(
                    meaning,
                    fontSize = if (phone) 14.sp else 17.sp,
                    lineHeight = if (phone) 21.sp else 25.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
    if (faces.size > 1) {
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Swipe for the other contexts", color = Glass.secondary, fontSize = Glass.caption, modifier = Modifier.weight(1f))
            faces.indices.forEach { i ->
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (i == pager.currentPage) Color.White else Color.White.copy(alpha = 0.3f)),
                )
            }
        }
    }
}

/** One context: the panel image when there is one, then where it was met and the sentence with the word marked. */
@Composable
private fun ReviewFaceView(face: ReviewFace, spelling: String, phone: Boolean) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(if (phone) 12.dp else 24.dp)) {
        face.image?.let { file ->
            val shape = RoundedCornerShape(Glass.r12)
            Box(
                Modifier
                    .width(if (phone) 120.dp else 300.dp)
                    .fillMaxHeight()
                    .clip(shape)
                    .background(Glass.page)
                    .border(1.dp, Glass.line, shape),
            ) {
                AsyncImage(
                    model = file,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (face.where.isNotEmpty()) {
                Text(face.where, color = Glass.secondary, fontSize = if (phone) Glass.caption else Glass.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(
                marked(face.sentence.ifBlank { spelling }, spelling),
                fontSize = if (phone) 22.sp else 32.sp,
                lineHeight = if (phone) 32.sp else 46.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = if (phone) 8.dp else 16.dp),
            )
        }
    }
}

private fun marked(text: String, word: String): AnnotatedString = buildAnnotatedString {
    for ((part, hit) in ReviewLogic.segments(text, word)) {
        if (hit) withStyle(SpanStyle(background = Glass.green.copy(alpha = 0.3f))) { append(part) } else append(part)
    }
}
