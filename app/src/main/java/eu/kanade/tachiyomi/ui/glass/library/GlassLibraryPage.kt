package eu.kanade.tachiyomi.ui.glass.library

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.Navigator
import coil3.compose.AsyncImage
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassChip
import eu.kanade.presentation.glass.GlassIcon
import eu.kanade.tachiyomi.ui.glass.GlassSort
import eu.kanade.tachiyomi.ui.glass.GlassTitle
import eu.kanade.tachiyomi.ui.glass.GlassTitles
import eu.kanade.tachiyomi.ui.glass.GlassTitlesModel
import eu.kanade.tachiyomi.ui.glass.Here
import eu.kanade.tachiyomi.ui.glass.ListFilter
import eu.kanade.tachiyomi.ui.glass.openTitle
import eu.kanade.tachiyomi.ui.glass.statusLabel
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/** Canvas `Learner-2.dc.html` without the FOR ME row and the known meters. State lives in [GlassTitlesModel]. */
@Composable
fun GlassLibraryPage(model: GlassTitlesModel, navigator: Navigator) {
    val titles by model.titles.collectAsStateWithLifecycle()
    val all = titles.orEmpty()
    val query = model.query
    val status = model.status
    val here = model.here
    val sort = model.sort

    // counts: each row counts within the filters to its left (search → AniList → tablet)
    val searched = remember(all, query) { all.filter { GlassTitles.matchesQuery(it, query) } }
    val statusCounts = remember(searched) { ListFilter.entries.associateWith { f -> searched.count { f.matches(it) } } }
    val hereCounts = remember(searched, status) {
        searched.filter { status.matches(it) }.groupingBy { GlassTitles.here(it) }.eachCount()
    }
    val shown = remember(all, query, status, here, sort) {
        GlassTitles.sort(GlassTitles.filter(all, query, status, here), sort)
    }

    Column(
        Modifier.fillMaxSize().padding(start = 108.dp, top = 24.dp, end = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(MR.strings.glass_library), fontSize = Glass.title, fontWeight = FontWeight.SemiBold)
            SearchField(
                value = query,
                onValueChange = { model.query = it },
                placeholder = stringResource(MR.strings.glass_search, all.size),
                modifier = Modifier.weight(1f),
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RowLabel(stringResource(MR.strings.glass_anilist))
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(ListFilter.ALL, ListFilter.READING, ListFilter.PLANNING).forEach { f ->
                    GlassChip(
                        filterLabel(f),
                        selected = status == f,
                        onClick = { model.status = f },
                        count = statusCounts[f].toString(),
                    )
                }
                MoreChip(status, statusCounts) { model.status = it }
            }
            SortButton(sort) { model.sort = it }
        }

        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RowLabel(stringResource(MR.strings.glass_on_tablet))
            Here.entries.forEach { h ->
                GlassChip(
                    hereLabel(h),
                    selected = here == h,
                    onClick = { model.here = if (here == h) null else h },
                    count = (hereCounts[h] ?: 0).toString(),
                )
            }
        }

        // Adaptive 120: 8 columns at 1280dp landscape (1140dp of grid), 4 in portrait
        LazyVerticalGrid(
            columns = GridCells.Adaptive(120.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(shown, key = { it.mediaId }, contentType = { 0 }) { t ->
                Cell(t, onClick = { openTitle(navigator, t) })
            }
        }
    }
}

@Composable
private fun RowLabel(text: String) {
    Text(
        text,
        color = Glass.secondary,
        fontSize = Glass.caption,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 2.sp,
        maxLines = 1,
        modifier = Modifier.width(116.dp),
    )
}

@Composable
private fun filterLabel(f: ListFilter): String = when (f) {
    ListFilter.ALL -> stringResource(MR.strings.glass_all)
    ListFilter.READING -> stringResource(MR.strings.glass_status_current)
    else -> statusLabel(f.statuses.first())
}

@Composable
private fun hereLabel(h: Here): String = when (h) {
    Here.EVERY -> stringResource(MR.strings.glass_here_every)
    Here.SOME -> stringResource(MR.strings.glass_here_some)
    Here.NOTHING -> stringResource(MR.strings.glass_here_nothing)
}

@Composable
private fun sortLabel(s: GlassSort): String = when (s) {
    GlassSort.LAST_READ -> stringResource(MR.strings.glass_sort_last_read)
    GlassSort.TITLE -> stringResource(MR.strings.glass_sort_title)
    GlassSort.VOLUMES_HERE -> stringResource(MR.strings.glass_sort_volumes_here)
}

/** "More ▾": Completed / Paused / Dropped; shows the picked one when it is active. */
@Composable
private fun MoreChip(status: ListFilter, counts: Map<ListFilter, Int>, onPick: (ListFilter) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val active = status in ListFilter.more
    Box {
        GlassChip(
            if (active) filterLabel(status) else stringResource(MR.strings.glass_more),
            selected = active,
            onClick = { open = true },
            count = if (active) counts[status].toString() else null,
            icon = "chevDown",
        )
        GlassMenu(open, onDismiss = { open = false }) {
            ListFilter.more.forEach { f ->
                MenuItem("${filterLabel(f)}  ${counts[f] ?: 0}", selected = f == status) {
                    onPick(f)
                    open = false
                }
            }
        }
    }
}

/** Ice text button at the end of the ANILIST row. */
@Composable
private fun SortButton(sort: GlassSort, onPick: (GlassSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .height(Glass.chip)
                .clip(CircleShape)
                .clickable { open = true }
                .padding(start = 14.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(sortLabel(sort), color = Glass.ice, fontSize = Glass.body, fontWeight = FontWeight.SemiBold, maxLines = 1)
            GlassIcon("chevDown", size = 16.dp, tint = Glass.ice)
        }
        GlassMenu(open, onDismiss = { open = false }) {
            GlassSort.entries.forEach { s ->
                MenuItem(sortLabel(s), selected = s == sort) {
                    onPick(s)
                    open = false
                }
            }
        }
    }
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
private fun MenuItem(text: String, selected: Boolean, onClick: () -> Unit) {
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

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier) {
    Row(
        modifier
            .height(Glass.button)
            .clip(CircleShape)
            .background(Glass.panel)
            .border(1.dp, Glass.line, CircleShape)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        GlassIcon("search", size = 18.dp, tint = Glass.secondary)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(placeholder, color = Glass.secondary, fontSize = Glass.label, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = Glass.label),
                cursorBrush = SolidColor(Color.White),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            GlassIcon("close", size = 18.dp, modifier = Modifier.clip(CircleShape).clickable { onValueChange("") })
        }
    }
}

/** Cover with the "↓ here / total" badge and the ice read bar, one-line title. */
@Composable
private fun Cell(t: GlassTitle, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Glass.r10)
    val total = t.entry.volumes
    val badge = when {
        total != null -> "${t.volumesHere} / $total"
        t.volumesHere > 0 -> t.volumesHere.toString()
        else -> null
    }
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
            badge?.let {
                Row(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 6.dp, bottom = 10.dp)
                        .height(24.dp)
                        .clip(CircleShape)
                        .background(Glass.scrim)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    GlassIcon("download", size = 12.dp)
                    Text(it, fontSize = Glass.caption, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
            t.readFraction?.takeIf { it > 0f }?.let {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color.Black.copy(alpha = 0.6f)),
                ) {
                    Box(Modifier.fillMaxWidth(it).fillMaxHeight().background(Glass.ice))
                }
            }
        }
        Text(t.name, fontSize = Glass.body, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
