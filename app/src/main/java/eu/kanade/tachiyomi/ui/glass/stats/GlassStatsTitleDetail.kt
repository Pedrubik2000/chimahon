package eu.kanade.tachiyomi.ui.glass.stats

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.Navigator
import coil3.compose.AsyncImage
import com.canopus.chimareader.ui.reader.NovelReaderActivity
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassIcon
import eu.kanade.presentation.glass.GlassToggle
import eu.kanade.presentation.glass.Pill
import eu.kanade.presentation.glass.glassPanel
import eu.kanade.presentation.more.stats.StatsLoadable
import eu.kanade.presentation.more.stats.StatsScreenState
import eu.kanade.presentation.more.stats.StatsTrendMetric
import eu.kanade.presentation.more.stats.characterValue
import eu.kanade.presentation.more.stats.estimateConfidenceLabel
import eu.kanade.presentation.more.stats.estimateRemainingAmount
import eu.kanade.presentation.more.stats.formatCount
import eu.kanade.presentation.more.stats.formatDecimal
import eu.kanade.presentation.more.stats.formatDuration
import eu.kanade.presentation.more.stats.formatDurationCompact
import eu.kanade.presentation.more.stats.formatInstant
import eu.kanade.presentation.more.stats.formatLocalDate
import eu.kanade.presentation.more.stats.formatPercent
import eu.kanade.presentation.more.stats.formatRate
import eu.kanade.presentation.more.stats.pluralCount
import eu.kanade.presentation.more.stats.provenanceLabel
import eu.kanade.presentation.more.stats.sessionStatusLabel
import eu.kanade.presentation.more.stats.sourceKindLabel
import eu.kanade.presentation.more.stats.titleLinkExplanation
import eu.kanade.presentation.more.stats.titleLinkStatus
import eu.kanade.presentation.more.stats.titleStateLabel
import eu.kanade.presentation.more.stats.titleUnitCount
import eu.kanade.presentation.more.stats.trendValue
import eu.kanade.tachiyomi.ui.dictionary.ProcessTextLookupActivity
import eu.kanade.tachiyomi.ui.entries.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.stats.StatsMaintenanceInitialAction
import eu.kanade.tachiyomi.ui.stats.StatsMaintenanceScreen
import eu.kanade.tachiyomi.ui.stats.StatsScreenModel
import eu.kanade.tachiyomi.ui.stats.StatsSourceNavigator
import eu.kanade.tachiyomi.ui.stats.StatsTitleLinkState
import eu.kanade.tachiyomi.ui.stats.StatsTitleMaintenanceScreen
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import tachiyomi.domain.immersion.model.AnalyticsPage
import tachiyomi.domain.immersion.model.AnalyticsResult
import tachiyomi.domain.immersion.model.AnalyticsSourceOccurrence
import tachiyomi.domain.immersion.model.AnalyticsTitleRow
import tachiyomi.domain.immersion.model.MediaKind
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import java.io.File
import kotlin.math.roundToLong

// ---- pure logic (tests in GlassStatsHelpersTest) ----

/** Characters an hour from a count and the active time in milliseconds; null when there is no time. */
internal fun ratePerHour(count: Long, millis: Long): Double? = if (millis <= 0L) null else count * 3_600_000.0 / millis

/** "Show more" is shown while the list has rows not yet shown or another page to load. */
internal fun moreAvailable(loaded: Int, shown: Int, hasNextPage: Boolean): Boolean = loaded > shown || hasNextPage

/** The weekday chip cycle: 100% -> 50% -> Rest -> 100%. */
internal fun nextMultiplier(current: Double): Double = when {
    current >= 1.0 -> 0.5
    current > 0.0 -> 0.0
    else -> 1.0
}

// ---- shared pieces of the three detail pages ----

/** The "back to the list" pill on the left and [trailing] on the right. */
@Composable
internal fun DetailHeader(back: String, onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Pill(back, onBack, small = true, icon = "chevLeft")
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

/** The numbers that matter, big, with a quiet label under each; they wrap on a narrow page. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HeroNumbers(items: List<Pair<String, String>>, narrow: Boolean, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(if (narrow) 20.dp else 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEach { (value, label) ->
            Column {
                Text(value, fontSize = if (narrow) Glass.title else Glass.hero, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(label, color = Glass.secondary, fontSize = Glass.body, maxLines = 1)
            }
        }
    }
}

/** A small panel: a title (with an optional [action] beside it) and its rows. No minimum height, unlike the tab panels. */
@Composable
internal fun DetailPanel(title: String, modifier: Modifier, action: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.glassPanel(Glass.r16).padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), fontSize = Glass.label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            action()
        }
        content()
    }
}

