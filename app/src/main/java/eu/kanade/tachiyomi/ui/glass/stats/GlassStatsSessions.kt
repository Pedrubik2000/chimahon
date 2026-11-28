package eu.kanade.tachiyomi.ui.glass.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassIcon
import eu.kanade.presentation.glass.Pill
import eu.kanade.presentation.more.stats.StatsScreenState
import eu.kanade.presentation.more.stats.StatsSection
import eu.kanade.presentation.more.stats.collectionLoadState
import eu.kanade.presentation.more.stats.formatCount
import eu.kanade.presentation.more.stats.formatDurationCompact
import eu.kanade.presentation.more.stats.formatHour
import eu.kanade.presentation.more.stats.sessionTitleName
import eu.kanade.tachiyomi.ui.stats.StatsScreenModel
import tachiyomi.domain.immersion.model.ImmersionLocalDate
import tachiyomi.domain.immersion.model.ImmersionSession
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** A stretch of time to draw on the 24-hour lines: [key] names the session it belongs to. */
internal class TimedSpan(val key: String, val startMillis: Long, val lengthMillis: Long)

/** A block on one day's 24-hour line: where it starts and how wide it is, both as a fraction of that day. */
internal class DayBlock(val key: String, val startFraction: Float, val widthFraction: Float)

internal class DayLine(val date: LocalDate, val blocks: List<DayBlock>)

/** For each of [days] (kept in order): the [spans] that overlap it as fractions of that day; a span that crosses midnight is cut at it and goes on in the next day. */
internal fun dayLines(spans: List<TimedSpan>, days: List<LocalDate>, zone: ZoneId): List<DayLine> = days.map { date ->
    val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val dayLength = (dayEnd - dayStart).toFloat()
    DayLine(
        date,
        spans.mapNotNull { s ->
            val end = s.startMillis + s.lengthMillis
            val from = maxOf(s.startMillis, dayStart)
            val to = minOf(end, dayEnd)
            val touches = if (s.lengthMillis == 0L) s.startMillis in dayStart until dayEnd else from < to
            if (touches) DayBlock(s.key, (from - dayStart) / dayLength, maxOf(to - from, 0L) / dayLength) else null
        },
    )
}

/** The days the "When you read" lines show: from the newest loaded day back to the oldest one, at most [max], newest first. */
internal fun recentDays(newest: LocalDate, oldest: LocalDate, max: Int = 7): List<LocalDate> {
    val count = (newest.toEpochDay() - oldest.toEpochDay() + 1).coerceIn(1L, max.toLong()).toInt()
    return (0 until count).map { newest.minusDays(it.toLong()) }
}

/** The block a tap at [x] (a fraction of the line) means: the one it is in, else the nearest within [reach]; null when none is that near. */
internal fun nearestBlock(blocks: List<DayBlock>, x: Float, reach: Float): DayBlock? = blocks
    .map { it to maxOf(it.startFraction - x, x - (it.startFraction + it.widthFraction), 0f) }
    .filter { it.second <= reach }
    .minByOrNull { it.second }
    ?.first

internal class SessionFact(val startMillis: Long, val activeMillis: Long)

/** The Sessions tiles. Only [count] is known while more pages exist; the rest are null then (a page is not the period). */
internal class SessionStats(
    val count: Int,
    val hasMore: Boolean,
    val typicalMillis: Long?,
    val longestMillis: Long?,
    val longestStartMillis: Long?,
    val favouriteHour: Int?,
    val perReadingDay: Double?,
)

/** Median length, longest, most common start hour and sessions per reading day: only when every session is loaded ([hasMore] false). */
internal fun sessionStats(facts: List<SessionFact>, hasMore: Boolean, zone: ZoneId): SessionStats? {
    if (facts.isEmpty()) return null
    if (hasMore) return SessionStats(facts.size, true, null, null, null, null, null)
    val lengths = facts.map { it.activeMillis }.sorted()
    val longest = facts.maxBy { it.activeMillis }
    val days = facts.map { Instant.ofEpochMilli(it.startMillis).atZone(zone).toLocalDate() }.distinct().size
    val favourite = facts.groupingBy { Instant.ofEpochMilli(it.startMillis).atZone(zone).hour }.eachCount().entries
        .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key }).first().key
    return SessionStats(facts.size, false, lengths[lengths.size / 2], longest.activeMillis, longest.startMillis, favourite, facts.size.toDouble() / days)
}

