package eu.kanade.tachiyomi.ui.glass.stats

import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassChip
import eu.kanade.presentation.glass.GlassIcon
import eu.kanade.presentation.glass.Pill
import eu.kanade.presentation.glass.glassPanel
import eu.kanade.presentation.more.stats.StatsLoadable
import eu.kanade.presentation.more.stats.StatsRangePreset
import eu.kanade.presentation.more.stats.StatsScreenState
import eu.kanade.presentation.more.stats.StatsSection
import eu.kanade.presentation.more.stats.StatsTab
import eu.kanade.presentation.more.stats.characterValue
import eu.kanade.presentation.more.stats.formatCount
import eu.kanade.presentation.more.stats.formatDurationCompact
import eu.kanade.presentation.more.stats.formatGoalValue
import eu.kanade.presentation.more.stats.formatRate
import eu.kanade.presentation.more.stats.goalTypeLabel
import eu.kanade.presentation.more.stats.tabLabel
import eu.kanade.tachiyomi.ui.stats.StatsExportEffect
import eu.kanade.tachiyomi.ui.stats.StatsRawTextDisclosure
import eu.kanade.tachiyomi.ui.stats.StatsRecentsPrivacy
import eu.kanade.tachiyomi.ui.stats.StatsScreenModel
import tachiyomi.domain.immersion.model.AnalyticsResult
import tachiyomi.domain.immersion.service.ImmersionStatsPreferences
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.NumberFormat
import kotlin.math.abs

/** The period pills and the Classic range each one selects (90 days has no pill; it shows none selected). */
internal val GlassStatsPeriods = listOf(
    StatsRangePreset.TODAY to MR.strings.glass_stats_today,
    StatsRangePreset.SEVEN_DAYS to MR.strings.glass_stats_week,
    StatsRangePreset.THIRTY_DAYS to MR.strings.glass_stats_month,
    StatsRangePreset.YEAR to MR.strings.glass_stats_year,
    StatsRangePreset.ALL to MR.strings.glass_stats_all,
)

private val TabIcons = mapOf(
    StatsTab.OVERVIEW to "stats",
    StatsTab.ACTIVITY to "calendar",
    StatsTab.TITLES to "library",
    StatsTab.CHARACTERS to "book",
    StatsTab.SESSIONS to "clock",
    StatsTab.GOALS to "target",
    StatsTab.ANKI to "cards",
)

/**
 * Canvas `option-b` card 1: Stats on its own rail page. Left: the Classic tabs as a sidebar. Right: the tab. Overview is
 * drawn in Glass from the Classic [StatsScreenModel] sections, as are the other six tabs and the title / session / character
 * detail pages (an open detail hides the sidebar and the period pills).
 */
