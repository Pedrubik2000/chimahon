package eu.kanade.tachiyomi.ui.glass.releases

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkManager
import cafe.adriel.voyager.navigator.Navigator
import chimahon.releases.ReleaseState
import chimahon.releases.VolState
import chimahon.releases.VolumeRow
import chimahon.releases.WatchStatus
import chimahon.releases.WatchedSeries
import coil3.compose.AsyncImage
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassChip
import eu.kanade.presentation.glass.GlassIcon
import eu.kanade.presentation.glass.GlassPhoneBarRoom
import eu.kanade.presentation.glass.GlassToggle
import eu.kanade.presentation.glass.Pill
import eu.kanade.presentation.glass.glassReleasesNew
import eu.kanade.presentation.util.isTabletUi
import eu.kanade.tachiyomi.data.anilist.AniListLibrary
import eu.kanade.tachiyomi.data.releases.ReleasesPlan
import eu.kanade.tachiyomi.data.releases.ReleasesWorker
import eu.kanade.tachiyomi.ui.glass.title.GlassTitleScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val JST = ZoneId.of("Asia/Tokyo") // the engine's "today" (release dates are Japanese)
private val DAY = DateTimeFormatter.ofPattern("EEE")
private val MONTH_DAY = DateTimeFormatter.ofPattern("MMM d")
private val MONTH_DAY_YEAR = DateTimeFormatter.ofPattern("MMM d, yyyy")

/** "Dec 2", with the year when it is not this year (a date from years ago must not read as this year's). */
private fun monthDay(d: LocalDate, today: LocalDate): String = (if (d.year == today.year) MONTH_DAY else MONTH_DAY_YEAR).format(d)
private val TIME = DateTimeFormatter.ofPattern("MMM d, HH:mm")

/** Keeps the rail badge current while the shell is up: when it is shown, comes back to the front, or a Releases job (now / daily / weekly) ends. Not while the page itself is open. */
@Composable
fun ReleasesBadgeEffect(page: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val wm = WorkManager.getInstance(context)
    val running = listOf(ReleasesWorker.WORK_NOW, ReleasesWorker.WORK_DAILY, ReleasesWorker.WORK_WEEKLY).map { name ->
        val infos by remember(name) { wm.getWorkInfosForUniqueWorkFlow(name) }.collectAsStateWithLifecycle(emptyList())
        infos.any { it.state == androidx.work.WorkInfo.State.RUNNING }
    }
    LaunchedEffect(page, running) { if (page != "releases") refreshReleasesNew(context) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (page != "releases") scope.launch { refreshReleasesNew(context) } }
}

private fun prefs(context: Context) = context.getSharedPreferences("glass_releases", Context.MODE_PRIVATE)

/** The rail badge: [GlassReleases.newCount] against the day the page was last opened. Reads off the main thread; call from a coroutine. */
suspend fun refreshReleasesNew(context: Context) {
    val n = withContext(Dispatchers.IO) {
        val seen = prefs(context).getLong("seen", -1L).takeIf { it >= 0 }?.let(LocalDate::ofEpochDay)
        GlassReleases.newCount(ReleasesWorker.store(context).get(), LocalDate.now(JST), seen)
    }
    glassReleasesNew.intValue = n
}

/** Opening the page: everything out so far counts as seen. */
private fun markSeen(context: Context) {
    prefs(context).edit().putLong("seen", LocalDate.now(JST).toEpochDay()).apply()
    glassReleasesNew.intValue = 0
}

private sealed interface Entry {
    data class Head(val kind: Int) : Entry // 0 out, 1 announced, 2 no date
    data object Today : Entry
    data class Row(val item: ReleaseItem, val showDate: Boolean) : Entry
}

