package eu.kanade.tachiyomi.ui.glass.title

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import eu.kanade.presentation.glass.CoverageBar
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassChip
import eu.kanade.presentation.glass.GlassIcon
import eu.kanade.presentation.glass.GlassPage
import eu.kanade.presentation.glass.GlassRail
import eu.kanade.presentation.glass.GlassToggle
import eu.kanade.presentation.glass.Pill
import eu.kanade.presentation.glass.glassPanel
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.glassDevice
import eu.kanade.presentation.util.isTabletUi
import eu.kanade.tachiyomi.data.anilist.AniListLibrary
import eu.kanade.tachiyomi.data.coverage.Coverage
import eu.kanade.tachiyomi.data.coverage.CoverageMatch
import eu.kanade.tachiyomi.data.download.RealDebridQueue
import eu.kanade.tachiyomi.data.mangadex.VolumeCovers
import eu.kanade.tachiyomi.data.track.anilist.AnilistApi
import eu.kanade.tachiyomi.ui.glass.downloads.GlassDownloads
import eu.kanade.tachiyomi.ui.glass.downloads.TitleGroup
import eu.kanade.tachiyomi.ui.glass.downloads.hatched
import eu.kanade.tachiyomi.ui.glass.manual.GlassManualScreen
import eu.kanade.tachiyomi.ui.glass.manual.GlassTorrentScreen
import eu.kanade.tachiyomi.ui.glass.statusLabel
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.setting.SettingsScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import tachiyomi.core.common.i18n.stringResource
import eu.kanade.domain.ui.UiPreferences
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import java.io.File