@Composable
fun GlassStatsPage(navigator: Navigator, statsModel: @Composable () -> StatsScreenModel) {
    StatsRecentsPrivacy()
    val prefs = remember { Injekt.get<ImmersionStatsPreferences>() }
    val enabled by prefs.uiEnabled().changes().collectAsState(prefs.uiEnabled().get())
    if (!enabled) {
        Column(
            Modifier.fillMaxSize().padding(start = 132.dp, end = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Text(stringResource(KMR.strings.stats_preview_disabled), color = Glass.secondary, fontSize = Glass.label)
            Pill(stringResource(KMR.strings.stats_preview_enable), { prefs.uiEnabled().set(true) }, primary = true)
        }
        return
    }
    val model = statsModel()
    val state by model.state.collectAsState()
    // the model lives as long as the shell: fresh numbers each time the page is entered
    androidx.compose.runtime.LaunchedEffect(Unit) { if (model.state.value is StatsScreenState.Success) model.refresh() }
    StatsExportEffect(model)
    StatsRawTextDisclosure(model)
    val success = state as? StatsScreenState.Success
    // one scroll position per tab, kept here so closing a Classic detail returns to the same place
    val listStates = StatsTab.entries.associateWith { rememberLazyListState() }
    val detailOpen = success != null && isDetailOpen(success.selectedTab, success.selection.title != null, success.selection.character != null, success.selection.session != null)
    // Back closes an open title / character / session detail (the shell's handler would go Home)
    BackHandler(enabled = detailOpen) {
        when (success?.selectedTab) {
            StatsTab.TITLES -> model.selectTitle(null)
            StatsTab.CHARACTERS -> model.selectCharacter(null)
            StatsTab.SESSIONS -> model.selectSession(null)
            else -> Unit
        }
    }

    Row(Modifier.fillMaxSize()) {
        Spacer(Modifier.width(100.dp)) // the rail
        // an open detail is a full page: no tab sidebar and no period pills (mockup A)
        if (!detailOpen) Column(
            Modifier
                .width(264.dp)
                .fillMaxHeight()
                .background(Glass.panel)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                stringResource(MR.strings.glass_stats),
                Modifier.padding(start = 12.dp, end = 12.dp, bottom = 14.dp),
                fontSize = Glass.title,
                fontWeight = FontWeight.SemiBold,
            )
            StatsTab.entries
                .filter { (it != StatsTab.GOALS || success?.goalsEnabled != false) && (it != StatsTab.ANKI || success?.ankiEnabled != false) }
                .forEach { tab ->
                    val on = success?.selectedTab == tab
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(Glass.button)
                            .clip(CircleShape)
                            .background(if (on) Color.White else Color.Transparent)
                            .clickable { model.selectTab(tab) }
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        GlassIcon(TabIcons.getValue(tab), size = 20.dp, tint = if (on) Color.Black else Color.White)
                        Text(
                            tabLabel(tab),
                            color = if (on) Color.Black else Color.White,
                            fontSize = Glass.label,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
        }
        if (!detailOpen) Box(Modifier.width(1.dp).fillMaxHeight().background(Glass.line))
        // a narrow page (the tablet upright, split screen): the periods go under the title, the tiles two a row, the panels one a row
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
        val narrow = maxWidth < NARROW
        Column(Modifier.fillMaxSize().padding(start = 32.dp, top = 28.dp, end = 32.dp)) {
            if (success == null) return@Column
            if (!detailOpen) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(tabLabel(success.selectedTab), fontSize = Glass.heading, fontWeight = FontWeight.SemiBold)
                    periodLine(success)?.let { Text(it, color = Glass.secondary, fontSize = Glass.body, maxLines = 1) }
                    if (success.hasHiddenFilters()) {
                        Pill(stringResource(MR.strings.glass_stats_filtered), { model.clearHiddenFilters() }, small = true, modifier = Modifier.padding(top = 6.dp))
                    }
                }
                eu.kanade.presentation.glass.GlassRoundButton("restore", model::refresh, description = stringResource(KMR.strings.stats_refresh), iconSize = 20.dp)
                if (!narrow) FlowPills(success.filter.rangePreset, model::selectRange)
            }
            if (narrow && !detailOpen) {
                Spacer(Modifier.height(12.dp))
                FlowPills(success.filter.rangePreset, model::selectRange)
            }
            Spacer(Modifier.height(16.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (success.selectedTab == StatsTab.OVERVIEW) {
                    GlassStatsOverview(success, onTab = model::selectTab, onRetry = model::retrySection, narrow = narrow)
                } else if (detailOpen) {
                    GlassStatsDetail(success, model, navigator, narrow)
                } else {
                    when (success.selectedTab) {
                        StatsTab.ACTIVITY -> GlassStatsActivity(success, model, narrow, listStates.getValue(StatsTab.ACTIVITY))
                        StatsTab.TITLES -> GlassStatsTitles(success, model, narrow, listStates.getValue(StatsTab.TITLES))
                        StatsTab.CHARACTERS -> GlassStatsCharacters(success, model, narrow, listStates.getValue(StatsTab.CHARACTERS))
                        StatsTab.SESSIONS -> GlassStatsSessions(success, model, narrow, listStates.getValue(StatsTab.SESSIONS))
                        StatsTab.GOALS -> GlassStatsGoals(success, model, narrow, listStates.getValue(StatsTab.GOALS))
                        else -> GlassStatsAnki(success, model, narrow, listStates.getValue(StatsTab.ANKI))
                    }
                }
            }
        }
        }
    }
}

