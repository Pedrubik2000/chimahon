package eu.kanade.tachiyomi.ui.glass.stats

import android.graphics.Paint
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassRoundButton
import eu.kanade.presentation.glass.Pill
import eu.kanade.presentation.more.stats.StatsScreenState
import eu.kanade.presentation.more.stats.characterPriorityModeLabel
import eu.kanade.presentation.more.stats.formatCount
import eu.kanade.presentation.more.stats.formatDecimal
import eu.kanade.presentation.more.stats.formatInstant
import eu.kanade.presentation.more.stats.maturityLabel
import eu.kanade.presentation.more.stats.unicodeScriptLabel
import eu.kanade.tachiyomi.ui.stats.StatsScreenModel
import eu.kanade.tachiyomi.ui.stats.characterDisplayText
import tachiyomi.domain.immersion.model.AnalyticsCharacterPriorityFormula
import tachiyomi.domain.immersion.model.AnalyticsCharacterRow
import tachiyomi.domain.immersion.model.MaturityTier
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import java.util.Locale

/** A character's full page in Glass (mockup A, card 4): the glyph with its big numbers, then small panels. Same data and actions as the Classic CharacterDetail. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GlassStatsCharacterDetail(state: StatsScreenState.Success, character: AnalyticsCharacterRow, model: StatsScreenModel, narrow: Boolean) {
    val context = LocalContext.current
    val details = state.details
    val rows = state.sections.characters.value?.value?.items.orEmpty()
    val index = rows.indexOfFirst { it.codePoint == character.codePoint }
    val previous = rows.getOrNull(index - 1)
    val next = rows.getOrNull(index + 1)
    val hasGlyph = remember(character.rendered) { Paint(Paint.ANTI_ALIAS_FLAG).hasGlyph(character.rendered) }
    val displayText = characterDisplayText(rendered = character.rendered, codePoint = character.codePoint, hasGlyph = { hasGlyph })
    val listState = remember(character.codePoint) { LazyListState() }
    TabList(listState) {
        block { DetailHeader(stringResource(KMR.strings.stats_tab_characters), { model.selectCharacter(null) }) }
        block {
            val glyph: @Composable () -> Unit = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassRoundButton("chevLeft", { previous?.let(model::selectCharacter) }, description = stringResource(KMR.strings.stats_previous), outlined = true, enabled = previous != null)
                    Text(displayText, fontSize = if (hasGlyph) 96.sp else Glass.title, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    GlassRoundButton("chevRight", { next?.let(model::selectCharacter) }, description = stringResource(KMR.strings.stats_next), outlined = true, enabled = next != null)
                }
            }
            val numbers: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HeroNumbers(
                        buildList {
                            add(formatCount(character.occurrenceCount) to stringResource(MR.strings.glass_stats_d_times_read))
                            add(formatCount(character.titleCount) to stringResource(MR.strings.glass_stats_d_titles))
                            add(maturityLabel(character.maturity) to stringResource(MR.strings.glass_stats_on_cards))
                            character.jlptLevel?.let { add("N$it" to stringResource(MR.strings.glass_stats_d_jlpt)) }
                            character.frequencyRank?.let { add("#${formatCount(it)}" to stringResource(MR.strings.glass_stats_d_how_common)) }
                        },
                        narrow,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill(stringResource(KMR.strings.stats_open_dictionary), { lookUp(context, character.rendered) }, small = true, icon = "search")
                        character.japaneseReadings?.let {
                            Text(stringResource(KMR.strings.stats_readings_label) + ": " + it, color = Glass.secondary, fontSize = Glass.body)
                        }
                    }
                }
            }
            if (narrow) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    glyph()
                    numbers()
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                    glyph()
                    Column(Modifier.weight(1f)) { numbers() }
                }
            }
        }
        block {
            PanelRow(
                narrow,
                { m ->
                    DetailPanel(stringResource(MR.strings.glass_stats_d_on_your_cards), m) {
                        val items = details.characterAnkiItems.value
                        when {
                            items == null && details.characterAnkiItems.error -> Note(stringResource(KMR.strings.stats_section_failed))
                            items == null -> Text(stringResource(MR.strings.glass_stats_loading), color = Glass.secondary, fontSize = Glass.body)
                            items.isEmpty() && character.maturity == MaturityTier.UNAVAILABLE -> Note(stringResource(KMR.strings.stats_character_anki_unavailable))
                            items.isEmpty() -> Text(stringResource(MR.strings.glass_stats_none), color = Glass.secondary, fontSize = Glass.body)
                            else -> items.forEach { item ->
                                val scheduling = listOfNotNull(
                                    item.intervalDays?.let {
                                        stringResource(KMR.strings.stats_anki_card_interval, pluralStringResource(KMR.plurals.stats_day_count, it, formatCount(it.toLong())))
                                    },
                                    item.repetitions?.let { stringResource(KMR.strings.stats_anki_card_reviews, formatCount(it.toLong())) },
                                    item.lapses?.let { stringResource(KMR.strings.stats_anki_card_lapses, formatCount(it.toLong())) },
                                )
                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(
                                        maturityLabel(item.maturityTier),
                                        Modifier.border(1.dp, Glass.ice, CircleShape).padding(horizontal = 12.dp, vertical = 4.dp),
                                        color = Glass.ice,
                                        fontSize = Glass.caption,
                                        maxLines = 1,
                                    )
                                    if (scheduling.isNotEmpty()) Text(scheduling.joinToString(" · "), color = Glass.secondary, fontSize = Glass.caption)
                                }
                            }
                        }
                    }
                },
                { m ->
                    DetailPanel(stringResource(MR.strings.glass_stats_d_where_met), m) {
                        DetailSection(details.characterOccurrences) { page ->
                            SourceRows(page.items, page.nextOffset != null, details.characterOccurrences.refreshing, model::loadMoreCharacterOccurrences, showTitle = true)
                        }
                    }
                },
            )
        }
        block {
            val hasPriority = character.frequencyRank != null || character.jlptLevel != null || character.gradeLevel != null
            PanelsEqual(
                narrow,
                listOfNotNull<@Composable (Modifier) -> Unit>(
                    { m ->
                        DetailPanel(stringResource(MR.strings.glass_stats_d_seen), m) {
                            Line(stringResource(KMR.strings.stats_source_units), formatCount(character.sourceUnitCount))
                            Line(stringResource(KMR.strings.stats_first_seen_label), formatInstant(character.firstSeenAtEpochMillis))
                            Line(stringResource(KMR.strings.stats_last_seen_label), formatInstant(character.lastSeenAtEpochMillis))
                        }
                    },
                    if (hasPriority) {
                        { m ->
                            DetailPanel(stringResource(MR.strings.glass_stats_d_priority), m) {
                                val parts = AnalyticsCharacterPriorityFormula.components(character.frequencyRank, character.jlptLevel, character.gradeLevel)
                                val mode = state.characterFilter.priorityMode
                                Line(stringResource(KMR.strings.stats_character_priority_score), formatDecimal(character.priorityScore))
                                Line(stringResource(KMR.strings.stats_character_priority_frequency_component), formatDecimal(parts.frequency))
                                Line(stringResource(KMR.strings.stats_character_priority_jlpt_component), formatDecimal(parts.jlpt))
                                Line(stringResource(KMR.strings.stats_character_priority_grade_component), formatDecimal(parts.grade))
                                Line(characterPriorityModeLabel(mode), formatDecimal(parts.score(mode)))
                                Note(stringResource(KMR.strings.stats_character_priority_formula_version, AnalyticsCharacterPriorityFormula.VERSION))
                            }
                        }
                    } else {
                        null
                    },
                    { m ->
                        DetailPanel(stringResource(MR.strings.glass_stats_anki_details), m) {
                            Line(stringResource(KMR.strings.stats_code_point_label), "U+%04X".format(Locale.ROOT, character.codePoint.value))
                            character.unicodeName?.let { Line(stringResource(KMR.strings.stats_unicode_name_label), it) }
                            Line(stringResource(KMR.strings.stats_script_label), unicodeScriptLabel(character.unicodeScript))
                            Line(stringResource(KMR.strings.stats_unicode_category_label), character.unicodeCategory)
                            character.gradeLevel?.let { Line(stringResource(KMR.strings.stats_grade_level), formatCount(it.toLong())) }
                        }
                    },
                ),
            )
        }
    }
}
