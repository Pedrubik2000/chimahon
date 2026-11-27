package eu.kanade.tachiyomi.ui.glass.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.Navigator
import coil3.compose.AsyncImage
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassIcon
import eu.kanade.presentation.glass.Pill
import eu.kanade.presentation.history.HistoryUiModel
import eu.kanade.presentation.history.components.HistoryDeleteAllDialog
import eu.kanade.presentation.history.components.HistoryDeleteDialog
import eu.kanade.tachiyomi.ui.glass.GlassTitle
import eu.kanade.tachiyomi.ui.glass.openTitle
import eu.kanade.tachiyomi.ui.history.HistoryScreenModel
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.util.system.toast
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import java.text.DecimalFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME = DateTimeFormatter.ofPattern("HH:mm")
private val CHAPTER = DecimalFormat("#.##")

/** "21:44" for a read time. */
internal fun readTime(millis: Long): String = TIME.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

/** The "Covers | History" switch on the Reading shelf header. */
@Composable
fun CoversHistorySwitch(history: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.height(36.dp).clip(CircleShape).border(1.dp, Glass.line, CircleShape), verticalAlignment = Alignment.CenterVertically) {
        listOf(false to MR.strings.glass_hist_covers, true to MR.strings.glass_hist_history).forEach { (value, label) ->
            val on = value == history
            Box(
                Modifier
                    .height(36.dp)
                    .clip(CircleShape)
                    .then(if (on) Modifier.background(Color.White) else Modifier)
                    .clickable { onChange(value) }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(label), color = if (on) Color.Black else Color.White, fontSize = Glass.body, maxLines = 1)
            }
        }
    }
}

