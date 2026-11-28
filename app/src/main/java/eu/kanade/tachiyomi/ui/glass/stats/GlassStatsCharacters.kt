package eu.kanade.tachiyomi.ui.glass.stats

import android.graphics.Paint
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassChip
import eu.kanade.presentation.glass.Pill
import eu.kanade.presentation.more.stats.StatsCharacterGridMode
import eu.kanade.presentation.more.stats.StatsCharacterLayout
import eu.kanade.presentation.more.stats.StatsRangePreset
import eu.kanade.presentation.more.stats.StatsScreenState
import eu.kanade.presentation.more.stats.StatsSection
import eu.kanade.presentation.more.stats.characterGridModeLabel
import eu.kanade.presentation.more.stats.characterMetadataBand
import eu.kanade.presentation.more.stats.characterPriorityModeLabel
import eu.kanade.presentation.more.stats.characterRangeLabel
import eu.kanade.presentation.more.stats.characterScriptLabel
import eu.kanade.presentation.more.stats.collectionLoadState
import eu.kanade.presentation.more.stats.formatCount
import eu.kanade.presentation.more.stats.formatDecimal
import eu.kanade.presentation.more.stats.formatInstant
import eu.kanade.presentation.more.stats.maturityLabel
import eu.kanade.presentation.more.stats.sortLabel
import eu.kanade.tachiyomi.ui.stats.StatsScreenModel
import eu.kanade.tachiyomi.ui.stats.characterDisplayText
import eu.kanade.tachiyomi.ui.stats.characterFrequencyLevel
import tachiyomi.domain.immersion.model.AnalyticsCharacterPriorityMode
import tachiyomi.domain.immersion.model.AnalyticsCharacterRange
import tachiyomi.domain.immersion.model.AnalyticsCharacterRow
import tachiyomi.domain.immersion.model.AnalyticsCharacterScript
import tachiyomi.domain.immersion.model.AnalyticsSort
import tachiyomi.domain.immersion.model.CapabilityState
import tachiyomi.domain.immersion.model.MaturityTier
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import java.text.NumberFormat
import java.util.Locale

