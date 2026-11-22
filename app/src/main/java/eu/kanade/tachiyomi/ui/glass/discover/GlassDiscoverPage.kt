package eu.kanade.tachiyomi.ui.glass.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.Navigator
import coil3.compose.AsyncImage
import eu.kanade.presentation.glass.CoverCard
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassChip
import eu.kanade.presentation.glass.GlassIcon
import eu.kanade.presentation.glass.Pill
import eu.kanade.tachiyomi.data.anilist.AniListLibrary
import eu.kanade.tachiyomi.data.anilist.AniListLibrary.Entry
import eu.kanade.tachiyomi.ui.glass.FilterGroup
import eu.kanade.tachiyomi.ui.glass.GlassFilterScaffold
import eu.kanade.tachiyomi.ui.glass.GlassSearchField
import eu.kanade.tachiyomi.ui.glass.GlassTitle
import eu.kanade.tachiyomi.ui.glass.TagChip
import eu.kanade.tachiyomi.ui.glass.TagState
import eu.kanade.tachiyomi.ui.glass.lengthLabel
import eu.kanade.tachiyomi.ui.glass.openTitle
import eu.kanade.tachiyomi.ui.setting.SettingsScreen
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Every Discover title opens through here: a title the list cache doesn't know (Browse, recommendations) is first
 * kept in [AniListLibrary.remember], so the title page (keyed by media id, `library.get`) finds it.
 */
fun openDiscoverTitle(navigator: Navigator, entry: Entry) {
    val library = Injekt.get<AniListLibrary>()
    if (library.get(entry.mediaId) == null) library.remember(entry)
    openTitle(navigator, GlassTitle(entry))
}

internal val ON_HOME = setOf("CURRENT", "REPEATING", "PLANNING")

/** Canvas `Discover-D`, `Discover-D-ThisOrThat`, `Browse-2`, without the known meters and the SOURCE group. */
@Composable
fun GlassDiscoverPage(model: GlassDiscoverModel, navigator: Navigator) {
    val entries by model.entries.collectAsStateWithLifecycle()
    val open: (Entry) -> Unit = { openDiscoverTitle(navigator, it) }

    LaunchedEffect(entries) { model.loadRecs() }

    if (!model.loggedIn) {
        Column(Modifier.padding(start = 108.dp, top = 28.dp, end = 32.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Tabs(model)
            NeedsList(stringResource(MR.strings.glass_discover_login), navigator)
        }
        return
    }
    when (model.tab) {
        DiscoverTab.PICK -> PickTab(model, open, navigator)
        DiscoverTab.DUEL -> DuelTab(model, open, navigator)
        DiscoverTab.BROWSE -> BrowseTab(model, open)
    }
}

@Composable
private fun Tabs(model: GlassDiscoverModel, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            DiscoverTab.PICK to MR.strings.glass_discover_pick,
            DiscoverTab.DUEL to MR.strings.glass_discover_duel,
            DiscoverTab.BROWSE to MR.strings.glass_discover_browse,
        ).forEach { (tab, label) ->
            GlassChip(stringResource(label), selected = model.tab == tab, onClick = { model.tab = tab })
        }
    }
}

@Composable
internal fun NeedsList(text: String, navigator: Navigator) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(text, fontSize = Glass.label)
        Pill(
            stringResource(MR.strings.glass_open_tracking),
            onClick = { navigator.push(SettingsScreen(SettingsScreen.Destination.Tracking)) },
            primary = true,
        )
    }
}

@Composable
internal fun ErrorLine(model: GlassDiscoverModel) {
    model.error?.let { Text(stringResource(MR.strings.glass_discover_error, it), color = Glass.secondary, fontSize = Glass.body) }
}

