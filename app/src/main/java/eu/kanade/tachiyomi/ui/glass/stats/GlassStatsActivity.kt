package eu.kanade.tachiyomi.ui.glass.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassChip
import eu.kanade.presentation.more.stats.StatsScreenState
import eu.kanade.presentation.more.stats.StatsSection
import eu.kanade.presentation.more.stats.StatsTrendMetric
import eu.kanade.presentation.more.stats.characterMetricLabel
import eu.kanade.presentation.more.stats.characterValue
import eu.kanade.presentation.more.stats.formatCount
import eu.kanade.presentation.more.stats.formatDecimal
import eu.kanade.presentation.more.stats.formatDurationCompact
import eu.kanade.presentation.more.stats.formatHour
import eu.kanade.presentation.more.stats.formatLocalDate
import eu.kanade.presentation.more.stats.formatRate
import eu.kanade.presentation.more.stats.formatTrendValue
import eu.kanade.presentation.more.stats.hasActivity
import eu.kanade.presentation.more.stats.titleSeriesSelectionLabel
import eu.kanade.presentation.more.stats.movingAverage
import eu.kanade.presentation.more.stats.trendValue
import eu.kanade.tachiyomi.ui.stats.StatsScreenModel
import tachiyomi.domain.immersion.model.AnalyticsActivityTotals
import tachiyomi.domain.immersion.model.AnalyticsBucketScale
import tachiyomi.domain.immersion.model.AnalyticsTemporalActivity
import tachiyomi.domain.immersion.model.AnalyticsTitleSeriesSelection
import tachiyomi.domain.immersion.model.AnalyticsTrends
import tachiyomi.domain.immersion.model.CharacterMetric
import tachiyomi.domain.immersion.model.ImmersionLocalDate
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** A date for a chart end or a list line: the Classic medium form, so a date in another year says so. */
internal fun shortDate(date: ImmersionLocalDate): String = formatLocalDate(date)

/** The value a By hour / By weekday cell is drawn by: characters, or active time when no cell has characters (Classic counts both as activity). */
internal fun activityValue(totals: AnalyticsActivityTotals, metric: CharacterMetric, useTime: Boolean): Long =
    if (useTime) totals.activeDurationMillis else totals.characterValue(metric).coerceAtLeast(0L)

/** The Activity tab in Glass (mockup C, card 1): five tiles that pick the metric, one chart, By hour and By weekday. Data = the Classic trends and temporal-activity sections. */
@Composable
internal fun GlassStatsActivity(state: StatsScreenState.Success, model: StatsScreenModel, narrow: Boolean, listState: LazyListState) {
    val metric = state.filter.characterMetric
    TabList(listState) {
        refreshLine(state.sections.trends.refreshingOverContent() || state.sections.temporalActivity.refreshingOverContent())
        block {
            LoadableBody(state.sections.trends, { model.retrySection(StatsSection.TRENDS) }) { trends ->
                ActivityChart(state, model, trends, narrow)
            }
        }
        block {
            LoadableBody(state.sections.temporalActivity, { model.retrySection(StatsSection.TEMPORAL_ACTIVITY) }) { activity ->
                val hoursUseTime = activity.hours.none { it.totals.characterValue(metric) > 0L }
                val hours = activity.hours.filter { it.totals.hasActivity(metric) }.associate { it.hourOfDay to activityValue(it.totals, metric, hoursUseTime) }
                val peak = hours.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PanelRow(
                        narrow,
                        { m ->
                            Panel(stringResource(MR.strings.glass_stats_by_hour), m, action = {
                                peak?.let { Text(stringResource(MR.strings.glass_stats_most_at, formatHour(it)), color = Glass.secondary, fontSize = Glass.caption) }
                            }) { HourCells(hours, peak) }
                        },
                        { m -> Panel(stringResource(MR.strings.glass_stats_by_weekday), m) { WeekdayRows(activity, metric) } },
                    )
                    Text(stringResource(KMR.strings.stats_time_patterns_explanation), color = Glass.secondary, fontSize = Glass.caption)
                }
            }
        }
        // the Classic "title contributions": the per-title series with its Top / Recent selector
        block {
            LoadableBody(state.sections.titleTrends, { model.retrySection(StatsSection.TITLE_TRENDS) }) { tt ->
                Panel(stringResource(MR.strings.glass_stats_titles_in_period), Modifier.fillMaxWidth(), actionBelow = narrow, action = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AnalyticsTitleSeriesSelection.entries.forEach { option ->
                            GlassChip(titleSeriesSelectionLabel(option), selected = option == state.titleTrendSelection, onClick = { model.selectTitleTrendSelection(option) })
                        }
                    }
                }) {
                    if (tt.series.isEmpty()) {
                        Text(stringResource(MR.strings.glass_stats_none), color = Glass.secondary, fontSize = Glass.body)
                    } else {
                        val totals = tt.series.map { it.points.lastOrNull()?.cumulativeMetrics?.trendValue(state.trendMetric, metric) ?: 0L }
                        val bars = timeBarFractions(totals)
                        tt.series.forEachIndexed { i, series ->
                            val active = series.points.count { it.metrics.trendValue(state.trendMetric, metric) > 0L }
                            BarRow(series.displayTitle, stringResource(MR.strings.glass_stats_read_in, active, series.points.size), formatTrendValue(totals[i], state.trendMetric), bars[i], i == 0)
                        }
                        Text(stringResource(KMR.strings.stats_title_series_limit, tt.series.size), color = Glass.secondary, fontSize = Glass.caption)
                    }
                }
            }
        }
        // the Classic cumulative / efficiency numbers the five tiles do not show, and its speed-threshold caveat
        block {
            LoadableBody(state.sections.trends, { model.retrySection(StatsSection.TRENDS) }) { trends ->
                val cumulative = trends.points.lastOrNull()?.cumulativeMetrics
                if (cumulative != null) {
                    Panel(stringResource(MR.strings.glass_stats_totals), Modifier.fillMaxWidth()) {
                        Line(stringResource(KMR.strings.stats_source_units), formatCount(cumulative.sourceUnits.value))
                        Line(
                            stringResource(KMR.strings.stats_reading_speed),
                            cumulative.readingSpeedPerHour(metric)?.let { stringResource(KMR.strings.stats_per_hour, formatRate(it)) } ?: stringResource(KMR.strings.stats_unavailable),
                        )
                        Line(
                            stringResource(KMR.strings.stats_mining_rate),
                            cumulative.miningRatePerTenThousandGrossCharacters()?.let { formatDecimal(it) } ?: stringResource(KMR.strings.stats_unavailable),
                        )
                        if (trends.points.any { it.metrics.activeTime.value > 0L || it.metrics.sessions.value > 0L }) {
                            Note(stringResource(KMR.strings.stats_minimum_threshold))
                        }
                    }
                }
            }
        }
    }
}

