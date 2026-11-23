package eu.kanade.tachiyomi.ui.glass.title

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import eu.kanade.presentation.glass.glassPanel
import eu.kanade.presentation.util.glassDevice
import eu.kanade.tachiyomi.data.anilist.AniListLibrary
import eu.kanade.tachiyomi.data.coverage.CoverageMatch
import eu.kanade.tachiyomi.data.download.RealDebridQueue
import eu.kanade.tachiyomi.ui.glass.downloads.hatched
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

private const val COLUMNS = 6

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
    missing: List<Int>,
    fetchable: List<Int>,
    failed: List<RealDebridQueue.Row>,
    here: Set<Int>,
    active: Set<Int>,
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
    // BoxWithConstraints outside the scrolling column: inside it maxHeight would be unbounded
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // the content needs ~210dp (back row 44 + 2-line title 76 + meta 24 + pills 54): never less, on a ~360dp window
        val heroHeight = maxOf(maxHeight * 0.45f, 280.dp)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(Modifier.fillMaxWidth().height(heroHeight).background(Glass.panel)) {
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
            }

            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                    PhoneLegend(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        noSource = cells.any { it.state == VolState.NONE && it.number !in fetchable },
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    cells.chunked(COLUMNS).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { cell ->
                                PhoneCell(
                                    cell = cell,
                                    current = cell.number == current,
                                    dashed = cell.state == VolState.NONE && cell.number !in fetchable,
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
                }

                failed.forEach { FailedRow(it, here, active, onFetch) }

                if (missing.isNotEmpty()) {
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

                sourceList()
                aniList()
            }
        }
    }
}

/** The legend of [PhoneCell]: one swatch per state, drawn with the cell's own fill and border. */
@Composable
private fun PhoneLegend(modifier: Modifier, noSource: Boolean) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Swatch(Color.White.copy(alpha = 0.30f), stringResource(MR.strings.glass_title_legend_read))
        Swatch(Glass.have, stringResource(MR.strings.glass_title_legend_here, glassDevice()))
        Swatch(Glass.ice, stringResource(MR.strings.glass_title_legend_downloading))
        Swatch(Glass.ice.copy(alpha = 0.35f), stringResource(MR.strings.glass_dl_queued).lowercase())
        Swatch(Color.Transparent, stringResource(MR.strings.glass_dl_key_failed).lowercase(), failed = true)
        Swatch(Color.Transparent, stringResource(MR.strings.glass_phone_title_legend_none), line = Glass.line)
        if (noSource) Swatch(Color.Transparent, stringResource(MR.strings.glass_phone_title_legend_no_source), dashed = true)
    }
}

@Composable
private fun Swatch(fill: Color, text: String, failed: Boolean = false, dashed: Boolean = false, line: Color? = null) {
    val shape = RoundedCornerShape(3.dp)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier
                .size(12.dp)
                .clip(shape)
                .background(fill)
                .then(if (failed) Modifier.hatched(Color.White.copy(alpha = 0.18f)) else Modifier)
                .then(
                    when {
                        dashed -> Modifier.drawBehind {
                            drawRoundRect(
                                color = Color.White.copy(alpha = 0.35f),
                                cornerRadius = CornerRadius(3.dp.toPx()),
                                style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))),
                            )
                        }
                        failed -> Modifier.border(1.dp, Color.White.copy(alpha = 0.5f), shape)
                        line != null -> Modifier.border(1.dp, line, shape)
                        else -> Modifier
                    },
                ),
        )
        Text(text, color = Glass.secondary, fontSize = Glass.caption)
    }
}

/** One compact numbered cell; tap and long-press do what [VolumeCellView] does on the tablet. */
@Composable
private fun PhoneCell(
    cell: VolumeCell,
    current: Boolean,
    dashed: Boolean,
    onOpen: (LocalVolume) -> Unit,
    onFetch: () -> Unit,
    onCancel: () -> Unit,
    onSetRead: (LocalVolume, Boolean) -> Unit,
    modifier: Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Glass.r8)
    val local = cell.local
    val failed = cell.state == VolState.FAILED
    val fill = when (cell.state) {
        VolState.READ -> Color.White.copy(alpha = 0.30f)
        VolState.HERE -> if (current) Color.White else Glass.have
        VolState.DOWNLOADING -> Glass.ice
        VolState.QUEUED -> Glass.ice.copy(alpha = 0.35f)
        VolState.FAILED, VolState.NONE -> Color.Transparent
    }
    val ink = if (cell.state == VolState.HERE || cell.state == VolState.DOWNLOADING) Color.Black else Color.White
    Box(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(shape)
                .background(fill)
                .then(if (failed) Modifier.hatched(Color.White.copy(alpha = 0.18f)) else Modifier)
                .then(
                    when {
                        current -> Modifier.border(2.dp, Glass.ice, shape)
                        dashed -> Modifier.drawBehind {
                            drawRoundRect(
                                color = Color.White.copy(alpha = 0.35f),
                                cornerRadius = CornerRadius(Glass.r8.toPx()),
                                style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))),
                            )
                        }
                        cell.state == VolState.NONE || failed -> Modifier.border(1.dp, if (failed) Color.White.copy(alpha = 0.5f) else Glass.line, shape)
                        else -> Modifier
                    },
                )
                .combinedClickable(
                    onClick = {
                        when {
                            local != null -> onOpen(local)
                            cell.state == VolState.NONE || cell.state == VolState.READ || failed -> onFetch() // READ here = read on AniList, not on the phone
                            else -> menu = true
                        }
                    },
                    onLongClick = { if (local != null) menu = true },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(cell.number.toString(), color = ink, fontSize = Glass.label, fontWeight = FontWeight.Bold)
                if (cell.state == VolState.DOWNLOADING) {
                    cell.fraction?.let { Text("${(it * 100).toInt()}%", color = ink, fontSize = 10.sp) }
                }
            }
            val icon = when (cell.state) {
                VolState.READ -> "check"
                VolState.FAILED -> "alert"
                else -> null
            }
            icon?.let {
                GlassIcon(
                    it,
                    size = 12.dp,
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.align(Alignment.TopEnd).padding(3.dp),
                )
            }
        }
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
