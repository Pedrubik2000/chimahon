package eu.kanade.tachiyomi.ui.glass.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassChip
import eu.kanade.presentation.glass.Pill
import eu.kanade.presentation.glass.glassPanel
import eu.kanade.presentation.more.stats.DEFAULT_GOAL_WINDOW_DAYS
import eu.kanade.presentation.more.stats.MAX_GOAL_CHECK_IN_NOTE_LENGTH
import eu.kanade.presentation.more.stats.goalEditModeLabel
import eu.kanade.presentation.more.stats.goalKindLabel
import eu.kanade.presentation.more.stats.goalMetricLabel
import eu.kanade.presentation.more.stats.goalMultiplierLabel
import eu.kanade.tachiyomi.ui.stats.ACTIVE_TIME_GOAL_METRIC
import eu.kanade.tachiyomi.ui.stats.SOURCE_UNITS_GOAL_METRIC
import eu.kanade.tachiyomi.ui.stats.StatsGoalEditMode
import eu.kanade.tachiyomi.ui.stats.StatsGoalEditorValues
import eu.kanade.tachiyomi.ui.stats.StatsGoalKind
import eu.kanade.tachiyomi.ui.stats.suggestedStatsGoalWeekdayMultipliers
import eu.kanade.tachiyomi.ui.stats.toStatsGoalEditorValues
import tachiyomi.domain.immersion.model.ImmersionGoal
import tachiyomi.domain.immersion.model.ImmersionLocalDate
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle as DayStyle
import java.util.Locale

/** What a goal can count (the Classic list, in its order). */
private val GoalMetrics = listOf(
    ACTIVE_TIME_GOAL_METRIC,
    "gross_characters",
    "unique_source_characters",
    "net_characters",
    SOURCE_UNITS_GOAL_METRIC,
    "sessions",
    "cards",
    "new_characters",
)

private val DateKinds = setOf(StatsGoalKind.DATE_BOUND_TOTAL, StatsGoalKind.FINISH_TITLE_BY_DATE)

/**
 * The values the goal box saves, or null when it must not save: the start date is not a date, or a finish-title goal has no
 * title. A bad end date gives no end date (the model's own check then refuses it), as in the Classic box.
 */
internal fun goalEditorValues(
    kind: StatsGoalKind,
    metric: String,
    target: String,
    startText: String,
    endText: String,
    weekdayMultipliers: Map<DayOfWeek, Double>,
    editMode: StatsGoalEditMode,
    hasTitleScope: Boolean,
): StatsGoalEditorValues? {
    if (kind == StatsGoalKind.FINISH_TITLE_BY_DATE && !hasTitleScope) return null
    val start = runCatching { ImmersionLocalDate.from(LocalDate.parse(startText.trim())) }.getOrNull() ?: return null
    val end = if (kind in DateKinds) runCatching { ImmersionLocalDate.from(LocalDate.parse(endText.trim())) }.getOrNull() else null
    return StatsGoalEditorValues(
        kind = kind,
        metric = when (kind) {
            StatsGoalKind.FINISH_TITLE_BY_DATE -> SOURCE_UNITS_GOAL_METRIC
            StatsGoalKind.MANUAL -> "manual"
            else -> metric
        },
        inputTarget = if (kind == StatsGoalKind.MANUAL) 1.0 else target.toDoubleOrNull() ?: Double.NaN,
        startDate = start,
        endDate = end,
        weekdayMultipliers = weekdayMultipliers,
        editMode = editMode,
    )
}

/** What a goal counts, in the words the detail pages use (Classic says gross exposure, unique source, net progress, source units). */
@Composable
private fun plainGoalMetric(value: String): String = when (value) {
    "gross_characters" -> stringResource(MR.strings.glass_stats_m_chars)
    "unique_source_characters" -> stringResource(MR.strings.glass_stats_d_new_text)
    "net_characters" -> stringResource(MR.strings.glass_stats_d_moved_ahead)
    SOURCE_UNITS_GOAL_METRIC -> stringResource(MR.strings.glass_stats_d_pages_seen)
    else -> goalMetricLabel(value)
}

