package eu.kanade.tachiyomi.ui.glass.downloads

import android.app.Application
import android.content.Context
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkManager
import cafe.adriel.voyager.navigator.Navigator
import coil3.compose.AsyncImage
import com.hippo.unifile.UniFile
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassRoundButton
import eu.kanade.presentation.glass.GlassPhoneBarRoom
import eu.kanade.presentation.glass.GlassIcon
import eu.kanade.presentation.glass.Meter
import eu.kanade.presentation.glass.Pill
import eu.kanade.presentation.glass.glassPanel
import eu.kanade.presentation.reader.glass.GlassReaderTheme
import eu.kanade.presentation.util.glassDevice
import eu.kanade.presentation.util.isTabletUi
import eu.kanade.tachiyomi.data.anilist.AniListLibrary
import eu.kanade.tachiyomi.data.animedownload.AnimeDownloadManager
import eu.kanade.tachiyomi.data.coverage.Coverage
import eu.kanade.tachiyomi.data.coverage.CoverageMatch
import eu.kanade.tachiyomi.data.coverage.SourceCoverage
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.RealDebridImportJob
import eu.kanade.tachiyomi.data.download.RealDebridQueue
import eu.kanade.tachiyomi.data.download.RealDebridQueue.State
import eu.kanade.tachiyomi.data.ocr.OcrManager
import eu.kanade.tachiyomi.data.ocr.OcrQueueStatus
import eu.kanade.tachiyomi.ui.download.DownloadQueueScreen
import eu.kanade.tachiyomi.ui.glass.GlassTitle
import eu.kanade.tachiyomi.ui.glass.GlassTitlesModel
import eu.kanade.tachiyomi.ui.glass.openTitle
import eu.kanade.tachiyomi.util.storage.DiskUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/** Canvas `Downloads-12B.dc.html`: Real-Debrid jobs by title, the OCR and source queues, sources and storage. */
@Composable
fun GlassDownloadsPage(model: GlassTitlesModel, navigator: Navigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val queue = remember { Injekt.get<RealDebridQueue>() }
    val aniList = remember { Injekt.get<AniListLibrary>() }
    val ocr = remember { Injekt.get<OcrManager>() }
    val downloadManager = remember { Injekt.get<DownloadManager>() }
    val animeDownloadManager = remember { Injekt.get<AnimeDownloadManager>() }
    val rowsFlow = remember { queue.rows }

    val rows by rowsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val titles by model.titles.collectAsStateWithLifecycle()
    val ocrQueue by ocr.queueState.collectAsStateWithLifecycle()
    val mangaQueue by downloadManager.queueState.collectAsStateWithLifecycle()
    val animeQueue by animeDownloadManager.queueState.collectAsStateWithLifecycle()

    val byId = remember(titles) { titles.orEmpty().associateBy { it.mediaId } }
    // the volumes on the tablet of each title (the bars and the failed rule), read again when a job finishes
    val mediaIds = remember(rows) { rows.mapNotNull { it.mediaId }.distinct().sorted() }
    val finished = rows.count { it.state == State.DONE || it.state == State.FAILED }
    val here by produceState(emptyMap<Long, Set<Int>>(), mediaIds, finished) { value = GlassDownloads.volumesHere(mediaIds) }
    val groups = remember(rows, here) { GlassDownloads.group(rows, here) }
    val sourceNames = remember { Injekt.get<SourceCoverage>().sources(includeOff = true).associate { it.id to it.name } }

    fun titleOf(id: Long?): GlassTitle? = id?.let { byId[it] ?: aniList.get(it)?.let(::GlassTitle) }
    fun groupKey(g: TitleGroup) = g.mediaId?.toString() ?: "shared"

    var open by remember { mutableStateOf<Set<String>?>(null) }
    val expanded = open ?: setOfNotNull(groups.firstOrNull { it.active }?.let(::groupKey))
    var confirmCancel by remember { mutableStateOf(false) }

    fun tryAgain(g: TitleGroup) {
        val title = titleOf(g.mediaId) ?: return
        scope.launch {
            val queued = withContext(Dispatchers.IO) {
                val cached = cachedCoverage(context, title.mediaId)
                if (cached.isEmpty()) emptySet() else queue.enqueueAll(title.entry, g.failedVolumes, cached)
            }
            // nothing cached (or no source has them now): the title page fetches coverage
            if (queued.isEmpty()) openTitle(navigator, title)
        }
    }

    val active = groups.filter { it.active }
    val activeVolumes = active.sumOf { g ->
        GlassDownloads.toAdd(g.rows.filter { it.state == State.RUNNING || it.state == State.QUEUED }, here[g.mediaId].orEmpty())
    }
    val speed = rows.filter { it.state == State.RUNNING }.sumOf { it.speed }
    val summary = buildString {
        append(stringResource(MR.strings.glass_dl_summary, active.size, activeVolumes))
        if (speed > 0) append(" · ").append(stringResource(MR.strings.glass_dl_speed, Formatter.formatShortFileSize(context, speed)))
    }

    val wide = LocalConfiguration.current.screenWidthDp >= 900
    val aside: @Composable (Modifier) -> Unit = { m -> Aside(sourceNames.keys.toList(), sourceNames, m) }

    val phone = !isTabletUi()
    Row(
        Modifier.fillMaxSize().padding(start = if (phone) 16.dp else 108.dp, top = 24.dp, end = if (phone) 16.dp else 32.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        LazyColumn(
            Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = if (phone) GlassPhoneBarRoom else 24.dp),
        ) {
            item {
                Row(
                    Modifier.padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        stringResource(MR.strings.glass_dl_title),
                        Modifier.weight(1f),
                        fontSize = Glass.title,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (active.isNotEmpty()) {
                        // phone: the summary goes under the heading, or it squeezes "Downloads" to a letter per line
                        if (!phone) Text(summary, color = Glass.secondary, fontSize = Glass.body, maxLines = 1)
                        Pill(stringResource(MR.strings.glass_dl_cancel_all), onClick = { confirmCancel = true }, icon = "close")
                    }
                }
            }
            if (phone && active.isNotEmpty()) {
                item { Text(summary, color = Glass.secondary, fontSize = Glass.body, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            if (groups.isEmpty()) {
                item {
                    Text(
                        stringResource(MR.strings.glass_dl_nothing),
                        Modifier.padding(vertical = 24.dp),
                        color = Glass.secondary,
                        fontSize = Glass.label,
                    )
                }
            }
            items(groups, key = { groupKey(it) }) { g ->
                val k = groupKey(g)
                TitleCard(
                    group = g,
                    title = titleOf(g.mediaId),
                    here = here[g.mediaId].orEmpty(),
                    sourceNames = sourceNames,
                    expanded = k in expanded,
                    onToggle = { open = if (k in expanded) expanded - k else expanded + k },
                    // not cancelWorkById: that also cancels every job queued behind this one
                    onCancel = { row -> queue.cancel(row.workId) },
                    onTryAgain = { tryAgain(g) },
                    onOpen = { t -> openTitle(navigator, t) },
                )
            }
            if (ocrQueue.isNotEmpty()) {
                item {
                    val current = ocrQueue.firstOrNull { it.status == OcrQueueStatus.PROCESSING } ?: ocrQueue.first()
                    QueueSection(
                        title = stringResource(MR.strings.glass_dl_ocr),
                        count = ocrQueue.size,
                        current = buildString {
                            append(current.chapter.name).append(" · ").append(current.manga.title)
                            if (current.status == OcrQueueStatus.PROCESSING && current.totalPages > 0) {
                                append(" · ").append(current.currentPage).append('/').append(current.totalPages)
                            }
                        },
                        onOpen = { navigator.push(DownloadQueueScreen) },
                    )
                }
            }
            if (mangaQueue.isNotEmpty() || animeQueue.isNotEmpty()) {
                item {
                    QueueSection(
                        title = stringResource(MR.strings.glass_dl_sources_queue),
                        count = mangaQueue.size + animeQueue.size,
                        current = mangaQueue.firstOrNull()?.let { "${it.manga.title} · ${it.chapter.name}" }
                            ?: animeQueue.firstOrNull()?.let { "${it.anime.title} · ${it.episode.name}" },
                        onOpen = { navigator.push(DownloadQueueScreen) },
                    )
                }
            }
            if (!wide) item { aside(Modifier.padding(top = 14.dp)) }
        }
        if (wide) aside(Modifier.width(330.dp).padding(top = 68.dp).verticalScroll(rememberScrollState()))
    }

    if (confirmCancel) {
        GlassReaderTheme {
            AlertDialog(
                onDismissRequest = { confirmCancel = false },
                text = { Text(stringResource(MR.strings.glass_dl_cancel_all_confirm)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            // flagged first: each job then removes what it wrote (RealDebridImportJob.undo)
                            rows.filter { it.state == State.RUNNING || it.state == State.QUEUED }.forEach { queue.cancel(it.workId) }
                            WorkManager.getInstance(context).cancelAllWorkByTag(RealDebridImportJob.TAG)
                            confirmCancel = false
                        },
                    ) { Text(stringResource(MR.strings.glass_dl_cancel_all)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmCancel = false }) { Text(stringResource(MR.strings.glass_dl_keep)) }
                },
            )
        }
    }
}

@Composable
private fun TitleCard(
    group: TitleGroup,
    title: GlassTitle?,
    here: Set<Int>,
    sourceNames: Map<Long, String>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onCancel: (RealDebridQueue.Row) -> Unit,
    onTryAgain: () -> Unit,
    onOpen: (GlassTitle) -> Unit,
) {
    val shape = RoundedCornerShape(if (expanded) Glass.r16 else Glass.r12)
    val failed = group.failed
    val border = when {
        expanded -> Modifier.border(2.dp, Glass.ice, shape)
        failed.isNotEmpty() && !group.active -> Modifier.border(1.dp, Color.White.copy(alpha = 0.6f), shape)
        else -> Modifier.border(1.dp, Glass.line, shape)
    }
    val running = group.running
    val name = when {
        group.mediaId == null -> stringResource(MR.strings.glass_dl_shared_links)
        title != null -> title.name
        else -> "AniList ${group.mediaId}"
    }

    Column(
        Modifier.fillMaxWidth().clip(shape).background(Glass.panel).then(border).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val coverW = if (expanded) 48.dp else 40.dp
            AsyncImage(
                model = title?.entry?.cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(coverW, coverW * 1.5f).clip(RoundedCornerShape(Glass.r8)).background(Glass.page)
                    // the cover opens the title page; the chevron keeps expand/collapse
                    .then(if (title != null && group.mediaId != null) Modifier.clickable { onOpen(title) } else Modifier),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val phone = !isTabletUi()
                val stateLine = if (expanded) null else titleLine(group, sourceNames, here)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        name,
                        Modifier.weight(1f),
                        fontSize = Glass.label,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (expanded) {
                        running?.let { r -> (r.servedBy ?: r.sourceId)?.let(sourceNames::get) }?.let {
                            Text(stringResource(MR.strings.glass_dl_from, it), color = Glass.secondary, fontSize = Glass.caption)
                        }
                    } else if (!phone && stateLine != null) {
                        val (line, color) = stateLine
                        Text(
                            line,
                            // shares the row with the name and ellipsizes; clear of the Try again pill
                            Modifier.weight(1f, fill = false)
                                .then(if (failed.isNotEmpty() && group.mediaId != null) Modifier.padding(end = 14.dp) else Modifier),
                            color = color,
                            fontSize = Glass.caption,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // phone: the state line goes under the name, so the name keeps the row's whole width
                if (phone && stateLine != null) {
                    Text(stateLine.first, color = stateLine.second, fontSize = Glass.caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (group.mediaId != null) {
                    val count = GlassDownloads.volumeCount(
                        title?.entry?.volumes,
                        title?.volumesRead ?: 0,
                        here.maxOrNull() ?: 0,
                        group.rows,
                    )
                    VolumeBar(
                        GlassDownloads.cells(count, title?.volumesRead ?: 0, here, group.rows, group.failedVolumes.toSet()),
                        height = if (expanded) 12.dp else 10.dp,
                    )
                }
            }
            if (failed.isNotEmpty() && group.mediaId != null) {
                Pill(stringResource(MR.strings.glass_dl_try_again), onClick = onTryAgain, primary = true, small = true)
            }
            GlassRoundButton(
                icon = if (expanded) "chevUp" else "chevDown",
                onClick = onToggle,
                description = stringResource(if (expanded) MR.strings.glass_dl_collapse else MR.strings.glass_dl_expand),
                tint = Glass.secondary,
            )
        }
        if (expanded) {
            val order = listOf(State.RUNNING, State.QUEUED, State.FAILED, State.DONE)
            group.rows
                .filter { it.state != State.FAILED || it in failed }
                .sortedBy { order.indexOf(it.state) }
                .forEach { r ->
                    // a failed row: only its volumes still to get (the card's "Vol. 1–2", not the pack's 1–4)
                    // a finished row: the volumes it put on the tablet (not the "1–5" it asked for)
                    val done = if (r.state == State.DONE) group.doneVolumesOf(r) else emptyList()
                    val gone = r.state == State.DONE && r.volumes.isNotEmpty() && done.isEmpty()
                    val vols = when {
                        r.state == State.FAILED -> group.failedVolumesOf(r).ifEmpty { r.volumes }
                        r.state == State.DONE && !gone -> done
                        else -> r.volumes
                    }
                    VolumeRow(r, vols, gone, onCancel)
                }
        }
    }
}

@Composable
private fun VolumeRow(row: RealDebridQueue.Row, volumes: List<Int>, gone: Boolean, onCancel: (RealDebridQueue.Row) -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Glass.r12))
            .background(Glass.page)
            .padding(start = 62.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (volumes.isEmpty()) "—" else stringResource(MR.strings.glass_dl_vol, CoverageMatch.ranges(volumes)),
            // ponytail: one width so every row's text starts at the same x; a longer label still pushes it
            Modifier.widthIn(min = 88.dp, max = 140.dp),
            fontSize = Glass.body,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val fraction = when {
                row.state == State.DONE -> 1f
                row.total > 0 -> row.bytes.toFloat() / row.total
                else -> 0f
            }
            Meter(fraction, color = Glass.ice)
            Text(
                if (gone) stringResource(MR.strings.glass_dl_not_on_tablet, glassDevice()) else rowLine(row, context),
                color = if (row.state == State.FAILED) Color.White else Glass.secondary,
                fontSize = Glass.caption,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (row.state == State.RUNNING || row.state == State.QUEUED) {
            GlassRoundButton("close", { onCancel(row) }, description = stringResource(MR.strings.glass_dl_cancel), outlined = true, iconSize = 16.dp)
        } else {
            Spacer(Modifier.size(Glass.button))
        }
    }
}

/** The collapsed card's state line and its colour (ice running, white failed, else 62%). */
@Composable
private fun titleLine(g: TitleGroup, sourceNames: Map<Long, String>, here: Set<Int>): Pair<String, Color> {
    val r = g.running
    val queuedVolumes = GlassDownloads.toAdd(g.queued, here) // a pack: only the volumes it will add
    return when {
        r != null -> buildString {
            if (r.volumes.isNotEmpty()) append(stringResource(MR.strings.glass_dl_vol, CoverageMatch.ranges(r.volumes))).append(" · ")
            append(
                if (r.step == RealDebridImportJob.STEP_DOWNLOADING && r.total > 0) "${r.bytes * 100 / r.total}%" else stepLabel(r),
            )
            (r.servedBy ?: r.sourceId)?.let(sourceNames::get)?.let { append(" · ").append(it) }
            if (queuedVolumes > 0) append(" · ").append(stringResource(MR.strings.glass_dl_more_queued, queuedVolumes))
        } to Glass.ice
        queuedVolumes > 0 -> stringResource(MR.strings.glass_dl_n_queued, queuedVolumes) to Glass.secondary
        g.failed.isNotEmpty() -> g.failed.first().let { f ->
            val vols = g.failedVolumes
            val why = GlassDownloads.shownReason(f.reason).ifEmpty { stringResource(MR.strings.glass_dl_not_found) }
            (if (vols.isEmpty()) f.reason.orEmpty() else stringResource(MR.strings.glass_dl_failed_line, CoverageMatch.ranges(vols), why)) to Color.White
        }
        else -> stringResource(MR.strings.glass_dl_done) to Glass.secondary
    }
}

@Composable
private fun stepLabel(row: RealDebridQueue.Row): String = when (row.step) {
    RealDebridImportJob.STEP_DOWNLOADING -> stringResource(MR.strings.rd_step_downloading)
    RealDebridImportJob.STEP_UNPACKING -> stringResource(MR.strings.rd_step_unpacking)
    RealDebridImportJob.STEP_ADDING -> stringResource(MR.strings.rd_step_adding)
    else -> stringResource(MR.strings.rd_step_waiting)
}

/** "84 of 140 MB · 18 MB/s" while downloading; the step, "Queued", the failure or "In the library" otherwise. */
@Composable
private fun rowLine(row: RealDebridQueue.Row, context: Context): String = when (row.state) {
    State.QUEUED -> stringResource(MR.strings.glass_dl_queued)
    State.DONE -> stringResource(MR.strings.glass_dl_done)
    // "Not found" when the reason is the volumes-not-found one (a "Failed: HTTP 503" stays as it is)
    State.FAILED -> GlassDownloads.shownReason(row.reason).let { why ->
        if (why.isNotEmpty() && why.equals(row.reason?.trim(), ignoreCase = true)) {
            why
        } else {
            listOf(stringResource(MR.strings.glass_dl_not_found), why).filter { it.isNotEmpty() }.joinToString(" · ")
        }
    }
    State.RUNNING -> when {
        // waiting: bytes = Real-Debrid's torrent percent of 100
        row.step == RealDebridImportJob.STEP_WAITING && row.total == 100L -> "${stepLabel(row)} · ${row.bytes}%"
        row.step == RealDebridImportJob.STEP_DOWNLOADING && row.total > 0 -> buildString {
            append(
                stringResource(
                    MR.strings.glass_dl_of,
                    Formatter.formatShortFileSize(context, row.bytes),
                    Formatter.formatShortFileSize(context, row.total),
                ),
            )
            if (row.speed > 0) {
                append(" · ").append(stringResource(MR.strings.glass_dl_speed, Formatter.formatShortFileSize(context, row.speed)))
            }
        }
        else -> stepLabel(row)
    }
}

/** One box per volume in the canvas key colours; failed = hatched. */
@Composable
private fun VolumeBar(cells: List<Cell>, height: androidx.compose.ui.unit.Dp) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        cells.forEach { c ->
            Box(
                Modifier
                    .weight(1f)
                    .height(height)
                    .clip(RoundedCornerShape(2.dp))
                    .then(if (c == Cell.FAILED) Modifier.hatched() else Modifier.background(cellColor(c))),
            )
        }
    }
}

private fun cellColor(c: Cell): Color = when (c) {
    Cell.READ -> Color.White.copy(alpha = 0.3f)
    Cell.HERE -> Glass.have
    Cell.DOWNLOADING -> Glass.ice
    Cell.QUEUED -> Glass.ice.copy(alpha = 0.5f)
    Cell.NONE -> Color.White.copy(alpha = 0.1f)
    Cell.FAILED -> Color.White.copy(alpha = 0.85f)
}

/** A failed volume: 10% white under 45° lines in [line]; the bar's segments and the title page's cells (18% white). */
internal fun Modifier.hatched(line: Color = Glass.have) = drawBehind {
    drawRect(Color.White.copy(alpha = 0.1f))
    val step = 5.dp.toPx()
    var x = -size.height
    while (x < size.width) {
        drawLine(line, Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = 2.dp.toPx())
        x += step
    }
}

/** OCR queue / source download queue: count, the current item, and the way to the classic queue screen. */
@Composable
private fun QueueSection(title: String, count: Int, current: String?, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().glassPanel(Glass.r12).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("$title · $count", fontSize = Glass.label, fontWeight = FontWeight.SemiBold)
            current?.let { Text(it, color = Glass.secondary, fontSize = Glass.caption, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Pill(stringResource(MR.strings.glass_dl_open_classic), onClick = onOpen, small = true)
    }
}

/** SOURCES (account + each source on/off), ON THIS TABLET (storage), KEY. */
@Composable
private fun Aside(sourceIds: List<Long>, sourceNames: Map<Long, String>, modifier: Modifier) {
    val context = LocalContext.current
    val prefs = remember { Injekt.get<DownloadPreferences>() }
    val token by prefs.realDebridToken().collectAsState()
    val off by prefs.realDebridSourcesOff().collectAsState()
    val account by produceState<Result<RealDebridImportJob.Account?>?>(null, token) {
        value = runCatching { RealDebridImportJob.account() }
    }
    // free space at once (with the last manga size), then the new manga size
    val storage by produceState(StorageCache.value) {
        value = StorageCache.space()
        value = StorageCache.load()
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Label(stringResource(MR.strings.glass_dl_sources))
        val a = account
        SourceCard(
            stringResource(MR.strings.glass_dl_rd),
            when {
                a == null -> stringResource(MR.strings.glass_dl_rd_checking)
                a.isFailure -> stringResource(MR.strings.glass_dl_rd_error)
                else -> a.getOrNull().let { acc ->
                    when {
                        acc == null -> stringResource(MR.strings.glass_dl_rd_no_key)
                        acc.tokenRejected -> stringResource(MR.strings.glass_dl_rd_rejected)
                        acc.premium -> stringResource(MR.strings.glass_dl_rd_premium, acc.daysLeft)
                        else -> stringResource(MR.strings.glass_dl_rd_free)
                    }
                }
            },
            on = a?.getOrNull()?.let { it.premium && !it.tokenRejected } == true,
        )
        sourceIds.forEach { id ->
            val on = id !in off
            SourceCard(sourceNames[id].orEmpty(), stringResource(if (on) MR.strings.glass_dl_on else MR.strings.glass_dl_off), on)
        }

        Label(stringResource(MR.strings.glass_on_tablet, glassDevice().uppercase()), Modifier.padding(top = 12.dp))
        Column(
            Modifier.fillMaxWidth().glassPanel(Glass.r12).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val s = storage
            Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Color.White.copy(alpha = 0.1f))) {
                if (s != null && s.total > 0) {
                    val manga = s.manga ?: 0
                    val other = (s.total - s.free - manga).coerceAtLeast(0)
                    if (s.manga != null) Box(Modifier.weight(manga.toFloat().coerceAtLeast(1f)).fillMaxHeight().background(Glass.ice))
                    Box(Modifier.weight(other.toFloat().coerceAtLeast(1f)).fillMaxHeight().background(Color.White.copy(alpha = 0.35f)))
                    Spacer(Modifier.weight(s.free.toFloat().coerceAtLeast(1f)))
                }
            }
            val measuring = stringResource(MR.strings.glass_dl_storage_measuring)
            Text(
                when {
                    s == null -> measuring
                    // chimahon: free space first; the manga part reads "Measuring…" until its first walk ends
                    s.manga == null -> stringResource(
                        MR.strings.glass_dl_storage_free,
                        Formatter.formatShortFileSize(context, s.free),
                        Formatter.formatShortFileSize(context, s.total),
                        measuring,
                    )
                    else -> stringResource(
                        MR.strings.glass_dl_storage,
                        Formatter.formatShortFileSize(context, s.manga),
                        Formatter.formatShortFileSize(context, (s.total - s.free - s.manga).coerceAtLeast(0)),
                        Formatter.formatShortFileSize(context, s.free),
                    )
                },
                color = Glass.secondary,
                fontSize = Glass.body,
            )
        }

        Label(stringResource(MR.strings.glass_dl_key), Modifier.padding(top = 12.dp))
        val key = listOf(
            Cell.READ to stringResource(MR.strings.glass_dl_key_read),
            Cell.HERE to stringResource(MR.strings.glass_dl_key_here, glassDevice()),
            Cell.DOWNLOADING to stringResource(MR.strings.glass_dl_key_downloading),
            Cell.QUEUED to stringResource(MR.strings.glass_dl_queued),
            Cell.NONE to stringResource(MR.strings.glass_dl_key_none),
            Cell.FAILED to stringResource(MR.strings.glass_dl_key_failed),
        )
        key.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { (c, n) ->
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            Modifier.size(16.dp, 10.dp).clip(RoundedCornerShape(2.dp))
                                .then(if (c == Cell.FAILED) Modifier.hatched() else Modifier.background(cellColor(c))),
                        )
                        Text(n, color = Glass.have, fontSize = Glass.body)
                    }
                }
            }
        }
    }
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = Glass.secondary, fontSize = Glass.caption, fontWeight = FontWeight.SemiBold, letterSpacing = Glass.caption * 0.16f)
}

