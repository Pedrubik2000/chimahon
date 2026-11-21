package eu.kanade.tachiyomi.ui.glass.title

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import eu.kanade.tachiyomi.data.anilist.AniListLibrary
import eu.kanade.tachiyomi.data.coverage.Coverage
import eu.kanade.tachiyomi.data.coverage.CoverageMatch
import eu.kanade.tachiyomi.data.download.RealDebridQueue
import eu.kanade.tachiyomi.data.track.anilist.AnilistApi
import eu.kanade.tachiyomi.ui.glass.statusLabel
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.setting.SettingsScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

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
        val message by model.message.collectAsStateWithLifecycle()
        val fillGapsPref = remember { model.downloadPreferences.realDebridFillGaps() }
        val fillGaps by fillGapsPref.collectAsState()

        val volumes = local?.volumes.orEmpty()
        val here = volumes.map { it.number }.toSet()
        val active = remember(rows) { GlassTitleState.active(rows) }
        val inOrder = remember(coverage, sources) { CoverageMatch.ordered(coverage.values, sources.map { it.id }) }
        val count = GlassTitleState.volumeCount(entry?.volumes, coverage.values, volumes)
        val chosen = chosenId ?: GlassTitleState.defaultSource(inOrder)
        val order = GlassTitleState.fetchOrder(inOrder, chosen, fillGaps)
        val fetchable = GlassTitleState.fetchable(GlassTitleState.missing(count, volumes, active), order)
        val primary = GlassTitleState.primary(volumes, entry?.progressVolumes ?: 0, count, active.keys)
        val current = when (primary) {
            is Primary.Resume -> primary.volume.number
            is Primary.Fetch -> primary.volume
            Primary.None -> null
        }
        val cells = GlassTitleState.cells(count, volumes, active)
        val failed = rows.filter { r ->
            r.state == RealDebridQueue.State.FAILED && r.reason != "Cancelled" &&
                r.volumes.any { it !in here && it !in active }
        }
        val sourceName = { id: Long -> sources.firstOrNull { it.id == id }?.name ?: coverage[id]?.sourceName.orEmpty() }

        var confirm by remember { mutableStateOf<List<Int>?>(null) }
        /** Single volume from one link: queued at once; anything with a pack, or [ask]: the confirm dialog first. */
        val request = { vols: List<Int>, ask: Boolean ->
            val plan = GlassTitleState.plan(vols, order)
            if (plan.isEmpty()) {
                model.message.value = context.stringResource(MR.strings.glass_title_no_source, CoverageMatch.ranges(vols))
            } else if (ask || plan.any { it.volumes.size > 1 }) {
                confirm = vols
            } else {
                model.fetch(vols, order)
            }
            Unit
        }
        val openVolume = { v: LocalVolume ->
            local?.let { context.startActivity(ReaderActivity.newIntent(context, it.manga.id, v.chapterId)) }
            Unit
        }

        val left: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TopRow(
                    onBack = { navigator.pop() },
                    onClassic = local?.let { l -> { navigator.push(MangaScreen(l.manga.id)) } },
                    onRefresh = model::refreshSources,
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
                                onClick = { request(listOf(primary.volume), false) },
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
                if (entry != null) {
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

        val right: LazyGridScope.() -> Unit = {
            fullSpan {
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
            fullSpan {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(MR.strings.glass_n_volumes, count), fontSize = Glass.heading, fontWeight = FontWeight.SemiBold)
                    Legend(Modifier.weight(1f))
                    if (fetchable.isNotEmpty()) {
                        // ponytail: no size: Coverage keeps links, not the link texts that carry sizes (X3DL)
                        Pill(
                            stringResource(MR.strings.glass_title_fetch_n, fetchable.size),
                            onClick = { request(fetchable, true) },
                            icon = "download",
                            small = true,
                        )
                    }
                }
            }
            failed.forEach { r ->
                fullSpan {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            stringResource(MR.strings.glass_title_failed, CoverageMatch.ranges(r.volumes), r.reason.orEmpty()),
                            color = Glass.secondary,
                            fontSize = Glass.body,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Pill(
                            stringResource(MR.strings.glass_title_try_again),
                            onClick = { request(r.volumes.filter { it !in here && it !in active }, false) },
                            small = true,
                        )
                    }
                }
            }
            items(cells, key = { it.number }) { cell ->
                VolumeCellView(
                    cell = cell,
                    cover = covers[cell.number],
                    seriesCover = entry?.let { it.coverLarge ?: it.cover },
                    current = cell.number == current,
                    onOpen = openVolume,
                    onFetch = { request(listOf(cell.number), false) },
                    onCancel = { model.cancel(cell.number) },
                    onSetRead = model::setRead,
                )
            }
        }

        GlassPage {
            entry?.banner?.let { banner ->
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
            BoxWithConstraints(Modifier.fillMaxSize().padding(start = 108.dp, end = 32.dp)) {
                val grid = @Composable { modifier: Modifier, withLeft: Boolean ->
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(68.dp),
                        modifier = modifier,
                        contentPadding = PaddingValues(top = 24.dp, bottom = 32.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (withLeft) fullSpan { left() }
                        right(this)
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
                    // portrait: one scrolling column, the left column first
                    grid(Modifier.fillMaxSize(), true)
                }
            }
            GlassRail(
                active = "none",
                // ponytail: back to the shell (its last page); the rail can't pick a page of GlassHomeScreen from here
                onSelect = { if (it == "settings") navigator.push(SettingsScreen()) else navigator.popUntilRoot() },
                modifier = Modifier.padding(start = 20.dp, top = 24.dp),
            )
        }

        confirm?.let { vols ->
            FetchDialog(
                packs = GlassTitleState.plan(vols, order),
                sourceName = sourceName,
                onConfirm = {
                    if (!model.fetch(vols, order)) {
                        model.message.value =
                            context.stringResource(MR.strings.glass_title_no_source, CoverageMatch.ranges(vols))
                    }
                    confirm = null
                },
                onDismiss = { confirm = null },
            )
        }
    }
}

