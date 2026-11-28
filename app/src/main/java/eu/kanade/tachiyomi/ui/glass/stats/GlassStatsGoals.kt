package eu.kanade.tachiyomi.ui.glass.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.Meter
import eu.kanade.presentation.glass.Pill
import eu.kanade.presentation.glass.glassPanel
import eu.kanade.presentation.more.stats.GoalCheckInDialog
import eu.kanade.presentation.more.stats.GoalEditorDialog
import eu.kanade.presentation.more.stats.StatsScreenState
import eu.kanade.presentation.more.stats.StatsSection
import eu.kanade.presentation.more.stats.collectionLoadState
import eu.kanade.presentation.more.stats.formatGoalValue
import eu.kanade.presentation.more.stats.goalTypeLabel
import eu.kanade.presentation.more.stats.pluralCount
import eu.kanade.tachiyomi.ui.stats.StatsGoalForecastPresentation
import eu.kanade.tachiyomi.ui.stats.StatsScreenModel
import eu.kanade.tachiyomi.ui.stats.statsGoalForecastPresentation
import tachiyomi.domain.immersion.model.AnalyticsGoalProgress
import tachiyomi.domain.immersion.model.ImmersionGoal
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

/** The goal whose forecast is [isAvailable] and whose [epochDay] is earliest; null when no goal has a forecast date. */
internal fun <T> soonestForecast(items: List<T>, isAvailable: (T) -> Boolean, epochDay: (T) -> Long?): T? =
    items.filter(isAvailable).mapNotNull { i -> epochDay(i)?.let { i to it } }.minByOrNull { it.second }?.first