/** Panels of equal width side by side (same height), or one under the other on a narrow page. */
@Composable
internal fun PanelsEqual(narrow: Boolean, panels: List<@Composable (Modifier) -> Unit>) {
    if (panels.isEmpty()) return
    if (narrow) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { panels.forEach { it(Modifier.fillMaxWidth()) } }
    } else {
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            panels.forEach { it(Modifier.weight(1f).fillMaxHeight()) }
        }
    }
}

/** A Classic detail section: its value when loaded, "Loading" while it loads, the Classic failure line when it failed. */
@Composable
internal fun <T> DetailSection(section: StatsLoadable<AnalyticsResult<T>>, content: @Composable (T) -> Unit) {
    val value = section.value?.value
    when {
        value != null -> {
            content(value)
            if (section.error) Note(stringResource(KMR.strings.stats_section_failed))
        }
        section.error -> Note(stringResource(KMR.strings.stats_section_failed))
        section.refreshing -> Text(stringResource(MR.strings.glass_stats_loading), color = Glass.secondary, fontSize = Glass.body)
        else -> Text(stringResource(MR.strings.glass_stats_none), color = Glass.secondary, fontSize = Glass.body)
    }
}

/** "Show more" under a list: shows [step] more rows, and asks for the next page when the loaded rows run out. */
@Composable
internal fun ShowMore(loaded: Int, shown: Int, hasNextPage: Boolean, loading: Boolean, onMore: () -> Unit) {
    if (!moreAvailable(loaded, shown, hasNextPage)) return
    if (loading) {
        Text(stringResource(MR.strings.glass_stats_loading), color = Glass.secondary, fontSize = Glass.body)
    } else {
        Text(
            stringResource(MR.strings.glass_stats_d_show_more),
            Modifier.clip(RoundedCornerShape(Glass.r8)).clickable(onClick = onMore).padding(vertical = 6.dp),
            color = Glass.ice,
            fontSize = Glass.body,
        )
    }
}

/** A tappable row of a manage list: icon, words; dimmed when [enabled] is false. */
@Composable
internal fun ManageAction(icon: String, text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(Glass.r8)).clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GlassIcon(icon, size = 18.dp, tint = if (enabled) Color.White else Glass.secondary)
        Text(text, color = if (enabled) Color.White else Glass.secondary, fontSize = Glass.body)
    }
}

/** The quiet dashed box at the bottom of a detail page: the actions on the left, the technical rows on the right (stacked on a narrow page). */
@Composable
internal fun ManageBox(narrow: Boolean, actions: @Composable ColumnScope.() -> Unit, rows: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRoundRect(
                    Glass.line,
                    cornerRadius = CornerRadius(Glass.r16.toPx()),
                    style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
                )
            }
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(KMR.strings.stats_section_manage), color = Glass.secondary, fontSize = Glass.label, fontWeight = FontWeight.SemiBold)
        if (narrow) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp), content = actions)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp), content = rows)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp), content = actions)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp), content = rows)
            }
        }
    }
}

/** A confirm box: [body], Close and the [confirmText] pill (white), dimmed until [confirmEnabled]. */
@Composable
internal fun GlassConfirm(title: String, confirmText: String, confirmEnabled: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit, body: @Composable () -> Unit) {
    GlassDialog(onDismiss) {
        Text(title, fontSize = Glass.heading, fontWeight = FontWeight.SemiBold)
        body()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
            Pill(stringResource(KMR.strings.stats_close), onDismiss, small = true)
            Pill(confirmText, onConfirm, small = true, primary = true, enabled = confirmEnabled)
        }
    }
}

/**
 * The source rows (Where you read / Where you met it): the first few, then Show more. Each has its text, where it is from
 * and "Open source" / "Look up again" as the Classic row has them.
 */