/** The Sessions tab in Glass (mockup C, card 4): tiles from the sessions, "When you read" (24 h line per day), then the sessions by day. */
@Composable
internal fun GlassStatsSessions(state: StatsScreenState.Success, model: StatsScreenModel, narrow: Boolean, listState: LazyListState) {
    val section = state.sections.sessions
    val page = section.value?.value
    val sessions = page?.items.orEmpty().sortedByDescending { it.startedAtEpochMillis }
    val loadState = section.collectionLoadState(sessions.isNotEmpty())
    val zone = ZoneId.systemDefault()
    val hasMore = page?.nextCursor != null
    fun dateOf(s: ImmersionSession) = Instant.ofEpochMilli(s.startedAtEpochMillis).atZone(zone).toLocalDate()
    TabList(listState) {
        refreshLine(section.refreshingOverContent())
        if (sessions.isEmpty()) {
            block {
                when {
                    loadState.showError -> Pill(stringResource(KMR.strings.stats_retry_section), { model.retrySection(StatsSection.SESSIONS) }, small = true)
                    loadState.showLoading -> Text(stringResource(MR.strings.glass_stats_loading), color = Glass.secondary, fontSize = Glass.body)
                    else -> Text(stringResource(MR.strings.glass_stats_none), color = Glass.secondary, fontSize = Glass.body)
                }
            }
            return@TabList
        }
        val stats = sessionStats(sessions.map { SessionFact(it.startedAtEpochMillis, it.activeDuration.value) }, hasMore, zone)
        val today = LocalDate.now(zone)
        if (stats != null) {
            block {
                Tiles(
                    buildList {
                        add(
                            Tile(
                                formatCount(stats.count.toLong()) + if (stats.hasMore) "+" else "",
                                stringResource(KMR.strings.stats_sessions),
                                stats.perReadingDay?.let { stringResource(MR.strings.glass_stats_per_reading_day, NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(it)) },
                            ),
                        )
                        stats.typicalMillis?.let { add(Tile(formatDurationCompact(it), stringResource(MR.strings.glass_stats_typical), null)) }
                        stats.longestMillis?.let {
                            add(
                                Tile(
                                    formatDurationCompact(it),
                                    stringResource(MR.strings.glass_stats_longest_session),
                                    stats.longestStartMillis?.let { s -> shortDate(ImmersionLocalDate.from(Instant.ofEpochMilli(s).atZone(zone).toLocalDate())) },
                                ),
                            )
                        }
                        stats.favouriteHour?.let { add(Tile(formatHour(it), stringResource(MR.strings.glass_stats_fav_hour), null)) }
                    },
                    narrow,
                    4,
                )
            }
        }
        block {
            // the oldest loaded day can be cut short by the page: it is drawn only once every session is loaded
            val days = recentDays(dateOf(sessions.first()), dateOf(sessions.last())).let { if (hasMore && it.size > 1) it.dropLast(1) else it }
            val lines = dayLines(sessions.map { TimedSpan(it.id.value.toString(), it.startedAtEpochMillis, it.activeDuration.value) }, days, zone)
            val byKey = sessions.associateBy { it.id.value.toString() }
            Panel(stringResource(MR.strings.glass_stats_when_read), Modifier.fillMaxWidth(), action = {
                Text(stringResource(MR.strings.glass_stats_tap_block), color = Glass.secondary, fontSize = Glass.caption, maxLines = 1)
            }) {
                lines.forEach { line ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(dayLabel(line.date, today), Modifier.width(64.dp), color = Glass.secondary, fontSize = Glass.caption, maxLines = 1)
                        // the line is 20 dp, its touch area 40 dp: a tap opens the block nearest to it (blocks can be a few dp wide and touch each other)
                        BoxWithConstraints(
                            Modifier.weight(1f).height(40.dp).pointerInput(line.blocks) {
                                detectTapGestures { tap ->
                                    nearestBlock(line.blocks, tap.x / size.width, 20.dp.toPx() / size.width)?.let { byKey[it.key] }?.let(model::selectSession)
                                }
                            },
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Box(Modifier.fillMaxWidth().height(20.dp).background(Glass.line, RoundedCornerShape(4.dp)))
                            line.blocks.forEach { b ->
                                Box(
                                    Modifier
                                        .offset(x = maxWidth * b.startFraction)
                                        .width((maxWidth * b.widthFraction).coerceAtLeast(6.dp))
                                        .height(20.dp)
                                        .background(Glass.ice, RoundedCornerShape(3.dp)),
                                )
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(start = 74.dp)) {
                    listOf(0, 3, 6, 9, 12, 15, 18, 21).forEach {
                        Text("%02d".format(it), Modifier.weight(1f), color = Glass.secondary, fontSize = Glass.caption, maxLines = 1)
                    }
                }
            }
        }
        // the sessions by day: day headers and session rows are the items of one rounded panel
        val entries = buildList<Any> {
            sessions.groupBy(::dateOf).forEach { (date, group) ->
                add(date)
                addAll(group)
            }
        }
        entries.forEachIndexed { i, e ->
            val bottom = i == entries.lastIndex && !hasMore
            when (e) {
                is LocalDate -> item(key = "day-$e") {
                    PanelSlice(top = i == 0, bottom = bottom) {
                        Text(dayLabel(e, today), Modifier.padding(top = 10.dp), color = Glass.secondary, fontSize = Glass.caption, fontWeight = FontWeight.SemiBold)
                    }
                }
                is ImmersionSession -> item(key = e.id.value.toString()) {
                    PanelSlice(top = false, bottom = bottom) { SessionRow(state, e) { model.selectSession(e) } }
                }
            }
        }
        if (hasMore) {
            item { PanelSlice(top = false, bottom = true) { LoadMore(section.refreshing, model::loadMoreSessions) } }
        }
    }
}

@Composable
private fun dayLabel(date: LocalDate, today: LocalDate): String =
    if (date == today) stringResource(MR.strings.glass_stats_today) else date.format(DateTimeFormatter.ofPattern("MMM d"))

/** One session: start time, title, characters, minutes, chevron. A tap opens the session's detail (Classic). */
@Composable
private fun SessionRow(state: StatsScreenState.Success, s: ImmersionSession, onClick: () -> Unit) {
    val time = Instant.ofEpochMilli(s.startedAtEpochMillis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Glass.r8)).clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(time, Modifier.width(72.dp), fontSize = Glass.body, maxLines = 1)
        Text(state.sessionTitleName(s) ?: stringResource(KMR.strings.stats_unknown), Modifier.weight(1f), fontSize = Glass.body, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(stringResource(MR.strings.glass_stats_chars_n, formatCount(s.grossCharacters.value)), color = Glass.secondary, fontSize = Glass.caption, maxLines = 1)
        Text(formatDurationCompact(s.activeDuration.value), Modifier.width(64.dp), fontSize = Glass.body, textAlign = TextAlign.End, maxLines = 1)
        GlassIcon("chevRight", size = 16.dp, tint = Glass.secondary)
    }
}