/** The Goals tab in Glass (mockup C, card 5): tiles, "New goal", one card per goal. Edit / Archive / Check in use the Classic dialogs and model calls. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GlassStatsGoals(state: StatsScreenState.Success, model: StatsScreenModel, narrow: Boolean, listState: LazyListState) {
    val section = state.sections.goals
    val goals = section.value?.value.orEmpty()
    val loadState = section.collectionLoadState(goals.isNotEmpty())
    var showEditor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ImmersionGoal?>(null) }
    var checkingIn by remember { mutableStateOf<ImmersionGoal?>(null) }
    TabList(listState) {
        refreshLine(section.refreshingOverContent())
        if (goals.isNotEmpty()) {
            block {
                val today = goals.filter { it.todayTarget > 0.0 }
                val soonest = soonestForecast(
                    goals,
                    { statsGoalForecastPresentation(it) == StatsGoalForecastPresentation.AVAILABLE },
                    { it.projectedCompletionDate?.epochDay },
                )
                Tiles(
                    buildList {
                        if (today.isNotEmpty()) {
                            add(Tile(stringResource(MR.strings.glass_stats_of, today.count { it.todayAchieved >= it.todayTarget }, today.size), stringResource(MR.strings.glass_stats_goals_on_track), null))
                        }
                        add(
                            Tile(
                                goals.maxOf { it.currentStreakDays }.toString(),
                                stringResource(MR.strings.glass_stats_streak),
                                stringResource(MR.strings.glass_stats_longest, goals.maxOf { it.longestStreakDays }),
                            ),
                        )
                        soonest?.projectedCompletionDate?.let { add(Tile(shortDate(it), stringResource(MR.strings.glass_stats_forecast_at, goalTypeLabel(soonest.goal.type)), null)) }
                    },
                    narrow,
                    3,
                )
            }
        }
        block {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(MR.strings.glass_stats_goals), Modifier.weight(1f), fontSize = Glass.label, fontWeight = FontWeight.SemiBold)
                Pill(stringResource(MR.strings.glass_stats_new_goal), { editing = null; showEditor = true }, small = true, icon = "plus")
            }
        }
        when {
            loadState.showContent -> goals.chunked(if (narrow) 1 else 2).forEach { pair ->
                block {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        pair.forEach { g ->
                            GoalCard(
                                g,
                                Modifier.weight(1f),
                                onEdit = { editing = g.goal; showEditor = true },
                                onArchive = { model.archiveGoal(g.goal) },
                                onCheckIn = { checkingIn = g.goal },
                            )
                        }
                        if (pair.size == 1 && !narrow) Spacer(Modifier.weight(1f))
                    }
                }
            }
            loadState.showError -> block { Pill(stringResource(KMR.strings.stats_retry_section), { model.retrySection(StatsSection.GOALS) }, small = true) }
            loadState.showLoading -> block { Text(stringResource(MR.strings.glass_stats_loading), color = Glass.secondary, fontSize = Glass.body) }
            else -> block { Text(stringResource(KMR.strings.stats_no_goals), color = Glass.secondary, fontSize = Glass.body) }
        }
    }
    if (showEditor) {
        GoalEditorDialog(
            original = editing,
            hasTitleScope = editing?.titleId != null || state.filter.titleId != null,
            onDismiss = { showEditor = false },
            onSave = { values ->
                if (model.saveGoal(values, editing)) {
                    showEditor = false
                    true
                } else {
                    false
                }
            },
        )
    }
    checkingIn?.let { goal ->
        GoalCheckInDialog(
            onDismiss = { checkingIn = null },
            onConfirm = { note ->
                model.checkInGoal(goal.id, note)
                checkingIn = null
            },
        )
    }
}

/** One goal card: name and actions, the ice progress bar, then the pace, forecast and streak lines of the Classic card. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GoalCard(g: AnalyticsGoalProgress, modifier: Modifier, onEdit: () -> Unit, onArchive: () -> Unit, onCheckIn: () -> Unit) {
    val metric = g.goal.metric
    val fraction = if (g.targetToDate > 0) (g.achieved / g.targetToDate).coerceIn(0.0, 1.0).toFloat() else 0f
    val forecast = statsGoalForecastPresentation(g)
    Column(modifier.glassPanel(Glass.r16).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(goalTypeLabel(g.goal.type), fontSize = Glass.label, fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (g.goal.type == "MANUAL" || metric == "manual") Pill(stringResource(KMR.strings.stats_goal_check_in), onCheckIn, small = true, icon = "check")
            Pill(stringResource(KMR.strings.stats_goal_edit), onEdit, small = true, icon = "pencil")
            Pill(stringResource(KMR.strings.stats_goal_archive), onArchive, small = true, icon = "folder")
        }
        Text(stringResource(KMR.strings.stats_goal_progress, formatGoalValue(metric, g.achieved), formatGoalValue(metric, g.targetToDate)), fontSize = Glass.body)
        Meter(fraction, Modifier.fillMaxWidth(), color = Glass.ice)
        if (g.todayTarget > 0.0) {
            Text(stringResource(MR.strings.glass_stats_goal_today, formatGoalValue(metric, g.todayAchieved), formatGoalValue(metric, g.todayTarget)), color = Glass.secondary, fontSize = Glass.caption)
        }
        g.pacePerDay?.let { Line(stringResource(KMR.strings.stats_goal_pace_label), stringResource(KMR.strings.stats_per_active_day, formatGoalValue(metric, it))) }
        g.rollingSevenDayPace?.let { Line(stringResource(KMR.strings.stats_goal_rolling_seven_label), stringResource(KMR.strings.stats_per_active_day, formatGoalValue(metric, it))) }
        g.rollingThirtyDayPace?.let { Line(stringResource(KMR.strings.stats_goal_rolling_thirty_label), stringResource(KMR.strings.stats_per_active_day, formatGoalValue(metric, it))) }
        g.requiredPacePerActiveDay?.let {
            Line(
                stringResource(KMR.strings.stats_goal_required_pace_label),
                stringResource(KMR.strings.stats_goal_required_pace_value, formatGoalValue(metric, it), pluralCount(KMR.plurals.stats_remaining_active_day_count, g.remainingActiveDays ?: 0)),
            )
        }
        when (forecast) {
            StatsGoalForecastPresentation.AVAILABLE -> g.projectedCompletionDate?.let { Line(stringResource(KMR.strings.stats_goal_projection_label), shortDate(it)) }
            StatsGoalForecastPresentation.PARTIAL -> Note(stringResource(KMR.strings.stats_goal_forecast_partial))
            StatsGoalForecastPresentation.UNAVAILABLE -> Note(stringResource(KMR.strings.stats_goal_forecast_unavailable))
            StatsGoalForecastPresentation.STALE -> Note(stringResource(KMR.strings.stats_goal_forecast_stale))
            StatsGoalForecastPresentation.NONE -> Unit
        }
        Line(stringResource(KMR.strings.stats_current_streak), pluralCount(KMR.plurals.stats_day_count, g.currentStreakDays))
        Line(stringResource(KMR.strings.stats_longest_streak), pluralCount(KMR.plurals.stats_day_count, g.longestStreakDays))
        if (g.isRestDay) Note(stringResource(KMR.strings.stats_goal_rest_day))
    }
}

@Composable
internal fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), color = Glass.secondary, fontSize = Glass.caption)
        Text(value, Modifier.weight(1f), fontSize = Glass.caption, textAlign = TextAlign.End)
    }
}

@Composable
internal fun Note(text: String) = Text(text, color = Glass.secondary, fontSize = Glass.caption)
