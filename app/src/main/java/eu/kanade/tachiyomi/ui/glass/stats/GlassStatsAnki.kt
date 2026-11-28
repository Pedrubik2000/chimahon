package eu.kanade.tachiyomi.ui.glass.stats

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassRoundButton
import eu.kanade.presentation.glass.Pill
import eu.kanade.presentation.more.stats.StatsScreenState
import eu.kanade.presentation.more.stats.StatsSection
import eu.kanade.presentation.more.stats.ankiCapabilityReason
import eu.kanade.presentation.more.stats.ankiReportLabel
import eu.kanade.presentation.more.stats.capabilityLabel
import eu.kanade.presentation.more.stats.formatCount
import eu.kanade.presentation.more.stats.formatDateRange
import eu.kanade.presentation.more.stats.formatDecimal
import eu.kanade.presentation.more.stats.formatDuration
import eu.kanade.presentation.more.stats.formatDurationCompact
import eu.kanade.presentation.more.stats.formatInstant
import eu.kanade.tachiyomi.ui.stats.StatsScreenModel
import eu.kanade.tachiyomi.ui.stats.ankiPresentationCapabilityState
import mihon.feature.stats.anki.AnkiInventorySyncJob
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import java.text.NumberFormat

/** The Anki tab in Glass (mockup C, card 6): tiles, cards made from reading per week, common characters not on a card yet. Data = the Classic Anki section. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GlassStatsAnki(state: StatsScreenState.Success, model: StatsScreenModel, narrow: Boolean, listState: LazyListState) {
    val context = LocalContext.current
    // the Classic "Inventory refresh requested" line: shown until a new inventory arrives
    var refreshRequested by remember(state.sections.anki.value?.value?.snapshot?.id) { mutableStateOf(false) }
    TabList(listState) {
        refreshLine(state.sections.anki.refreshingOverContent())
        block {
            LoadableBody(state.sections.anki, { model.retrySection(StatsSection.ANKI) }) { a ->
                val snapshot = a.snapshot
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Tiles(
                        buildList {
                            if (a.characterCoverageEncountered > 0) {
                                add(
                                    Tile(
                                        NumberFormat.getPercentInstance().format(a.characterCoverageKnown.toDouble() / a.characterCoverageEncountered),
                                        stringResource(MR.strings.glass_stats_on_cards),
                                        stringResource(MR.strings.glass_stats_aim, state.characterCoverageTargetPercent),
                                    ),
                                )
                            }
                            add(Tile(formatCount(a.cardsCreated), stringResource(MR.strings.glass_stats_cards_from_reading), null))
                            a.meanReadingToCardLagMillis?.let { add(Tile(formatDurationCompact(it), stringResource(MR.strings.glass_stats_read_to_card), null)) }
                            snapshot?.let { s ->
                                val checked = s.completedAtEpochMillis?.let { stringResource(MR.strings.glass_stats_checked, formatInstant(it)) }
                                add(Tile(formatCount(s.itemCount.toLong()), stringResource(MR.strings.glass_stats_cards_in_anki), checked))
                            }
                        },
                        narrow,
                        4,
                    )
                    if (snapshot == null) {
                        Text(stringResource(MR.strings.glass_stats_no_snapshot), color = Glass.secondary, fontSize = Glass.body)
                    } else if (snapshot.isStale) {
                        Text(stringResource(KMR.strings.stats_anki_snapshot_stale), color = Glass.secondary, fontSize = Glass.caption)
                    }
                    // the weekly series does not depend on the inventory snapshot
                    Panel(stringResource(MR.strings.glass_stats_weekly_cards), Modifier.fillMaxWidth(), action = {
                        GlassRoundButton(
                            "restore",
                            {
                                AnkiInventorySyncJob.refreshNow(context)
                                refreshRequested = true
                            },
                            description = stringResource(KMR.strings.stats_anki_refresh_inventory),
                            iconSize = 20.dp,
                        )
                    }) {
                        if (refreshRequested) Note(stringResource(KMR.strings.stats_anki_refresh_requested))
                        val weeks = a.weeklyImpact.takeLast(12)
                        if (weeks.none { it.cardsCreated > 0 }) {
                            Text(stringResource(MR.strings.glass_stats_none), color = Glass.secondary, fontSize = Glass.body)
                        } else {
                            BarChart(weeks.map { it.cardsCreated.toDouble() }, null, shortDate(weeks.first().weekStart), shortDate(weeks.last().weekStart), height = 150.dp)
                        }
                    }
                    Panel(stringResource(MR.strings.glass_stats_missing), Modifier.fillMaxWidth()) {
                        val missing = a.missingHighFrequencyCharacters.take(12)
                        if (missing.isEmpty()) {
                            Text(stringResource(MR.strings.glass_stats_none), color = Glass.secondary, fontSize = Glass.body)
                        } else {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                missing.forEach { c ->
                                    Box(Modifier.size(44.dp).clip(CircleShape).border(1.dp, Glass.line, CircleShape), contentAlignment = Alignment.Center) {
                                        Text(c.rendered, fontSize = Glass.label, maxLines = 1)
                                    }
                                }
                            }
                        }
                        Pill(stringResource(MR.strings.glass_stats_open_list), model::openMissingAnkiCharactersWorkbench, small = true, trailingIcon = "chevRight")
                    }
                    // the Classic "cards and mining rate by source"
                    if (a.titleImpact.isNotEmpty()) {
                        Panel(stringResource(MR.strings.glass_stats_cards_by_title), Modifier.fillMaxWidth()) {
                            val titles = a.titleImpact.take(10)
                            val bars = timeBarFractions(titles.map { it.operationCount })
                            titles.forEachIndexed { i, t ->
                                BarRow(
                                    t.displayTitle ?: stringResource(KMR.strings.stats_anki_unattributed),
                                    t.cardsPerTenThousandGrossCharacters()?.let { stringResource(KMR.strings.stats_cards_per_ten_thousand, formatDecimal(it)) },
                                    formatCount(t.operationCount),
                                    bars[i],
                                    i == 0,
                                )
                            }
                        }
                    }
                    // the Classic weekly rows: what the chart above does not say
                    if (a.weeklyImpact.isNotEmpty()) {
                        Panel(stringResource(MR.strings.glass_stats_week_by_week), Modifier.fillMaxWidth()) {
                            a.weeklyImpact.asReversed().take(12).forEach { week ->
                                Column {
                                    Line(
                                        stringResource(
                                            KMR.strings.stats_anki_week_heading,
                                            formatDateRange(week.weekStart, week.weekEndInclusive),
                                            stringResource(if (week.partial) KMR.strings.stats_anki_week_partial else KMR.strings.stats_anki_week_complete),
                                        ),
                                        stringResource(KMR.strings.stats_anki_week_cards, formatCount(week.cardsCreated), formatCount(week.cardsUpdated)),
                                    )
                                    Note(stringResource(KMR.strings.stats_anki_week_volume, formatCount(week.grossCharacters), formatDuration(week.activeDurationMillis)))
                                    Line(stringResource(KMR.strings.stats_anki_linked_operations), formatCount(week.linkedOperations))
                                    Line(stringResource(KMR.strings.stats_anki_unattributed_operations), formatCount(week.unattributedOperations))
                                    Line(stringResource(KMR.strings.stats_anki_same_week_flow), formatCount(week.sameWeekReadingToCardOperations))
                                    week.meanReadingToCardLagMillis?.let { Line(stringResource(KMR.strings.stats_anki_reading_to_card_lag), formatDuration(it)) }
                                    if (week.maturedOperations > 0) Line(stringResource(KMR.strings.stats_anki_matured_links), formatCount(week.maturedOperations))
                                    week.meanCardToMaturityLagMillis?.let { Line(stringResource(KMR.strings.stats_anki_card_to_maturity_lag), formatDuration(it)) }
                                }
                            }
                        }
                    }
                    // the Classic snapshot rows, totals, "available reports" and caveats
                    Panel(stringResource(MR.strings.glass_stats_anki_details), Modifier.fillMaxWidth()) {
                        if (snapshot != null) {
                            Line(stringResource(KMR.strings.stats_anki_state), capabilityLabel(ankiPresentationCapabilityState(capabilityState = snapshot.capabilityState, isStale = snapshot.isStale)))
                            Note(stringResource(KMR.strings.stats_anki_items, formatCount(snapshot.itemCount.toLong()), formatCount(snapshot.noteCount.toLong())))
                            Line(stringResource(KMR.strings.stats_anki_maturity_threshold), stringResource(KMR.strings.stats_anki_mature_interval_days, snapshot.matureIntervalDays))
                        }
                        a.generatedAtEpochMillis?.let { Note(stringResource(KMR.strings.stats_anki_report_generated, formatInstant(it))) }
                        Line(stringResource(KMR.strings.stats_cards_updated), formatCount(a.cardsUpdated))
                        Line(stringResource(KMR.strings.stats_anki_linked_operations), formatCount(a.linkedOperationCount))
                        Line(stringResource(KMR.strings.stats_anki_unattributed_operations), formatCount(a.unattributedOperationCount))
                        if (a.capabilities.isNotEmpty()) {
                            Text(stringResource(KMR.strings.stats_anki_capabilities), fontSize = Glass.body, fontWeight = FontWeight.SemiBold)
                            a.capabilities.forEach { capability ->
                                Column {
                                    Line(ankiReportLabel(capability.report), capabilityLabel(capability.state))
                                    ankiCapabilityReason(capability.reason)?.let { Note(it) }
                                }
                            }
                        }
                        Note(stringResource(KMR.strings.stats_anki_sample_limit, a.minimumComparisonSampleSize))
                        Note(stringResource(KMR.strings.stats_anki_observational))
                        if (!a.reviewHistoryAvailable) Note(stringResource(KMR.strings.stats_review_history_unavailable))
                    }
                }
            }
        }
    }
}