@Composable
private fun SourceCard(name: String, line: String, on: Boolean) {
    Row(
        Modifier.fillMaxWidth().glassPanel(Glass.r12).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GlassIcon(if (on) "check" else "minus", size = 18.dp, tint = if (on) Color.White else Glass.secondary)
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = Glass.label, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(line, color = Glass.secondary, fontSize = Glass.caption, maxLines = 1)
        }
    }
}

/** [manga] null = not measured yet ("measuring"); total and free come from StatFs at once. */
private data class Storage(val manga: Long?, val total: Long, val free: Long)

/**
 * Local-source folder size + its volume's StatFs, off the main thread. Free/total are instant; the manga sum (a walk of
 * the folder, slow through SAF) shows the last measured size (memory, then [UiPreferences.glassMangaBytes]) until the
 * new one arrives.
 */
private object StorageCache {
    @Volatile
    var value: Storage? = null

    private val walk = Mutex()

    @Volatile
    private var measuredAt = 0L

    private val saved get() = Injekt.get<UiPreferences>().glassMangaBytes()

    private fun local(): Pair<UniFile, File>? {
        val local = Injekt.get<StorageManager>().getLocalSourceDirectory() ?: return null
        return local.filePath?.let { local to File(it) }
    }

    /** StatFs now, with the last known manga size (or null). */
    suspend fun space(): Storage? = withContext(Dispatchers.IO) {
        val (_, dir) = local() ?: return@withContext value
        runCatching {
            val manga = value?.manga ?: saved.get().takeIf { it >= 0 }
            Storage(manga, DiskUtil.getTotalStorageSpace(dir), DiskUtil.getAvailableStorageSpace(dir))
        }.getOrNull()?.also { value = it } ?: value
    }

