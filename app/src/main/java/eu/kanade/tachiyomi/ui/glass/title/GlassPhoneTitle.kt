package eu.kanade.tachiyomi.ui.glass.title

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassIcon
import eu.kanade.presentation.glass.Pill
import eu.kanade.presentation.glass.StatusColours
import eu.kanade.presentation.glass.glassPanel
import eu.kanade.presentation.util.glassDevice
import eu.kanade.tachiyomi.data.anilist.AniListLibrary
import eu.kanade.tachiyomi.data.coverage.CoverageMatch
import eu.kanade.tachiyomi.data.download.RealDebridQueue
import eu.kanade.tachiyomi.ui.glass.downloads.hatched
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

private const val COLUMNS = 4

/**
 * The title page on a phone (canvas "Title page" of option A): one scrolling column. Cover hero, volume grid,
 * the "not on this phone" panel, sources, AniList. State and dialogs stay in [GlassTitleScreen]; this only lays them out.
 */
@Composable
internal fun GlassPhoneTitle(
    entry: AniListLibrary.Entry?,
    fallbackTitle: String?,
    primary: Primary,
    current: Int?,
    count: Int,
    cells: List<VolumeCell>,
    localCovers: Map<Int, java.io.File>,
    covers: Map<Int, String>,
    hideUnread: Boolean,
    missing: List<Int>,
    fetchable: List<Int>,
    failed: List<RealDebridQueue.Row>,
    here: Set<Int>,
    active: Set<Int>,
    colours: StatusColours,
    unavailable: Set<Int>,
    topRow: @Composable () -> Unit,
    onHome: Boolean,
    onAddHome: () -> Unit,
    onOpen: (LocalVolume) -> Unit,
    onFetch: (List<Int>) -> Unit,
    onByHand: () -> Unit,
    onCancel: (Int) -> Unit,
    onSetRead: (LocalVolume, Boolean) -> Unit,
    sourceList: @Composable () -> Unit,
    aniList: @Composable () -> Unit,
) {
    val seriesCover = entry?.let { it.coverLarge ?: it.cover }
    // BoxWithConstraints outside the scrolling column: inside it maxHeight would be unbounded
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // the content needs ~210dp (back row 44 + 2-line title 76 + meta 24 + pills 54): never less, on a ~360dp window
        val heroHeight = maxOf(maxHeight * 0.45f, 280.dp)
        // lazy: a volume row composes (and loads its cover) only when it scrolls near the screen
        val pad = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item { Box(Modifier.fillMaxWidth().height(heroHeight).background(Glass.panel)) {
                AsyncImage(
                    model = entry?.let { it.coverLarge ?: it.cover },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0f to Glass.page.copy(alpha = 0.55f),
                            0.2f to Glass.page.copy(alpha = 0f),
                            0.4f to Glass.page.copy(alpha = 0f),
                            0.75f to Glass.page.copy(alpha = 0.8f),
                            1f to Glass.page,
                        ),
                    ),
                )
                Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) { topRow() }
                Column(Modifier.align(Alignment.BottomStart).padding(start = 16.dp, end = 16.dp, bottom = 4.dp)) {
                    Text(
                        entry?.let { it.native ?: it.romaji ?: it.english } ?: fallbackTitle.orEmpty(),
                        fontSize = Glass.title,
                        lineHeight = 38.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val line = titleMetaLine(entry)
                    if (line.isNotEmpty()) {
                        Text(line, color = Glass.secondary, fontSize = Glass.body, modifier = Modifier.padding(top = 4.dp))
                    }
                    Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (primary) {
                            is Primary.Resume -> Pill(
                                stringResource(MR.strings.glass_title_resume, primary.volume.number, primary.volume.lastPageRead + 1),
                                onClick = { onOpen(primary.volume) },
                                primary = true,
                                icon = "play",
                            )
                            is Primary.Fetch -> if (entry != null) {
                                Pill(
                                    stringResource(MR.strings.glass_title_fetch_vol, primary.volume),
                                    onClick = { onFetch(listOf(primary.volume)) },
                                    primary = true,
                                    icon = "download",
                                )
                            }
                            Primary.None -> Unit
                        }
                        if (entry != null) {
                            if (onHome) {
                                Pill(stringResource(MR.strings.glass_title_on_home), onClick = {}, icon = "check")
                            } else {
                                Pill(stringResource(MR.strings.glass_title_add_home), onClick = onAddHome, icon = "plus")
                            }
                        }
                    }
                }
            } }

            item {
                Row(pad, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(MR.strings.glass_n_volumes, count),
                        fontSize = Glass.heading,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    if (fetchable.isNotEmpty()) {
                        Pill(
                            stringResource(MR.strings.glass_title_fetch_n, fetchable.size),
                            onClick = { onFetch(fetchable) },
                            icon = "download",
                            small = true,
                        )
                    }
                }
            }

            items(cells.chunked(COLUMNS), key = { it.first().number }) { row ->
                Row(
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEach { cell ->
                        PhoneCell(
                            cell = cell,
                            image = volumeImage(localCovers[cell.number], covers[cell.number], seriesCover),
                            standIn = localCovers[cell.number] == null && covers[cell.number] == null,
                            hidden = hideUnread && cell.state != VolState.READ &&
                                cell.local?.let { it.read || it.lastPageRead > 0 } != true,
                            current = cell.number == current,
                            unavailable = cell.number in unavailable && cell.state == VolState.NONE,
                            colours = colours,
                            onOpen = onOpen,
                            onFetch = { onFetch(listOf(cell.number)) },
                            onCancel = { onCancel(cell.number) },
                            onSetRead = onSetRead,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }

            item { StatusLegend(pad, cover = seriesCover, colours = colours, unavailable = unavailable.isNotEmpty()) }

            items(failed) { Box(pad) { FailedRow(it, here, active, onFetch) } }

            if (missing.isNotEmpty()) item {
                Box(pad) {
                    Column(
                        Modifier.fillMaxWidth().glassPanel().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            stringResource(MR.strings.glass_phone_title_missing, CoverageMatch.ranges(missing), glassDevice()),
                            fontSize = Glass.label,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (fetchable.isNotEmpty()) {
                                Pill(
                                    stringResource(MR.strings.glass_phone_title_fetch_range, CoverageMatch.ranges(fetchable)),
                                    onClick = { onFetch(fetchable) },
                                    modifier = Modifier.weight(1f),
                                    primary = true,
                                    icon = "download",
                                )
                            }
                            Pill(stringResource(MR.strings.glass_phone_title_by_hand), onClick = onByHand, icon = "external")
                        }
                    }
                }
            }

            item { Box(pad) { sourceList() } }
            item { Box(pad) { aniList() } }
        }
    }
}

