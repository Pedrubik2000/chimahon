package eu.kanade.tachiyomi.ui.glass.releases

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassChip
import eu.kanade.presentation.glass.GlassIcon
import eu.kanade.presentation.glass.Pill
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.updates.UpdatesDeleteConfirmationDialog
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.updates.UpdatesItem
import eu.kanade.tachiyomi.ui.updates.UpdatesScreenModel
import eu.kanade.tachiyomi.util.system.toast
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The chips of the inbox band. */
enum class InboxFilter { Unread, All, OnTablet, Started, Bookmarked }

/** The pure rules of the inbox band (no Android), so tests can run them. */
object InboxLogic {
    /** The band: what arrived after [seen] and is not read yet; [showSeen] = everything the update found. */
    fun <T> band(items: List<T>, seen: Long, showSeen: Boolean, dateOf: (T) -> Long, readOf: (T) -> Boolean): List<T> =
        if (showSeen) items else items.filter { dateOf(it) > seen && !readOf(it) }

    /** The same filters as Classic Updates, over what the band holds. */
    fun <T> filter(
        items: List<T>,
        f: InboxFilter,
        read: (T) -> Boolean,
        onTablet: (T) -> Boolean,
        started: (T) -> Boolean,
        bookmarked: (T) -> Boolean,
    ): List<T> = when (f) {
        InboxFilter.Unread -> items.filter { !read(it) }
        InboxFilter.All -> items
        InboxFilter.OnTablet -> items.filter(onTablet)
        InboxFilter.Started -> items.filter(started)
        InboxFilter.Bookmarked -> items.filter(bookmarked)
    }
}

/**
 * "Seen" for the inbox: the moment he last pressed Mark all seen, in the app's own preferences (nothing in the database).
 * [ts] is state, so the rail badge and the band follow it.
 */
object InboxSeen {
    val ts = mutableLongStateOf(0L)

    /** chapter ids the band shows right now (after the chip), what Select all / Invert reach */
    val shown = mutableStateOf<Set<Long>>(emptySet())
    private var loaded = false
    private var saved = false

    private fun prefs(context: Context) = context.getSharedPreferences("glass_releases", Context.MODE_PRIVATE)

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val p = prefs(context)
        saved = p.contains("inbox_seen_ts")
        ts.longValue = p.getLong("inbox_seen_ts", 0L)
    }

    /** First run (nothing saved): what the library update already holds counts as seen, so the inbox starts empty. */
    fun initIfFirst(context: Context, newestDateFetch: Long) {
        if (saved) return
        saved = true
        prefs(context).edit().putLong("inbox_seen_ts", newestDateFetch).apply()
        ts.longValue = newestDateFetch
    }

    /** Seen up to [newestInBand]: the newest item that was in the band, so one fetched after it (or never shown) stays new. */
    fun markAll(context: Context, newestInBand: Long) {
        val at = maxOf(ts.longValue, newestInBand)
        prefs(context).edit().putLong("inbox_seen_ts", at).apply()
        ts.longValue = at
    }
}

private val CHECKED = DateTimeFormatter.ofPattern("EEE HH:mm")

private fun InboxFilter.label() = when (this) {
    InboxFilter.Unread -> MR.strings.glass_inbox_f_unread
    InboxFilter.All -> MR.strings.glass_inbox_f_all
    InboxFilter.OnTablet -> MR.strings.glass_inbox_f_tablet
    InboxFilter.Started -> MR.strings.glass_inbox_f_started
    InboxFilter.Bookmarked -> MR.strings.glass_inbox_f_bookmarked
}