/** Under this width of the Stats content the layout is the narrow one. */
private val NARROW = 600.dp

/** Two panels side by side (3 : 2), or one under the other on a [narrow] page. */
@Composable
internal fun PanelRow(narrow: Boolean, first: @Composable (Modifier) -> Unit, second: (@Composable (Modifier) -> Unit)? = null) {
    if (narrow) {
        first(Modifier.fillMaxWidth())
        second?.invoke(Modifier.fillMaxWidth())
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            first(Modifier.weight(3f))
            second?.invoke(Modifier.weight(2f))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowPills(current: StatsRangePreset, onSelect: (StatsRangePreset) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        GlassStatsPeriods.forEach { (preset, label) ->
            GlassChip(stringResource(label), selected = current == preset, onClick = { onSelect(preset) })
        }
    }
}

/** "Nov 21 – 27" from the overview's own period, null until it is loaded. */
@Composable
private fun periodLine(state: StatsScreenState.Success): String? {
    val range = state.sections.overview.value?.value?.period?.range ?: return null
    val f = java.time.format.DateTimeFormatter.ofPattern("MMM d")
    val a = range.start.toLocalDate()
    val b = range.endInclusive.toLocalDate()
    return if (a == b) a.format(f) else "${a.format(f)} – ${b.format(f)}"
}

/** The Overview tab in Glass (canvas option-b): six tiles, the last 14 days, goals, titles, Anki. Data = the Classic sections. */
@Composable
internal fun GlassStatsOverview(state: StatsScreenState.Success, onTab: (StatsTab) -> Unit, onRetry: (StatsSection) -> Unit, narrow: Boolean = false) {
    val overview = state.sections.overview.value?.value
    val metric = state.filter.characterMetric
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (overview == null) {
            if (state.sections.overview.error) {
                Pill(stringResource(KMR.strings.stats_retry_section), { onRetry(StatsSection.OVERVIEW) }, small = true)
            } else {
                Text(stringResource(MR.strings.glass_stats_loading), color = Glass.secondary, fontSize = Glass.body)
            }
            return@Column
        }
        val cur = overview.comparison.current
        val tiles = listOf(
            Tile(formatDurationCompact(cur.activeTime.value), stringResource(KMR.strings.stats_active_time), delta(overview.comparison.activeTimeChangeRatio)),
            Tile(formatCount(cur.characterValue(metric)), stringResource(MR.strings.glass_stats_chars), delta(overview.comparison.characterChangeRatio)),
            Tile(
                cur.readingSpeedPerHour(metric)?.let { formatRate(it) } ?: "-",
                stringResource(MR.strings.glass_stats_chars_per_hour),
                null,
            ),
            Tile(
                overview.streak.currentDays.toString(),
                stringResource(MR.strings.glass_stats_streak),
                stringResource(MR.strings.glass_stats_longest, overview.streak.longestDays),
            ),
            Tile(formatCount(cur.sessions.value), stringResource(KMR.strings.stats_sessions), null),
            Tile(formatCount(cur.cardsCreated.value), stringResource(KMR.strings.stats_cards_created), null),
        )
        tiles.chunked(if (narrow) 2 else 3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEach { t -> StatTile(t, Modifier.weight(1f)) }
            }
        }
        PanelRow(
            narrow,
            { m -> Panel(chartTitle(state), m) { Bars(state) } },
            { m ->
                Panel(stringResource(MR.strings.glass_stats_goals), m, action = {
                    if (state.goalsEnabled) Text(stringResource(KMR.strings.stats_view_all), color = Glass.ice, fontSize = Glass.body, modifier = Modifier.clickable { onTab(StatsTab.GOALS) })
                }) { Goals(state, onTab) }
            },
        )
        PanelRow(
            narrow,
            { m ->
                Panel(tabLabel(StatsTab.TITLES), m, action = {
                    Text(stringResource(KMR.strings.stats_view_all), color = Glass.ice, fontSize = Glass.body, modifier = Modifier.clickable { onTab(StatsTab.TITLES) })
                }) { TitlesList(state) }
            },
            if (state.ankiEnabled) {
                { m ->
                    Panel(stringResource(MR.strings.glass_stats_anki), m, action = {
                        Text(stringResource(KMR.strings.stats_view_all), color = Glass.ice, fontSize = Glass.body, modifier = Modifier.clickable { onTab(StatsTab.ANKI) })
                    }) { AnkiBlock(state) }
                }
            } else {
                null
            },
        )
        Spacer(Modifier.height(16.dp))
    }
}