/** Glass title page (canvas `Title2-B`) of one AniList media, whatever is on the tablet. */
class GlassTitleScreen(private val mediaId: Long) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val uriHandler = LocalUriHandler.current
        val model = rememberScreenModel { GlassTitleScreenModel(mediaId) }

        val entry by model.entry.collectAsStateWithLifecycle()
        val local by model.local.collectAsStateWithLifecycle()
        val rows by model.rows.collectAsStateWithLifecycle()
        val sources by model.sources.collectAsStateWithLifecycle()
        val coverage by model.coverage.collectAsStateWithLifecycle()
        val chosenId by model.chosen.collectAsStateWithLifecycle()
        val covers by model.covers.collectAsStateWithLifecycle()
        val localCovers by model.localCovers.collectAsStateWithLifecycle()
        val message by model.message.collectAsStateWithLifecycle()
        val fillGapsPref = remember { model.downloadPreferences.realDebridFillGaps() }
        val fillGaps by fillGapsPref.collectAsState()
        val hideUnreadPref = remember { Injekt.get<UiPreferences>().glassHideUnreadCovers() }
        val hideUnread by hideUnreadPref.collectAsState()

        val volumes = local?.volumes.orEmpty()
        val here = volumes.map { it.number }.toSet()
        val active = remember(rows) { GlassTitleState.active(rows) }
        val inOrder = remember(coverage, sources) { CoverageMatch.ordered(coverage.values, sources.map { it.id }) }
        val count = GlassTitleState.volumeCount(entry?.volumes, coverage.values, volumes)
        val chosen = chosenId ?: GlassTitleState.defaultSource(inOrder)
        val order = GlassTitleState.fetchOrder(inOrder, chosen, fillGaps)
        val missing = GlassTitleState.missing(count, volumes, active)
        val fetchable = GlassTitleState.fetchable(missing, order)
        // only volumes 1..AniList's total can be resumed (cells and counts keep every file)
        val primary = GlassTitleState.primary(
            GlassTitleState.inRange(volumes, entry?.volumes ?: 0),
            entry?.progressVolumes ?: 0,
            count,
            active.keys,
        )
        val current = when (primary) {
            is Primary.Resume -> primary.volume.number
            is Primary.Fetch -> primary.volume
            Primary.None -> null
        }
        val cells = GlassTitleState.cells(
            count,
            volumes,
            active,
            readUpTo = entry?.progressVolumes ?: 0,
            totalKnown = (entry?.volumes ?: 0) > 0,
            // the Downloads page's rule: a failed, not cancelled row's volumes not here and not queued again
            failed = remember(rows, here) { TitleGroup(mediaId, rows, here).failedVolumes.toSet() },
        )
        val failed = rows.filter { r ->
            r.state == RealDebridQueue.State.FAILED && r.reason != "Cancelled" &&
                r.volumes.any { it !in here && it !in active }
        }
        val sourceName = { id: Long -> sources.firstOrNull { it.id == id }?.name ?: coverage[id]?.sourceName.orEmpty() }

        // Select mode: pick volumes on the tablet to delete their files (rows, read marks and AniList stay)
        val readUpTo = entry?.progressVolumes ?: 0
        val reading = (primary as? Primary.Resume)?.volume?.number
        val selectable = GlassTitleState.selectable(cells, reading)
        val locked = GlassTitleState.locked(cells, reading)
        var selecting by remember { mutableStateOf(false) }
        var picked by remember { mutableStateOf(emptySet<Int>()) }
        // picks made by "Select read" stay tied to it (they shrink if the read set does) until a hand pick or another chip
        var readMode by remember { mutableStateOf(false) }
        val readNow = GlassTitleState.selectRead(cells, readUpTo, reading)
        val pickedNow = if (readMode) picked intersect readNow else picked intersect selectable
        var pickedBytes by remember { mutableLongStateOf(0L) }
        LaunchedEffect(pickedNow, local) { pickedBytes = if (pickedNow.isEmpty()) 0L else model.sizeOf(pickedNow) }
        if (selecting && selectable.isEmpty()) selecting = false

        var confirm by remember { mutableStateOf<List<Int>?>(null) }
        /** Any fetch, a single volume too: the confirm dialog first (what it adds, from which source). */
        val request = { vols: List<Int> ->
            if (GlassTitleState.plan(vols, order).isEmpty()) {
                model.message.value = context.stringResource(MR.strings.glass_title_no_source, CoverageMatch.ranges(vols))
            } else if (model.downloadPreferences.realDebridToken().get().isBlank()) {
                // no Real-Debrid key: a Fetch can only fail, so go to the by-hand page
                navigator.push(GlassManualScreen(mediaId, vols, here.toList()))
            } else {
                confirm = vols
            }
            Unit
        }
        val openVolume = { v: LocalVolume ->
            local?.let { context.startActivity(ReaderActivity.newIntent(context, it.manga.id, v.chapterId)) }
            Unit
        }

        val header: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TopRow(
                    onBack = { navigator.pop() },
                    onClassic = local?.let { l -> { navigator.push(MangaScreen(l.manga.id)) } },
                    onRefresh = model::refreshSources,
                    hideUnread = hideUnread,
                    onToggleHideUnread = { hideUnreadPref.set(!hideUnread) },
                )
                Heading(entry, fallbackTitle = local?.manga?.title)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (primary) {
                        is Primary.Resume -> Pill(
                            stringResource(MR.strings.glass_title_resume, primary.volume.number, primary.volume.lastPageRead + 1),
                            onClick = { openVolume(primary.volume) },
                            primary = true,
                            icon = "play",
                        )
                        is Primary.Fetch -> if (entry != null) {
                            Pill(
                                stringResource(MR.strings.glass_title_fetch_vol, primary.volume),
                                onClick = { request(listOf(primary.volume)) },
                                primary = true,
                                icon = "download",
                            )
                        }
                        Primary.None -> Unit
                    }
                    if (entry != null) {
                        if (GlassTitleState.onHome(entry?.status)) {
                            Pill(stringResource(MR.strings.glass_title_on_home), onClick = {}, icon = "check")
                        } else {
                            Pill(stringResource(MR.strings.glass_title_add_home), onClick = model::addToHome, icon = "plus")
                        }
                    }
                }
            }
        }

        val sourceList: @Composable () -> Unit = {
            if (entry != null) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        if (count > 0) {
                            stringResource(MR.strings.glass_title_sources, count)
                        } else {
                            stringResource(MR.strings.glass_title_sources_none)
                        },
                        color = Glass.secondary,
                        fontSize = Glass.caption,
                        letterSpacing = 2.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    if (sources.isEmpty()) {
                        Text(stringResource(MR.strings.glass_title_no_sources), color = Glass.secondary, fontSize = Glass.body)
                    }
                    sources.forEach { s ->
                        val c = coverage[s.id]
                        SourceCard(
                            name = s.name,
                            coverage = c,
                            count = count,
                            boxes = GlassTitleState.boxes(c, count, here, active),
                            selected = s.id == chosen,
                            onSelect = { model.chosen.value = s.id },
                            onVisit = {
                                navigator.push(WebViewScreen(url = s.getHomeUrl(), initialTitle = s.name, sourceId = s.id))
                            },
                        )
                    }
                    Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(MR.strings.glass_title_fill_gaps), fontSize = Glass.body, modifier = Modifier.weight(1f))
                        GlassToggle(fillGaps, { fillGapsPref.set(it) })
                    }
                }
            }
        }

        val left: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                header()
                sourceList()
            }
        }

        val aniListBlock: @Composable () -> Unit = {
            if (entry != null) {
                AniListPanel(
                    entry = entry!!,
                    message = message,
                    onStatus = model::setStatus,
                    onStars = model::setStars,
                    onOpen = { uriHandler.openUri(AnilistApi.mangaUrl(mediaId)) },
                )
            } else {
                Text(stringResource(MR.strings.glass_title_unknown), color = Glass.secondary, fontSize = Glass.body)
            }
        }
        val aniList: LazyGridScope.() -> Unit = {
            fullSpan { aniListBlock() }
        }

        val volumeGrid: LazyGridScope.() -> Unit = {
            fullSpan {
                if (selecting) Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(CircleShape)
                        .background(Glass.panel)
                        .border(1.dp, Glass.line, CircleShape)
                        .padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.size(32.dp).clip(CircleShape).clickable { selecting = false; picked = emptySet(); readMode = false }, contentAlignment = Alignment.Center) {
                        GlassIcon("close", size = 18.dp)
                    }
                    Text(
                        stringResource(MR.strings.glass_title_n_selected, pickedNow.size, GlassTitleState.sizeText(pickedBytes)),
                        fontSize = Glass.body,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    GlassChip(stringResource(MR.strings.glass_title_select_read), selected = readMode && pickedNow == readNow && readNow.isNotEmpty(), onClick = { picked = readNow; readMode = true })
                    GlassChip(stringResource(MR.strings.glass_title_select_all), selected = pickedNow == selectable, onClick = { picked = selectable; readMode = false })
                    GlassChip(stringResource(MR.strings.glass_title_select_none), selected = false, onClick = { picked = emptySet(); readMode = false })
                } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(MR.strings.glass_n_volumes, count), fontSize = Glass.heading, fontWeight = FontWeight.SemiBold)
                    // scrolls sideways on a phone, where it does not fit next to the Fetch pill
                    Legend(Modifier.weight(1f).horizontalScroll(rememberScrollState()), notFound = cells.any { it.state == VolState.FAILED })
                    if (selectable.isNotEmpty()) {
                        Pill(stringResource(MR.strings.glass_title_select), onClick = { selecting = true }, icon = "check", small = true)
                    }
                    if (fetchable.isNotEmpty()) {
                        // ponytail: no size: Coverage keeps links, not the link texts that carry sizes (X3DL)
                        Pill(
                            stringResource(MR.strings.glass_title_fetch_n, fetchable.size),
                            onClick = { request(fetchable) },
                            icon = "download",
                            small = true,
                        )
                    }
                }
            }
            failed.forEach { r ->
                fullSpan { FailedRow(r, here, active.keys, request) }
            }
            items(cells, key = { it.number }) { cell ->
                VolumeCellView(
                    cell = cell,
                    localCover = localCovers[cell.number],
                    // a cover shows once the volume is read or started
                    hidden = hideUnread && cell.state != VolState.READ &&
                        cell.local?.let { it.read || it.lastPageRead > 0 } != true,
                    cover = covers[cell.number],
                    seriesCover = entry?.let { it.coverLarge ?: it.cover },
                    current = cell.number == current,
                    onOpen = openVolume,
                    onFetch = { request(listOf(cell.number)) },
                    onCancel = { model.cancel(cell.number) },
                    onSetRead = model::setRead,
                    select = if (selecting) {
                        CellSelect(cell.number in selectable, cell.number in pickedNow, cell.number in locked && cell.local != null)
                    } else {
                        null
                    },
                    readHere = GlassTitleState.readHere(cell, readUpTo),
                    onToggle = {
                        picked = if (cell.number in pickedNow) pickedNow - cell.number else pickedNow + cell.number
                        readMode = false
                    },
                )
            }
            if (selecting) fullSpan {
                val missingRead = GlassTitleState.readNotHere(cells)
                val note = listOfNotNull(
                    when (missingRead.size) {
                        0 -> null
                        1 -> stringResource(MR.strings.glass_title_note_missing_one, missingRead.first())
                        else -> stringResource(MR.strings.glass_title_note_missing, CoverageMatch.ranges(missingRead))
                    },
                    reading?.let { stringResource(MR.strings.glass_title_note_reading, it) },
                ).joinToString(" ")
                if (note.isNotEmpty()) Text(note, color = Glass.secondary, fontSize = Glass.caption)
            }
        }

        val phone = !isTabletUi()
        GlassPage {
            if (!phone) entry?.banner?.let { banner ->
                AsyncImage(
                    model = banner,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alpha = 0.35f,
                    modifier = Modifier.fillMaxWidth().height(300.dp),
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .background(Brush.verticalGradient(listOf(Glass.page.copy(alpha = 0.3f), Glass.page))),
                )
            }
            if (phone) GlassPhoneTitle(
                entry = entry,
                fallbackTitle = local?.manga?.title,
                primary = primary,
                current = current,
                count = count,
                cells = cells,
                missing = missing,
                fetchable = fetchable,
                failed = failed,
                here = here,
                active = active.keys,
                topRow = {
                    TopRow(
                        onBack = { navigator.pop() },
                        onClassic = local?.let { l -> { navigator.push(MangaScreen(l.manga.id)) } },
                        onRefresh = model::refreshSources,
                        hideUnread = hideUnread,
                        onToggleHideUnread = { hideUnreadPref.set(!hideUnread) },
                        showHideUnread = false, // phone cells draw no covers
                    )
                },
                onHome = GlassTitleState.onHome(entry?.status),
                onAddHome = model::addToHome,
                onOpen = openVolume,
                onFetch = request,
                onByHand = { navigator.push(GlassManualScreen(mediaId, missing, here.toList())) },
                onCancel = model::cancel,
                onSetRead = model::setRead,
                sourceList = sourceList,
                aniList = aniListBlock,
            ) else BoxWithConstraints(
                Modifier.fillMaxSize().padding(start = if (phone) 16.dp else 108.dp, end = if (phone) 16.dp else 32.dp),
            ) {
                val grid = @Composable { modifier: Modifier, portrait: Boolean ->
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(68.dp),
                        modifier = modifier,
                        contentPadding = PaddingValues(top = 24.dp, bottom = 32.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (portrait) {
                            // header → volumes → AniList → sources
                            fullSpan { header() }
                            volumeGrid(this)
                            aniList(this)
                            fullSpan { sourceList() }
                        } else {
                            aniList(this)
                            volumeGrid(this)
                        }
                    }
                }
                if (maxWidth >= 900.dp) {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(42.dp)) {
                        Box(
                            Modifier
                                .width(380.dp)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState())
                                .padding(top = 24.dp, bottom = 32.dp),
                        ) { left() }
                        grid(Modifier.weight(1f).fillMaxHeight(), false)
                    }
                } else {
                    // portrait: one scrolling column
                    grid(Modifier.fillMaxSize(), true)
                }
            }
            if (selecting && pickedNow.isNotEmpty()) Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = if (phone) 16.dp else 108.dp, end = if (phone) 16.dp else 32.dp, bottom = 16.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Glass.r8))
                    .background(Glass.panel)
                    .border(1.dp, Glass.line, RoundedCornerShape(Glass.r8))
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val freed = GlassTitleState.sizeText(pickedBytes)
                Column(Modifier.weight(1f)) {
                    Text(
                        if (pickedNow.size == 1) {
                            stringResource(MR.strings.glass_title_delete_ask_one)
                        } else {
                            stringResource(MR.strings.glass_title_delete_ask, pickedNow.size)
                        },
                        fontSize = Glass.label,
                        fontWeight = FontWeight.SemiBold,
                    )
                    // a mark kept only by the row (read here, above AniList's count) goes with the file: say so
                    val onlyHere = GlassTitleState.readOnlyHere(cells, pickedNow, readUpTo)
                    Text(
                        when {
                            onlyHere.size == 1 -> stringResource(MR.strings.glass_title_read_only_here_one, onlyHere.first())
                            onlyHere.isNotEmpty() ->
                                stringResource(MR.strings.glass_title_read_only_here, CoverageMatch.ranges(onlyHere))
                            readUpTo > 0 -> stringResource(MR.strings.glass_title_delete_frees_anilist, freed, readUpTo)
                            else -> stringResource(MR.strings.glass_title_delete_frees, freed)
                        },
                        color = if (onlyHere.isEmpty()) Glass.secondary else Glass.red,
                        fontSize = Glass.caption,
                    )
                }
                Box(
                    Modifier
                        .height(Glass.button)
                        .clip(CircleShape)
                        .background(Glass.red.copy(alpha = 0.9f))
                        .clickable {
                            val chosenVolumes = pickedNow
                            selecting = false
                            picked = emptySet()
                            readMode = false
                            // runs in the model's scope: leaving the page does not cancel it half-way
                            model.deleteVolumes(chosenVolumes) { r ->
                                if (r.deleted == 0 && r.failed == 0) return@deleteVolumes // no files found: nothing to say
                                val sizeText = GlassTitleState.sizeText(r.freed)
                                val done = when {
                                    r.deleted == 0 && r.failed > 0 -> null
                                    r.deleted == 1 -> context.stringResource(MR.strings.glass_title_deleted_one, sizeText)
                                    else -> context.stringResource(MR.strings.glass_title_deleted, r.deleted, sizeText)
                                }
                                val failedText = if (r.failed > 0) context.stringResource(MR.strings.glass_title_delete_failed, r.failed) else null
                                Toast.makeText(context, listOfNotNull(done, failedText).joinToString(" · "), Toast.LENGTH_LONG).show()
                            }
                        }
                        .padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(MR.strings.glass_title_delete_n, pickedNow.size),
                        color = Color.Black,
                        fontSize = Glass.label,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            if (!phone) GlassRail(
                active = "none",
                onSelect = {
                    if (it == "settings") {
                        navigator.push(SettingsScreen())
                    } else {
                        eu.kanade.tachiyomi.ui.glass.GlassHomeScreen.show(it)
                        navigator.popUntilRoot()
                    }
                },
                modifier = Modifier.padding(start = 20.dp, top = 24.dp),
            )
        }

        confirm?.let { vols ->
            FetchDialog(
                packs = GlassTitleState.plan(vols, order),
                here = here,
                sourceName = sourceName,
                onConfirm = {
                    if (!model.fetch(vols, order)) {
                        model.message.value =
                            context.stringResource(MR.strings.glass_title_no_source, CoverageMatch.ranges(vols))
                    }
                    confirm = null
                },
                onDismiss = { confirm = null },
                onByHand = {
                    confirm = null
                    navigator.push(GlassManualScreen(mediaId, vols, here.toList()))
                },
                onTorrent = {
                    confirm = null
                    navigator.push(GlassTorrentScreen(mediaId, vols, here.toList()))
                },
            )
        }
    }
}