/** A dark glass box over the page: the panel, a scrolling body, 24 dp inside. */
@Composable
internal fun GlassDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(16.dp)
                .widthIn(max = 680.dp)
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .glassPanel(Glass.r16)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

/** A labelled text field in the Glass look; an invalid one gets the ice outline (no red). */
@Composable
internal fun GlassField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    singleLine: Boolean = true,
    minHeight: Dp = Glass.button,
    keyboardType: KeyboardType = KeyboardType.Text,
    invalid: Boolean = false,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = Glass.secondary, fontSize = Glass.caption)
        androidx.compose.foundation.layout.Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .clip(RoundedCornerShape(Glass.r12))
                .background(Glass.page)
                .border(1.dp, if (invalid) Glass.ice else Glass.line, RoundedCornerShape(Glass.r12))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = singleLine,
                textStyle = TextStyle(color = Color.White, fontSize = Glass.label),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        hint?.let { Text(it, color = Glass.secondary, fontSize = Glass.caption) }
    }
}

/**
 * The new / edit goal box (mockup A, card 5): kind and what-to-count as chips, target and dates, a chip per weekday that cycles
 * 100% / 50% / Rest, and, when editing, Continue / Restart. Same parameters, checks and callbacks as the Classic GoalEditorDialog.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GlassGoalEditor(
    original: ImmersionGoal?,
    hasTitleScope: Boolean,
    onDismiss: () -> Unit,
    onSave: (StatsGoalEditorValues) -> Boolean,
) {
    val today = remember { ImmersionLocalDate.from(LocalDate.now()) }
    val key1 = original?.id
    val key2 = original?.updatedAtEpochMillis
    val initial = remember(key1, key2) { original?.toStatsGoalEditorValues(today) }
    var kind by remember(key1, key2) { mutableStateOf(initial?.kind ?: StatsGoalKind.DAILY) }
    var metric by remember(key1, key2) { mutableStateOf(initial?.metric?.takeIf { it in GoalMetrics } ?: GoalMetrics.first()) }
    var target by remember(key1, key2) { mutableStateOf(initial?.inputTarget?.toString().orEmpty()) }
    var startDate by remember(key1, key2) { mutableStateOf((initial?.startDate ?: today).toString()) }
    var endDate by remember(key1, key2) {
        mutableStateOf(initial?.endDate?.toString() ?: LocalDate.now().plusDays(DEFAULT_GOAL_WINDOW_DAYS).toString())
    }
    var multipliers by remember(key1, key2) { mutableStateOf(initial?.weekdayMultipliers ?: suggestedStatsGoalWeekdayMultipliers()) }
    var editMode by remember(key1, key2) { mutableStateOf(StatsGoalEditMode.PROSPECTIVE) }
    var invalid by remember { mutableStateOf(false) }
    GlassDialog(onDismiss) {
        Text(stringResource(if (original == null) KMR.strings.stats_goal_create else KMR.strings.stats_goal_edit), fontSize = Glass.heading, fontWeight = FontWeight.SemiBold)
        Text(stringResource(MR.strings.glass_stats_g_kind), color = Glass.secondary, fontSize = Glass.caption)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatsGoalKind.entries.forEach {
                GlassChip(goalKindLabel(it), selected = kind == it, onClick = { kind = it; invalid = false })
            }
        }
        Text(stringResource(MR.strings.glass_stats_g_count), color = Glass.secondary, fontSize = Glass.caption)
        if (kind != StatsGoalKind.FINISH_TITLE_BY_DATE && kind != StatsGoalKind.MANUAL) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GoalMetrics.forEach { GlassChip(plainGoalMetric(it), selected = metric == it, onClick = { metric = it }) }
            }
        } else {
            Text(goalMetricLabel(if (kind == StatsGoalKind.MANUAL) "manual" else SOURCE_UNITS_GOAL_METRIC), color = Glass.secondary, fontSize = Glass.body)
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (kind != StatsGoalKind.MANUAL) {
                GlassField(
                    target,
                    { target = it; invalid = false },
                    stringResource(
                        if (kind != StatsGoalKind.FINISH_TITLE_BY_DATE && metric == ACTIVE_TIME_GOAL_METRIC) KMR.strings.stats_goal_target_minutes else KMR.strings.stats_goal_target,
                    ),
                    Modifier.widthIn(min = 150.dp, max = 240.dp),
                    keyboardType = KeyboardType.Decimal,
                    invalid = invalid,
                )
            }
            GlassField(startDate, { startDate = it; invalid = false }, stringResource(KMR.strings.stats_start_date), Modifier.widthIn(min = 150.dp, max = 240.dp), invalid = invalid)
            if (kind in DateKinds) {
                GlassField(endDate, { endDate = it; invalid = false }, stringResource(KMR.strings.stats_end_date), Modifier.widthIn(min = 150.dp, max = 240.dp), invalid = invalid)
            }
        }
        Note(stringResource(KMR.strings.stats_custom_range_hint))
        Text(stringResource(KMR.strings.stats_goal_weekday_targets), color = Glass.secondary, fontSize = Glass.caption)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DayOfWeek.entries.forEach { day ->
                val value = multipliers.getValue(day)
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    GlassChip(
                        goalMultiplierLabel(value),
                        selected = value >= 1.0,
                        onClick = { multipliers = multipliers + (day to nextMultiplier(value)); invalid = false },
                        borderColor = if (value > 0.0 && value < 1.0) Glass.ice else Glass.line,
                    )
                    Text(day.getDisplayName(DayStyle.SHORT, Locale.getDefault()), color = Glass.secondary, fontSize = Glass.caption)
                }
            }
        }
        Note(stringResource(KMR.strings.stats_goal_timezone_policy))
        if (original != null) {
            Text(stringResource(KMR.strings.stats_goal_edit_mode), color = Glass.secondary, fontSize = Glass.caption)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatsGoalEditMode.entries.forEach {
                    GlassChip(goalEditModeLabel(it), selected = editMode == it, onClick = { editMode = it })
                }
            }
            Note(
                stringResource(
                    if (editMode == StatsGoalEditMode.RESTART_HISTORY) KMR.strings.stats_goal_edit_restart_summary else KMR.strings.stats_goal_edit_prospective,
                ),
            )
        }
        if (kind == StatsGoalKind.FINISH_TITLE_BY_DATE && !hasTitleScope) {
            Text(stringResource(KMR.strings.stats_goal_title_required), color = Glass.ice, fontSize = Glass.caption)
        }
        if (invalid) Text(stringResource(KMR.strings.stats_goal_invalid), color = Glass.ice, fontSize = Glass.caption)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
            Pill(stringResource(KMR.strings.stats_close), onDismiss, small = true)
            Pill(
                stringResource(MR.strings.glass_stats_g_save),
                {
                    val values = goalEditorValues(kind, metric, target, startDate, endDate, multipliers, editMode, hasTitleScope)
                    invalid = values == null || !onSave(values)
                },
                small = true,
                primary = true,
            )
        }
    }
}

/** The check-in box of a manual habit: a private note (500 characters at most), Close and Check in. Same callbacks as the Classic GoalCheckInDialog. */
@Composable
internal fun GlassGoalCheckIn(onDismiss: () -> Unit, onConfirm: (String?) -> Unit) {
    var note by remember { mutableStateOf("") }
    GlassDialog(onDismiss) {
        Text(stringResource(KMR.strings.stats_goal_check_in), fontSize = Glass.heading, fontWeight = FontWeight.SemiBold)
        GlassField(
            note,
            { note = it.take(MAX_GOAL_CHECK_IN_NOTE_LENGTH) },
            stringResource(KMR.strings.stats_goal_check_in_note),
            singleLine = false,
            minHeight = 96.dp,
            hint = stringResource(KMR.strings.stats_goal_check_in_note_count, note.length, MAX_GOAL_CHECK_IN_NOTE_LENGTH),
        )
        Note(stringResource(KMR.strings.stats_goal_check_in_privacy))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
            Pill(stringResource(KMR.strings.stats_close), onDismiss, small = true)
            Pill(stringResource(KMR.strings.stats_goal_check_in), { onConfirm(note.trim().takeIf(String::isNotEmpty)) }, small = true, primary = true)
        }
    }
}
