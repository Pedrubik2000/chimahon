package eu.kanade.tachiyomi.ui.glass.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.Navigator
import coil3.compose.AsyncImage
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassRoundButton
import eu.kanade.presentation.glass.GlassChip
import eu.kanade.presentation.glass.GlassIcon
import eu.kanade.presentation.glass.GlassPhoneBarRoom
import eu.kanade.presentation.glass.Meter
import eu.kanade.presentation.glass.Pill
import eu.kanade.presentation.util.glassDevice
import eu.kanade.tachiyomi.ui.glass.GlassSearchField
import eu.kanade.tachiyomi.ui.glass.GlassSort
import eu.kanade.tachiyomi.ui.glass.GlassTitle
import eu.kanade.tachiyomi.ui.glass.GlassTitlesModel
import eu.kanade.tachiyomi.ui.glass.Here
import eu.kanade.tachiyomi.ui.glass.LibraryView
import eu.kanade.tachiyomi.ui.glass.ListFilter
import eu.kanade.tachiyomi.ui.glass.TitleFilters
import eu.kanade.tachiyomi.ui.glass.openTitle
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Canvas `phone-lib-a` (Library): the tablet Library for one thumb. One scrolling grid: heading with sort and search,
 * AniList list pills, "on this phone" + Filters chips, then 3 columns of covers. Same model as the tablet page; the
 * groups the tablet sidebar has past lists and Here live in the Filters sheet ([LibraryMoreFilters]).
 * Not built (no data): the "Readable for me" slider and the "known" meters of the mockup.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassPhoneLibraryPage(model: GlassTitlesModel, navigator: Navigator) {
    val titles by model.titles.collectAsStateWithLifecycle()
    val all = titles.orEmpty()
    val v = model.library.collectAsStateWithLifecycle().value ?: LibraryView()
    val f = model.filters
    val set: (TitleFilters) -> Unit = { model.filters = it }
    var searching by remember { mutableStateOf(model.query.isNotEmpty()) }
    var sortMenu by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(false) }
    val full: (androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> GridItemSpan) = { GridItemSpan(maxLineSpan) }
    // Filters chip lights up for any filter that only lives in the sheet
    val sheetOn = f.copy(status = ListFilter.ALL, here = null) != TitleFilters()
    val device = glassDevice()

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = GlassPhoneBarRoom),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "heading", span = full) {
            Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(MR.strings.glass_library),
                    fontSize = Glass.title,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    GlassRoundButton("list", { sortMenu = true }, outlined = true, iconSize = 20.dp)
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }, modifier = Modifier.background(Glass.panel)) {
                        GlassSort.entries.forEach { s ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        sortLabel(s),
                                        color = if (model.sort == s) Glass.ice else Color.White,
                                        fontWeight = if (model.sort == s) FontWeight.SemiBold else FontWeight.Normal,
                                    )
                                },
                                onClick = {
                                    model.sort = s
                                    sortMenu = false
                                },
                            )
                        }
                    }
                }
                Box(Modifier.padding(start = 8.dp)) {
                    GlassRoundButton("search", {
                        searching = !searching
                        if (!searching) model.query = ""
                    }, on = searching, outlined = true, iconSize = 20.dp)
                }
            }
        }
        if (searching) {
            item(key = "search", span = full) {
                GlassSearchField(
                    value = model.query,
                    onValueChange = { model.query = it },
                    placeholder = stringResource(MR.strings.glass_search, all.size),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item(key = "lists", span = full) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ListFilter.entries.forEach { s ->
                    GlassChip(
                        filterLabel(s),
                        selected = f.status == s,
                        onClick = { set(f.copy(status = s)) },
                        count = (v.statusCounts[s] ?: 0).toString(),
                    )
                }
            }
        }
        item(key = "chips", span = full) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Here.entries.forEach { h ->
                    GlassChip(
                        hereLabel(h),
                        selected = f.here == h,
                        onClick = { set(f.copy(here = if (f.here == h) null else h)) },
                        count = (v.hereCounts[h] ?: 0).toString(),
                    )
                }
                GlassChip(
                    stringResource(MR.strings.glass_discover_filters),
                    selected = sheetOn,
                    onClick = { sheet = true },
                    icon = "chevDown",
                )
                if (f != TitleFilters() || model.sort != GlassSort.LAST_READ) {
                    Text(
                        stringResource(MR.strings.glass_discover_clear),
                        color = Glass.ice,
                        fontSize = Glass.body,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable {
                                model.filters = TitleFilters()
                                model.sort = GlassSort.LAST_READ
                            }
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                    )
                }
            }
        }
        items(v.shown, key = { it.mediaId }, contentType = { 0 }) { t ->
            PhoneCell(t, device) { openTitle(navigator, t) }
        }
    }

    if (sheet) {
        ModalBottomSheet(onDismissRequest = { sheet = false }, containerColor = Glass.panel, contentColor = Color.White) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(MR.strings.glass_discover_filters), fontSize = Glass.heading, fontWeight = FontWeight.Bold)
                LibraryMoreFilters(v, f, set)
                Pill(
                    stringResource(MR.strings.glass_phone_show_results),
                    { sheet = false },
                    Modifier.fillMaxWidth(),
                    primary = true,
                )
            }
        }
    }
}

/** Cover, one-line title, ice read bar, "N/M on this phone". */
@Composable
private fun PhoneCell(t: GlassTitle, device: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Glass.r10)
    val total = t.total
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
                model = t.entry.cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(t.name, fontSize = Glass.body, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        t.readFraction?.takeIf { it > 0f }?.let { Meter(it, Modifier.fillMaxWidth(), Glass.ice) }
        if (total != null) {
            Text(
                stringResource(MR.strings.glass_phone_library_here, t.volumesHere, total, device),
                color = Glass.secondary,
                fontSize = Glass.caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