/** [selected] = the ice 1 dp outline and ice number (Activity: the tile the chart shows); [onClick] makes the tile a button. */
internal class Tile(val value: String, val label: String, val note: String?, val selected: Boolean = false, val onClick: (() -> Unit)? = null)

@Composable
internal fun delta(ratio: Double?): String? = ratio?.let {
    val pct = NumberFormat.getPercentInstance().apply { maximumFractionDigits = 0 }.format(abs(it))
    (if (it >= 0) "+" else "-") + pct
}

@Composable
internal fun StatTile(t: Tile, modifier: Modifier) {
    val shape = RoundedCornerShape(Glass.r16)
    Column(
        modifier
            .heightIn(min = 112.dp)
            .glassPanel(Glass.r16)
            .then(if (t.selected) Modifier.border(1.dp, Glass.ice, shape) else Modifier)
            .then(if (t.onClick != null) Modifier.clickable(onClick = t.onClick) else Modifier)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(t.value, color = if (t.selected) Glass.ice else Color.White, fontSize = Glass.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(t.label, color = Glass.secondary, fontSize = Glass.body, maxLines = 1)
        t.note?.let { Text(it, color = Glass.ice, fontSize = Glass.body, maxLines = 1) }
    }
}

@Composable
/** [actionBelow]: the action goes under the title instead of beside it (a narrow page, where chips beside it cut the title to "Sess…"). */
internal fun Panel(title: String, modifier: Modifier, action: @Composable () -> Unit = {}, actionBelow: Boolean = false, content: @Composable () -> Unit) {
    Column(modifier.heightIn(min = 200.dp).glassPanel(Glass.r16).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), fontSize = Glass.label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!actionBelow) action()
        }
        if (actionBelow) action()
        content()
    }
}

/** The days [Bars] draws: the chosen period's, at most the last 14. */
private fun chartPoints(state: StatsScreenState.Success) = state.sections.heatmap.value?.value?.points.orEmpty().takeLast(14)

/** "Reading time · last 7 days" for the bars that are drawn; one day is named ("Reading time · Nov 27"). */
@Composable
private fun chartTitle(state: StatsScreenState.Success): String {
    val points = chartPoints(state)
    return if (points.size == 1) {
        val day = points[0].range.start.toLocalDate().format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))
        stringResource(MR.strings.glass_stats_chart_day, day)
    } else {
        stringResource(MR.strings.glass_stats_chart, if (points.isEmpty()) 14 else points.size)
    }
}

/** The chosen period's days (at most the last 14) of the Classic heatmap section: active time per day, today in ice. */
@Composable
private fun Bars(state: StatsScreenState.Success) {
    val points = chartPoints(state)
    if (points.isEmpty()) {
        Text(stringResource(MR.strings.glass_stats_none), color = Glass.secondary, fontSize = Glass.body)
        return
    }
    val values = points.map { it.metrics.activeTime.value }
    val max = values.max().coerceAtLeast(1L)
    Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
        points.forEachIndexed { i, p ->
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .height((110 * values[i].toFloat() / max).dp.coerceAtLeast(2.dp))
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                        .background(if (i == points.lastIndex) Glass.ice else Glass.secondary),
                )
                Text(p.range.start.toLocalDate().dayOfMonth.toString(), color = Glass.secondary, fontSize = Glass.caption, maxLines = 1)
            }
        }
    }
}

