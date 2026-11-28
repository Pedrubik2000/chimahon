package eu.kanade.tachiyomi.ui.glass.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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
import eu.kanade.presentation.more.stats.characterValue
import eu.kanade.presentation.more.stats.collectionLoadState
import eu.kanade.presentation.more.stats.formatCount
import eu.kanade.presentation.more.stats.formatDecimal
import eu.kanade.presentation.more.stats.formatDurationCompact
import eu.kanade.presentation.more.stats.formatRate
import eu.kanade.presentation.more.stats.titleCoverageFilterLabel
import eu.kanade.presentation.more.stats.titleSortLabel
import eu.kanade.presentation.more.stats.titleStateFilterLabel
import eu.kanade.tachiyomi.ui.stats.StatsScreenModel
import tachiyomi.domain.immersion.model.AnalyticsTitleCoverageFilter
import tachiyomi.domain.immersion.model.AnalyticsTitleFilter
import tachiyomi.domain.immersion.model.AnalyticsTitleRow
import tachiyomi.domain.immersion.model.AnalyticsTitleSort
import tachiyomi.domain.immersion.model.AnalyticsTitleStateFilter
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

/** Width of each title's time bar: the longest time is 1 (full width), the others in proportion; all zero gives 0. */
internal fun timeBarFractions(times: List<Long>): List<Float> {
    val max = times.maxOrNull() ?: 0L
    return times.map { if (max <= 0L) 0f else it.coerceAtLeast(0L).toFloat() / max }
}

/** The tiles total the whole period only when every title is loaded and no search or filter narrows the list. */
internal fun titleTotalsComplete(loaded: Boolean, nextOffset: Long?, search: String, filter: AnalyticsTitleFilter): Boolean =
    loaded && nextOffset == null && search.isBlank() && filter.copy(searchQuery = null) == AnalyticsTitleFilter()

@Composable
private fun coverageLabel(value: AnalyticsTitleCoverageFilter): String = when (value) {
    AnalyticsTitleCoverageFilter.ALL -> titleCoverageFilterLabel(value)
    AnalyticsTitleCoverageFilter.COMPLETE -> stringResource(MR.strings.glass_stats_cov_complete)
    AnalyticsTitleCoverageFilter.PARTIAL -> stringResource(MR.strings.glass_stats_cov_partial)
    AnalyticsTitleCoverageFilter.MISSING -> stringResource(MR.strings.glass_stats_cov_missing)
}

