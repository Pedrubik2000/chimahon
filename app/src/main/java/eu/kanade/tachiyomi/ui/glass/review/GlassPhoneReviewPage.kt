package eu.kanade.tachiyomi.ui.glass.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassPhoneBarRoom
import eu.kanade.presentation.glass.Meter
import eu.kanade.presentation.glass.Pill

/** Review, phone (mockup option C): progress, counts and Undo on top, the card, a 2x2 answer grid above the bar. */
@Composable
fun GlassPhoneReviewPage(model: GlassReviewModel) {
    LaunchedEffect(Unit) { model.start() }
    Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
        when {
            model.loading -> Unit
            model.card == null -> {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ReviewTitle(Modifier.weight(1f).padding(top = 12.dp))
                    if (model.canUndo) Pill("Undo", onClick = model::undo, icon = "undo", small = true)
                }
                Box(Modifier.weight(1f).fillMaxWidth().padding(bottom = GlassPhoneBarRoom), contentAlignment = Alignment.Center) {
                    ReviewEmpty(model, Modifier.fillMaxWidth().height(300.dp))
                }
            }
            else -> {
                val s = model.session
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("${s.done} of ${s.total}", fontSize = Glass.body, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Spacer(Modifier.weight(1f))
                            ReviewCounts(s, Glass.caption)
                        }
                        Meter(fraction = if (s.total == 0) 0f else s.done.toFloat() / s.total)
                    }
                    if (model.canUndo) Pill("Undo", onClick = model::undo, icon = "undo", small = true)
                }
                ReviewCardStack(model, phone = true, Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp, bottom = 12.dp))
                if (!model.shown) {
                    ReviewShowButton(model::show, Modifier.fillMaxWidth().height(72.dp), compact = true)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(listOf(1, 2), listOf(3, 4)).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { ReviewAnswer(model, it, Modifier.weight(1f).height(88.dp)) }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(GlassPhoneBarRoom))
            }
        }
    }
}