/** Today's goals from the Classic goals section: the first one as a ring, all as lines. */
@Composable
private fun Goals(state: StatsScreenState.Success, onTab: (StatsTab) -> Unit) {
    val goals = state.sections.goals.value?.value.orEmpty().filter { it.todayTarget > 0.0 }.take(3)
    if (!state.goalsEnabled || goals.isEmpty()) {
        Text(stringResource(MR.strings.glass_stats_no_goals), color = Glass.secondary, fontSize = Glass.body)
        if (state.goalsEnabled) Pill(stringResource(MR.strings.glass_stats_new_goal), { onTab(StatsTab.GOALS) }, small = true, icon = "plus")
        return
    }
    val first = goals.first()
    val fraction = (first.todayAchieved / first.todayTarget).coerceIn(0.0, 1.0).toFloat()
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val w = 8.dp.toPx()
                val inset = Offset(w / 2, w / 2)
                val s = Size(size.width - w, size.height - w)
                drawArc(Glass.line, 0f, 360f, false, inset, s, style = Stroke(w))
                drawArc(Glass.ice, -90f, 360f * fraction, false, inset, s, style = Stroke(w, cap = StrokeCap.Round))
            }
            Text(NumberFormat.getPercentInstance().format(fraction.toDouble()), fontSize = Glass.body, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            goals.forEach { g ->
                Column {
                    Text(goalTypeLabel(g.goal.type), fontSize = Glass.body, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(
                        stringResource(KMR.strings.stats_goal_progress, formatGoalValue(g.goal.metric, g.todayAchieved), formatGoalValue(g.goal.metric, g.todayTarget)),
                        color = Glass.secondary,
                        fontSize = Glass.caption,
                    )
                }
            }
        }
    }
}

/** The top three titles of the period (Classic titles section): name, characters, a time bar. */
@Composable
private fun TitlesList(state: StatsScreenState.Success) {
    val rows = state.sections.titles.value?.value?.items.orEmpty().take(3)
    if (rows.isEmpty()) {
        Text(stringResource(MR.strings.glass_stats_none), color = Glass.secondary, fontSize = Glass.body)
        return
    }
    val max = rows.maxOf { it.metrics.activeTime.value }.coerceAtLeast(1L)
    rows.forEach { r ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(2f)) {
                Text(r.displayTitle, fontSize = Glass.label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(formatCount(r.metrics.characterValue(state.filter.characterMetric)), color = Glass.secondary, fontSize = Glass.caption, maxLines = 1)
            }
            Box(Modifier.weight(2f).height(6.dp).clip(CircleShape).background(Glass.line)) {
                Box(Modifier.fillMaxWidth(r.metrics.activeTime.value.toFloat() / max).fillMaxHeight().background(Color.White))
            }
            Text(formatDurationCompact(r.metrics.activeTime.value), Modifier.width(72.dp), fontSize = Glass.body, maxLines = 1)
        }
    }
}

/** Cards from reading and character coverage (Classic Anki section). */
@Composable
private fun AnkiBlock(state: StatsScreenState.Success) {
    val a = state.sections.anki.value?.value
    if (a == null) {
        Text(stringResource(MR.strings.glass_stats_none), color = Glass.secondary, fontSize = Glass.body)
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column {
            Text(formatCount(a.cardsCreated), fontSize = Glass.heading, fontWeight = FontWeight.SemiBold)
            Text(stringResource(KMR.strings.stats_cards_created), color = Glass.secondary, fontSize = Glass.caption)
        }
        Column {
            Text("${formatCount(a.characterCoverageKnown)} / ${formatCount(a.characterCoverageEncountered)}", fontSize = Glass.heading, fontWeight = FontWeight.SemiBold)
            Text(stringResource(KMR.strings.stats_character_coverage), color = Glass.secondary, fontSize = Glass.caption)
        }
    }
}

/** A saved Classic filter this page has no control for is active (the Classic screen keeps them across visits). */
private fun StatsScreenState.Success.hasHiddenFilters(): Boolean = filter.let {
    it.titleId != null || it.mediaKind != null || it.profileId != null || it.periodOffset != 0 ||
        it.maturityTiers.isNotEmpty() || it.provenanceStates.isNotEmpty() || !it.includeLegacy || !it.includeRereadsAndReplays
}