private fun LazyGridScope.fullSpan(content: @Composable () -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }) { content() }
}

@Composable
internal fun TopRow(
    onBack: () -> Unit,
    onClassic: (() -> Unit)?,
    onRefresh: () -> Unit,
    hideUnread: Boolean,
    onToggleHideUnread: () -> Unit,
    showHideUnread: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Pill(stringResource(MR.strings.glass_back), onClick = onBack, icon = "chevLeft", small = true)
        Spacer(Modifier.weight(1f))
        Box {
            GlassChip(stringResource(MR.strings.glass_more), selected = false, onClick = { open = true }, icon = "chevDown")
            GlassMenu(open, { open = false }) {
                MenuItem(stringResource(MR.strings.glass_title_refresh_sources)) {
                    open = false
                    onRefresh()
                }
                if (showHideUnread) MenuItem(
                    stringResource(
                        if (hideUnread) MR.strings.glass_title_show_unread_covers else MR.strings.glass_title_hide_unread_covers,
                    ),
                ) {
                    open = false
                    onToggleHideUnread()
                }
                onClassic?.let {
                    MenuItem(stringResource(MR.strings.glass_classic_view)) {
                        open = false
                        it()
                    }
                }
            }
        }
    }
}

@Composable
private fun Heading(entry: AniListLibrary.Entry?, fallbackTitle: String?) {
    val shape = RoundedCornerShape(Glass.r10)
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Bottom) {
        AsyncImage(
            model = entry?.let { it.coverLarge ?: it.cover },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(120.dp, 180.dp).clip(shape).background(Glass.panel).border(1.dp, Glass.line, shape),
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                entry?.let { it.native ?: it.romaji ?: it.english } ?: fallbackTitle.orEmpty(),
                fontSize = Glass.heading,
                lineHeight = 28.sp,
                fontWeight = FontWeight.SemiBold,
            )
            val line = titleMetaLine(entry)
            if (line.isNotEmpty()) Text(line, color = Glass.secondary, fontSize = Glass.body)
        }
    }
}