/** The Characters tab in Glass (mockup C, card 3): tiles, the new-character chart, then search / chips and the grid or list, all rows of one lazy list. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun GlassStatsCharacters(state: StatsScreenState.Success, model: StatsScreenModel, narrow: Boolean, listState: LazyListState) {
    val summary = state.sections.characterSummary.value?.value
    val section = state.sections.characters
    val page = section.value?.value
    val rows = page?.items.orEmpty()
    val loadState = section.collectionLoadState(rows.isNotEmpty())
    val filter = state.characterFilter
    val selected = state.selectedCharacterCodePoints
    val hasMore = page?.nextOffset != null
    val grid = state.characterLayout == StatsCharacterLayout.GRID
    // the cells of a grid row come from the width the panel has: 64 dp cells, 8 dp apart, inside the 18 dp panel padding
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = ((maxWidth - 36.dp + 8.dp) / 72.dp).toInt().coerceAtLeast(1)
        val max = summary?.maximumOccurrenceCount ?: 0L
        val open = { c: AnalyticsCharacterRow -> model.selectCharacter(c) }
        val toggle = { c: AnalyticsCharacterRow -> model.setCharacterSelected(c.codePoint.value, c.codePoint.value !in selected) }
        TabList(listState) {
            refreshLine(section.refreshingOverContent() || state.sections.characterSummary.refreshingOverContent())
            if (summary != null) {
                block {
                    val mapped = state.profiles
                        .filter { state.filter.profileId == null || it.id == state.filter.profileId }
                        .any { it.ankiStatsCharacterField.isNotBlank() }
                    val capability = state.sections.anki.value?.value?.snapshot?.capabilityState
                    val coverage = mapped && summary.distinctCharacters > 0 &&
                        capability in setOf(CapabilityState.AVAILABLE, CapabilityState.PARTIAL, CapabilityState.STALE)
                    val newNote = if (state.filter.rangePreset != StatsRangePreset.ALL && summary.firstSeenInRange > 0) {
                        stringResource(MR.strings.glass_stats_new_in_period, formatCount(summary.firstSeenInRange))
                    } else {
                        null
                    }
                    Tiles(
                        buildList {
                            add(Tile(formatCount(summary.distinctCharacters), stringResource(MR.strings.glass_stats_different_chars), newNote))
                            add(Tile(formatCount(summary.grossOccurrenceExposure), stringResource(MR.strings.glass_stats_chars), null))
                            if (coverage) {
                                add(
                                    Tile(
                                        NumberFormat.getPercentInstance().format(summary.representedInAnki.toDouble() / summary.distinctCharacters),
                                        stringResource(MR.strings.glass_stats_on_cards),
                                        stringResource(MR.strings.glass_stats_aim, state.characterCoverageTargetPercent),
                                    ),
                                )
                            }
                        },
                        narrow,
                        3,
                    )
                }
            } else if (state.sections.characterSummary.error) {
                block { Pill(stringResource(KMR.strings.stats_retry_section), { model.retrySection(StatsSection.CHARACTER_SUMMARY) }, small = true) }
            }
            // what Classic plots: the characters first met in each bucket; hidden when every bucket is zero
            val points = state.sections.trends.value?.value?.points.orEmpty()
            val fresh = points.map { it.metrics.newCharacters.value.toDouble() }
            if (fresh.any { it > 0.0 }) {
                block {
                    Panel(stringResource(MR.strings.glass_stats_new_chars_over_time), Modifier.fillMaxWidth(), action = {
                        ScaleChips(state.trendScale, model::selectTrendScale)
                    }) {
                        BarChart(fresh, null, shortDate(points.first().range.start), shortDate(points.last().range.endInclusive), height = 110.dp)
                    }
                }
            }
            // the Characters panel: a head, the rows (grid rows or list rows) and a tail, one rounded panel
            // a key: the search box inside keeps its focus when the tiles or the refresh line above come and go
            item(key = "head") {
                PanelSlice(top = true, bottom = !loadState.showContent) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(stringResource(MR.strings.glass_stats_characters), fontSize = Glass.label, fontWeight = FontWeight.SemiBold)
                        GlassSearchBox(state.characterSearch, model::searchCharacters, stringResource(KMR.strings.stats_search_characters))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            GlassChip(stringResource(KMR.strings.stats_all), selected = filter.scripts.isEmpty(), onClick = { model.updateCharacterFilter(filter.copy(scripts = emptySet())) })
                            AnalyticsCharacterScript.entries.forEach { script ->
                                GlassChip(
                                    characterScriptLabel(script),
                                    selected = script in filter.scripts,
                                    onClick = { model.updateCharacterFilter(filter.copy(scripts = if (script in filter.scripts) filter.scripts - script else filter.scripts + script)) },
                                )
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            GlassMenuChip(
                                sortLabel(state.characterSort),
                                listOf(
                                    AnalyticsSort.MOST_OCCURRENCES,
                                    AnalyticsSort.MOST_RECENT,
                                    AnalyticsSort.FIRST_SEEN,
                                    AnalyticsSort.ALPHABETICAL,
                                    AnalyticsSort.FREQUENCY_RANK,
                                    AnalyticsSort.PRIORITY,
                                ),
                                { sortLabel(it) },
                                model::selectCharacterSort,
                            )
                            GlassMenuChip(
                                stringResource(MR.strings.glass_stats_colour, characterGridModeLabel(state.characterGridMode)),
                                StatsCharacterGridMode.entries,
                                { characterGridModeLabel(it) },
                                model::selectCharacterGridMode,
                            )
                            // the Classic "more filters": which characters (range) and how priority is scored
                            GlassMenuChip(
                                characterRangeLabel(filter.range),
                                AnalyticsCharacterRange.entries,
                                { characterRangeLabel(it) },
                                { model.updateCharacterFilter(filter.copy(range = it)) },
                            )
                            GlassMenuChip(
                                characterPriorityModeLabel(filter.priorityMode),
                                AnalyticsCharacterPriorityMode.entries,
                                { characterPriorityModeLabel(it) },
                                { model.updateCharacterFilter(filter.copy(priorityMode = it)) },
                            )
                            GlassChip(
                                stringResource(KMR.strings.stats_character_layout_grid),
                                selected = grid,
                                onClick = { model.selectCharacterLayout(StatsCharacterLayout.GRID) },
                            )
                            GlassChip(
                                stringResource(KMR.strings.stats_character_layout_list),
                                selected = !grid,
                                onClick = { model.selectCharacterLayout(StatsCharacterLayout.LIST) },
                            )
                        }
                        Text(stringResource(KMR.strings.stats_character_select_hint), color = Glass.secondary, fontSize = Glass.caption)
                        if (selected.isNotEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(MR.strings.glass_stats_selected, selected.size), Modifier.weight(1f), fontSize = Glass.body)
                                Pill(stringResource(KMR.strings.stats_clear_selection), model::clearCharacterSelection, small = true)
                                Pill(stringResource(KMR.strings.stats_export), model::exportSelectedCharacters, small = true, primary = true)
                            }
                        }
                        if (state.characterGridMode == StatsCharacterGridMode.MATURITY) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                Swatch(Color.White, stringResource(MR.strings.glass_stats_legend_learned))
                                Swatch(Glass.ice, stringResource(MR.strings.glass_stats_legend_young))
                                Text(stringResource(MR.strings.glass_stats_legend_none), color = Glass.secondary, fontSize = Glass.caption)
                            }
                        }
                        when {
                            loadState.showContent -> Unit
                            loadState.showError -> Pill(stringResource(KMR.strings.stats_retry_section), { model.retrySection(StatsSection.CHARACTERS) }, small = true)
                            loadState.showLoading -> Text(stringResource(MR.strings.glass_stats_loading), color = Glass.secondary, fontSize = Glass.body)
                            else -> Text(stringResource(MR.strings.glass_stats_none), color = Glass.secondary, fontSize = Glass.body)
                        }
                    }
                }
            }
            if (loadState.showContent) {
                if (grid) {
                    val lines = rows.chunked(columns)
                    lines.forEachIndexed { i, line ->
                        item(key = line.first().codePoint.value) {
                            PanelSlice(top = false, bottom = i == lines.lastIndex && !hasMore) {
                                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    line.forEach { c -> GridCell(c, state.characterGridMode, max, c.codePoint.value in selected, { open(c) }, { toggle(c) }) }
                                }
                            }
                        }
                    }
                } else {
                    rows.forEachIndexed { i, c ->
                        item(key = c.codePoint.value) {
                            PanelSlice(top = false, bottom = i == rows.lastIndex && !hasMore) {
                                Box(Modifier.padding(top = 6.dp)) { ListRow(c, state.characterGridMode, max, c.codePoint.value in selected, { open(c) }, { toggle(c) }) }
                            }
                        }
                    }
                }
                if (hasMore) {
                    item { PanelSlice(top = false, bottom = true) { Box(Modifier.padding(top = 10.dp)) { LoadMore(section.refreshing, model::loadMoreCharacters) } } }
                }
            }
        }
    }
}

@Composable
private fun Swatch(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Text(label, color = Glass.secondary, fontSize = Glass.caption)
    }
}

/** Fill and ink of a character: Colour = maturity gives white (learned card), ice (young card), dark (not on a card); any other colouring shades by how often it was met. */
private fun cellColours(c: AnalyticsCharacterRow, mode: StatsCharacterGridMode, max: Long): Pair<Color, Color> =
    if (mode == StatsCharacterGridMode.MATURITY) {
        when (c.maturity) {
            MaturityTier.MATURE -> Color.White to Color.Black
            MaturityTier.YOUNG, MaturityTier.LEARNING, MaturityTier.NEW -> Glass.ice to Color.Black
            else -> Glass.page to Color.White
        }
    } else {
        Color.White.copy(alpha = 0.06f + 0.07f * characterFrequencyLevel(c.occurrenceCount, max)) to Color.White
    }

