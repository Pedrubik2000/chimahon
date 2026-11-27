package eu.kanade.presentation.reader.glass

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.canopus.chimareader.data.MangaStatsStorage
import eu.kanade.presentation.reader.stats.MangaStatsDisplay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassWideButton
import eu.kanade.presentation.reader.stats.MangaStatsEstimate
import eu.kanade.presentation.reader.stats.formatDuration
import eu.kanade.presentation.reader.stats.formatDurationSeconds
import eu.kanade.presentation.reader.stats.sessionSpeed
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import java.text.NumberFormat

/** The Glass statistics panel (mockup A1): the session's numbers of the classic sheet, same state and pause action. */
@Composable
fun GlassStatsPanel(
    mangaId: Long,
    sessionCharacters: Int,
    sessionTimeMs: Long,
    estimate: MangaStatsEstimate,
    isTracking: Boolean?,
    onToggleTracking: (() -> Unit)?,
    onClose: () -> Unit,
) {
    val number = NumberFormat.getIntegerInstance()
    val context = LocalContext.current
    var today by remember { mutableStateOf(MangaStatsDisplay()) }
    var allTime by remember { mutableStateOf(MangaStatsDisplay()) }
    LaunchedEffect(mangaId) {
        val all = withContext(Dispatchers.IO) { MangaStatsStorage.loadAll(context) }
        val mine = all.filter { it.mangaId == mangaId }
        val now = mine.filter { it.dateKey == LocalDate.now().toString() }
        today = MangaStatsDisplay(now.sumOf { it.charactersRead }, now.sumOf { it.readingTime })
        allTime = MangaStatsDisplay(mine.sumOf { it.charactersRead }, mine.sumOf { it.readingTime })
    }
    GlassSidePanel(onLeft = false, onClose = onClose) {
        GlassPanelTitle(stringResource(MR.strings.action_manga_stats), onClose) {
            if (isTracking != null) {
                Text(
                    stringResource(if (isTracking) MR.strings.glass_stats_timer_running else MR.strings.glass_stats_timer_paused),
                    color = Glass.secondary,
                    fontSize = Glass.caption,
                )
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            GlassCap(stringResource(MR.strings.glass_stats_session))
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(number.format(sessionCharacters), fontSize = Glass.title, fontFamily = FontFamily.Serif)
                Text(stringResource(MR.strings.glass_stats_chars_read), Modifier.padding(bottom = 6.dp), color = Glass.secondary, fontSize = Glass.body)
            }
            GlassDivider()
            StatRow(stringResource(MR.strings.glass_stats_speed), stringResource(MR.strings.glass_stats_per_hour, number.format(sessionSpeed(sessionCharacters, sessionTimeMs))))
            GlassDivider()
            StatRow(stringResource(MR.strings.glass_stats_time), formatDuration(sessionTimeMs / 1000))
            GlassDivider()
            StatRow(stringResource(MR.strings.glass_stats_book), formatDurationSeconds(estimate.remainingBookSeconds))
            GlassDivider()
            StatRow(stringResource(MR.strings.glass_stats_chapter), formatDurationSeconds(estimate.remainingChapterSeconds))
            Period(stringResource(MR.strings.glass_stats_today), today)
            Period(stringResource(MR.strings.glass_stats_all_time), allTime)
        }
        if (onToggleTracking != null) {
            val running = isTracking == true
            GlassWideButton(
                stringResource(if (running) MR.strings.glass_stats_pause else MR.strings.glass_stats_resume),
                onToggleTracking,
                Modifier.fillMaxWidth().padding(top = 8.dp),
                primary = true,
                icon = if (running) "pause" else "play",
            )
        }
    }
}

/** Today / all time: characters, speed and time of this series, as rows. */
@Composable
private fun Period(title: String, stats: MangaStatsDisplay) {
    val number = NumberFormat.getIntegerInstance()
    GlassCap(title)
    StatRow(stringResource(MR.strings.glass_stats_chars_read), number.format(stats.charactersRead))
    GlassDivider()
    StatRow(stringResource(MR.strings.glass_stats_speed), stringResource(MR.strings.glass_stats_per_hour, number.format(stats.readingSpeed)))
    GlassDivider()
    StatRow(stringResource(MR.strings.glass_stats_time), formatDuration(stats.readingTimeMs / 1000))
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = Glass.secondary, fontSize = Glass.body)
        Text(value, fontSize = Glass.body)
    }
}