/**
 * Mockup D's inbox band on Releases: what the library update found since he last pressed Mark all seen, as covers with
 * Download / Mark read / more, the Classic Updates filters as chips, Check sources, Mark all seen; folds to one line when empty.
 * All data and actions are [UpdatesScreenModel]'s (Classic Updates').
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun GlassInboxBand(model: UpdatesScreenModel, phone: Boolean) {
    val context = LocalContext.current
    val state by model.state.collectAsStateWithLifecycle()
    val seen = InboxSeen.ts.longValue
    var showSeen by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf(InboxFilter.Unread) }
    val sources = remember { Injekt.get<SourceManager>() }

    val newCount = remember(state.items, seen) {
        InboxLogic.band(state.items, seen, false, { it.update.dateFetch }, { it.update.read }).size
    }
    val band = remember(state.items, seen, showSeen) {
        InboxLogic.band(state.items, seen, showSeen, { it.update.dateFetch }, { it.update.read })
    }
    val shown = remember(band, filter) {
        InboxLogic.filter(
            band, filter,
            read = { it.update.read },
            onTablet = { it.downloadStateProvider() == Download.State.DOWNLOADED },
            started = { it.update.lastPageRead > 0 },
            bookmarked = { it.update.bookmark },
        )
    }
    // each part only when it exists: "since you last looked" needs a seen time, "checked ..." a last library update
    val checkedAt = model.lastUpdated.takeIf { it > 0 }?.let { stringResource(MR.strings.glass_inbox_checked, CHECKED.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))) }
    val subline = listOfNotNull(stringResource(MR.strings.glass_inbox_since).takeIf { seen > 0 }, checkedAt).joinToString(" \u00b7 ")

    SideEffect { InboxSeen.shown.value = shown.mapTo(HashSet()) { it.update.chapterId } }
    // like Classic Updates on opening: the library's new-updates count goes back to 0
    LaunchedEffect(Unit) { model.resetNewUpdatesCount() }
    LaunchedEffect(Unit) {
        model.events.collect { e ->
            if (e is UpdatesScreenModel.Event.LibraryUpdateTriggered) {
                context.toast(if (e.started) MR.strings.updating_library else MR.strings.update_already_running)
            }
        }
    }
    (state.dialog as? UpdatesScreenModel.Dialog.DeleteConfirmation)?.let { d ->
        UpdatesDeleteConfirmationDialog(onDismissRequest = { model.setDialog(null) }, onConfirm = { model.deleteChapters(d.toDelete) })
    }

    if (state.hasActiveFilters) {
        // Classic Updates' own filters shrink what the update found: say so, one tap clears them
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(MR.strings.glass_inbox_filters_on), Modifier.weight(1f), color = Glass.secondary, fontSize = Glass.caption)
            Pill(stringResource(MR.strings.glass_inbox_filters_clear), onClick = { clearClassicFilters() }, small = true)
        }
    }
    if (newCount == 0 && !showSeen) {
        // folded: one line
        Row(
            Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(Glass.r12)).border(1.dp, Glass.line, RoundedCornerShape(Glass.r12)).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(MR.strings.glass_inbox_none), color = Glass.secondary, fontSize = Glass.body, fontWeight = FontWeight.SemiBold)
                checkedAt?.let { Text(it, color = Glass.secondary, fontSize = Glass.caption) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(stringResource(MR.strings.glass_inbox_check), onClick = { model.updateLibrary() }, small = true)
                Pill(stringResource(MR.strings.glass_inbox_show_seen), onClick = { showSeen = true }, small = true)
            }
        }
        return
    }

    val box = RoundedCornerShape(Glass.r16)
    Column(
        Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(box).border(1.dp, Glass.ice, box).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column {
            Text(
                if (showSeen) stringResource(MR.strings.glass_inbox_all, shown.size) else stringResource(MR.strings.glass_inbox_new, shown.size),
                fontSize = Glass.label,
                fontWeight = FontWeight.SemiBold,
            )
            if (subline.isNotEmpty()) Text(subline, color = Glass.secondary, fontSize = Glass.caption)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            InboxFilter.entries.forEach { GlassChip(stringResource(it.label()), selected = it == filter, onClick = { filter = it }) }
            Pill(stringResource(MR.strings.glass_inbox_check), onClick = { model.updateLibrary() }, small = true)
            if (showSeen) {
                Pill(stringResource(MR.strings.glass_inbox_hide_seen), onClick = { showSeen = false }, small = true)
            } else {
                Pill(stringResource(MR.strings.glass_inbox_mark_seen), onClick = { InboxSeen.markAll(context, band.maxOfOrNull { it.update.dateFetch } ?: 0L) }, small = true, primary = true, icon = "check")
            }
        }
        // the covers wrap in rows; a long inbox scrolls inside the band
        Column(Modifier.heightIn(max = if (phone) 420.dp else 380.dp).verticalScroll(rememberScrollState())) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                shown.forEach { item ->
                    InboxCard(
                        item = item,
                        sourceName = sources.getOrStub(item.update.sourceId).name,
                        width = if (phone) 96.dp else 104.dp,
                        selectionMode = state.selectionMode,
                        onOpen = { context.startActivity(ReaderActivity.newIntent(context, item.update.mangaId, item.update.chapterId)) },
                        onSelect = { model.toggleSelection(item, UpdatesScreenModel.UpdateSelectionOptions(selected = !item.selected, fromLongPress = false)) },
                        model = model,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InboxCard(
    item: UpdatesItem,
    sourceName: String,
    width: androidx.compose.ui.unit.Dp,
    selectionMode: Boolean,
    onOpen: () -> Unit,
    onSelect: () -> Unit,
    model: UpdatesScreenModel,
) {
    val shape = RoundedCornerShape(Glass.r8)
    var menu by remember { mutableStateOf(false) }
    val downloaded = item.downloadStateProvider() == Download.State.DOWNLOADED
    Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(shape)
                .background(Glass.panel)
                .border(if (item.selected) 2.dp else 1.dp, if (item.selected) Glass.ice else Glass.line, shape)
                .combinedClickable(onClick = { if (selectionMode) onSelect() else onOpen() }, onLongClick = onSelect),
        ) {
            // a local volume has its own first page as cover (what the Glass title page uses); else the manga's cover
            var own by remember(item.update.chapterId) { mutableStateOf<java.io.File?>(null) }
            LaunchedEffect(item.update.chapterId) {
                if (item.update.sourceId == tachiyomi.source.local.LocalSource.ID) {
                    Injekt.get<tachiyomi.domain.chapter.interactor.GetChapter>().await(item.update.chapterId)?.let { chapter ->
                        eu.kanade.tachiyomi.ui.glass.title.LocalVolumeCovers.load(item.update.mangaId, mapOf(0 to chapter)) { _, f -> own = f }
                    }
                }
            }
            AsyncImage(own ?: item.update.coverData, null, Modifier.fillMaxWidth().aspectRatio(2f / 3f), contentScale = ContentScale.Crop)
            numberOf(item.update.chapterName)?.let {
                Text(
                    it,
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                        .padding(start = 6.dp, top = 12.dp, bottom = 2.dp),
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = Glass.heading,
                    color = Color.White,
                )
            }
            if (item.selected) {
                GlassIcon("check", Modifier.padding(4.dp).size(20.dp).clip(CircleShape).background(Glass.ice).padding(3.dp), tint = Color.Black)
            }
            if (item.update.bookmark) {
                GlassIcon("bookmark", Modifier.align(Alignment.TopEnd).padding(4.dp).size(16.dp), tint = Glass.ice)
            }
        }
        Text(item.update.mangaTitle, fontSize = Glass.caption, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(item.update.chapterName + " · " + sourceName, color = Glass.secondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            CardButton("download", if (downloaded) Glass.ice else Color.White, stringResource(MR.strings.glass_inbox_download)) {
                if (!downloaded) model.downloadChapters(listOf(item), ChapterDownloadAction.START)
            }
            CardButton("check", if (item.update.read) Glass.ice else Color.White, stringResource(MR.strings.glass_inbox_mark_read)) {
                model.markUpdatesRead(listOf(item), !item.update.read)
            }
            Box {
                CardButton("grip", Color.White, stringResource(MR.strings.glass_inbox_more)) { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(if (item.update.bookmark) MR.strings.glass_inbox_unbookmark else MR.strings.glass_inbox_bookmark)) },
                        onClick = { menu = false; model.bookmarkUpdates(listOf(item), !item.update.bookmark) },
                    )
                    if (downloaded) {
                        DropdownMenuItem(
                            text = { Text(stringResource(MR.strings.glass_inbox_delete_download)) },
                            onClick = { menu = false; model.showConfirmDeleteChapters(listOf(item)) },
                        )
                    }
                    if (item.update.read) {
                        DropdownMenuItem(
                            text = { Text(stringResource(MR.strings.glass_inbox_mark_unread)) },
                            onClick = { menu = false; model.markUpdatesRead(listOf(item), false) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CardButton(icon: String, tint: Color, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(28.dp).clip(CircleShape).border(1.dp, Glass.line, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { GlassIcon(icon, size = 14.dp, tint = tint, contentDescription = description) }
}

/**
 * The white bar of select-many (long-press a cover): Download / Mark read / Bookmark / Delete, Select all / Invert, close.
 * Acts on the selected items of the band ([UpdatesScreenModel] state).
 */