/** The Titles tab in Glass (mockup C, card 2): tiles where the loaded rows give the number, then "Time per title" with search, sort and filters. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GlassStatsTitles(state: StatsScreenState.Success, model: StatsScreenModel, narrow: Boolean, listState: LazyListState) {
    val metric = state.filter.characterMetric
    val section = state.sections.titles
    val page = section.value?.value
    val rows = page?.items.orEmpty()
    val loadState = section.collectionLoadState(rows.isNotEmpty())
    val complete = titleTotalsComplete(page != null, page?.nextOffset, state.titleSearch, state.titleFilter)
    val hasMore = page?.nextOffset != null
    TabList(listState) {
        refreshLine(section.refreshingOverContent())
        if (rows.isNotEmpty() && complete) {
            block {
                val time = rows.sumOf { it.metrics.activeTime.value }
                val chars = rows.sumOf { it.metrics.characterValue(metric) }
                val gross = rows.sumOf { it.metrics.characters.gross.value }
                val cards = rows.sumOf { it.metrics.cardsCreated.value }
                val tiles = buildList {
                    add(Tile(formatCount(rows.size.toLong()), stringResource(MR.strings.glass_stats_titles_read), null))
                    if (time > 0L) {
                        val top = rows.maxBy { it.metrics.activeTime.value }
                        add(Tile(formatDurationCompact(time), stringResource(MR.strings.glass_stats_time_read), stringResource(MR.strings.glass_stats_most, top.displayTitle)))
                        if (chars >= 0L) {
                            val fastest = rows.filter { it.speedRankingEligible }.mapNotNull { r -> r.metrics.readingSpeedPerHour(metric)?.let { r to it } }.maxByOrNull { it.second }
                            add(
                                Tile(
                                    formatRate(chars * 3_600_000.0 / time),
                                    stringResource(MR.strings.glass_stats_chars_per_hour),
                                    fastest?.let { stringResource(MR.strings.glass_stats_fastest, it.first.displayTitle) },
                                ),
                            )
                        }
                    }
                    if (gross > 0L) {
                        val best = rows.mapNotNull { r -> r.metrics.miningRatePerTenThousandGrossCharacters()?.let { r to it } }.maxByOrNull { it.second }
                        add(
                            Tile(
                                formatDecimal(cards * 10_000.0 / gross),
                                stringResource(MR.strings.glass_stats_cards_per_10k),
                                best?.takeIf { it.second > 0.0 }?.let { stringResource(MR.strings.glass_stats_most, it.first.displayTitle) },
                            ),
                        )
                    }
                }
                Tiles(tiles, narrow, 4)
            }
        }
        // the "Time per title" panel is the head, one item per row and a tail: together one rounded panel
        // a key: the search box inside keeps its focus when the tiles or the refresh line above come and go
        item(key = "head") {
            PanelSlice(top = true, bottom = !loadState.showContent) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(stringResource(MR.strings.glass_stats_time_per_title), fontSize = Glass.label, fontWeight = FontWeight.SemiBold)
                    GlassSearchBox(state.titleSearch, model::searchTitles, stringResource(KMR.strings.stats_search_titles))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassMenuChip(
                            titleSortLabel(state.titleSort),
                            listOf(
                                AnalyticsTitleSort.MOST_RECENT,
                                AnalyticsTitleSort.ALPHABETICAL,
                                AnalyticsTitleSort.MOST_TIME,
                                AnalyticsTitleSort.MOST_CHARACTERS,
                                AnalyticsTitleSort.READING_SPEED,
                                AnalyticsTitleSort.MINING_RATE,
                                AnalyticsTitleSort.PROGRESS,
                            ),
                            { titleSortLabel(it) },
                            model::selectTitleSort,
                        )
                        GlassMenuChip(
                            stringResource(KMR.strings.stats_title_state_filter, titleStateFilterLabel(state.titleFilter.state)),
                            AnalyticsTitleStateFilter.entries,
                            { titleStateFilterLabel(it) },
                            { model.updateTitleFilter(state.titleFilter.copy(state = it)) },
                        )
                        GlassMenuChip(
                            stringResource(MR.strings.glass_stats_text_found, coverageLabel(state.titleFilter.coverage)),
                            AnalyticsTitleCoverageFilter.entries,
                            { coverageLabel(it) },
                            { model.updateTitleFilter(state.titleFilter.copy(coverage = it)) },
                        )
                    }
                    when {
                        loadState.showContent -> Unit
                        loadState.showError -> Pill(stringResource(KMR.strings.stats_retry_section), { model.retrySection(StatsSection.TITLES) }, small = true)
                        loadState.showLoading -> Text(stringResource(MR.strings.glass_stats_loading), color = Glass.secondary, fontSize = Glass.body)
                        else -> Text(stringResource(MR.strings.glass_stats_none), color = Glass.secondary, fontSize = Glass.body)
                    }
                }
            }
        }
        if (loadState.showContent) {
            val bars = timeBarFractions(rows.map { it.metrics.activeTime.value })
            rows.forEachIndexed { i, t ->
                item(key = t.titleId.value) {
                    PanelSlice(top = false, bottom = i == rows.lastIndex && !hasMore) {
                        TitleRow(state, t, bars[i], i == 0) { model.selectTitle(t) }
                    }
                }
            }
            if (hasMore) {
                item { PanelSlice(top = false, bottom = true) { LoadMore(section.refreshing, model::loadMoreTitles) } }
            }
        }
    }
}

/** One "Time per title" row: name and details, the time bar (first row ice), the time, a chevron. A tap opens the title's detail (Classic). */
@Composable
private fun TitleRow(state: StatsScreenState.Success, t: AnalyticsTitleRow, fraction: Float, first: Boolean, onClick: () -> Unit) {
    val name = state.titleMetadata[t.titleId]?.localDisplayTitle ?: t.displayTitle
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Glass.r8)).clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(2f)) {
            Text(name, fontSize = Glass.body, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(
                    MR.strings.glass_stats_title_sub,
                    formatCount(t.metrics.characterValue(state.filter.characterMetric)),
                    formatCount(t.metrics.sessions.value),
                    shortDate(t.lastActiveDate),
                ),
                color = Glass.secondary,
                fontSize = Glass.caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(Modifier.weight(3f).height(10.dp).clip(CircleShape).background(Glass.line)) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(if (first) Glass.ice else Glass.secondary))
        }
        Text(formatDurationCompact(t.metrics.activeTime.value), Modifier.width(72.dp), fontSize = Glass.body, textAlign = TextAlign.End, maxLines = 1)
        GlassIcon("chevRight", size = 16.dp, tint = Glass.secondary)
    }
}