/** Header row of Pick and This or that: tabs, then a secondary line on the right. */
@Composable
private fun Header(model: GlassDiscoverModel, right: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Tabs(model)
        Spacer(Modifier.weight(1f))
        right?.let { Text(it, color = Glass.secondary, fontSize = Glass.body, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

internal fun Entry.name() = NextVolumeRanking.name(this)

@Composable
internal fun reasonText(r: Reason): String = when (r) {
    is Reason.Recommended -> stringResource(MR.strings.glass_discover_why_recs, r.by)
    is Reason.Taste -> if (r.titles.isEmpty()) {
        stringResource(MR.strings.glass_discover_why_taste_plain, r.features.joinToString(" + "))
    } else {
        stringResource(MR.strings.glass_discover_why_taste, r.features.joinToString(" + "), r.titles.joinToString(", "))
    }
    is Reason.Finished -> stringResource(MR.strings.glass_discover_why_finished, r.volumes)
    is Reason.Score -> stringResource(MR.strings.glass_discover_why_score, r.averageScore)
}

/** "Comedy · Romance · 26 vols · Finished" */
@Composable
internal fun metaLine(e: Entry): String = listOfNotNull(
    e.genres.take(3).joinToString(" · ").takeIf { it.isNotEmpty() },
    e.volumes?.let { stringResource(MR.strings.glass_discover_vols, it) },
    when (e.mediaStatus) {
        "FINISHED" -> stringResource(MR.strings.glass_discover_finished)
        "RELEASING" -> stringResource(MR.strings.glass_discover_releasing)
        else -> null
    },
).joinToString(" · ")

@Composable
private fun Score(avg: Int?, size: Int = 16) {
    if (avg == null) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        GlassIcon("star", size = size.dp, tint = Glass.ice)
        Text("$avg%", color = Glass.ice, fontSize = size.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun HomeButton(model: GlassDiscoverModel, entry: Entry, primary: Boolean = true) {
    if (entry.status in ON_HOME) {
        Pill(stringResource(MR.strings.glass_discover_on_home), onClick = {}, icon = "check")
    } else {
        Pill(
            stringResource(MR.strings.glass_discover_add_home),
            onClick = { model.addHome(entry) },
            primary = primary && entry.mediaId !in model.adding,
            icon = "plus",
        )
    }
}

@Composable
private fun Cover(e: Entry, width: Int, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Glass.r12)
    AsyncImage(
        model = e.coverLarge ?: e.cover,
        contentDescription = e.name(),
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .width(width.dp)
            .aspectRatio(2f / 3f)
            .clip(shape)
            .background(Glass.panel)
            .border(1.dp, Glass.line, shape)
            .clickable(onClick = onClick),
    )
}

// Pick for me

@Composable
private fun PickTab(model: GlassDiscoverModel, open: (Entry) -> Unit, navigator: Navigator) {
    val ranking by model.ranking.collectAsStateWithLifecycle()
    val hidden by model.hidden.collectAsStateWithLifecycle()
    val recommendedBy by model.recommendedBy.collectAsStateWithLifecycle()
    LaunchedEffect(ranking, hidden, model.pickId) { model.ensurePick() }
    val nv = ranking
    val pick = nv?.ranked?.firstOrNull { it.mediaId == model.pickId }

    Box(Modifier.fillMaxSize()) {
        pick?.let { Backdrop(it.entry) }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 108.dp, top = 28.dp, end = 32.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Header(
                model,
                nv?.takeIf { pick != null }?.let {
                    stringResource(MR.strings.glass_discover_pick_count, model.pickCount, it.completed, it.dropped)
                },
            )
            when {
                nv == null -> Unit
                nv.ranked.isEmpty() && model.entries.value.isNullOrEmpty() ->
                    NeedsList(stringResource(MR.strings.glass_empty), navigator)
                pick == null -> Text(stringResource(MR.strings.glass_discover_nothing), fontSize = Glass.label)
                else -> {
                    PickMain(model, pick, nv.why(pick, recommendedBy), open)
                    NextPicks(nv.ranked.filter { it.mediaId !in hidden && it.mediaId != pick.mediaId }.take(9), open)
                }
            }
            ErrorLine(model)
        }
    }
}

@Composable
private fun Backdrop(e: Entry) {
    Box(Modifier.fillMaxSize()) {
        AsyncImage(
            model = e.banner ?: e.coverLarge ?: e.cover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().alpha(0.5f),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Glass.page.copy(alpha = 0.6f),
                    0.55f to Glass.page.copy(alpha = 0.85f),
                    1f to Glass.page,
                ),
            ),
        )
    }
}

@Composable
private fun PickMain(model: GlassDiscoverModel, pick: Pick, why: List<Reason>, open: (Entry) -> Unit) {
    val e = pick.entry
    Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
        Cover(e, 260) { open(e) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                e.name(),
                fontSize = Glass.hero,
                lineHeight = 48.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Score(e.averageScore)
                Text(
                    stringResource(MR.strings.glass_discover_match, pick.match),
                    color = Color.White,
                    fontSize = Glass.label,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(metaLine(e), color = Glass.secondary, fontSize = Glass.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (why.isNotEmpty()) {
                Column(
                    Modifier
                        .padding(top = 8.dp)
                        .widthIn(max = 640.dp)
                        .clip(RoundedCornerShape(Glass.r16))
                        .background(Glass.page.copy(alpha = 0.72f))
                        .border(1.dp, Glass.line, RoundedCornerShape(Glass.r16))
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(stringResource(MR.strings.glass_discover_why), fontSize = Glass.label, fontWeight = FontWeight.SemiBold)
                    why.forEach { r ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.padding(top = 7.dp).size(6.dp).clip(CircleShape).background(Glass.ice))
                            Text(reasonText(r), color = Color.White.copy(alpha = 0.85f), fontSize = Glass.body, lineHeight = 20.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HomeButton(model, e)
                Pill(stringResource(MR.strings.glass_discover_not_for_me), onClick = { model.notForMe(e.mediaId) })
                Pill(stringResource(MR.strings.glass_discover_skip), onClick = model::next)
            }
        }
    }
}

@Composable
private fun NextPicks(next: List<Pick>, open: (Entry) -> Unit) {
    if (next.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(MR.strings.glass_discover_next_picks), fontSize = Glass.label, fontWeight = FontWeight.SemiBold)
            Text(stringResource(MR.strings.glass_discover_best_first), color = Glass.secondary, fontSize = Glass.body)
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            items(next, key = { it.mediaId }) { p ->
                CoverCard(
                    cover = p.entry.coverLarge ?: p.entry.cover,
                    title = p.entry.name(),
                    onClick = { open(p.entry) },
                    modifier = Modifier.width(110.dp),
                    badge = stringResource(MR.strings.glass_discover_match, p.match),
                )
            }
        }
    }
}

// This or that

@Composable
private fun DuelTab(model: GlassDiscoverModel, open: (Entry) -> Unit, navigator: Navigator) {
    val ranking by model.ranking.collectAsStateWithLifecycle()
    LaunchedEffect(ranking) { if (model.duel == null) model.startDuel() }
    val d = model.duel
    val pair = d?.pair

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 108.dp, top = 28.dp, end = 32.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Header(
            model,
            when {
                d != null && pair != null ->
                    stringResource(MR.strings.glass_discover_duel_progress, d.done + 1, d.total, d.all.size)
                d?.winner != null -> stringResource(MR.strings.glass_discover_you_chose)
                else -> null
            },
        )
        val winner = d?.winner
        when {
            ranking == null -> Unit
            model.entries.value.isNullOrEmpty() -> NeedsList(stringResource(MR.strings.glass_empty), navigator)
            pair != null -> {
                Text(stringResource(MR.strings.glass_discover_which), fontSize = Glass.heading, fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth().height(466.dp), verticalAlignment = Alignment.CenterVertically) {
                    DuelCard(pair.first, Modifier.weight(1f), open) { model.choose(first = true) }
                    Box(
                        Modifier
                            .padding(horizontal = 4.dp)
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Glass.page)
                            .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("VS", color = Glass.ice, fontSize = Glass.body, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }
                    DuelCard(pair.second, Modifier.weight(1f), open) { model.choose(first = false) }
                }
            }
            winner != null -> Winner(model, winner, open)
            else -> Text(stringResource(MR.strings.glass_discover_too_few), fontSize = Glass.label)
        }
        if (d != null && d.all.size >= 2) Strip(d)
        ErrorLine(model)
    }
}

@Composable
private fun DuelCard(p: Pick, modifier: Modifier, open: (Entry) -> Unit, onKeep: () -> Unit) {
    val e = p.entry
    val shape = RoundedCornerShape(Glass.r16)
    Box(modifier.fillMaxHeight().clip(shape).background(Glass.panel).border(1.dp, Glass.line, shape)) {
        AsyncImage(
            model = e.coverLarge ?: e.cover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().alpha(0.25f).blur(2.dp),
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Glass.page.copy(alpha = 0.4f), Glass.page))))
        Row(Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Cover(e, 190) { open(e) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(MR.strings.glass_discover_match, p.match), color = Glass.secondary, fontSize = Glass.caption)
                Text(
                    e.name(),
                    fontSize = Glass.heading,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Score(e.averageScore, size = 14)
                Text(metaLine(e), color = Glass.secondary, fontSize = Glass.body)
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(24.dp)
                .fillMaxWidth()
                .height(Glass.button)
                .clip(CircleShape)
                .background(Color.White)
                .clickable(onClick = onKeep),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(MR.strings.glass_discover_keep), color = Color.Black, fontSize = Glass.label, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun Winner(model: GlassDiscoverModel, p: Pick, open: (Entry) -> Unit) {
    val e = p.entry
    Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
        Cover(e, 260) { open(e) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(e.name(), fontSize = Glass.hero, lineHeight = 48.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Score(e.averageScore)
                Text(metaLine(e), color = Glass.secondary, fontSize = Glass.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Pill(stringResource(MR.strings.glass_discover_open), onClick = { open(e) }, primary = true)
                HomeButton(model, e, primary = false)
                Pill(stringResource(MR.strings.glass_discover_again), onClick = model::startDuel)
            }
        }
    }
}

/** The 8 titles: ice outline = now, crossed = out, dim = waiting. */
@Composable
private fun Strip(d: Duel) {
    val now = setOfNotNull(d.pair?.first?.mediaId, d.pair?.second?.mediaId, d.winner?.mediaId)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(MR.strings.glass_discover_duel_hint), color = Glass.secondary, fontSize = Glass.body)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            d.all.forEach { p ->
                val shape = RoundedCornerShape(Glass.r8)
                val out = p.mediaId in d.out
                Box(
                    Modifier
                        .size(60.dp, 90.dp)
                        .clip(shape)
                        .border(if (p.mediaId in now) 2.dp else 1.dp, if (p.mediaId in now) Glass.ice else Glass.line, shape),
                    contentAlignment = Alignment.Center,
                ) {
                    AsyncImage(
                        model = p.entry.cover,
                        contentDescription = p.entry.name(),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(if (p.mediaId in now) 1f else if (out) 0.25f else 0.55f),
                    )
                    if (out) GlassIcon("close", size = 24.dp)
                }
            }
        }
    }
}

