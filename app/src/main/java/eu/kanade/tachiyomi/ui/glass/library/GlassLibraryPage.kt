package eu.kanade.tachiyomi.ui.glass.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import eu.kanade.presentation.glass.GlassChip
import eu.kanade.presentation.glass.GlassIcon
import eu.kanade.tachiyomi.ui.glass.FilterGroup
import eu.kanade.tachiyomi.ui.glass.GlassFilterScaffold
import eu.kanade.tachiyomi.ui.glass.GlassSearchField
import eu.kanade.tachiyomi.ui.glass.GlassSort
import eu.kanade.tachiyomi.ui.glass.GlassTitle
import eu.kanade.tachiyomi.ui.glass.GlassTitlesModel
import eu.kanade.tachiyomi.ui.glass.Here
import eu.kanade.tachiyomi.ui.glass.LibraryView
import eu.kanade.tachiyomi.ui.glass.ListFilter
import eu.kanade.tachiyomi.ui.glass.TagChip
import eu.kanade.tachiyomi.ui.glass.TagState
import eu.kanade.tachiyomi.ui.glass.TitleFilters
import eu.kanade.tachiyomi.ui.glass.discover.Length
import eu.kanade.tachiyomi.ui.glass.lengthLabel
import eu.kanade.tachiyomi.ui.glass.openTitle
import eu.kanade.tachiyomi.ui.glass.statusLabel
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Canvas `Learner-2.dc.html` without the FOR ME row and the known meters, filters in the Browse sidebar
 * ([GlassFilterScaffold]). State lives in [GlassTitlesModel].
 */
@Composable
fun GlassLibraryPage(model: GlassTitlesModel, navigator: Navigator) {
    val titles by model.titles.collectAsStateWithLifecycle()
    val all = titles.orEmpty()
    val query = model.query
    val f = model.filters
    val sort = model.sort
    // shown + faceted counts come from the model (off the main thread)
    val v = model.library.collectAsStateWithLifecycle().value ?: LibraryView()
    val shown = v.shown
    val set: (TitleFilters) -> Unit = { model.filters = it }

    GlassFilterScaffold(
        panel = {
            FilterGroup(stringResource(MR.strings.glass_anilist)) {
                ListFilter.entries.forEach { s ->
                    GlassChip(
                        filterLabel(s),
                        selected = f.status == s,
                        onClick = { set(f.copy(status = s)) },
                        count = (v.statusCounts[s] ?: 0).toString(),
                    )
                }
            }
            FilterGroup(stringResource(MR.strings.glass_on_tablet)) {
                Here.entries.forEach { h ->
                    GlassChip(
                        hereLabel(h),
                        selected = f.here == h,
                        onClick = { set(f.copy(here = if (f.here == h) null else h)) },
                        count = (v.hereCounts[h] ?: 0).toString(),
                    )
                }
            }
            FilterGroup(stringResource(MR.strings.glass_sort)) {
                GlassSort.entries.forEach { s ->
                    GlassChip(sortLabel(s), selected = sort == s, onClick = { model.sort = s })
                }
            }
            if (v.genres.isNotEmpty()) {
                FilterGroup(stringResource(MR.strings.glass_discover_genres)) {
                    v.genres.forEach { (g, n) ->
                        GlassChip(g, selected = g in f.genres, onClick = { set(f.toggleGenre(g)) }, count = n.toString())
                    }
                }
            }
            FilterGroup(stringResource(MR.strings.glass_discover_status)) {
                listOf("FINISHED" to MR.strings.glass_discover_finished, "RELEASING" to MR.strings.glass_discover_releasing)
                    .forEach { (s, label) ->
                        GlassChip(
                            stringResource(label),
                            selected = f.published == s,
                            onClick = { set(f.copy(published = if (f.published == s) null else s)) },
                            count = (v.publishedCounts[s] ?: 0).toString(),
                        )
                    }
            }
            FilterGroup(stringResource(MR.strings.glass_discover_length)) {
                Length.entries.forEach { l ->
                    GlassChip(
                        stringResource(lengthLabel(l)),
                        selected = f.length == l,
                        onClick = { set(f.copy(length = if (f.length == l) null else l)) },
                        count = (v.lengthCounts[l] ?: 0).toString(),
                    )
                }
            }
            if (v.tags.isNotEmpty()) {
                FilterGroup(stringResource(MR.strings.glass_discover_tags)) {
                    v.tags.forEach { (name, n) ->
                        val state = when (name) {
                            in f.tagsIn -> TagState.IN
                            in f.tagsOut -> TagState.OUT
                            else -> TagState.OFF
                        }
                        TagChip(name, state, n) { set(f.cycleTag(name)) }
                    }
                }
                Text(stringResource(MR.strings.glass_discover_tags_hint), color = Glass.secondary, fontSize = Glass.caption, lineHeight = 16.sp)
            }
        },
        // next to the "Filters" heading: the panel is long now
        headerAction = {
            if (f != TitleFilters() || sort != GlassSort.LAST_READ) {
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
                        .padding(horizontal = 4.dp, vertical = 10.dp),
                )
            }
        },
    ) { filters ->
        Column(
            Modifier.fillMaxSize().padding(top = 24.dp, end = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(MR.strings.glass_library), fontSize = Glass.title, fontWeight = FontWeight.SemiBold)
                GlassSearchField(
                    value = query,
                    onValueChange = { model.query = it },
                    placeholder = stringResource(MR.strings.glass_search, all.size),
                    modifier = Modifier.weight(1f),
                )
            }
            Text(stringResource(MR.strings.glass_discover_count, shown.size), color = Glass.secondary, fontSize = Glass.body, maxLines = 1)
            filters()
            // Adaptive 120 + 18 gap: 6 columns at 1280dp landscape (852dp right of the sidebar), 5 in portrait
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