/** One volume cover; tap and long-press do what [VolumeCellView] does on the tablet. */
@Composable
private fun PhoneCell(
    cell: VolumeCell,
    image: Any?,
    standIn: Boolean,
    hidden: Boolean,
    current: Boolean,
    unavailable: Boolean,
    colours: StatusColours,
    onOpen: (LocalVolume) -> Unit,
    onFetch: () -> Unit,
    onCancel: () -> Unit,
    onSetRead: (LocalVolume, Boolean) -> Unit,
    modifier: Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val local = cell.local
    Box(modifier) {
        VolumeTile(
            state = cell.state,
            number = cell.number,
            image = image,
            fraction = cell.fraction,
            standIn = standIn,
            blurred = hidden,
            current = current,
            onDevice = cell.state == VolState.READ && local != null,
            unavailable = unavailable,
            colours = colours,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {
                        when {
                            local != null -> onOpen(local)
                            cell.state == VolState.NONE || cell.state == VolState.READ || cell.state == VolState.FAILED -> onFetch() // READ here = read on AniList, not on the phone
                            else -> menu = true
                        }
                    },
                    onLongClick = { if (local != null) menu = true },
                ),
        )
        GlassMenu(menu, { menu = false }) {
            if (local != null) {
                MenuItem(stringResource(if (local.read) MR.strings.glass_title_mark_unread else MR.strings.glass_title_mark_read)) {
                    menu = false
                    onSetRead(local, !local.read)
                }
            } else {
                MenuItem(stringResource(MR.strings.action_cancel)) {
                    menu = false
                    onCancel()
                }
            }
        }
    }
}