// Browse

@Composable
private fun BrowseTab(model: GlassDiscoverModel, open: (Entry) -> Unit) {
    LaunchedEffect(Unit) { model.startBrowse() }
    val b = model.browse
    val f = model.filters
    val grid = rememberLazyGridState()
    LaunchedEffect(grid, b.page, b.loading) {
        snapshotFlow { (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) to grid.layoutInfo.totalItemsCount }
            .collect { (last, total) -> if (last >= total - 12) model.loadMore() }
    }
    // the tag hint list = the most frequent tags of the current results
    val hints = remember(b.entries, f.tagsIn, f.tagsOut) { tagHints(b, f) }

    GlassFilterScaffold(
        panel = { BrowseGroups(model, hints) },
        headerAction = {
            if (!f.isEmpty) Pill(stringResource(MR.strings.glass_discover_clear), onClick = { model.filters = BrowseFilters() }, small = true)
        },
    ) { filters ->
        Column(
            Modifier.fillMaxSize().padding(top = 24.dp, end = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Tabs(model)
                GlassSearchField(
                    f.search,
                    { model.filters = model.filters.copy(search = it) },
                    stringResource(MR.strings.glass_discover_search),
                    Modifier.weight(1f),
                )
            }
            Text(summary(b, f), color = Glass.secondary, fontSize = Glass.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ErrorLine(model)
            filters()
            if (b.entries.isEmpty() && !b.loading && b.page > 0 && model.error == null) {
                Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(MR.strings.glass_discover_no_results), fontSize = Glass.label)
                    Pill(stringResource(MR.strings.glass_discover_clear_filters), onClick = { model.filters = BrowseFilters() })
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(120.dp),
                    state = grid,
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                    contentPadding = PaddingValues(bottom = 32.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(b.entries, key = { it.mediaId }) { e -> BrowseCell(e) { open(e) } }
                }
            }
        }
    }
}