/**
 * The full Recently read list in place of the shelf (Classic History): search, day groups, Resume and remove on each row,
 * Select / Clear all in the menu. Data and actions are [HistoryScreenModel]'s.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GlassHistoryList(model: HistoryScreenModel, titles: List<GlassTitle>, navigator: Navigator, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by model.state.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var rows by remember { mutableStateOf(50) } // the list draws this many; Show more adds 50
    // the title whose Resume was tapped last: with no next chapter (finished) its title page opens instead
    var resumed by remember { mutableStateOf<Long?>(null) }
    val currentTitles by androidx.compose.runtime.rememberUpdatedState(titles) // the event loop below starts once

    LaunchedEffect(Unit) {
        model.events.collect { e ->
            if (e is HistoryScreenModel.Event.OpenChapter) {
                val chapter = e.chapter
                if (chapter != null) {
                    context.startActivity(ReaderActivity.newIntent(context, chapter.mangaId, chapter.id))
                } else {
                    context.toast(MR.strings.no_next_chapter)
                    resumed?.let { openHistoryTitle(it, currentTitles, navigator) }
                }
            }
        }
    }
    when (val d = state.dialog) {
        HistoryScreenModel.Dialog.DeleteAll -> HistoryDeleteAllDialog(
            onDismissRequest = { model.setDialog(null) },
            onDelete = { model.removeAllHistory(); model.setDialog(null) },
        )
        is HistoryScreenModel.Dialog.Delete -> HistoryDeleteDialog(
            onDismissRequest = { model.setDialog(null) },
            onDelete = { all ->
                if (all) model.removeAllFromHistory(d.histories) else model.removeFromHistory(d.histories)
                model.setDialog(null)
            },
        )
        else -> {}
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.weight(1f).height(40.dp).clip(CircleShape).border(1.dp, Glass.line, CircleShape).padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (state.searchQuery.isNullOrEmpty()) Text(stringResource(MR.strings.glass_hist_search), color = Glass.secondary, fontSize = Glass.body)
                BasicTextField(
                    value = state.searchQuery.orEmpty(),
                    onValueChange = { model.updateSearchQuery(it.ifEmpty { null }) },
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = Glass.body),
                    cursorBrush = SolidColor(Glass.ice),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Box {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).border(1.dp, Glass.line, CircleShape).clickable { menu = true },
                    contentAlignment = Alignment.Center,
                ) { GlassIcon("grip", size = 18.dp, contentDescription = stringResource(MR.strings.glass_inbox_more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(MR.strings.glass_hist_select)) }, onClick = { menu = false; model.toggleSelectionMode(true) })
                    DropdownMenuItem(text = { Text(stringResource(MR.strings.glass_hist_clear)) }, onClick = { menu = false; model.setDialog(HistoryScreenModel.Dialog.DeleteAll) })
                }
            }
        }
        if (state.selectionMode) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(MR.strings.glass_inbox_selected, state.selected.size), fontSize = Glass.body, fontWeight = FontWeight.SemiBold)
                Pill(
                    stringResource(MR.strings.glass_inbox_select_all),
                    onClick = { // only the rows drawn, not the whole history
                        val drawn = state.getUiModel().take(rows).filterIsInstance<HistoryUiModel.Item>()
                        drawn.forEach { model.toggleSelection(it.item, HistoryScreenModel.HistorySelectionOptions(true)) }
                    },
                    small = true,
                )
                Pill(
                    stringResource(MR.strings.glass_inbox_invert),
                    onClick = { // only the rows drawn, like Select all: Delete must never reach rows nobody saw
                        state.getUiModel().take(rows).filterIsInstance<HistoryUiModel.Item>().forEach {
                            model.toggleSelection(it.item, HistoryScreenModel.HistorySelectionOptions(it.item.chapterId !in state.selection))
                        }
                    },
                    small = true,
                )
                Pill(stringResource(MR.strings.glass_inbox_delete), onClick = { model.setDialog(HistoryScreenModel.Dialog.Delete(state.selected)) }, small = true, primary = true)
                Pill(stringResource(MR.strings.action_cancel), onClick = { model.toggleSelectionMode(false) }, small = true)
            }
        }
        val all = state.getUiModel()
        all.take(rows).forEach { row ->
            when (row) {
                is HistoryUiModel.Header -> Text(
                    relativeDateText(row.date).uppercase(),
                    Modifier.padding(top = 10.dp),
                    color = Glass.secondary,
                    fontSize = Glass.caption,
                )
                is HistoryUiModel.Item -> HistoryRow(row.item, state.selectionMode, row.item.chapterId in state.selection, model, titles, navigator) { resumed = it }
            }
        }
        if (all.size > rows) Pill(stringResource(MR.strings.glass_hist_show_more), onClick = { rows += 50 }, small = true)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryRow(
    item: HistoryWithRelations,
    selectionMode: Boolean,
    selected: Boolean,
    model: HistoryScreenModel,
    titles: List<GlassTitle>,
    navigator: Navigator,
    onResume: (Long) -> Unit,
) {
    val shape = RoundedCornerShape(Glass.r12)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) Glass.ice else Glass.line, shape)
            .combinedClickable(
                onClick = {
                    if (selectionMode) {
                        model.toggleSelection(item, HistoryScreenModel.HistorySelectionOptions(!selected))
                    } else {
                        openHistoryTitle(item.mangaId, titles, navigator)
                    }
                },
                onLongClick = { model.toggleSelection(item, HistoryScreenModel.HistorySelectionOptions(!selected, fromLongPress = true)) },
            )
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AsyncImage(item.coverData, null, Modifier.size(40.dp, 58.dp).clip(RoundedCornerShape(Glass.r8)), contentScale = ContentScale.Crop)
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                fontSize = Glass.body,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(MR.strings.recent_manga_time, CHAPTER.format(item.chapterNumber), item.readAt?.time?.let(::readTime).orEmpty()),
                color = Glass.secondary,
                fontSize = Glass.caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!selectionMode) {
            Pill(
                stringResource(MR.strings.glass_hist_resume),
                onClick = { onResume(item.mangaId); model.getNextChapterForManga(item.mangaId, item.chapterId) },
                small = true,
                primary = true,
                icon = "play",
            )
            Box(
                Modifier.size(36.dp).clip(CircleShape).border(1.dp, Glass.line, CircleShape).clickable { model.setDialog(HistoryScreenModel.Dialog.Delete(item)) },
                contentAlignment = Alignment.Center,
            ) { GlassIcon("close", size = 14.dp, contentDescription = stringResource(MR.strings.glass_hist_remove)) }
        }
    }
}

/** The title's Glass page when it is on the device, else the classic title page. */
private fun openHistoryTitle(mangaId: Long, titles: List<GlassTitle>, navigator: Navigator) {
    titles.firstOrNull { it.localMangaId == mangaId }?.let { openTitle(navigator, it) } ?: navigator.push(MangaScreen(mangaId))
}