/** "18 vols · Finished · 2023", shared by the tablet Heading and the phone hero. */
@Composable
internal fun titleMetaLine(entry: AniListLibrary.Entry?): String {
    val status = when (entry?.mediaStatus) {
        "FINISHED" -> stringResource(MR.strings.glass_title_finished)
        "RELEASING" -> stringResource(MR.strings.glass_title_releasing)
        "HIATUS" -> stringResource(MR.strings.glass_title_hiatus)
        "CANCELLED" -> stringResource(MR.strings.glass_title_cancelled)
        "NOT_YET_RELEASED" -> stringResource(MR.strings.glass_title_unreleased)
        else -> null
    }
    return listOfNotNull(
        entry?.volumes?.let { stringResource(MR.strings.glass_title_vols, it) },
        status,
        entry?.year?.toString(),
    ).joinToString(" · ")
}

@Composable
private fun SourceCard(
    name: String,
    coverage: Coverage?,
    count: Int,
    boxes: List<BoxState>,
    selected: Boolean,
    onSelect: () -> Unit,
    onVisit: () -> Unit,
) {
    val shape = RoundedCornerShape(Glass.r12)
    val found = coverage != null && coverage.state == Coverage.State.FOUND && coverage.volumes.isNotEmpty()
    Column(
        Modifier
            .fillMaxWidth()
            .glassPanel(Glass.r12)
            .then(if (selected) Modifier.border(2.dp, Glass.ice, shape) else Modifier)
            .clickable(enabled = found, onClick = onSelect)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .border(2.dp, if (selected) Glass.ice else Color.White.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(Glass.ice))
            }
            Text(name, fontSize = Glass.label, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (coverage?.state == Coverage.State.NEEDS_VISIT) {
                Pill(stringResource(MR.strings.glass_title_open_source), onClick = onVisit, icon = "external", small = true)
            }
        }
        if (count > 0) {
            CoverageBar(
                boxes.map {
                    when (it) {
                        BoxState.NONE -> Glass.none
                        BoxState.HAVE -> Glass.have
                        BoxState.HERE -> Color.White.copy(alpha = 0.35f)
                        BoxState.QUEUED -> Glass.ice
                    }
                },
            )
        }
        Text(stateText(coverage, count), color = Glass.secondary, fontSize = Glass.caption)
        coverage?.takeIf { it.state == Coverage.State.FOUND }?.postTitle?.let {
            Text(it, color = Glass.secondary, fontSize = Glass.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun stateText(c: Coverage?, count: Int): String {
    if (c == null) return stringResource(MR.strings.glass_title_checking)
    return stateTextOf(c, count)
}

@Composable
private fun stateTextOf(c: Coverage, count: Int): String = when (c.state) {
    Coverage.State.NOT_FOUND -> stringResource(MR.strings.glass_title_not_on_source)
    Coverage.State.NEEDS_VISIT -> stringResource(MR.strings.glass_title_needs_visit)
    Coverage.State.ERROR -> stringResource(MR.strings.glass_title_error, c.message.orEmpty())
    Coverage.State.FOUND -> {
        val has = c.volumes.keys
        val missing = (1..count).filter { it !in has }
        when {
            has.isEmpty() -> stringResource(MR.strings.glass_title_no_numbers)
            missing.isEmpty() -> stringResource(MR.strings.glass_title_all, count)
            missing.all { it > has.max() } -> stringResource(MR.strings.glass_title_range, CoverageMatch.ranges(has))
            else -> stringResource(
                MR.strings.glass_title_partial,
                has.count { it in 1..count },
                count,
                CoverageMatch.ranges(missing),
            )
        }
    }
}

@Composable
private fun AniListPanel(
    entry: AniListLibrary.Entry,
    message: String?,
    onStatus: (String) -> Unit,
    onStars: (Int) -> Unit,
    onOpen: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val stars = GlassTitleState.stars(entry.score)
    Column(
        Modifier.fillMaxWidth().glassPanel().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(28.dp).clip(RoundedCornerShape(Glass.r8)).background(Color(0xFF02A9FF)),
                contentAlignment = Alignment.Center,
            ) {
                Text("AL", color = Glass.page, fontSize = Glass.caption, fontWeight = FontWeight.ExtraBold)
            }
            Box {
                GlassChip(
                    if (entry.status in GlassTitleState.statuses) {
                        statusLabel(entry.status)
                    } else {
                        stringResource(MR.strings.glass_title_not_on_list)
                    },
                    selected = false,
                    onClick = { open = true },
                    icon = "chevDown",
                )
                GlassMenu(open, { open = false }) {
                    GlassTitleState.statuses.forEach { s ->
                        MenuItem(statusLabel(s), selected = s == entry.status) {
                            open = false
                            if (s != entry.status) onStatus(s)
                        }
                    }
                }
            }
            Text(
                message ?: stringResource(MR.strings.glass_title_on_anilist, entry.progressVolumes),
                color = Glass.secondary,
                fontSize = Glass.body,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Row {
                (1..5).forEach { k ->
                    val label = stringResource(MR.strings.glass_title_stars, k)
                    Box(Modifier.size(44.dp).clickable { onStars(k) }, contentAlignment = Alignment.Center) {
                        GlassIcon(if (k <= stars) "star" else "starOutline", size = 20.dp, tint = Glass.ice, contentDescription = label)
                    }
                }
            }
            Box(
                Modifier.size(40.dp).clip(CircleShape).border(1.dp, Glass.line, CircleShape).clickable(onClick = onOpen),
                contentAlignment = Alignment.Center,
            ) {
                GlassIcon("external", size = 18.dp, contentDescription = stringResource(MR.strings.glass_title_open_anilist))
            }
        }
        val tags = entry.tags.filter { !it.spoiler && it.rank >= 60 }.sortedByDescending { it.rank }.take(6)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            tags.forEach { GlassChip(it.name, selected = false, onClick = {}, detail = "${it.rank}%") }
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.height(Glass.chip).clip(CircleShape).clickable(onClick = onOpen).padding(start = 12.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(stringResource(MR.strings.glass_title_more_anilist), color = Color.White.copy(alpha = 0.85f), fontSize = Glass.body)
                GlassIcon("chevRight", size = 16.dp, tint = Color.White.copy(alpha = 0.85f))
            }
        }
    }
}

/** One failed download: its reason and a Try again pill (tablet grid and phone column). */
@Composable
internal fun FailedRow(r: RealDebridQueue.Row, here: Set<Int>, active: Set<Int>, request: (List<Int>) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            // chimahon: only the row's volumes not on the tablet (a pack of 1-4 for 1-2 failed 1-2)
            GlassDownloads.shownReason(r.reason).let { why ->
                val vols = CoverageMatch.ranges(r.volumes.filter { it !in here })
                if (why.isEmpty()) {
                    stringResource(MR.strings.glass_title_failed_bare, vols)
                } else {
                    stringResource(MR.strings.glass_title_failed, vols, why)
                }
            },
            color = Glass.secondary,
            fontSize = Glass.body,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Pill(
            stringResource(MR.strings.glass_title_try_again),
            onClick = { request(r.volumes.filter { it !in here && it !in active }) },
            small = true,
        )
    }
}