@Composable
internal fun summary(b: BrowseState, f: BrowseFilters): String {
    val count = when {
        b.loading && b.entries.isEmpty() -> stringResource(MR.strings.glass_discover_loading)
        b.hasNext -> stringResource(MR.strings.glass_discover_count_more, b.entries.size)
        else -> stringResource(MR.strings.glass_discover_count, b.entries.size)
    }
    val status = when (f.status) {
        "FINISHED" -> stringResource(MR.strings.glass_discover_finished)
        "RELEASING" -> stringResource(MR.strings.glass_discover_releasing)
        else -> null
    }
    val length = f.length?.let { stringResource(lengthLabel(it)) }
    val myList = f.myList?.let { stringResource(myListLabel(it)) }
    val without = f.tagsOut.map { stringResource(MR.strings.glass_discover_without, it) }
    return (listOf(count) + f.genres + listOfNotNull(status, length, myList) + f.tagsIn + without).joinToString(" · ")
}

internal fun myListLabel(m: MyList) = when (m) {
    MyList.HIDE -> MR.strings.glass_discover_hide_list
    MyList.ONLY_PLANNING -> MR.strings.glass_discover_only_planning
}

/** A Browse filter group: one panel section on the tablet, one chip + sheet on the phone. */
internal enum class BrowseFacet { GENRES, STATUS, LENGTH, MY_LIST, TAGS }

