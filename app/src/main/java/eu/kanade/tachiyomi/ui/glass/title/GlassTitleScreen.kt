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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.domain.manga.model.hasCustomCover
import eu.kanade.presentation.manga.EditCoverAction
import eu.kanade.presentation.manga.components.MangaCoverDialog
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.manga.EditMangaDialog
import eu.kanade.tachiyomi.ui.manga.MangaCoverScreenModel
import eu.kanade.tachiyomi.util.system.toShareIntent
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.launch
import mihon.feature.migration.config.MigrationConfigScreen
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.manga.model.Manga
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassChip
import eu.kanade.presentation.glass.GlassIcon
import eu.kanade.presentation.glass.GlassPage
import eu.kanade.presentation.glass.GlassRail
import eu.kanade.presentation.glass.GlassToggle
import eu.kanade.presentation.glass.Pill
import eu.kanade.presentation.glass.StatusColours
import eu.kanade.presentation.glass.StatusPalette
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
import kotlin.math.roundToInt

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
        val volumeSave by model.volumeSave.collectAsStateWithLifecycle()
        val savedVolumes by model.savedVolumes.collectAsStateWithLifecycle()
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
            readUpTo = savedVolumes,
            totalKnown = (entry?.volumes ?: 0) > 0,
            // the Downloads page's rule: a failed, not cancelled row's volumes not here and not queued again
            failed = remember(rows, here) { TitleGroup(mediaId, rows, here).failedVolumes.toSet() },
        )
        val failed = rows.filter { r ->
            r.state == RealDebridQueue.State.FAILED && r.reason != "Cancelled" &&
                r.volumes.any { it !in here && it !in active }
        }
        val sourceName = { id: Long -> sources.firstOrNull { it.id == id }?.name ?: coverage[id]?.sourceName.orEmpty() }

        // Select mode (canvas kumahon-volume-actions C): pick volumes, then Read / Unread / Mark / Get / Delete files
        // (a delete removes files only: rows, read marks and AniList stay)
        val readUpTo = savedVolumes
        val reading = (primary as? Primary.Resume)?.volume?.number
        val locked = GlassTitleState.locked(cells, reading)
        var selecting by remember { mutableStateOf(false) }
        var picked by remember { mutableStateOf(emptySet<Int>()) }
        var ranging by remember { mutableStateOf(false) }
        var rangeFrom by remember { mutableStateOf<Int?>(null) }
        // locked volumes (the one being read) are never picked: Unread would reset its page and unprotect it
        val pickedNow = picked intersect cells.mapNotNullTo(mutableSetOf()) { c -> c.number.takeIf { it !in locked } }
        val deletable = GlassTitleState.deletable(cells, pickedNow, reading)
        val gettable = GlassTitleState.gettable(cells, pickedNow)
        val pickedHere = cells.filter { it.number in pickedNow && it.local != null }
        var pickedBytes by remember { mutableLongStateOf(0L) }
        LaunchedEffect(deletable, local) { pickedBytes = if (deletable.isEmpty()) 0L else model.sizeOf(deletable) }
        // "Delete read": the read volumes still on the tablet (not the one being read)
        val readNow = GlassTitleState.selectRead(cells, readUpTo, reading)
        var readBytes by remember { mutableLongStateOf(0L) }
        LaunchedEffect(readNow, local) { readBytes = if (readNow.isEmpty()) 0L else model.sizeOf(readNow) }
        var deleteAsk by remember { mutableStateOf<Set<Int>?>(null) }
        var sheetBytes by remember { mutableLongStateOf(0L) }
        LaunchedEffect(deleteAsk, local) { sheetBytes = deleteAsk?.let { model.sizeOf(GlassTitleState.deletable(cells, it, reading)) } ?: 0L }
        val leaveSelect = {
            selecting = false
            picked = emptySet()
            ranging = false
            rangeFrom = null
        }
        var dialog by remember { mutableStateOf<TitleDialog?>(null) }
        val scope = rememberCoroutineScope()

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

        val actions = VolumeActions(
            open = openVolume,
            readUpTo = { n -> model.setRead(GlassTitleState.upTo(cells, n), true) },
            setRead = { v, read -> model.setRead(listOf(v.number), read) },
            bookmark = { v -> model.setBookmark(listOf(v.number), !v.bookmark) },
            delete = { n -> deleteAsk = setOf(n) },
            selectMore = { n ->
                selecting = true
                picked = if (n in locked) emptySet() else setOf(n)
            },
            locked = locked,
            size = { n -> model.sizeOf(setOf(n)) },
        )
        /** A tap on a cover in Select mode: toggles it, or (Range) sets the first, then the last. */
        val toggle = toggle@{ n: Int ->
            if (n in locked) return@toggle
            val from = rangeFrom
            if (!ranging) {
                picked = if (n in pickedNow) pickedNow - n else pickedNow + n
            } else if (from == null) {
                rangeFrom = n
            } else {
                picked = pickedNow + GlassTitleState.range(cells, from, n, locked)
                ranging = false
                rangeFrom = null
            }
        }
        val selectOf = { cell: VolumeCell ->
            if (selecting) CellSelect(cell.number in pickedNow, cell.number in locked && cell.local != null) else null
        }
        val selectActions: @Composable () -> Unit = {
            SelectActions(
                hasLocal = pickedHere.isNotEmpty(),
                allMarked = pickedHere.all { it.local?.bookmark == true },
                canGet = gettable.isNotEmpty(),
                canDelete = deletable.isNotEmpty(),
                onRead = { model.setRead(pickedHere.map { it.number }, true); leaveSelect() },
                onUnread = { model.setRead(pickedHere.map { it.number }, false); leaveSelect() },
                onMark = {
                    model.setBookmark(pickedHere.map { it.number }, !pickedHere.all { it.local?.bookmark == true })
                    leaveSelect()
                },
                onGet = { request(gettable); leaveSelect() },
                onDelete = { deleteAsk = deletable },
            )
        }
        /** The grid's header: "N volumes" with Delete read / Select / Fetch, or Select mode's toolbar. */
        val volumesHeader: @Composable (Boolean) -> Unit = { phone ->
            if (selecting) {
                SelectBar(
                    title = stringResource(MR.strings.glass_title_n_selected, pickedNow.size) +
                        if (pickedBytes > 0) " · ${GlassTitleState.sizeText(pickedBytes)}" else "",
                    onClose = leaveSelect,
                    actions = if (phone) null else selectActions,
                    picks = {
                        PickRow(
                            cells, locked, pickedNow, ranging, rangeFrom,
                            onPick = { picked = it; ranging = false; rangeFrom = null },
                            onRange = { ranging = !ranging; rangeFrom = null },
                        )
                    },
                )
            } else {
                val pills: @Composable () -> Unit = {
                    if (readNow.isNotEmpty()) {
                        Pill(
                            stringResource(MR.strings.glass_title_delete_read, GlassTitleState.sizeText(readBytes)),
                            onClick = { deleteAsk = readNow },
                            small = true,
                        )
                    }
                    if (cells.isNotEmpty()) {
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
                if (phone) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(MR.strings.glass_n_volumes, count), fontSize = Glass.heading, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { pills() }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(MR.strings.glass_n_volumes, count), fontSize = Glass.heading, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        pills()
                    }
                }
            }
        }

        val manga = local?.manga
        val aniListUrl = AnilistApi.mangaUrl(mediaId)
        val topRow: @Composable () -> Unit = {
            TopRow(
                onBack = { navigator.pop() },
                onRefresh = model::refreshSources,
                hideUnread = hideUnread,
                onToggleHideUnread = { hideUnreadPref.set(!hideUnread) },
                more = listOfNotNull(
                    manga?.let {
                        MR.strings.glass_title_edit_info to {
                            scope.launch { model.freshManga()?.let { dialog = TitleDialog.EditInfo(it) } }
                            Unit
                        }
                    },
                    manga?.let { MR.strings.glass_title_change_cover to { dialog = TitleDialog.Cover(it.id) } },
                    manga?.let {
                        MR.strings.action_edit_categories to {
                            scope.launch { model.categorySelection()?.let { dialog = TitleDialog.Categories(it) } }
                            Unit
                        }
                    },
                    manga?.takeIf { it.favorite }?.let { MR.strings.action_migrate to { navigator.push(MigrationConfigScreen(it.id)) } },
                    MR.strings.action_share to {
                        runCatching { context.startActivity(aniListUrl.toUri().toShareIntent(context, type = "text/plain")) }
                        Unit
                    },
                    MR.strings.action_open_in_browser to { uriHandler.openUri(aniListUrl) },
                ),
            )
        }

        val header: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                topRow()
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

        val colours = StatusPalette.current() // read once per screen, passed down
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
                            colours = colours,
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
                    volumeMax = GlassTitleState.volumeSliderMax(entry!!.volumes, entry!!.progressVolumes, volumes),
                    volumeSave = volumeSave,
                    onVolume = model::setVolume,
                    onVolumeOpen = model::resetVolumeSave,
                    onVolumeFlush = model::flushVolume,
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
            fullSpan { volumesHeader(false) }
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
                    colours = colours,
                    current = cell.number == current,
                    onOpen = openVolume,
                    onFetch = { request(listOf(cell.number)) },
                    onCancel = { model.cancel(cell.number) },
                    actions = actions,
                    select = selectOf(cell),
                    onToggle = { toggle(cell.number) },
                )
            }
            fullSpan { StatusLegend(Modifier.padding(top = 4.dp), cover = entry?.let { it.coverLarge ?: it.cover }, colours = colours) }
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
                localCovers = localCovers,
                covers = covers,
                hideUnread = hideUnread,
                missing = missing,
                fetchable = fetchable,
                failed = failed,
                here = here,
                active = active.keys,
                colours = colours,
                unavailable = GlassTitleState.unavailable(coverage, sources.map { it.id }, count),
                topRow = topRow,
                // the dock (~88 dp) and the navigation bar cover the end of the list while it shows
                bottomPadding = if (selecting && pickedNow.isNotEmpty()) 96.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() else 0.dp,
                volumesHeader = { volumesHeader(true) },
                onHome = GlassTitleState.onHome(entry?.status),
                onAddHome = model::addToHome,
                onOpen = openVolume,
                onFetch = request,
                onByHand = { navigator.push(GlassManualScreen(mediaId, missing, here.toList())) },
                onCancel = model::cancel,
                actions = actions,
                select = selectOf,
                onToggle = toggle,
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
            // Select mode on a phone: the actions sit in a dock at the bottom (the tablet has them in the header)
            if (selecting && phone && pickedNow.isNotEmpty()) Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(16.dp)
                    .clip(RoundedCornerShape(Glass.r16))
                    .background(Glass.panel)
                    .border(1.dp, Glass.line, RoundedCornerShape(Glass.r16))
                    .padding(10.dp),
            ) { selectActions() }
            deleteAsk?.let { goes ->
                DeleteSheet(
                    cells = cells,
                    goes = goes,
                    reading = reading,
                    bytes = sheetBytes,
                    onlyHere = GlassTitleState.readOnlyHere(cells, goes, readUpTo),
                    onCancel = { deleteAsk = null },
                    onConfirm = {
                        val chosenVolumes = GlassTitleState.deletable(cells, goes, reading)
                        deleteAsk = null
                        leaveSelect()
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
                    },
                )
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

        when (val d = dialog) {
            null -> Unit
            is TitleDialog.EditInfo -> EditMangaDialog(
                manga = d.manga,
                coverRatio = remember { mutableFloatStateOf(1f) },
                onDismissRequest = { dialog = null },
                onPositiveClick = model::updateInfo,
            )
            is TitleDialog.Categories -> ChangeCategoryDialog(
                initialSelection = d.selection,
                onDismissRequest = { dialog = null },
                onEditCategories = { navigator.push(CategoryScreen()) },
                onConfirm = { include, _ -> model.setCategories(include) },
            )
            is TitleDialog.Cover -> {
                val sm = rememberScreenModel { MangaCoverScreenModel(d.mangaId) }
                val coverManga by sm.state.collectAsStateWithLifecycle()
                val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                    if (uri != null) sm.editCover(context, uri)
                }
                coverManga?.let {
                    MangaCoverDialog(
                        manga = it,
                        snackbarHostState = sm.snackbarHostState,
                        isCustomCover = remember(it) { it.hasCustomCover() },
                        onShareClick = { sm.shareCover(context) },
                        onSaveClick = { sm.saveCover(context) },
                        onEditClick = { action ->
                            when (action) {
                                EditCoverAction.EDIT -> pick.launch("image/*")
                                EditCoverAction.DELETE -> sm.deleteCustomCover(context)
                            }
                        },
                        onDismissRequest = { dialog = null },
                    )
                }
            }
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

/** The classic dialogs the "More" menu opens over the page. */
private sealed interface TitleDialog {
    data class EditInfo(val manga: Manga) : TitleDialog
    data class Categories(val selection: ImmutableList<CheckboxState<Category>>) : TitleDialog
    data class Cover(val mangaId: Long) : TitleDialog
}

private fun LazyGridScope.fullSpan(content: @Composable () -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }) { content() }
}