/** Back to the defaults, through the model's own setters (they also save the preference). */
private fun StatsScreenModel.clearHiddenFilters() {
    selectTitleFilter(null)
    selectMedia(null)
    selectProfile(null)
    movePeriod(0)
    selectMaturityTiers(emptySet())
    selectProvenanceStates(emptySet())
    setIncludeLegacy(true)
    setIncludeRereads(true)
}

/** Tiles: [perRow] a row, two on a narrow page. */
@Composable
internal fun Tiles(tiles: List<Tile>, narrow: Boolean, perRow: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        tiles.chunked(if (narrow) 2 else perRow).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEach { t -> StatTile(t, Modifier.weight(1f)) }
                repeat((if (narrow) 2 else perRow) - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** A tab body: ONE lazy list, so long lists only compose what is on screen. */
@Composable
internal fun TabList(listState: LazyListState, content: LazyListScope.() -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), state = listState, content = content)
}

/** A standalone item with the 14 dp gap under it. */
internal fun LazyListScope.block(content: @Composable () -> Unit) {
    item { Column(Modifier.padding(bottom = 14.dp)) { content() } }
}

/** The 2 dp ice line at the top of a tab while its sections refresh over content already shown. */
internal fun LazyListScope.refreshLine(show: Boolean) {
    if (show) {
        item(key = "refresh") {
            Box(Modifier.padding(bottom = 12.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Glass.ice, trackColor = Glass.line)
            }
        }
    }
}

/** True while a section that already has content is loading again. */
internal fun StatsLoadable<*>.refreshingOverContent(): Boolean = refreshing && value != null

/** "Load more", or the loading text while that page loads. */
@Composable
internal fun LoadMore(loading: Boolean, onClick: () -> Unit) {
    if (loading) {
        Text(stringResource(MR.strings.glass_stats_loading), color = Glass.secondary, fontSize = Glass.body)
    } else {
        Pill(stringResource(KMR.strings.stats_load_more), onClick, small = true)
    }
}

/**
 * One slice of a panel that is spread over several lazy items: the first has the top corners, the last the bottom ones, the rest
 * only the side lines, so together they look like one rounded panel. [content] sits inside the 18 dp side padding.
 */
@Composable
internal fun PanelSlice(top: Boolean, bottom: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(bottom = if (bottom) 14.dp else 0.dp)
            .drawBehind {
                val r = Glass.r16.toPx()
                val w = size.width
                val h = size.height
                val top0 = androidx.compose.ui.geometry.CornerRadius(if (top) r else 0f)
                val bot0 = androidx.compose.ui.geometry.CornerRadius(if (bottom) r else 0f)
                val fill = androidx.compose.ui.graphics.Path().apply {
                    addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, w, h, top0, top0, bot0, bot0))
                }
                drawPath(fill, Glass.panel)
                val line = androidx.compose.ui.graphics.Path().apply {
                    moveTo(0f, if (bottom) h - r else h)
                    lineTo(0f, if (top) r else 0f)
                    if (top) {
                        arcTo(androidx.compose.ui.geometry.Rect(0f, 0f, 2 * r, 2 * r), 180f, 90f, false)
                        lineTo(w - r, 0f)
                        arcTo(androidx.compose.ui.geometry.Rect(w - 2 * r, 0f, w, 2 * r), 270f, 90f, false)
                    } else {
                        moveTo(w, 0f)
                    }
                    lineTo(w, if (bottom) h - r else h)
                    if (bottom) {
                        arcTo(androidx.compose.ui.geometry.Rect(w - 2 * r, h - 2 * r, w, h), 0f, 90f, false)
                        lineTo(r, h)
                        arcTo(androidx.compose.ui.geometry.Rect(0f, h - 2 * r, 2 * r, h), 90f, 90f, false)
                    }
                }
                drawPath(line, Glass.line, style = Stroke(1.dp.toPx()))
            }
            .padding(start = 18.dp, end = 18.dp, top = if (top) 18.dp else 0.dp, bottom = if (bottom) 18.dp else 0.dp),
    ) { content() }
}