private fun LazyGridScope.fullSpan(content: @Composable () -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }) { content() }
}

@Composable
private fun TopRow(onBack: () -> Unit, onClassic: (() -> Unit)?, onRefresh: () -> Unit) {
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
            val status = when (entry?.mediaStatus) {
                "FINISHED" -> stringResource(MR.strings.glass_title_finished)
                "RELEASING" -> stringResource(MR.strings.glass_title_releasing)
                "HIATUS" -> stringResource(MR.strings.glass_title_hiatus)
                "CANCELLED" -> stringResource(MR.strings.glass_title_cancelled)
                "NOT_YET_RELEASED" -> stringResource(MR.strings.glass_title_unreleased)
                else -> null
            }
            val line = listOfNotNull(
                entry?.volumes?.let { stringResource(MR.strings.glass_title_vols, it) },
                status,
                entry?.year?.toString(),
            ).joinToString(" · ")
            if (line.isNotEmpty()) Text(line, color = Glass.secondary, fontSize = Glass.body)
        }
    }
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

@Composable
private fun Legend(modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        LegendItem("check", stringResource(MR.strings.glass_title_legend_read), Glass.secondary)
        LegendItem("dot", stringResource(MR.strings.glass_title_legend_here), Color.White)
        LegendItem("download", stringResource(MR.strings.glass_title_legend_downloading), Glass.ice)
    }
}

@Composable
private fun LegendItem(icon: String, text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        GlassIcon(icon, size = 14.dp, tint = color)
        Text(text, color = color, fontSize = Glass.caption)
    }
}

@Composable
private fun VolumeCellView(
    cell: VolumeCell,
    cover: String?,
    seriesCover: String?,
    current: Boolean,
    onOpen: (LocalVolume) -> Unit,
    onFetch: () -> Unit,
    onCancel: () -> Unit,
    onSetRead: (LocalVolume, Boolean) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Glass.r8)
    val local = cell.local
    val alpha = when {
        cover == null -> 0.3f // the series cover standing in, dimmed: the number says which volume
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
        VolState.NONE -> null to Color.White
    }
    Box {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(shape)
                .background(Glass.panel)
                .border(if (current) 2.dp else 1.dp, if (current) Glass.ice else Glass.line, shape)
                .combinedClickable(
                    onClick = {
                        when {
                            local != null -> onOpen(local)
                            cell.state == VolState.NONE -> onFetch()
                            else -> menu = true
                        }
                    },
                    onLongClick = { if (local != null) menu = true },
                ),
        ) {
            AsyncImage(
                model = cover ?: seriesCover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = alpha,
                modifier = Modifier.fillMaxSize(),
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
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Glass.scrim),
                    contentAlignment = Alignment.Center,
                ) {
                    GlassIcon(it, size = 14.dp, tint = tint)
                }
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
    sourceName: (Long) -> String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(Glass.r16),
        containerColor = Glass.panel,
        titleContentColor = Color.White,
        textContentColor = Color.White,
        title = {
            Text(stringResource(MR.strings.glass_title_fetch_confirm, packs.sumOf { it.volumes.size }), fontSize = Glass.heading)
        },
        text = {
            Column(
                Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                packs.forEach { p ->
                    Text(
                        stringResource(
                            if (p.volumes.size > 1) MR.strings.glass_title_pack else MR.strings.glass_title_single,
                            CoverageMatch.ranges(p.volumes),
                            sourceName(p.sourceId),
                        ),
                        fontSize = Glass.body,
                    )
                }
            }
        },
        confirmButton = { Pill(stringResource(MR.strings.glass_title_fetch), onClick = onConfirm, primary = true, small = true) },
        dismissButton = { Pill(stringResource(MR.strings.action_cancel), onClick = onDismiss, small = true) },
    )
}

@Composable
private fun GlassMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
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
private fun MenuItem(text: String, selected: Boolean = false, onClick: () -> Unit) {
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
