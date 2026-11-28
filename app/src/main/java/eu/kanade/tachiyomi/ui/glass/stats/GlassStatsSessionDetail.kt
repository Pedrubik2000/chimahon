package eu.kanade.tachiyomi.ui.glass.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.more.stats.SessionTitleCorrectionDialog
import eu.kanade.presentation.more.stats.StatsScreenState
import eu.kanade.presentation.more.stats.formatCount
import eu.kanade.presentation.more.stats.formatDuration
import eu.kanade.presentation.more.stats.formatDurationCompact
import eu.kanade.presentation.more.stats.formatInstant
import eu.kanade.presentation.more.stats.formatRate
import eu.kanade.presentation.more.stats.sessionStatusLabel
import eu.kanade.presentation.more.stats.sessionTitleName
import eu.kanade.tachiyomi.ui.stats.StatsScreenModel
import tachiyomi.domain.immersion.model.AnalyticsSessionDetail
import tachiyomi.domain.immersion.model.ImmersionSession
import tachiyomi.domain.immersion.model.SessionStatus
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import java.text.DateFormat
import java.util.Date

/** The "Over the session" bars: gross characters of each slice, in order. */
internal fun timelineValues(detail: AnalyticsSessionDetail): List<Double> = detail.timeline.map { it.grossCharacters.toDouble() }