@Composable
internal fun Legend(modifier: Modifier, notFound: Boolean) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        LegendItem("check", stringResource(MR.strings.glass_title_legend_read), Glass.secondary)
        LegendItem("dot", stringResource(MR.strings.glass_title_legend_here, glassDevice()), Color.White)
        LegendItem("download", stringResource(MR.strings.glass_title_legend_downloading), Glass.ice)
        if (notFound) LegendItem("alert", stringResource(MR.strings.glass_title_legend_not_found), Color.White)
    }
}

@Composable
private fun LegendItem(icon: String, text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        GlassIcon(icon, size = 14.dp, tint = color)
        Text(text, color = color, fontSize = Glass.caption)
    }
}

/** A cell in Select mode: [pickable] = on the tablet and not the one being read; [reading] = that locked one. */
private data class CellSelect(val pickable: Boolean, val picked: Boolean, val reading: Boolean)

@Composable
private fun VolumeCellView(
    cell: VolumeCell,
    localCover: File?,
    hidden: Boolean,
    cover: String?,
    seriesCover: String?,
    current: Boolean,
    onOpen: (LocalVolume) -> Unit,
    onFetch: () -> Unit,
    onCancel: () -> Unit,
    onSetRead: (LocalVolume, Boolean) -> Unit,
    /** Select mode: null = off; else whether this cell can be picked, is picked, is the locked reading volume. */
    select: CellSelect? = null,
    readHere: Boolean = false,
    onToggle: () -> Unit = {},
) {
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Glass.r8)
    val local = cell.local
    val alpha = when {
        localCover == null && cover == null -> 0.3f // the series cover standing in, dimmed
        cell.state == VolState.READ -> 0.35f
        cell.state == VolState.HERE -> 1f
        cell.state == VolState.DOWNLOADING -> 0.7f
        cell.state == VolState.QUEUED -> 0.5f
        else -> 0.4f
    }
    val (icon, tint) = when (cell.state) {
        VolState.READ -> "check" to Glass.have
        VolState.HERE -> "dot" to Color.White
        VolState.DOWNLOADING -> "download" to Glass.ice
        VolState.QUEUED -> "download" to Glass.ice.copy(alpha = 0.55f)
        VolState.FAILED -> "alert" to Color.White
        VolState.NONE -> null to Color.White
    }
    val failed = cell.state == VolState.FAILED
    Box {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(shape)
                .background(Glass.panel)
                // failed: the Downloads bar's hatch instead of a cover
                .then(if (failed) Modifier.hatched(Color.White.copy(alpha = 0.18f)) else Modifier)
                .border(
                    if (current || select?.picked == true) 2.dp else 1.dp,
                    if (current || select?.picked == true) Glass.ice else Glass.line,
                    shape,
                )
                .combinedClickable(
                    onClick = {
                        when {
                            select != null -> if (select.pickable) onToggle()
                            local != null -> onOpen(local)
                            cell.state == VolState.NONE || cell.state == VolState.READ || failed -> onFetch() // READ here = read on AniList, not on the tablet
                            else -> menu = true
                        }
                    },
                    onLongClick = { if (local != null && select == null) menu = true },
                ),
        ) {
            val context = LocalPlatformContext.current
            // hidden = blurred beyond recognition (needs Android 12; older: no image at all)
            if (!failed && (!hidden || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)) AsyncImage(
                // the volume's own first page, else MangaDex's cover, else the series cover
                model = when {
                    localCover != null -> localCover
                    cover == null -> seriesCover
                    else -> remember(cover) {
                        ImageRequest.Builder(context).data(cover).httpHeaders(VolumeCovers.imageHeaders).build()
                    }
                },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = alpha,
                modifier = Modifier.fillMaxSize().then(if (hidden) Modifier.blur(18.dp) else Modifier),
            )
            Text(
                cell.number.toString(),
                fontSize = Glass.caption,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .padding(4.dp)
                    .heightIn(min = 20.dp)
                    .widthIn(min = 20.dp)
                    .clip(CircleShape)
                    .background(Glass.scrim)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            icon?.let {
                Row(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .height(22.dp)
                        .widthIn(min = 22.dp)
                        .clip(CircleShape)
                        .background(Glass.scrim)
                        .padding(horizontal = if (readHere) 4.dp else 0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
                ) {
                    // read and still on the tablet: both marks
                    if (readHere && cell.state != VolState.READ) GlassIcon("check", size = 14.dp, tint = Glass.have)
                    GlassIcon(it, size = 14.dp, tint = tint)
                    if (readHere && cell.state == VolState.READ) GlassIcon("dot", size = 14.dp, tint = Color.White)
                }
            }
            if (select != null && select.pickable) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .padding(bottom = 4.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(if (select.picked) Glass.ice else Glass.scrim)
                        .border(1.5.dp, if (select.picked) Glass.ice else Color.White.copy(alpha = 0.8f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (select.picked) GlassIcon("check", size = 14.dp, tint = Color.Black)
                }
            }
            if (select?.reading == true) {
                Text(
                    stringResource(MR.strings.glass_title_reading),
                    fontSize = Glass.caption,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 10.dp)
                        .clip(CircleShape)
                        .background(Glass.scrim)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            if (failed) {
                Text(
                    stringResource(MR.strings.glass_dl_not_found),
                    fontSize = Glass.caption,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 10.dp)
                        .clip(CircleShape)
                        .background(Glass.scrim)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            // ponytail: no reading-progress bar for a half-read volume: chapters keep the last page, not the page count
            val bar = when (cell.state) {
                VolState.READ -> 1f
                VolState.DOWNLOADING -> cell.fraction ?: 0f
                else -> 0f
            }
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(Color.Black.copy(alpha = 0.6f)),
            ) {
                Box(Modifier.fillMaxWidth(bar).fillMaxHeight().background(tint))
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

@Composable
private fun FetchDialog(
    packs: List<Pack>,
    here: Set<Int>,
    sourceName: (Long) -> String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onByHand: () -> Unit,
    onTorrent: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(Glass.r16),
        containerColor = Glass.panel,
        titleContentColor = Color.White,
        textContentColor = Color.White,
        title = {
            // what the jobs add: a pack's volumes already on the tablet are skipped by the import
            val adds = packs.flatMap { p -> p.volumes.filter { it !in here } }
            Text(stringResource(MR.strings.glass_title_fetch_confirm, CoverageMatch.ranges(adds)), fontSize = Glass.heading)
        },
        text = {
            Column(
                Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                packs.forEach { p ->
                    val (already, adds) = p.volumes.partition { it in here }
                    Text(
                        if (already.isNotEmpty()) {
                            stringResource(
                                MR.strings.glass_title_pack_here,
                                CoverageMatch.ranges(p.volumes),
                                sourceName(p.sourceId),
                                CoverageMatch.ranges(adds),
                                CoverageMatch.ranges(already),
                            )
                        } else {
                            stringResource(
                                if (p.volumes.size > 1) MR.strings.glass_title_pack else MR.strings.glass_title_single,
                                CoverageMatch.ranges(p.volumes),
                                sourceName(p.sourceId),
                            )
                        },
                        fontSize = Glass.body,
                    )
                }
            }
        },
        confirmButton = { Pill(stringResource(MR.strings.glass_title_fetch), onClick = onConfirm, primary = true, small = true) },
        dismissButton = {
            // two rows: three pills do not fit a phone's dialog
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(stringResource(MR.strings.glass_torrent_use), onClick = onTorrent, small = true)
                    Pill(stringResource(MR.strings.glass_manual_by_hand), onClick = onByHand, small = true)
                }
                Pill(stringResource(MR.strings.action_cancel), onClick = onDismiss, small = true)
            }
        },
    )
}

@Composable
internal fun GlassMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(Glass.r12),
        containerColor = Glass.panel,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, Glass.line),
    ) { content() }
}

@Composable
internal fun MenuItem(text: String, selected: Boolean = false, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(
                text,
                color = if (selected) Glass.ice else Color.White,
                fontSize = Glass.body,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        },
        onClick = onClick,
    )
}