/** The chart title in plain words ("Characters read", not "Gross exposure"). */
@Composable
private fun metricTitle(m: StatsTrendMetric, metric: CharacterMetric): String = when (m) {
    StatsTrendMetric.ACTIVE_TIME -> stringResource(MR.strings.glass_stats_m_active)
    StatsTrendMetric.CHARACTERS -> if (metric == CharacterMetric.GROSS) stringResource(MR.strings.glass_stats_m_chars) else characterMetricLabel(metric)
    StatsTrendMetric.SESSIONS -> stringResource(MR.strings.glass_stats_m_sessions)
    StatsTrendMetric.CARDS -> stringResource(MR.strings.glass_stats_m_cards)
    StatsTrendMetric.NEW_CHARACTERS -> stringResource(MR.strings.glass_stats_m_newchars)
}

@Composable
private fun ActivityChart(state: StatsScreenState.Success, model: StatsScreenModel, trends: AnalyticsTrends, narrow: Boolean) {
    val metric = state.filter.characterMetric
    val points = trends.points
    val overview = state.sections.overview.value?.value
    val last = points.lastOrNull()?.cumulativeMetrics
    fun total(m: StatsTrendMetric) = points.sumOf { it.metrics.trendValue(m, metric) }
    fun tile(m: StatsTrendMetric, value: String, label: String, note: String?) =
        Tile(value, label, note, selected = state.trendMetric == m, onClick = { model.selectTrendMetric(m) })
    val activeNote = delta(overview?.comparison?.activeTimeChangeRatio)
    val charNote = delta(overview?.comparison?.characterChangeRatio)
    val cardNote = last?.miningRatePerTenThousandGrossCharacters()?.let { stringResource(MR.strings.glass_stats_per_10k, formatDecimal(it)) }
    val charLabel = if (metric == CharacterMetric.GROSS) stringResource(MR.strings.glass_stats_chars) else characterMetricLabel(metric)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Tiles(
            listOf(
                tile(StatsTrendMetric.ACTIVE_TIME, formatDurationCompact(total(StatsTrendMetric.ACTIVE_TIME)), stringResource(KMR.strings.stats_active_time), activeNote),
                tile(StatsTrendMetric.CHARACTERS, formatCount(total(StatsTrendMetric.CHARACTERS)), charLabel, charNote),
                tile(StatsTrendMetric.SESSIONS, formatCount(total(StatsTrendMetric.SESSIONS)), stringResource(KMR.strings.stats_sessions), null),
                tile(StatsTrendMetric.CARDS, formatCount(total(StatsTrendMetric.CARDS)), stringResource(KMR.strings.stats_cards_created), cardNote),
                tile(StatsTrendMetric.NEW_CHARACTERS, formatCount(total(StatsTrendMetric.NEW_CHARACTERS)), stringResource(KMR.strings.stats_new_characters), null),
            ),
            narrow,
            5,
        )
        val speed = if (state.trendMetric == StatsTrendMetric.CHARACTERS) {
            last?.readingSpeedPerHour(metric)?.let { " · " + stringResource(MR.strings.glass_stats_an_hour, formatRate(it)) }
        } else {
            null
        }
        Panel(metricTitle(state.trendMetric, metric) + (speed ?: ""), Modifier.fillMaxWidth(), actionBelow = narrow, action = {
            ScaleChips(state.trendScale, model::selectTrendScale)
        }) {
            val values = points.map { it.metrics.trendValue(state.trendMetric, metric).toDouble() }
            if (values.none { it > 0.0 }) {
                Text(stringResource(MR.strings.glass_stats_none), color = Glass.secondary, fontSize = Glass.body)
            } else {
                BarChart(
                    values,
                    movingAverage(points, state.trendMetric, metric).map { it.second },
                    shortDate(points.first().range.start),
                    shortDate(points.last().range.endInclusive),
                )
                Text(
                    stringResource(if (state.trendScale == AnalyticsBucketScale.DAY) MR.strings.glass_stats_avg_day else MR.strings.glass_stats_avg_bar),
                    color = Glass.secondary,
                    fontSize = Glass.caption,
                )
            }
        }
    }
}