/** The Browse panel groups; the "Filters" heading and Clear come from [GlassFilterScaffold]. */
@Composable
private fun BrowseGroups(model: GlassDiscoverModel, hints: List<Pair<String, Int>>) {
    listOf(
        BrowseFacet.GENRES to MR.strings.glass_discover_genres,
        BrowseFacet.STATUS to MR.strings.glass_discover_status,
        BrowseFacet.LENGTH to MR.strings.glass_discover_length,
        BrowseFacet.MY_LIST to MR.strings.glass_discover_my_list,
        BrowseFacet.TAGS to MR.strings.glass_discover_tags,
    ).forEach { (facet, name) -> FilterGroup(stringResource(name)) { BrowseChips(model, hints, facet) } }
    Text(stringResource(MR.strings.glass_discover_tags_hint), color = Glass.secondary, fontSize = Glass.caption, lineHeight = 16.sp)
}

/** The chips of one group, for a FlowRow. */
@Composable
internal fun BrowseChips(model: GlassDiscoverModel, hints: List<Pair<String, Int>>, facet: BrowseFacet) {
    val f = model.filters
    val set: (BrowseFilters) -> Unit = { model.filters = it }
    when (facet) {
        BrowseFacet.GENRES -> BrowseFilters.GENRES.forEach { g ->
            GlassChip(g, selected = g in f.genres, onClick = {
                set(f.copy(genres = if (g in f.genres) f.genres - g else f.genres + g))
            })
        }
        BrowseFacet.STATUS ->
            listOf("FINISHED" to MR.strings.glass_discover_finished, "RELEASING" to MR.strings.glass_discover_releasing)
                .forEach { (s, label) ->
                    GlassChip(stringResource(label), selected = f.status == s, onClick = {
                        set(f.copy(status = if (f.status == s) null else s))
                    })
                }
        BrowseFacet.LENGTH -> Length.entries.forEach { l ->
            GlassChip(stringResource(lengthLabel(l)), selected = f.length == l, onClick = {
                set(f.copy(length = if (f.length == l) null else l))
            })
        }
        BrowseFacet.MY_LIST -> MyList.entries.forEach { m ->
            GlassChip(stringResource(myListLabel(m)), selected = f.myList == m, onClick = {
                set(f.copy(myList = if (f.myList == m) null else m))
            })
        }
        BrowseFacet.TAGS -> {
            f.tagsIn.forEach { TagChip(it, TagState.IN, null) { set(f.cycleTag(it)) } }
            f.tagsOut.forEach { TagChip(it, TagState.OUT, null) { set(f.cycleTag(it)) } }
            hints.forEach { (name, count) -> TagChip(name, TagState.OFF, count) { set(f.cycleTag(name)) } }
        }
    }
}

/** The most frequent tags of the current results that are not chosen yet: the tag hint list. */
internal fun tagHints(b: BrowseState, f: BrowseFilters): List<Pair<String, Int>> =
    b.entries.flatMap { e -> e.tags.filter { it.rank >= 60 && !it.spoiler }.map { it.name } }
        .groupingBy { it }.eachCount()
        .filterKeys { it !in f.tagsIn && it !in f.tagsOut }
        .entries.sortedByDescending { it.value }
        .take(8)
        .map { it.key to it.value }

/** Cover with the AniList score (top) and volume count (bottom), title, strongest non-spoiler tag. */
@Composable
internal fun BrowseCell(e: Entry, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Glass.r10)
    val tag = e.tags.filter { !it.spoiler }.maxByOrNull { it.rank }
    Column(Modifier.clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(shape)
                .background(Glass.panel)
                .border(1.dp, Glass.line, shape),
        ) {
            AsyncImage(
                model = e.coverLarge ?: e.cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            e.averageScore?.let {
                Badge(Modifier.align(Alignment.TopStart)) { Score(it, size = 12) }
            }
            e.volumes?.let {
                Badge(Modifier.align(Alignment.BottomStart)) {
                    Text(stringResource(MR.strings.glass_discover_vols, it), fontSize = Glass.caption, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Text(e.name(), fontSize = Glass.body, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        tag?.let {
            Text("${it.name} ${it.rank}%", color = Glass.ice, fontSize = Glass.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Badge(modifier: Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .padding(6.dp)
            .heightIn(min = 24.dp)
            .clip(CircleShape)
            .background(Glass.scrim)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}