@Composable
internal fun SourceRows(items: List<AnalyticsSourceOccurrence>, hasNextPage: Boolean, loading: Boolean, onLoadMore: () -> Unit, showTitle: Boolean, firstShown: Int = 3) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val unavailable = stringResource(KMR.strings.stats_source_open_unavailable)
    var shown by remember { mutableIntStateOf(firstShown) }
    if (items.isEmpty()) {
        Text(stringResource(MR.strings.glass_stats_none), color = Glass.secondary, fontSize = Glass.body)
        return
    }
    if (items.all { it.excerpt.isNullOrBlank() }) Note(stringResource(KMR.strings.stats_source_text_unavailable))
    items.take(shown).forEach { occurrence ->
        Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(KMR.strings.stats_source_meta, sourceKindLabel(occurrence.sourceKind), formatInstant(occurrence.occurredAtEpochMillis)),
                color = Glass.secondary,
                fontSize = Glass.caption,
            )
            if (showTitle) Text(occurrence.displayTitle, fontSize = Glass.body, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            occurrence.excerpt?.let { Text(it, fontSize = Glass.body, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(
                    stringResource(KMR.strings.stats_open_source),
                    { scope.launch { if (!StatsSourceNavigator.open(context, occurrence)) context.toast(unavailable) } },
                    small = true,
                    icon = "external",
                )
                if (occurrence.rawTextAvailable && !occurrence.excerpt.isNullOrBlank()) {
                    Pill(
                        stringResource(KMR.strings.stats_mine_again),
                        { lookUp(context, occurrence.excerpt!!) },
                        small = true,
                        icon = "search",
                    )
                }
            }
        }
    }
    ShowMore(items.size, shown, hasNextPage, loading) {
        shown += 6
        if (shown >= items.size && hasNextPage) onLoadMore()
    }
}

/** The dictionary lookup the Classic rows start. */
internal fun lookUp(context: Context, text: String) {
    context.startActivity(
        android.content.Intent(context, ProcessTextLookupActivity::class.java).apply {
            action = android.content.Intent.ACTION_PROCESS_TEXT
            putExtra(android.content.Intent.EXTRA_PROCESS_TEXT, text)
        },
    )
}

private fun openLocalTitle(context: Context, navigator: Navigator, title: AnalyticsTitleRow, navigationPath: String?) {
    when (title.mediaKind) {
        MediaKind.MANGA -> (title.libraryId ?: title.mediaId?.toLongOrNull())?.let { navigator.push(MangaScreen(it)) }
        MediaKind.NOVEL -> navigationPath?.let(::File)?.takeIf(File::isDirectory)?.let { NovelReaderActivity.launch(context, it) }
        MediaKind.VIDEO -> (title.libraryId ?: title.mediaId?.toLongOrNull())?.let { navigator.push(AnimeScreen(it)) }
    }
}

// ---- the title detail ----