@Composable
internal fun TopRow(
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    hideUnread: Boolean,
    onToggleHideUnread: () -> Unit,
    showHideUnread: Boolean = true,
    /** The title-level actions after the two above: Edit info, Change cover, Categories, Migrate, Share, Open in browser. */
    more: List<Pair<StringResource, () -> Unit>> = emptyList(),
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
                more.forEach { (label, action) ->
                    MenuItem(stringResource(label)) {
                        open = false
                        action()
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
    colours: StatusColours,
    selected: Boolean,
    onSelect: () -> Unit,
    onVisit: () -> Unit,
) {
    val shape = RoundedCornerShape(Glass.r12)
    val found = GlassTitleState.showBoxes(coverage)
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
            if (found) {
                SourceBoxes(boxes, colours)
            } else {
                // checking / not on the source / needs a visit / error / no volume numbers: no boxes, no label
                Box(Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.08f)))
            }
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
        if (has.isEmpty()) {
            stringResource(MR.strings.glass_title_no_numbers)
        } else {
            GlassTitleState.missingLine(has, count) ?: stringResource(MR.strings.glass_title_all, count)
        }
    }
}

@Composable
private fun AniListPanel(
    entry: AniListLibrary.Entry,
    message: String?,
    onStatus: (String) -> Unit,
    onStars: (Int) -> Unit,
    volumeMax: Int,
    volumeSave: GlassTitleScreenModel.VolumeSave,
    onVolume: (n: Int, debounce: Boolean) -> Unit,
    onVolumeOpen: () -> Unit,
    onVolumeFlush: () -> Unit,
    onOpen: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var volumeOpen by remember { mutableStateOf(false) }
    val stars = GlassTitleState.stars(entry.score)
    // leaving the page within the debounce still sends the last -/+ tap
    val flush by rememberUpdatedState(onVolumeFlush)
    DisposableEffect(Unit) { onDispose { flush() } }
    val volumeChip: @Composable () -> Unit = {
        Box {
            GlassChip(
                stringResource(MR.strings.glass_title_on_anilist, entry.progressVolumes),
                selected = false,
                onClick = {
                    onVolumeOpen()
                    volumeOpen = true
                },
                icon = "chevDown",
            )
            if (volumeOpen) {
                VolumePopover(entry, volumeMax, volumeSave, onVolume) {
                    volumeOpen = false
                    onVolumeFlush()
                }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().glassPanel().padding(horizontal = 16.dp, vertical = 14.dp)) {
    // badge + status + volume + 5 stars + open button need ~350 dp: narrower (phone) puts the volume chip on its own row
    val narrow = maxWidth < 420.dp
    Column(
        Modifier.fillMaxWidth(),
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
            if (!narrow) volumeChip()
            Text(
                message.orEmpty(),
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
        if (narrow) volumeChip()
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
}

/** The "volumes read on AniList" popover above its chip: number, -/+, slider; saves on release and on -/+ (debounced). */
@Composable
private fun VolumePopover(
    entry: AniListLibrary.Entry,
    max: Int,
    save: GlassTitleScreenModel.VolumeSave,
    onVolume: (n: Int, debounce: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val blue = Color(0xFF02A9FF)
    var n by remember { mutableIntStateOf(entry.progressVolumes) }
    // a failed save drops the optimistic number: follow the entry back
    LaunchedEffect(entry.progressVolumes) { n = entry.progressVolumes }
    val above = remember {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ) = IntOffset(
                anchorBounds.left.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
                (anchorBounds.top - popupContentSize.height - 8).coerceAtLeast(0),
            )
        }
    }
    Popup(popupPositionProvider = above, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Column(
            Modifier.width(340.dp).clip(RoundedCornerShape(Glass.r16)).background(Glass.panel)
                .border(1.dp, Glass.line, RoundedCornerShape(Glass.r16)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(MR.strings.glass_title_volumes_read), color = Glass.secondary, fontSize = Glass.caption)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("$n", color = Color.White, fontSize = Glass.title, fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(MR.strings.glass_title_volumes_of, entry.volumes?.takeIf { it > 0 }?.toString() ?: "?"),
                        color = Glass.secondary,
                        fontSize = Glass.body,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("−" to -1, "+" to 1).forEach { (sign, d) ->
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).border(1.dp, Glass.line, CircleShape).clickable {
                                val next = (n + d).coerceIn(0, max)
                                if (next != n) {
                                    n = next
                                    onVolume(next, true)
                                }
                            },
                            contentAlignment = Alignment.Center,
                        ) { Text(sign, color = Color.White, fontSize = Glass.heading) }
                    }
                }
            }
            Slider(
                value = n.toFloat(),
                onValueChange = { n = it.roundToInt() },
                onValueChangeFinished = { if (n != entry.progressVolumes) onVolume(n, false) },
                valueRange = 0f..max.toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = blue,
                    inactiveTrackColor = Color.White.copy(alpha = 0.18f),
                ),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf(0, max / 3, max * 2 / 3, max).forEach { Text("$it", color = Glass.secondary, fontSize = Glass.caption) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                when (save) {
                    GlassTitleScreenModel.VolumeSave.Idle ->
                        Text(stringResource(MR.strings.glass_title_volumes_hint), color = Glass.secondary, fontSize = Glass.caption)
                    is GlassTitleScreenModel.VolumeSave.Saving -> {
                        CircularProgressIndicator(Modifier.size(14.dp), color = Glass.ice, strokeWidth = 2.dp)
                        Text(stringResource(MR.strings.glass_title_volumes_saving, save.n), color = Glass.ice, fontSize = Glass.caption)
                    }
                    is GlassTitleScreenModel.VolumeSave.Saved ->
                        Text(stringResource(MR.strings.glass_title_volumes_saved, save.n), color = Glass.green, fontSize = Glass.caption)
                    is GlassTitleScreenModel.VolumeSave.Failed -> {
                        Text(stringResource(MR.strings.glass_title_volumes_failed, save.back), color = Glass.red, fontSize = Glass.caption)
                        Text(
                            stringResource(MR.strings.glass_title_volumes_retry),
                            color = Glass.red,
                            fontSize = Glass.caption,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable {
                                n = save.n
                                onVolume(save.n, false)
                            },
                        )
                    }
                }
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
private fun VolumeCellView(
    cell: VolumeCell,
    localCover: File?,
    hidden: Boolean,
    cover: String?,
    seriesCover: String?,
    colours: StatusColours,
    current: Boolean,
    onOpen: (LocalVolume) -> Unit,
    onFetch: () -> Unit,
    onCancel: () -> Unit,
    actions: VolumeActions,
    /** Select mode: null = off; else whether this cell is picked and whether it is the locked reading volume. */
    select: CellSelect? = null,
    onToggle: () -> Unit = {},
) {
    var menu by remember { mutableStateOf(false) }
    val local = cell.local
    val failed = cell.state == VolState.FAILED
    val image = volumeImage(localCover, cover, seriesCover) // the series cover stands in (dimmed) when neither exists
    Box {
        VolumeTile(
            state = cell.state,
            number = cell.number,
            image = image,
            fraction = cell.fraction,
            standIn = localCover == null && cover == null,
            blurred = hidden,
            onDevice = cell.state == VolState.READ && local != null,
            colours = colours,
            current = current || select?.picked == true,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {
                        when {
                            select != null -> onToggle()
                            local != null -> onOpen(local)
                            cell.state == VolState.NONE || cell.state == VolState.READ || failed -> onFetch() // READ here = read on AniList, not on the tablet
                            else -> menu = true
                        }
                    },
                    onLongClick = { if (local != null && select == null) menu = true },
                ),
            overlay = { CellOverlay(select, local?.bookmark == true) },
        )
        if (local != null) {
            VolumeMenu(menu, { menu = false }, cell.number, local, actions)
        } else {
            GlassMenu(menu, { menu = false }) {
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
