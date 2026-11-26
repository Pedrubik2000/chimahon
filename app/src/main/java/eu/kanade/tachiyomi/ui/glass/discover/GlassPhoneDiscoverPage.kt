package eu.kanade.tachiyomi.ui.glass.discover

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.Navigator
import coil3.compose.AsyncImage
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassWideButton
import eu.kanade.presentation.glass.GlassChip
import eu.kanade.presentation.glass.GlassIcon
import eu.kanade.presentation.glass.GlassPhoneBarRoom
import eu.kanade.presentation.glass.GlassRailItems
import eu.kanade.presentation.glass.Meter
import eu.kanade.presentation.glass.glassPanel
import eu.kanade.tachiyomi.data.anilist.AniListLibrary.Entry
import eu.kanade.tachiyomi.ui.glass.GlassSearchField
import eu.kanade.tachiyomi.ui.glass.lengthLabel
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Canvas `PhoneDiscover-2` (+ `-thisorthat`, `-browse`): the tablet Discover's three tabs for one thumb. Pick for me
 * is a card you swipe (right = Add to Home, left = Not for me), This or that stacks the two titles, Browse has one
 * chip per filter group, each opening its own sheet. Same model as the tablet page.
 */
@Composable
fun GlassPhoneDiscoverPage(model: GlassDiscoverModel, navigator: Navigator) {
    val entries by model.entries.collectAsStateWithLifecycle()
    val loggedIn by model.loggedInState.collectAsStateWithLifecycle()
    val open: (Entry) -> Unit = { openDiscoverTitle(navigator, it) }

    LaunchedEffect(entries, loggedIn) { model.loadRecs() }

    if (loggedIn && model.tab == DiscoverTab.BROWSE) {
        PhoneBrowse(model, open)
        return
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = GlassPhoneBarRoom),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PhoneHeader(model)
        when {
            !loggedIn -> NeedsList(stringResource(MR.strings.glass_discover_login))
            model.tab == DiscoverTab.PICK -> PhonePick(model, open, navigator)
            else -> PhoneDuel(model, open, navigator)
        }
        ErrorLine(model)
    }
}