/** A session's full page in Glass (mockup A, card 3). Same data and actions as the Classic SessionDetail. */
@Composable
internal fun GlassStatsSessionDetail(state: StatsScreenState.Success, fallback: ImmersionSession, model: StatsScreenModel, narrow: Boolean) {
    val details = state.details
    val detail = details.session
    val loaded = detail.value?.value
    val session = loaded?.session ?: fallback
    val name = state.sessionTitleName(session) ?: loaded?.displayTitle
    val cover = state.titleMetadata[session.titleId]?.coverLocation
    var confirmDelete by remember { mutableStateOf(false) }
    var correctTitle by remember(fallback.id) { mutableStateOf(false) }
    val listState = remember(fallback.id) { LazyListState() }
    TabList(listState) {
        block { DetailHeader(stringResource(KMR.strings.stats_tab_sessions), { model.selectSession(null) }) }
        block {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(width = 56.dp, height = 80.dp).clip(RoundedCornerShape(Glass.r8)).background(Glass.panel)) {
                        cover?.let { AsyncImage(it, name, Modifier.fillMaxWidth().fillMaxHeight(), contentScale = ContentScale.Crop) }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(name ?: stringResource(KMR.strings.stats_session_detail), fontSize = Glass.heading, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            stringResource(KMR.strings.stats_source_meta, formatInstant(session.startedAtEpochMillis), sessionStatusLabel(session.status)),
                            color = Glass.secondary,
                            fontSize = Glass.body,
                            maxLines = 1,
                        )
                    }
                }
                HeroNumbers(
                    buildList {
                        add(formatDurationCompact(session.activeDuration.value) to stringResource(MR.strings.glass_stats_d_active_time))
                        add(formatCount(session.grossCharacters.value) to stringResource(MR.strings.glass_stats_chars))
                        add(formatCount(session.sourceUnitCount.value) to stringResource(MR.strings.glass_stats_d_pages))
                        ratePerHour(session.grossCharacters.value, session.activeDuration.value)?.let { add(formatRate(it) to stringResource(MR.strings.glass_stats_d_an_hour)) }
                    },
                    narrow,
                )
            }
        }
        block {
            DetailPanel(stringResource(MR.strings.glass_stats_d_over_session), Modifier.fillMaxWidth()) {
                if (loaded == null) {
                    when {
                        detail.error -> Note(stringResource(KMR.strings.stats_section_failed))
                        detail.value != null -> Text(stringResource(MR.strings.glass_stats_none), color = Glass.secondary, fontSize = Glass.body)
                        else -> Text(stringResource(MR.strings.glass_stats_loading), color = Glass.secondary, fontSize = Glass.body)
                    }
                } else if (session.legacyImport || loaded.timeline.none { it.grossCharacters > 0 }) {
                    Text(stringResource(MR.strings.glass_stats_none), color = Glass.secondary, fontSize = Glass.body)
                } else {
                    val events = loaded.timeline.sumOf { it.eventCount }
                    Note(
                        stringResource(
                            KMR.strings.stats_timeline_summary,
                            pluralStringResource(KMR.plurals.stats_timeline_bucket_count, loaded.timeline.size, formatCount(loaded.timeline.size.toLong())),
                            pluralStringResource(KMR.plurals.stats_event_count, events.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), formatCount(events)),
                        ),
                    )
                    val clock = DateFormat.getTimeInstance(DateFormat.SHORT)
                    BarChart(
                        timelineValues(loaded),
                        null,
                        clock.format(Date(loaded.timeline.first().startEpochMillis)),
                        clock.format(Date(loaded.timeline.last().endEpochMillis)),
                        height = 120.dp,
                    )
                }
                if (detail.error && loaded != null) Note(stringResource(KMR.strings.stats_section_failed))
            }
        }
        block {
            PanelsEqual(
                narrow,
                listOf(
                    { m ->
                        DetailPanel(stringResource(MR.strings.glass_stats_d_time), m) {
                            Line(stringResource(MR.strings.glass_stats_d_with_pauses), formatDuration(session.elapsedDuration.value))
                            Line(stringResource(KMR.strings.stats_started_label), formatInstant(session.startedAtEpochMillis))
                        }
                    },
                    { m ->
                        DetailPanel(stringResource(KMR.strings.stats_section_characters), m) {
                            Line(stringResource(MR.strings.glass_stats_d_new_text), formatCount(session.uniqueSourceCharacters.value))
                            Line(stringResource(MR.strings.glass_stats_d_moved_ahead), formatCount(session.netCharacters.value))
                        }
                    },
                ),
            )
        }
        block {
            DetailPanel(stringResource(MR.strings.glass_stats_d_where_read), Modifier.fillMaxWidth()) {
                if (loaded == null) {
                    when {
                        detail.error -> Note(stringResource(KMR.strings.stats_section_failed))
                        detail.value != null -> Text(stringResource(MR.strings.glass_stats_none), color = Glass.secondary, fontSize = Glass.body)
                        else -> Text(stringResource(MR.strings.glass_stats_loading), color = Glass.secondary, fontSize = Glass.body)
                    }
                } else {
                    SourceRows(loaded.sources, hasNextPage = false, loading = false, onLoadMore = {}, showTitle = false)
                }
            }
        }
        block {
            ManageBox(
                narrow,
                actions = {
                    if (!session.legacyImport && session.status != SessionStatus.ACTIVE) {
                        ManageAction("arrowUp", stringResource(MR.strings.glass_stats_d_move_session)) { correctTitle = true }
                    }
                    ManageAction("close", stringResource(KMR.strings.stats_delete_session)) { confirmDelete = true }
                    if (session.legacyImport) Note(stringResource(KMR.strings.stats_legacy_session_detail))
                },
                rows = {
                    Line(stringResource(KMR.strings.stats_started_label), formatInstant(session.startedAtEpochMillis))
                    Line(stringResource(KMR.strings.stats_status_label), sessionStatusLabel(session.status))
                    if (session.profileId.isNotBlank()) Line(stringResource(KMR.strings.stats_profile_label), session.profileId)
                },
            )
        }
    }
    if (confirmDelete) {
        val preview = details.sessionDeletionPreview
        GlassConfirm(
            stringResource(KMR.strings.stats_delete_session),
            stringResource(KMR.strings.stats_confirm),
            confirmEnabled = preview.value != null,
            onConfirm = {
                confirmDelete = false
                model.deleteSession(fallback)
            },
            onDismiss = { confirmDelete = false },
        ) {
            val p = preview.value
            Text(
                when {
                    p != null -> stringResource(
                        KMR.strings.stats_delete_scoped_preview,
                        formatCount(p.sessions),
                        formatDuration(p.activeDurationMillis),
                        formatCount(p.grossCharacters),
                        formatCount(p.sourceUnits),
                        formatCount(p.characters),
                        formatCount(p.goals),
                    )
                    preview.error -> stringResource(KMR.strings.stats_delete_preview_failed)
                    else -> stringResource(KMR.strings.stats_loading_section)
                },
                color = Glass.secondary,
                fontSize = Glass.body,
            )
        }
    }
    if (correctTitle) {
        // the Classic box (it holds a search field and the exact preview); only its opening is new
        SessionTitleCorrectionDialog(
            session = session,
            titleOptions = state.titleOptions,
            preview = details.sessionRelinkPreview,
            onDismiss = {
                correctTitle = false
                model.clearSessionRelinkPreview()
            },
            onPreview = model::previewSessionRelink,
            onApply = model::applySessionRelink,
            onClearPreview = model::clearSessionRelinkPreview,
        )
    }
}