/** Mockup C "By hour": 24 cells, busiest hour ice, the rest white by share; the labels 0, 6, 12, 18 each span 6 cells so they sit under their hour. */
@Composable
private fun HourCells(hours: Map<Int, Long>, peak: Int?) {
    val max = hours.values.maxOrNull() ?: 0L
    if (max <= 0L) {
        Text(stringResource(MR.strings.glass_stats_none), color = Glass.secondary, fontSize = Glass.body)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            (0..23).forEach { h ->
                val v = hours[h] ?: 0L
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (h == peak) Glass.ice else Color.White.copy(alpha = 0.12f + 0.5f * v / max)),
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf(0, 6, 12, 18).forEach { Text(it.toString(), Modifier.weight(1f), color = Glass.secondary, fontSize = Glass.caption) }
        }
    }
}

/** Mockup C "By weekday": seven rows, busiest day ice. */
@Composable
private fun WeekdayRows(activity: AnalyticsTemporalActivity, metric: CharacterMetric) {
    val useTime = activity.weekdays.none { it.totals.characterValue(metric) > 0L }
    val byDay = activity.weekdays.filter { it.totals.hasActivity(metric) }.associate { it.isoDayOfWeek to activityValue(it.totals, metric, useTime) }
    val max = byDay.values.maxOrNull() ?: 0L
    if (max <= 0L) {
        Text(stringResource(MR.strings.glass_stats_none), color = Glass.secondary, fontSize = Glass.body)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        (1..7).forEach { d ->
            val v = byDay[d] ?: 0L
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(DayOfWeek.of(d).getDisplayName(TextStyle.SHORT, Locale.getDefault()), Modifier.width(40.dp), color = Glass.secondary, fontSize = Glass.caption, maxLines = 1)
                Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(Glass.line)) {
                    Box(Modifier.fillMaxWidth(v.toFloat() / max).fillMaxHeight().background(if (v == max) Glass.ice else Glass.secondary))
                }
                Text(if (useTime) formatDurationCompact(v) else formatCount(v), Modifier.width(64.dp), fontSize = Glass.caption, textAlign = TextAlign.End, maxLines = 1)
            }
        }
    }
}

/** Day / Week / Month on a chart panel (the Classic Daily / Weekly / Monthly switch). */
@Composable
internal fun ScaleChips(current: AnalyticsBucketScale, onSelect: (AnalyticsBucketScale) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AnalyticsBucketScale.entries.forEach { scale ->
            GlassChip(
                stringResource(
                    when (scale) {
                        AnalyticsBucketScale.DAY -> MR.strings.glass_stats_bucket_day
                        AnalyticsBucketScale.WEEK -> MR.strings.glass_stats_bucket_week
                        AnalyticsBucketScale.MONTH -> MR.strings.glass_stats_month
                    },
                ),
                selected = current == scale,
                onClick = { onSelect(scale) },
            )
        }
    }
}