/** "Discover · from AniList", then the three tabs as one segmented pill. */
@Composable
private fun PhoneHeader(model: GlassDiscoverModel) {
    Column {
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(GlassRailItems.first { it.first == "discover" }.second, fontSize = Glass.heading, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(stringResource(MR.strings.glass_phone_from_anilist), color = Glass.secondary, fontSize = Glass.caption)
        }
        Row(Modifier.fillMaxWidth().height(Glass.button).border(1.dp, Glass.line, CircleShape).padding(4.dp)) {
            listOf(
                DiscoverTab.PICK to MR.strings.glass_discover_pick,
                DiscoverTab.DUEL to MR.strings.glass_discover_duel,
                DiscoverTab.BROWSE to MR.strings.glass_discover_browse,
            ).forEach { (tab, label) ->
                val on = model.tab == tab
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(if (on) Color.White else Color.Transparent)
                        .clickable { model.tab = tab },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(label),
                        color = if (on) Color.Black else Color.White,
                        fontSize = Glass.body,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun CapsLabel(text: String, color: Color = Glass.secondary, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        color = color,
        fontSize = Glass.caption,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 2.sp,
        maxLines = 1,
        modifier = modifier,
    )
}

// Pick for me

@Composable
private fun PhonePick(model: GlassDiscoverModel, open: (Entry) -> Unit, navigator: Navigator) {
    val ranking by model.ranking.collectAsStateWithLifecycle()
    val hidden by model.hidden.collectAsStateWithLifecycle()
    val recommendedBy by model.recommendedBy.collectAsStateWithLifecycle()
    LaunchedEffect(ranking, hidden, model.pickId) { model.ensurePick() }
    val nv = ranking
    val pick = nv?.ranked?.firstOrNull { it.mediaId == model.pickId }
    // what the last swipe or button did; the swipe hint until then
    var said by remember { mutableStateOf<String?>(null) }

    when {
        nv == null -> Unit
        nv.ranked.isEmpty() && model.entries.value.isNullOrEmpty() ->
            NeedsList(stringResource(MR.strings.glass_empty))
        pick == null -> Text(stringResource(MR.strings.glass_discover_nothing), fontSize = Glass.label)
        else -> {
            val e = pick.entry
            val onHome = e.status in ON_HOME
            val adding = stringResource(MR.strings.glass_phone_adding, e.name())
            val hiding = stringResource(MR.strings.glass_phone_hidden, e.name())
            // a title already on Home has nothing to add: right is then the same as Skip
            val add = {
                if (onHome) {
                    said = null
                } else {
                    model.addHome(e)
                    said = adding
                }
                model.next()
            }
            val nope = {
                said = hiding
                model.notForMe(e.mediaId)
            }
            PickDeck(pick, nv.why(pick, recommendedBy).take(3), onHome, onOpen = { open(e) }, onRight = add, onLeft = nope)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassWideButton(stringResource(MR.strings.glass_discover_not_for_me), nope, Modifier.weight(1f), icon = "close")
                GlassWideButton(
                    stringResource(MR.strings.glass_discover_skip),
                    onClick = {
                        said = null
                        model.next()
                    },
                    Modifier.weight(1f),
                )
                if (onHome) {
                    GlassWideButton(stringResource(MR.strings.glass_discover_on_home), {}, Modifier.weight(1f), icon = "check")
                } else {
                    GlassWideButton(stringResource(MR.strings.glass_discover_add_home), add, Modifier.weight(1f), primary = true)
                }
            }
            Text(
                said ?: stringResource(MR.strings.glass_phone_swipe_hint),
                color = Glass.secondary,
                fontSize = Glass.caption,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * The pick as a card over the edge of a second one. Dragged past 96dp it acts ([onRight] / [onLeft]), else it
 * springs back; a tap opens the title.
 * ponytail: the card behind is only the stack's edge, not the next cover (the next pick is drawn when this one
 * leaves); the model would have to draw one ahead to show it.
 */
@Composable
private fun PickDeck(
    pick: Pick,
    why: List<Reason>,
    onHome: Boolean,
    onOpen: () -> Unit,
    onRight: () -> Unit,
    onLeft: () -> Unit,
) {
    val e = pick.entry
    val scope = rememberCoroutineScope()
    val x = remember { Animatable(0f) }
    val threshold = with(LocalDensity.current) { 96.dp.toPx() }
    val right by rememberUpdatedState(onRight)
    val left by rememberUpdatedState(onLeft)
    LaunchedEffect(pick.mediaId) { x.snapTo(0f) }

    Box(Modifier.fillMaxWidth().height(512.dp)) {
        Box(Modifier.padding(horizontal = 14.dp).fillMaxWidth().height(500.dp).glassPanel())
        Box(
            Modifier
                .padding(top = 12.dp)
                .fillMaxWidth()
                .height(500.dp)
                .offset { IntOffset(x.value.roundToInt(), 0) }
                .graphicsLayer { rotationZ = x.value / threshold * 3f }
                .glassPanel()
                .pointerInput(pick.mediaId) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val v = x.value
                            when {
                                v > threshold -> right()
                                v < -threshold -> left()
                            }
                            scope.launch { if (abs(v) > threshold) x.snapTo(0f) else x.animateTo(0f) }
                        },
                        onDragCancel = { scope.launch { x.animateTo(0f) } },
                    ) { _, drag -> scope.launch { x.snapTo(x.value + drag) } }
                }
                .clickable(onClick = onOpen),
        ) {
            AsyncImage(
                model = e.coverLarge ?: e.cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Glass.page.copy(alpha = 0.5f),
                        0.16f to Glass.page.copy(alpha = 0f),
                        0.3f to Glass.page.copy(alpha = 0f),
                        0.56f to Glass.page.copy(alpha = 0.82f),
                        1f to Glass.page,
                    ),
                ),
            )
            Row(Modifier.fillMaxWidth().padding(12.dp)) {
                SwipeHint(stringResource(MR.strings.glass_discover_not_for_me), before = "chevLeft")
                Spacer(Modifier.weight(1f))
                if (!onHome) SwipeHint(stringResource(MR.strings.glass_discover_add_home), after = "chevRight")
            }
            Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                CapsLabel(stringResource(MR.strings.glass_discover_pick), Glass.ice)
                Text(
                    e.name(),
                    fontSize = Glass.heading,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    listOf(stringResource(MR.strings.glass_discover_match, pick.match), metaLine(e))
                        .filter { it.isNotEmpty() }
                        .joinToString(" · "),
                    color = Glass.secondary,
                    fontSize = Glass.body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (why.isNotEmpty()) {
                    CapsLabel(stringResource(MR.strings.glass_discover_why), modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    why.forEach { r ->
                        Row(Modifier.padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GlassIcon("check", Modifier.padding(top = 2.dp), size = 16.dp, tint = Glass.ice)
                            Text(reasonText(r), fontSize = Glass.body, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SwipeHint(text: String, before: String? = null, after: String? = null) {
    Row(
        Modifier
            .height(Glass.chip)
            .clip(CircleShape)
            .background(Glass.scrim)
            .padding(start = if (before != null) 6.dp else 12.dp, end = if (after != null) 6.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        before?.let { GlassIcon(it, size = 18.dp) }
        Text(text, color = Color.White.copy(alpha = 0.86f), fontSize = Glass.caption, maxLines = 1)
        after?.let { GlassIcon(it, size = 18.dp) }
    }
}

// This or that

@Composable
private fun PhoneDuel(model: GlassDiscoverModel, open: (Entry) -> Unit, navigator: Navigator) {
    val ranking by model.ranking.collectAsStateWithLifecycle()
    LaunchedEffect(ranking) { if (model.duel == null) model.startDuel() }
    val d = model.duel
    val pair = d?.pair
    val winner = d?.winner

    when {
        ranking == null -> Unit
        model.entries.value.isNullOrEmpty() -> NeedsList(stringResource(MR.strings.glass_empty))
        d != null && pair != null -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(MR.strings.glass_discover_duel_progress, d.done + 1, d.total, d.all.size),
                    fontSize = Glass.label,
                    fontWeight = FontWeight.Bold,
                )
                Meter(d.done.toFloat() / d.total.coerceAtLeast(1), Modifier.fillMaxWidth(), color = Glass.ice)
            }
            Column {
                DuelRow(pair.first) { model.choose(first = true) }
                Row(
                    Modifier.fillMaxWidth().height(32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.weight(1f).height(1.dp).background(Glass.line))
                    CapsLabel(stringResource(MR.strings.glass_phone_or))
                    Box(Modifier.weight(1f).height(1.dp).background(Glass.line))
                }
                DuelRow(pair.second) { model.choose(first = false) }
            }
            Text(stringResource(MR.strings.glass_discover_duel_hint), color = Glass.secondary, fontSize = Glass.caption)
        }
        winner != null -> {
            val e = winner.entry
            Column(Modifier.fillMaxWidth().glassPanel()) {
                Box(Modifier.fillMaxWidth().height(320.dp).clickable { open(e) }) {
                    AsyncImage(
                        model = e.coverLarge ?: e.cover,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Glass.panel.copy(alpha = 0f), 1f to Glass.panel)))
                }
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    CapsLabel(stringResource(MR.strings.glass_discover_you_chose), Glass.ice)
                    Text(
                        e.name(),
                        fontSize = Glass.heading,
                        lineHeight = 28.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(metaLine(e), color = Glass.secondary, fontSize = Glass.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassWideButton(stringResource(MR.strings.glass_discover_open), { open(e) }, Modifier.weight(1f), primary = true)
                        if (e.status !in ON_HOME) {
                            GlassWideButton(
                                stringResource(MR.strings.glass_discover_add_home),
                                { model.addHome(e) },
                                Modifier.weight(1f),
                                icon = if (e.mediaId in model.adding) null else "plus",
                            )
                        }
                        GlassWideButton(stringResource(MR.strings.glass_discover_again), model::startDuel, Modifier.weight(1f))
                    }
                }
            }
        }
        else -> Text(stringResource(MR.strings.glass_discover_too_few), fontSize = Glass.label)
    }
}

/** One of the two: cover left, title and what it is, the whole card keeps it. */
@Composable
private fun DuelRow(p: Pick, onKeep: () -> Unit) {
    val e = p.entry
    Row(Modifier.fillMaxWidth().height(216.dp).glassPanel().clickable(onClick = onKeep)) {
        AsyncImage(
            model = e.coverLarge ?: e.cover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.width(144.dp).fillMaxHeight(),
        )
        Column(Modifier.weight(1f).fillMaxHeight().padding(16.dp)) {
            Text(
                e.name(),
                fontSize = Glass.heading,
                lineHeight = 28.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                metaLine(e),
                color = Glass.secondary,
                fontSize = Glass.body,
                lineHeight = 20.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GlassIcon("check", size = 18.dp, tint = Glass.ice)
                Text(stringResource(MR.strings.glass_phone_keep), color = Glass.ice, fontSize = Glass.body, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// Browse

private val FACETS = listOf(
    BrowseFacet.GENRES to MR.strings.glass_phone_genres,
    BrowseFacet.STATUS to MR.strings.glass_phone_status,
    BrowseFacet.LENGTH to MR.strings.glass_phone_length,
    BrowseFacet.MY_LIST to MR.strings.glass_phone_my_list,
    BrowseFacet.TAGS to MR.strings.glass_phone_tags,
)

/** What is chosen in one group, as its chip shows it ("Genres: Comedy +1"). */
@Composable
private fun chosen(f: BrowseFilters, facet: BrowseFacet): List<String> = when (facet) {
    BrowseFacet.GENRES -> f.genres.toList()
    BrowseFacet.STATUS -> listOfNotNull(
        when (f.status) {
            "FINISHED" -> stringResource(MR.strings.glass_discover_finished)
            "RELEASING" -> stringResource(MR.strings.glass_discover_releasing)
            else -> null
        },
    )
    BrowseFacet.LENGTH -> listOfNotNull(f.length?.let { stringResource(lengthLabel(it)) })
    BrowseFacet.MY_LIST -> listOfNotNull(f.myList?.let { stringResource(myListLabel(it)) })
    BrowseFacet.TAGS -> f.tagsIn.toList() + f.tagsOut.map { stringResource(MR.strings.glass_discover_without, it) }
}

private fun BrowseFilters.cleared(facet: BrowseFacet): BrowseFilters = when (facet) {
    BrowseFacet.GENRES -> copy(genres = emptySet())
    BrowseFacet.STATUS -> copy(status = null)
    BrowseFacet.LENGTH -> copy(length = null)
    BrowseFacet.MY_LIST -> copy(myList = null)
    BrowseFacet.TAGS -> copy(tagsIn = emptySet(), tagsOut = emptySet())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneBrowse(model: GlassDiscoverModel, open: (Entry) -> Unit) {
    LaunchedEffect(Unit) { model.startBrowse() }
    val b = model.browse
    val f = model.filters
    val grid = rememberLazyGridState()
    LaunchedEffect(grid, b.page, b.loading) {
        snapshotFlow { (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) to grid.layoutInfo.totalItemsCount }
            .collect { (last, total) -> if (last >= total - 12) model.loadMore() }
    }
    val hints = remember(b.entries, f.tagsIn, f.tagsOut) { tagHints(b, f) }
    var sheet by remember { mutableStateOf<BrowseFacet?>(null) }
    val full: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = grid,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = GlassPhoneBarRoom),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "header", span = full) { PhoneHeader(model) }
        item(key = "search", span = full) {
            GlassSearchField(
                f.search,
                { model.filters = model.filters.copy(search = it) },
                stringResource(MR.strings.glass_discover_search),
                Modifier.fillMaxWidth(),
            )
        }
        item(key = "chips", span = full) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FACETS.forEach { (facet, name) ->
                    val on = chosen(f, facet)
                    GlassChip(
                        if (on.isEmpty()) {
                            stringResource(name)
                        } else {
                            stringResource(name) + ": " + on.first() + if (on.size > 1) " +${on.size - 1}" else ""
                        },
                        selected = on.isNotEmpty(),
                        onClick = { sheet = facet },
                        icon = "chevDown",
                    )
                }
                if (f.copy(search = "") != BrowseFilters()) {
                    Text(
                        stringResource(MR.strings.glass_phone_clear_all),
                        color = Glass.ice,
                        fontSize = Glass.body,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { model.filters = BrowseFilters(search = model.filters.search) }
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                    )
                }
            }
        }
        item(key = "count", span = full) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // the chips above already say which filters are on: only the count here
                Text(summary(b, BrowseFilters()), color = Glass.secondary, fontSize = Glass.body, maxLines = 1)
                ErrorLine(model)
                if (b.entries.isEmpty() && !b.loading && b.page > 0 && model.error == null) {
                    Text(stringResource(MR.strings.glass_discover_no_results), fontSize = Glass.label, modifier = Modifier.padding(top = 16.dp))
                }
            }
        }
        items(b.entries, key = { it.mediaId }) { e -> BrowseCell(e) { open(e) } }
    }

    sheet?.let { facet ->
        ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = Glass.panel, contentColor = Color.White) {
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(FACETS.first { it.first == facet }.second),
                        color = Color.White,
                        fontSize = Glass.heading,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        stringResource(MR.strings.glass_discover_clear),
                        color = Glass.ice,
                        fontSize = Glass.body,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { model.filters = model.filters.cleared(facet) }
                            .padding(horizontal = 12.dp, vertical = 14.dp),
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BrowseChips(model, hints, facet)
                }
                if (facet == BrowseFacet.TAGS) {
                    Text(
                        stringResource(MR.strings.glass_discover_tags_hint),
                        color = Glass.secondary,
                        fontSize = Glass.caption,
                        lineHeight = 16.sp,
                    )
                }
                GlassWideButton(
                    stringResource(MR.strings.glass_phone_show_results),
                    { sheet = null },
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    primary = true,
                )
            }
        }
    }
}