/** The glyph, or its "U+XXXX" when the font has none. */
@Composable
private fun glyphText(c: AnalyticsCharacterRow): String {
    val hasGlyph = remember(c.rendered) { Paint(Paint.ANTI_ALIAS_FLAG).hasGlyph(c.rendered) }
    return characterDisplayText(c.rendered, c.codePoint) { hasGlyph }
}

/** Mockup C grid cell: the character and how often it was met. Tap = the Classic character detail, long press = select for export. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridCell(c: AnalyticsCharacterRow, mode: StatsCharacterGridMode, max: Long, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val (fill, ink) = cellColours(c, mode, max)
    val shape = RoundedCornerShape(Glass.r8)
    Column(
        Modifier
            .size(64.dp)
            .clip(shape)
            .background(fill)
            .border(if (selected) 2.dp else 1.dp, if (selected) Glass.ice else Glass.line, shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(glyphText(c), color = ink, fontSize = Glass.heading, maxLines = 1, overflow = TextOverflow.Clip)
        Text(formatCount(c.occurrenceCount), color = ink.copy(alpha = 0.7f), fontSize = Glass.caption, maxLines = 1)
    }
}

/** The List layout: glyph, name and the value of the chosen colouring, count. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListRow(c: AnalyticsCharacterRow, mode: StatsCharacterGridMode, max: Long, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val (fill, ink) = cellColours(c, mode, max)
    val shape = RoundedCornerShape(Glass.r8)
    val level = characterFrequencyLevel(c.occurrenceCount, max)
    val detail = when (mode) {
        StatsCharacterGridMode.FREQUENCY -> stringResource(KMR.strings.stats_character_frequency_level, level, 5)
        StatsCharacterGridMode.FIRST_SEEN -> formatInstant(c.firstSeenAtEpochMillis)
        StatsCharacterGridMode.MATURITY -> maturityLabel(c.maturity)
        StatsCharacterGridMode.METADATA -> characterMetadataBand(c)
        StatsCharacterGridMode.PRIORITY -> formatDecimal(c.priorityScore)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) Glass.ice else Glass.line, shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(44.dp).clip(shape).background(fill), contentAlignment = Alignment.Center) {
            Text(glyphText(c), color = ink, fontSize = Glass.heading, maxLines = 1, overflow = TextOverflow.Clip)
        }
        Column(Modifier.weight(1f)) {
            Text(c.unicodeName ?: "U+%04X".format(Locale.ROOT, c.codePoint.value), fontSize = Glass.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, color = Glass.secondary, fontSize = Glass.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(formatCount(c.occurrenceCount), Modifier.width(64.dp), fontSize = Glass.body, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End, maxLines = 1)
    }
}