@Composable
fun GlassInboxSelectionBar(model: UpdatesScreenModel, modifier: Modifier = Modifier) {
    val state by model.state.collectAsStateWithLifecycle()
    val selected = state.selected
    if (selected.isEmpty()) return
    // select all / invert reach exactly what the band shows (its chip included), not the Classic list behind it
    val shownIds = InboxSeen.shown.value
    val listed = state.items.filter { it.update.chapterId in shownIds }
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(Glass.r16)).background(Color.White).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(MR.strings.glass_inbox_selected, selected.size), color = Color.Black, fontWeight = FontWeight.SemiBold, fontSize = Glass.body)
        BarText(stringResource(MR.strings.glass_inbox_select_all)) {
            listed.forEach { model.toggleSelection(it, UpdatesScreenModel.UpdateSelectionOptions(selected = true)) }
        }
        BarText(stringResource(MR.strings.glass_inbox_invert)) {
            listed.forEach { model.toggleSelection(it, UpdatesScreenModel.UpdateSelectionOptions(selected = !it.selected)) }
        }
        Box(Modifier.weight(1f))
        BarText(stringResource(MR.strings.glass_inbox_download), strong = true) { model.downloadChapters(selected, ChapterDownloadAction.START) }
        BarText(stringResource(MR.strings.glass_inbox_mark_read), strong = true) { model.markUpdatesRead(selected, true) }
        BarText(stringResource(MR.strings.glass_inbox_bookmark), strong = true) { model.bookmarkUpdates(selected, true) }
        BarText(stringResource(MR.strings.glass_inbox_delete), strong = true) { model.showConfirmDeleteChapters(selected) }
        GlassIcon("close", Modifier.clickable { model.toggleAllSelection(false) }.padding(4.dp), tint = Color.Black)
    }
}