/** A title's full page in Glass (mockup A, card 1-2): big numbers, one chart, small panels, then Manage. Same data and actions as the Classic TitleDetail. */
@Composable
internal fun GlassStatsTitleDetail(state: StatsScreenState.Success, title: AnalyticsTitleRow, model: StatsScreenModel, navigator: Navigator, narrow: Boolean) {
    val context = LocalContext.current
    val metric = state.filter.characterMetric
    val metadata = state.titleMetadata[title.titleId]
    val details = state.details
    val name = metadata?.localDisplayTitle ?: title.displayTitle
    val linked = metadata?.linkState == StatsTitleLinkState.AVAILABLE
    val busy = details.titleMutationInProgress
    val unavailable = stringResource(KMR.strings.stats_unavailable)
    var confirmUnlink by remember { mutableStateOf(false) }
    val listState = remember(title.titleId) { LazyListState() }
    TabList(listState) {
        block {
            DetailHeader(stringResource(KMR.strings.stats_tab_titles), { model.selectTitle(null) }) {
                if (linked) {
                    Pill(stringResource(MR.strings.glass_stats_d_open_library), { openLocalTitle(context, navigator, title, metadata?.navigationPath) }, small = true, primary = true, icon = "external")
                }
            }
        }
        block {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.size(width = 96.dp, height = 136.dp).clip(RoundedCornerShape(Glass.r8)).background(Glass.panel)) {
                    metadata?.coverLocation?.let {
                        AsyncImage(it, name, Modifier.fillMaxWidth().fillMaxHeight(), contentScale = ContentScale.Crop)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column {
                        Text(name, fontSize = Glass.heading, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        metadata?.author?.let { Text(stringResource(KMR.strings.stats_title_author, it), color = Glass.secondary, fontSize = Glass.body, maxLines = 1) }
                    }
                    HeroNumbers(
                        buildList {
                            add(formatDurationCompact(title.metrics.activeTime.value) to stringResource(MR.strings.glass_stats_d_active_time))
                            add(formatCount(title.metrics.characterValue(metric)) to stringResource(MR.strings.glass_stats_chars))
                            title.progress?.let { add(formatPercent(it) to stringResource(MR.strings.glass_stats_d_progress)) }
                            title.estimate?.let { add(formatDurationCompact(it.estimatedActiveTimeMillis) to stringResource(MR.strings.glass_stats_d_left)) }
                            add(formatCount(title.metrics.cardsCreated.value) to stringResource(MR.strings.glass_stats_d_new_cards))
                        },
                        narrow,
                    )
                }
            }
        }
        block {
            DetailPanel(
                stringResource(MR.strings.glass_stats_d_chars_per_day),
                Modifier.fillMaxWidth(),
                action = {
                    title.metrics.readingSpeedPerHour(metric)?.let {
                        Text(stringResource(MR.strings.glass_stats_an_hour, formatRate(it)), color = Glass.secondary, fontSize = Glass.caption)
                    }
                },
            ) {
                DetailSection(details.titleTrends) { trends ->
                    val values = trends.points.map { it.metrics.trendValue(StatsTrendMetric.CHARACTERS, metric).toDouble() }
                    if (values.none { it > 0.0 }) {
                        Text(stringResource(MR.strings.glass_stats_none), color = Glass.secondary, fontSize = Glass.body)
                    } else {
                        BarChart(values, null, shortDate(trends.points.first().range.start), shortDate(trends.points.last().range.endInclusive), height = 150.dp)
                    }
                }
            }
        }
        block {
            PanelsEqual(
                narrow,
                listOf(
                    { m ->
                        DetailPanel(stringResource(KMR.strings.stats_section_reading), m) {
                            Line(stringResource(MR.strings.glass_stats_d_pages_seen), formatCount(title.metrics.sourceUnits.value))
                            Line(stringResource(KMR.strings.stats_sessions), formatCount(title.metrics.sessions.value))
                            Line(stringResource(KMR.strings.stats_distinct_characters), formatCount(title.metrics.distinctCharacters.value))
                            Line(stringResource(KMR.strings.stats_new_characters), formatCount(title.metrics.newCharacters.value))
                            Line(stringResource(KMR.strings.stats_character_coverage), title.metrics.characterCoverage.ratio()?.let(::formatPercent) ?: unavailable)
                        }
                    },
                    { m ->
                        DetailPanel(stringResource(MR.strings.glass_stats_d_cards), m) {
                            Line(stringResource(KMR.strings.stats_cards_created), formatCount(title.metrics.cardsCreated.value))
                            Line(stringResource(KMR.strings.stats_cards_updated), formatCount(title.metrics.cardsUpdated.value))
                            Line(stringResource(KMR.strings.stats_mining_rate), title.metrics.miningRatePerTenThousandGrossCharacters()?.let(::formatDecimal) ?: unavailable)
                        }
                    },
                    { m ->
                        DetailPanel(stringResource(MR.strings.glass_stats_d_days), m) {
                            Line(stringResource(KMR.strings.stats_first_active_label), formatLocalDate(title.firstActiveDate))
                            Line(stringResource(KMR.strings.stats_last_active_label), formatLocalDate(title.lastActiveDate))
                            Line(stringResource(KMR.strings.stats_active_days), formatCount(title.activeDays.toLong()))
                            Line(stringResource(KMR.strings.stats_calendar_span), pluralCount(KMR.plurals.stats_day_count, title.calendarSpanDays))
                            Line(
                                stringResource(KMR.strings.stats_average_per_active_day),
                                stringResource(
                                    KMR.strings.stats_day_value,
                                    formatCount(title.averageCharactersPerActiveDay.roundToLong()),
                                    formatDuration(title.averageActiveTimePerActiveDayMillis.roundToLong()),
                                ),
                            )
                        }
                    },
                ),
            )
        }
        block {
            val highlights = title.dayHighlights
            PanelsEqual(
                narrow,
                listOfNotNull<@Composable (Modifier) -> Unit>(
                    { m ->
                        DetailPanel(stringResource(MR.strings.glass_stats_d_finishing), m) {
                            title.totalUnits?.let { Line(stringResource(KMR.strings.stats_title_total_units), titleUnitCount(title.mediaKind, it)) }
                            Line(
                                stringResource(KMR.strings.stats_title_completed_units),
                                if (title.unitProgress.hasTrustworthyIdentity) titleUnitCount(title.mediaKind, title.unitProgress.completedUnits) else unavailable,
                            )
                            if (!title.unitProgress.hasTrustworthyIdentity) {
                                Note(
                                    stringResource(
                                        if (title.unitProgress.identityAvailable) {
                                            KMR.strings.stats_title_completed_units_incomplete
                                        } else {
                                            KMR.strings.stats_title_completed_units_unavailable
                                        },
                                    ),
                                )
                            }
                            Line(stringResource(KMR.strings.stats_progress), title.progress?.let(::formatPercent) ?: unavailable)
                            val estimate = title.estimate
                            if (estimate != null) {
                                Line(stringResource(KMR.strings.stats_estimated_remaining), formatDuration(estimate.estimatedActiveTimeMillis))
                                Note(estimateRemainingAmount(title.mediaKind, estimate))
                                Note(
                                    stringResource(
                                        KMR.strings.stats_estimate_confidence,
                                        estimateConfidenceLabel(estimate.confidence),
                                        pluralCount(KMR.plurals.stats_qualifying_day_count, estimate.qualifyingDayCount),
                                    ),
                                )
                            } else {
                                Line(stringResource(KMR.strings.stats_estimated_remaining), unavailable)
                                Note(stringResource(KMR.strings.stats_estimate_unavailable))
                            }
                        }
                    },
                    if (highlights.characters != null || highlights.activeTime != null || highlights.speed != null) {
                        { m ->
                            DetailPanel(stringResource(MR.strings.glass_stats_d_best_days), m) {
                                highlights.characters?.let {
                                    Line(stringResource(KMR.strings.stats_best_characters_day), stringResource(KMR.strings.stats_day_value, formatLocalDate(it.date), formatCount(it.value.roundToLong())))
                                }
                                highlights.activeTime?.let {
                                    Line(stringResource(KMR.strings.stats_best_time_day), stringResource(KMR.strings.stats_day_value, formatLocalDate(it.date), formatDuration(it.value.roundToLong())))
                                }
                                highlights.speed?.let {
                                    Line(
                                        stringResource(KMR.strings.stats_best_speed_day),
                                        stringResource(KMR.strings.stats_day_value, formatLocalDate(it.date), stringResource(KMR.strings.stats_per_hour, formatRate(it.value))),
                                    )
                                }
                            }
                        }
                    } else {
                        null
                    },
                    { m ->
                        DetailPanel(stringResource(KMR.strings.stats_sessions), m) {
                            var shown by remember { mutableIntStateOf(3) }
                            DetailSection(details.titleSessions) { page ->
                                if (page.items.isEmpty()) {
                                    Text(stringResource(MR.strings.glass_stats_none), color = Glass.secondary, fontSize = Glass.body)
                                }
                                page.items.take(shown).forEach { s ->
                                    Row(
                                        Modifier.fillMaxWidth().clip(RoundedCornerShape(Glass.r8)).clickable {
                                            model.selectTab(eu.kanade.presentation.more.stats.StatsTab.SESSIONS)
                                            model.selectSession(s)
                                        }.padding(vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(formatInstant(s.startedAtEpochMillis), fontSize = Glass.body, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                            Text(sessionStatusLabel(s.status), color = Glass.secondary, fontSize = Glass.caption, maxLines = 1)
                                        }
                                        Text(formatDurationCompact(s.activeDuration.value), fontSize = Glass.body, maxLines = 1)
                                        GlassIcon("chevRight", size = 16.dp, tint = Glass.secondary)
                                    }
                                }
                                ShowMore(page.items.size, shown, page.nextCursor != null, details.titleSessions.refreshing) {
                                    shown += 6
                                    if (shown >= page.items.size && page.nextCursor != null) model.loadMoreTitleSessions()
                                }
                            }
                        }
                    },
                ),
            )
        }
        block {
            PanelRow(
                narrow,
                { m ->
                    DetailPanel(stringResource(MR.strings.glass_stats_d_finished), m) {
                        var shown by remember { mutableIntStateOf(4) }
                        DetailSection(details.titleCompletedUnits) { page ->
                            if (page.items.isEmpty()) {
                                Text(stringResource(MR.strings.glass_stats_none), color = Glass.secondary, fontSize = Glass.body)
                            }
                            page.items.take(shown).forEach { unit ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(unit.completionUnitId, Modifier.weight(1f), fontSize = Glass.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        stringResource(
                                            KMR.strings.stats_title_unit_completed_on,
                                            formatLocalDate(unit.firstCompletedDate),
                                            pluralCount(KMR.plurals.stats_completion_event_count, unit.completionEventCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()),
                                        ),
                                        color = Glass.secondary,
                                        fontSize = Glass.body,
                                        maxLines = 1,
                                    )
                                }
                            }
                            ShowMore(page.items.size, shown, page.nextOffset != null, details.titleCompletedUnits.refreshing) {
                                shown += 6
                                if (shown >= page.items.size && page.nextOffset != null) model.loadMoreTitleCompletedUnits()
                            }
                        }
                    }
                },
                { m ->
                    DetailPanel(stringResource(MR.strings.glass_stats_d_where_read), m) {
                        DetailSection(details.titleSources) { page: AnalyticsPage<AnalyticsSourceOccurrence> ->
                            SourceRows(page.items, page.nextOffset != null, details.titleSources.refreshing, model::loadMoreTitleSources, showTitle = false)
                        }
                    }
                },
            )
        }
        block {
            ManageBox(
                narrow,
                actions = {
                    if (linked) ManageAction("undo", stringResource(KMR.strings.stats_title_unlink), !busy) { confirmUnlink = true }
                    ManageAction("gear", stringResource(KMR.strings.stats_title_manage), !busy) { navigator.push(StatsTitleMaintenanceScreen(title.titleId.value)) }
                    ManageAction("eye", stringResource(KMR.strings.stats_title_delete_raw_text), !busy) {
                        navigator.push(StatsMaintenanceScreen(initialTitleId = title.titleId.value, initialAction = StatsMaintenanceInitialAction.DELETE_RAW_TEXT))
                    }
                    ManageAction("close", stringResource(KMR.strings.stats_title_delete_stats), !busy) {
                        navigator.push(StatsMaintenanceScreen(initialTitleId = title.titleId.value, initialAction = StatsMaintenanceInitialAction.DELETE_STATS))
                    }
                    details.titleCaptureExcluded.value?.let { excluded ->
                        val locked = details.titleCaptureExcluded.refreshing
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(KMR.strings.stats_capture_title_toggle), fontSize = Glass.body)
                                Note(stringResource(KMR.strings.stats_capture_title_toggle_summary))
                            }
                            GlassToggle(excluded, { if (!locked) model.setSelectedTitleCaptureExcluded(it) })
                        }
                    }
                    if (details.titleCaptureExcluded.refreshing || busy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Glass.ice, trackColor = Glass.line)
                    }
                    if (details.titleCaptureExcluded.error) Note(stringResource(KMR.strings.stats_capture_title_error_summary))
                    if (details.titleMutationError) Note(stringResource(KMR.strings.stats_title_mutation_error))
                },
                rows = {
                    Line(stringResource(KMR.strings.stats_title_link), titleLinkStatus(metadata?.linkState))
                    Line(stringResource(KMR.strings.stats_title_identity), title.sourceKey)
                    Line(stringResource(KMR.strings.stats_title_status), titleStateLabel(title.completed))
                    if (title.profileId.isNotBlank()) Line(stringResource(KMR.strings.stats_profile_label), title.profileId)
                    titleLinkExplanation(metadata?.linkState)?.let { Note(it) }
                    Line(stringResource(KMR.strings.stats_indexing_coverage), title.coverage.indexingCompletion?.let(::formatPercent) ?: unavailable)
                    Line(stringResource(KMR.strings.stats_text_coverage), title.coverage.textCoverage?.let(::formatPercent) ?: unavailable)
                    Line(stringResource(KMR.strings.stats_ocr_coverage), title.coverage.ocrTextCoverage?.let(::formatPercent) ?: unavailable)
                    Line(stringResource(KMR.strings.stats_provenance_state), provenanceLabel(title.coverage.provenanceState))
                },
            )
        }
    }
    if (confirmUnlink) {
        GlassConfirm(
            stringResource(KMR.strings.stats_title_unlink),
            stringResource(KMR.strings.stats_title_unlink),
            confirmEnabled = !busy,
            onConfirm = {
                confirmUnlink = false
                model.unlinkSelectedTitle()
            },
            onDismiss = { confirmUnlink = false },
        ) { Text(stringResource(KMR.strings.stats_title_unlink_warning), color = Glass.secondary, fontSize = Glass.body) }
    }
}