    /** Measures the manga folder; one walk at a time, a second caller (rotation, page again) takes its result. */
    suspend fun load(): Storage? = withContext(Dispatchers.IO) {
        walk.withLock {
            // ponytail: a walk in the last minute is fresh enough; the page's own job changes refresh on the next visit
            if (value?.manga != null && System.currentTimeMillis() - measuredAt < 60_000) return@withLock value
            val (local, dir) = local() ?: return@withLock value
            runCatching {
                // chimahon: a SAF folder (no all-files access) has a real path for StatFs, but java.io.File can't see
                // its files (scoped storage: Manga read 0 B): then the size is summed through the UniFile tree
                val manga = if (local.uri.scheme == "file") DiskUtil.getDirectorySize(dir) else treeSize(local)
                saved.set(manga)
                measuredAt = System.currentTimeMillis()
                Storage(manga, DiskUtil.getTotalStorageSpace(dir), DiskUtil.getAvailableStorageSpace(dir))
            }.getOrNull()?.also { value = it } ?: value
        }
    }

    // chimahon: one children query per folder with _size (UniFile's isDirectory/length per entry opened every .rar
    // through MediaProvider: "Measuring…" for over a minute); a folder whose query fails counts as 0, logged once
    private fun treeSize(f: UniFile): Long {
        val uri = f.uri
        val resolver = Injekt.get<Application>().contentResolver
        val start = System.currentTimeMillis()
        var total = 0L
        var files = 0
        var queries = 0
        var logged = false
        val folders = ArrayDeque(listOf(DocumentsContract.getDocumentId(uri)))
        while (folders.isNotEmpty()) {
            val id = folders.removeFirst()
            queries++
            try {
                resolver.query(
                    DocumentsContract.buildChildDocumentsUriUsingTree(uri, id),
                    arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE),
                    null,
                    null,
                    null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        if (c.getString(1) == Document.MIME_TYPE_DIR) {
                            folders.add(c.getString(0))
                        } else {
                            total += c.getLong(2)
                            files++
                        }
                    }
                }
            } catch (e: Exception) {
                if (!logged) logcat(LogPriority.WARN, e) { "Storage: can't list $id" }
                logged = true
            }
        }
        logcat(LogPriority.INFO) {
            "Storage: manga $total B in ${System.currentTimeMillis() - start} ms, $queries folders, $files files"
        }
        return total
    }
}

private val coverageJson = Json { ignoreUnknownKeys = true }

// ponytail: reads SourceCoverage's cache file itself (its reader is private, file not to be touched here);
// move into SourceCoverage as a public cached() if a second caller appears
/** The title's cached coverage of the sources that are on, in the preferred order, whatever its age. */
internal fun cachedCoverage(context: Context, mediaId: Long): List<Coverage> {
    val list = runCatching {
        coverageJson.decodeFromString<List<Coverage>>(File(context.filesDir, "coverage/$mediaId.json").readText())
    }.getOrDefault(emptyList())
    val order = Injekt.get<SourceCoverage>().sources().map { it.id }
    return CoverageMatch.ordered(list.filter { it.sourceId in order }, order)
}