@Composable
private fun BarText(text: String, strong: Boolean = false, onClick: () -> Unit) {
    Text(
        text,
        Modifier.clickable(onClick = onClick).padding(vertical = 6.dp),
        color = if (strong) Color.Black else Color.Black.copy(alpha = 0.62f),
        fontSize = Glass.caption,
        fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
    )
}

/** Resets Classic Updates' filter preferences (unread / started / bookmarked / downloaded; the excluded-scanlators choice is left alone) to their defaults. */
private fun clearClassicFilters() {
    val p = Injekt.get<tachiyomi.domain.updates.service.UpdatesPreferences>()
    p.filterDownloaded().delete()
    p.filterUnread().delete()
    p.filterStarted().delete()
    p.filterBookmarked().delete()
}

private val VOLUME_WORD = Regex("(?i)(?:(?<![a-z])(?:vol(?:ume)?|ch(?:apter)?)|\\u7b2c|\\bv)\\.?\\s*(\\d+(?:\\.\\d+)?)") // not the "ch" of "Zach"
private val ANY_NUMBER = Regex("\\d+(?:\\.\\d+)?")

/**
 * The number for a cover: the one after "Vol." / "Chapter" / "Ch." / "v" / 第 ("Mob Psycho 100 - Vol. 3" -> "03", "Spy x Family 2023 Vol 5"
 * -> "05", "Vol 1-2" -> "01"); only when there is no such word the first number; null when none.
 */
fun numberOf(chapterName: String): String? {
    val n = (VOLUME_WORD.find(chapterName)?.groupValues?.get(1) ?: ANY_NUMBER.find(chapterName)?.value) ?: return null
    return if (n.contains('.')) n else n.trimStart('0').ifEmpty { "0" }.padStart(2, '0')
}