/** A Classic section: its value when loaded, else the retry pill (failed) or "Loading" (as the Overview does). */
@Composable
internal fun <T> LoadableBody(section: StatsLoadable<AnalyticsResult<T>>, onRetry: () -> Unit, content: @Composable (T) -> Unit) {
    val value = section.value?.value
    when {
        value != null -> content(value)
        section.error -> Pill(stringResource(KMR.strings.stats_retry_section), onRetry, small = true)
        else -> Text(stringResource(MR.strings.glass_stats_loading), color = Glass.secondary, fontSize = Glass.body)
    }
}

/** The search field of a list panel: the 40 dp pill the Releases search uses. */
@Composable
internal fun GlassSearchBox(query: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    Row(
        modifier.height(Glass.chip).clip(CircleShape).background(Glass.page).border(1.dp, Glass.line, CircleShape).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GlassIcon("search", size = 16.dp, tint = Glass.secondary)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) Text(placeholder, color = Glass.secondary, fontSize = Glass.body, maxLines = 1)
            androidx.compose.foundation.text.BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = Glass.body),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** A chip that opens a small menu of [options] (the Classic filter / sort menus). */
@Composable
internal fun <T> GlassMenuChip(label: String, options: List<T>, optionLabel: @Composable (T) -> String, onSelect: (T) -> Unit) {
    var open by remember { androidx.compose.runtime.mutableStateOf(false) }
    Box {
        GlassChip(label, selected = false, onClick = { open = true }, icon = "chevDown")
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                androidx.compose.material3.DropdownMenuItem(text = { Text(optionLabel(option)) }, onClick = { onSelect(option); open = false })
            }
        }
    }
}

/**
 * Bars for [values] (the last one ice, the others white 62%) with an optional dashed [average] line and the first / last labels
 * under them (Activity chart, Characters growth, Anki weekly cards).
 */
@Composable
internal fun BarChart(values: List<Double>, average: List<Double>?, firstLabel: String, lastLabel: String, modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 170.dp) {
    val max = (values + (average ?: emptyList())).maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val slot = size.width / values.size
            val barW = (slot * 0.7f).coerceAtLeast(1f)
            values.forEachIndexed { i, v ->
                if (v <= 0.0) return@forEachIndexed
                val h = (size.height * (v / max)).toFloat().coerceAtLeast(2f)
                drawRect(
                    if (i == values.lastIndex) Glass.ice else Glass.secondary,
                    Offset(i * slot + (slot - barW) / 2, size.height - h),
                    Size(barW, h),
                )
            }
            if (average != null && average.size > 1) {
                val path = androidx.compose.ui.graphics.Path()
                average.forEachIndexed { i, v ->
                    val x = i * slot + slot / 2
                    val y = size.height - (size.height * (v / max)).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path,
                    Color.White,
                    style = Stroke(2.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(firstLabel, color = Glass.secondary, fontSize = Glass.caption, maxLines = 1)
            Text(lastLabel, color = Glass.secondary, fontSize = Glass.caption, maxLines = 1)
        }
    }
}

/** True while a title / character / session detail page is open on its tab (it replaces the tab body, the sidebar and the period pills). */
internal fun isDetailOpen(tab: StatsTab, titleOpen: Boolean, characterOpen: Boolean, sessionOpen: Boolean): Boolean = when (tab) {
    StatsTab.TITLES -> titleOpen
    StatsTab.CHARACTERS -> characterOpen
    StatsTab.SESSIONS -> sessionOpen
    else -> false
}

/** The detail page of the open selection on the current tab. */
@Composable
private fun GlassStatsDetail(state: StatsScreenState.Success, model: StatsScreenModel, navigator: Navigator, narrow: Boolean) {
    when (state.selectedTab) {
        StatsTab.TITLES -> state.selection.title?.let { GlassStatsTitleDetail(state, it, model, navigator, narrow) }
        StatsTab.CHARACTERS -> state.selection.character?.let { GlassStatsCharacterDetail(state, it, model, narrow) }
        StatsTab.SESSIONS -> state.selection.session?.let { GlassStatsSessionDetail(state, it, model, narrow) }
        else -> Unit
    }
}