/** Canvas `releases-a`: a timeline of volume days, TODAY in the middle, announced below, a No date lane; the bell opens the title's popover. */
@Composable
fun GlassReleasesPage(navigator: Navigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val phone = !isTabletUi()
    var state by remember { mutableStateOf(ReleaseState()) }
    var loaded by remember { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf(ReleasesFilter.ALL) }
    var popover by rememberSaveable { mutableStateOf<String?>(null) } // "anilistId:vol"
    var showUnwatched by rememberSaveable { mutableStateOf(false) }
    var today by remember { mutableStateOf(LocalDate.now(JST)) }

    suspend fun reload() {
        state = withContext(Dispatchers.IO) { ReleasesWorker.store(context).get() }
        today = LocalDate.now(JST)
        loaded = true
    }
    // the file is read again when the page comes back (the daily job may have run) and when a Check now starts or ends
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { scope.launch { reload() } }
    val work by remember { WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(ReleasesWorker.WORK_NOW) }
        .collectAsStateWithLifecycle(emptyList())
    val checking = work.any { !it.state.isFinished }
    LaunchedEffect(checking) { reload() }
    // the page is open: what is out counts as seen (the badge shows again for whatever comes out after)
    LaunchedEffect(Unit) { markSeen(context) }

    val timeline = remember(state, filter, today, showUnwatched) { GlassReleases.build(state, today, filter, showUnwatched) }
    val library = remember { Injekt.get<AniListLibrary>() }
    val entries = remember(timeline) {
        buildList {
            fun days(list: List<DaySection>) = list.forEach { d ->
                d.items.forEachIndexed { i, it -> add(Entry.Row(it, showDate = i == 0)) }
            }
            if (timeline.past.isNotEmpty()) add(Entry.Head(0))
            days(timeline.past)
            add(Entry.Today)
            if (timeline.upcoming.isNotEmpty()) add(Entry.Head(1))
            days(timeline.upcoming)
            if (timeline.noDate.isNotEmpty()) add(Entry.Head(2))
            timeline.noDate.forEach { add(Entry.Row(it, showDate = false)) }
        }
    }

    val checked = state.rows.maxOfOrNull { it.lastChecked }
    val checkNow: @Composable () -> Unit = {
        Pill(
            stringResource(if (checking) MR.strings.glass_rel_checking else MR.strings.glass_rel_check_now),
            onClick = { if (!checking) ReleasesWorker.runNow(context) },
            small = true,
        )
    }
    Column(
        Modifier.fillMaxSize().padding(start = if (phone) 16.dp else 108.dp, top = 24.dp, end = if (phone) 16.dp else 32.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(MR.strings.glass_rel_title), fontSize = Glass.title, fontWeight = FontWeight.SemiBold, maxLines = 1)
                if (loaded && state.series.isNotEmpty()) {
                    Text(
                        if (checked == null && state.lastRunAt == null) {
                            stringResource(MR.strings.glass_rel_never, state.series.size)
                        } else {
                            val at = state.lastRunAt?.let { TIME.format(java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) }
                                ?: MONTH_DAY.format(checked!!)
                            stringResource(MR.strings.glass_rel_summary, state.series.size, at)
                        },
                        color = Glass.secondary,
                        fontSize = Glass.body,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                state.lastError?.let {
                    Text(stringResource(MR.strings.glass_rel_error, it), color = Glass.red, fontSize = Glass.caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (!(loaded && state.series.isEmpty())) checkNow()
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassChip(stringResource(MR.strings.glass_rel_all), filter == ReleasesFilter.ALL, { filter = ReleasesFilter.ALL })
            GlassChip(stringResource(MR.strings.glass_rel_reading), filter == ReleasesFilter.READING, { filter = ReleasesFilter.READING })
            GlassChip(stringResource(MR.strings.glass_rel_up_to_date), filter == ReleasesFilter.UP_TO_DATE, { filter = ReleasesFilter.UP_TO_DATE })
            if (timeline.unwatched > 0) { // series switched off in the bell: hidden, one line brings them back (dimmed)
                Text(
                    stringResource(if (showUnwatched) MR.strings.glass_rel_not_watched_hide else MR.strings.glass_rel_not_watched_show, timeline.unwatched),
                    Modifier.clickable { showUnwatched = !showUnwatched }.padding(horizontal = 6.dp, vertical = 8.dp),
                    color = Glass.secondary,
                    fontSize = Glass.caption,
                    maxLines = 1,
                )
            }
        }
        if (loaded && state.series.isEmpty()) {
            Column(Modifier.padding(top = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(MR.strings.glass_rel_empty), color = Glass.secondary, fontSize = Glass.label)
                checkNow()
            }
        } else {
            val dayLabel = DAY.format(today) + " " + MONTH_DAY.format(today)
            val next = timeline.nextUp?.let { titleOf(library, it) }
            val heads = listOf(MR.strings.glass_rel_out, MR.strings.glass_rel_announced, MR.strings.glass_rel_no_date)
            LazyColumn(
                Modifier.fillMaxSize().padding(top = 12.dp),
                contentPadding = PaddingValues(bottom = if (phone) GlassPhoneBarRoom else 24.dp),
            ) {
                items(entries) { e ->
                    when (e) {
                        is Entry.Head -> Text(
                            stringResource(heads[e.kind]).uppercase(),
                            Modifier.padding(start = if (phone) 90.dp else 106.dp, top = 12.dp, bottom = 8.dp),
                            color = Glass.secondary,
                            fontSize = Glass.caption,
                        )
                        is Entry.Today -> {
                            val line = if (timeline.outToday == 0) {
                                stringResource(MR.strings.glass_rel_today_none, dayLabel)
                            } else {
                                stringResource(MR.strings.glass_rel_today_some, dayLabel, timeline.outToday)
                            }
                            TodayLine(line + (next?.let { " · " + stringResource(MR.strings.glass_rel_next, it) } ?: ""), phone)
                        }
                        is Entry.Row -> TimelineRow(
                            e, today, library, phone,
                            open = { navigator.push(GlassTitleScreen(e.item.series.anilistId)) },
                            bell = { popover = "${e.item.series.anilistId}:${e.item.row.vol}" },
                        ) {
                            if (popover == "${e.item.series.anilistId}:${e.item.row.vol}") {
                                BellPopover(e.item, state.rows, titleOf(library, e.item), scope, onClose = { popover = null }) { reload() }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The cover's own title (native first like the other glass pages), else the series' search title. */
private fun titleOf(library: AniListLibrary, item: ReleaseItem): String {
    val e = library.get(item.series.anilistId)
    return e?.native ?: e?.romaji ?: e?.english ?: item.series.jpTitle
}

private fun stateColor(s: VolState) = when (s) {
    VolState.ANNOUNCED, VolState.READ -> Glass.secondary
    VolState.RELEASED -> Color.White
    VolState.ON_SOURCES -> Glass.blue
    VolState.ON_TABLET -> Glass.green
}

@Composable
private fun TodayLine(text: String, phone: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(MR.strings.glass_rel_today).uppercase(),
            Modifier.width(if (phone) 64.dp else 80.dp),
            fontWeight = FontWeight.Bold,
            fontSize = Glass.body,
            maxLines = 1,
        )
        Box(Modifier.width(26.dp), contentAlignment = Alignment.Center) { Box(Modifier.size(14.dp).clip(CircleShape).background(Color.White)) }
        Column(Modifier.weight(1f)) {
            Box(Modifier.fillMaxWidth().height(2.dp).background(Color.White))
            Text(text, Modifier.padding(top = 4.dp), color = Glass.secondary, fontSize = Glass.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun TimelineRow(
    e: Entry.Row,
    today: LocalDate,
    library: AniListLibrary,
    phone: Boolean,
    open: () -> Unit,
    bell: () -> Unit,
    popover: @Composable () -> Unit,
) {
    val item = e.item
    val row = item.row
    val title = titleOf(library, item)
    val cover = library.get(item.series.anilistId)?.cover
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Column(Modifier.width(if (phone) 64.dp else 80.dp).padding(top = 14.dp), horizontalAlignment = Alignment.End) {
            val d = row.date
            if (d != null && e.showDate) {
                Text(DAY.format(d), color = Glass.secondary, fontSize = Glass.caption)
                Text(monthDay(d, today), fontSize = Glass.label, fontWeight = FontWeight.Medium, maxLines = 1)
                row.previousDate?.let {
                    Text(monthDay(it, today), color = Glass.secondary, fontSize = Glass.caption, textDecoration = TextDecoration.LineThrough)
                }
            } else if (d == null) {
                Text("–", color = Glass.secondary, fontSize = Glass.label)
            }
        }
        // the timeline: one line through every row, a dot near the row's top
        Box(Modifier.width(26.dp).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.width(2.dp).fillMaxHeight().background(Glass.line))
            Box(Modifier.padding(top = 26.dp).size(10.dp).clip(CircleShape).background(stateColor(row.state)))
        }
        val shape = RoundedCornerShape(Glass.r12)
        Row(
            Modifier
                .weight(1f)
                .padding(bottom = 10.dp)
                .alpha(if (item.dimmed) 0.5f else 1f)
                .clip(shape)
                .background(Glass.panel)
                .border(1.dp, Glass.line, shape)
                .clickable(onClick = open)
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(width = 44.dp, height = 64.dp).clip(RoundedCornerShape(Glass.r8)).background(Glass.page)) {
                AsyncImage(cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(title, Modifier.weight(1f, fill = false), fontSize = Glass.label, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(MR.strings.glass_rel_vol, row.vol), color = Glass.secondary, fontSize = Glass.body, maxLines = 1)
                }
                Text(stateLine(item, today), color = stateColor(row.state), fontSize = Glass.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (item.behind > 0) Text(stringResource(MR.strings.glass_rel_behind, item.behind), color = Glass.ice, fontSize = Glass.caption)
                if (row.previousDate != null && row.date != null) {
                    Text(stringResource(MR.strings.glass_rel_date_changed), color = Glass.ice, fontSize = Glass.caption)
                }
            }
            if (!item.dimmed) when (row.state) {
                VolState.ON_SOURCES -> Pill(stringResource(MR.strings.glass_rel_fetch), onClick = open, small = true, primary = true)
                VolState.ON_TABLET -> Pill(stringResource(MR.strings.glass_rel_read), onClick = open, small = true)
                else -> Unit
            }
            Box {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).border(1.dp, Glass.line, CircleShape).clickable(onClick = bell),
                    contentAlignment = Alignment.Center,
                ) {
                    GlassIcon("bell", size = 20.dp, contentDescription = stringResource(MR.strings.glass_rel_bell, title))
                }
                popover()
            }
        }
    }
}

@Composable
private fun stateLine(item: ReleaseItem, today: LocalDate): String {
    val row = item.row
    val date = row.date
    return when {
        row.state == VolState.ON_SOURCES && (date == null || date <= today) -> row.fetchSource?.let { stringResource(MR.strings.glass_rel_on_source, it) } ?: stringResource(MR.strings.glass_rel_on_sources)
        date == null -> stringResource(MR.strings.glass_rel_no_date_yet)
        row.state == VolState.ANNOUNCED || date > today -> {
            val n = GlassReleases.daysTo(today, date)
            if (n == 1) stringResource(MR.strings.glass_rel_announced_tomorrow) else stringResource(MR.strings.glass_rel_announced_in, n)
        }
        row.state == VolState.ON_SOURCES -> row.fetchSource?.let { stringResource(MR.strings.glass_rel_on_source, it) } // the download source, never a date source
            ?: stringResource(MR.strings.glass_rel_on_sources)
        row.state == VolState.ON_TABLET -> "✓ " + stringResource(MR.strings.glass_rel_on_tablet)
        else -> {
            val ago = -GlassReleases.daysTo(today, date)
            val from = row.source // where the date came from (books.or.jp, NDL, Kodansha): not a place to fetch from
            when {
                ago <= 0 && from != null -> stringResource(MR.strings.glass_rel_out_from, from)
                ago <= 0 -> stringResource(MR.strings.glass_rel_out_waiting)
                from != null -> stringResource(MR.strings.glass_rel_out_days_from, ago, from)
                else -> stringResource(MR.strings.glass_rel_out_days, ago)
            }
        }
    }
}

/** The bell's popover: auto-fetch on/off and the Japanese search title (both saved in the store right away). */
@Composable
/** [scope] is the page's: a save started as the popover closes must not be cancelled with it. */
private fun BellPopover(item: ReleaseItem, rows: List<VolumeRow>, title: String, scope: CoroutineScope, onClose: () -> Unit, changed: suspend () -> Unit) {
    val context = LocalContext.current
    val series = item.series
    val plan = series.status == WatchStatus.PLAN
    // one rule with the notifier (null in the store = ReleasesPlan.autoFetchOn); Plan to read is never auto-fetched
    var auto by remember { mutableStateOf(ReleasesPlan.autoFetchOn(series, rows)) }
    var watching by remember { mutableStateOf(series.watching != false) } // null in the store = watched
    var jp by remember { mutableStateOf(series.jpTitle) }

    fun save(change: (WatchedSeries) -> WatchedSeries) {
        scope.launch {
            // NonCancellable: leaving the page right after closing the popover must not drop the edit
            val saved = withContext(NonCancellable + Dispatchers.IO) {
                runCatching {
                    ReleasesWorker.store(context).update { s ->
                        s.copy(series = s.series.map { if (it.anilistId == series.anilistId) change(it) else it })
                    }
                }
            }
            if (saved.isFailure) {
                android.widget.Toast.makeText(context, saved.exceptionOrNull()?.message ?: "Couldn't save", android.widget.Toast.LENGTH_SHORT).show()
                auto = ReleasesPlan.autoFetchOn(series, rows)
                watching = series.watching != false
            }
            changed()
        }
    }
    fun close() {
        val t = jp.trim()
        if (t.isNotEmpty() && t != series.jpTitle) save { it.copy(jpTitle = t) }
        onClose()
    }

    Popup(alignment = Alignment.TopEnd, onDismissRequest = ::close, properties = PopupProperties(focusable = true)) {
        val shape = RoundedCornerShape(Glass.r16)
        Column(
            Modifier.width(300.dp).clip(shape).background(Glass.panel).border(1.dp, Glass.line, shape).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column {
                Text(title, fontSize = Glass.heading, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(MR.strings.glass_rel_you_on, series.lastKnownVol), color = Glass.secondary, fontSize = Glass.body)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(MR.strings.glass_rel_watch), fontSize = Glass.label)
                    Text(stringResource(MR.strings.glass_rel_watch_hint), color = Glass.secondary, fontSize = Glass.caption)
                }
                GlassToggle(watching, { v ->
                    watching = v
                    save { it.copy(watching = v) }
                })
            }
            Row(Modifier.alpha(if (watching) 1f else 0.4f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(MR.strings.glass_rel_auto_fetch), fontSize = Glass.label)
                    Text(
                        stringResource(if (plan) MR.strings.glass_rel_auto_fetch_plan else MR.strings.glass_rel_auto_fetch_hint),
                        color = Glass.secondary,
                        fontSize = Glass.caption,
                    )
                }
                Box(Modifier.alpha(if (plan) 0.4f else 1f)) {
                    GlassToggle(auto, { v ->
                        if (!plan) {
                            auto = v
                            save { it.copy(autoFetch = v) }
                        }
                    })
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(MR.strings.glass_rel_search_title), fontSize = Glass.label)
                Box(
                    Modifier.fillMaxWidth().height(Glass.chip).clip(CircleShape).background(Glass.page).border(1.dp, Glass.line, CircleShape).padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    BasicTextField(
                        value = jp,
                        onValueChange = { jp = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = Glass.body),
                        cursorBrush = SolidColor(Color.White),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { close() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
