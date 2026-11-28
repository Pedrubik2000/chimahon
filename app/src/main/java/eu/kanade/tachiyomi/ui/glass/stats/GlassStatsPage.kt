package eu.kanade.tachiyomi.ui.glass.stats

import androidx.compose.foundation.background
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
import eu.kanade.tachiyomi.ui.stats.StatsScreenHost
import eu.kanade.tachiyomi.ui.stats.StatsScreenModel
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
 * drawn in Glass from the Classic [StatsScreenModel] sections; the other six tabs are the Classic tab bodies, hosted
 * (StatsScreenHost with showChrome = false) inside this page.
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

    Row(Modifier.fillMaxSize()) {
        Spacer(Modifier.width(100.dp)) // the rail
        Column(
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
        Box(Modifier.width(1.dp).fillMaxHeight().background(Glass.line))
        // a narrow page (the tablet upright, split screen): the periods go under the title, the tiles two a row, the panels one a row
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
        val narrow = maxWidth < NARROW
        Column(Modifier.fillMaxSize().padding(start = 32.dp, top = 28.dp, end = 32.dp)) {
            if (success == null) return@Column
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
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
            if (narrow) {
                Spacer(Modifier.height(12.dp))
                FlowPills(success.filter.rangePreset, model::selectRange)
            }
            Spacer(Modifier.height(16.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (success.selectedTab == StatsTab.OVERVIEW) {
                    GlassStatsOverview(success, onTab = model::selectTab, onRetry = model::retrySection, narrow = narrow)
                } else {
                    StatsScreenHost(model, success, null, PaddingValues(0.dp), onNavigator = navigator, showChrome = false)
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
private fun PanelRow(narrow: Boolean, first: @Composable (Modifier) -> Unit, second: (@Composable (Modifier) -> Unit)? = null) {
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
            { m -> Panel(stringResource(MR.strings.glass_stats_chart), m) { Bars(state) } },
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

private class Tile(val value: String, val label: String, val note: String?)

@Composable
private fun delta(ratio: Double?): String? = ratio?.let {
    val pct = NumberFormat.getPercentInstance().apply { maximumFractionDigits = 0 }.format(abs(it))
    (if (it >= 0) "+" else "-") + pct
}

@Composable
private fun StatTile(t: Tile, modifier: Modifier) {
    Column(modifier.heightIn(min = 112.dp).glassPanel(Glass.r16).padding(18.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(t.value, fontSize = Glass.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(t.label, color = Glass.secondary, fontSize = Glass.body, maxLines = 1)
        t.note?.let { Text(it, color = Glass.ice, fontSize = Glass.body, maxLines = 1) }
    }
}

@Composable
private fun Panel(title: String, modifier: Modifier, action: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    Column(modifier.heightIn(min = 200.dp).glassPanel(Glass.r16).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), fontSize = Glass.label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            action()
        }
        content()
    }
}

/** The last 14 days of the Classic heatmap section: active time per day, today in ice. */
@Composable
private fun Bars(state: StatsScreenState.Success) {
    val points = state.sections.heatmap.value?.value?.points.orEmpty().takeLast(14)
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
