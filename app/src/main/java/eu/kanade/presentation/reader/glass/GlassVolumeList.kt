package eu.kanade.presentation.reader.glass

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassChip
import eu.kanade.presentation.glass.GlassIcon
import eu.kanade.tachiyomi.ui.glass.title.LocalVolumeCovers
import eu.kanade.tachiyomi.ui.reader.chapter.ReaderChapterItem
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.source.local.isLocal
import java.io.File

enum class VolumeFilter { All, OnTablet, Unread, Bookmarks }

/** The chapters [filter] keeps; [onTablet] is asked only for [VolumeFilter.OnTablet] (it can hit the disk). */
fun <T> List<T>.filterVolumes(filter: VolumeFilter, chapter: (T) -> Chapter, onTablet: (T) -> Boolean): List<T> =
    when (filter) {
        VolumeFilter.All -> this
        VolumeFilter.OnTablet -> filter(onTablet)
        VolumeFilter.Unread -> filter { !chapter(it).read }
        VolumeFilter.Bookmarks -> filter { chapter(it).bookmark }
    }

/** How many chapters each filter chip shows ("All 25", "On tablet 5"). */
fun <T> List<T>.volumeCounts(chapter: (T) -> Chapter, onTablet: (T) -> Boolean): Map<VolumeFilter, Int> =
    VolumeFilter.entries.associateWith { filterVolumes(it, chapter, onTablet).size }

/** The number on a cover: the chapter's own number when the source gave one ("04", "10.5"), else its place in the list. */
fun volumeNumber(chapter: Chapter, index: Int): String {
    if (!chapter.isRecognizedNumber) return "%02d".format(index + 1)
    val n = chapter.chapterNumber
    return if (n % 1.0 == 0.0) "%02d".format(n.toInt()) else n.toString()
}

/**
 * The Glass volume list (mockup A3): "N volumes" and where you are, filter chips with counts, then a 4-column grid of covers:
 * a white check = read, an ice ring = the one you are reading, a phone mark = on the tablet. Tap opens the volume, a long press
 * toggles its bookmark. [chapters] is every chapter of the series, read ones included.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun GlassVolumeList(
    chapters: List<ReaderChapterItem>,
    /** ids of the chapters on the device, worked out once off the main thread */
    tablet: Set<Long>,
    currentPage: String,
    totalPages: Int,
    onClose: () -> Unit,
    onClickChapter: (Chapter) -> Unit,
    onBookmark: (Chapter) -> Unit,
) {
    var filter by remember { mutableStateOf(VolumeFilter.All) }
    val onTablet: (ReaderChapterItem) -> Boolean = { it.chapter.id in tablet }
    val counts = remember(chapters, tablet) { chapters.volumeCounts({ it.chapter }, onTablet) }
    val shown = remember(chapters, filter) { chapters.filterVolumes(filter, { it.chapter }, onTablet) }
    val current = chapters.firstOrNull { it.isCurrent }

    // a local series has its own cover per volume (the title page's); any other shows the series cover
    val covers = remember { mutableStateMapOf<Long, File>() }
    val manga = chapters.firstOrNull()?.manga
    LaunchedEffect(chapters) {
        if (manga?.isLocal() == true) {
            LocalVolumeCovers.load(manga.id, chapters.mapIndexed { i, it -> i to it.chapter }.toMap()) { i, file ->
                covers[chapters[i].chapter.id] = file
            }
        }
    }
    val state = rememberLazyGridState(shown.indexOfFirst { it.isCurrent }.coerceAtLeast(0) / 4 * 4)

    GlassSidePanel(onLeft = false, onClose = onClose) {
        GlassPanelTitle(pluralStringResource(MR.plurals.glass_volumes, chapters.size, chapters.size), onClose)
        if (current != null) {
            Text(
                if (currentPage.isNotEmpty() && totalPages > 0) {
                    stringResource(MR.strings.glass_vol_reading, current.chapter.name, currentPage, totalPages)
                } else {
                    current.chapter.name
                },
                Modifier.padding(bottom = 10.dp),
                color = Glass.secondary,
                fontSize = Glass.caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        FlowRow(Modifier.padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            VolumeFilter.entries.forEach {
                GlassChip(stringResource(it.label()), selected = it == filter, onClick = { filter = it }, count = counts[it]?.toString())
            }
        }
        LazyVerticalGrid(
            GridCells.Fixed(4),
            Modifier.weight(1f),
            state = state,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(shown, key = { _, it -> "glass-volume-${it.chapter.id}" }) { _, item ->
                val chapter = item.chapter
                val number = volumeNumber(chapter, chapters.indexOf(item))
                val ring = item.isCurrent
                val shape = RoundedCornerShape(Glass.r8)
                Column(
                    Modifier.combinedClickable(onLongClick = { onBookmark(chapter) }, onClick = { onClickChapter(chapter) }),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(2f / 3f)
                            .clip(shape)
                            .background(Glass.panel)
                            .border(if (ring) 2.dp else 1.dp, if (ring) Glass.ice else Glass.line, shape),
                    ) {
                        AsyncImage(
                            model = covers[chapter.id] ?: item.manga.asMangaCover(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            alpha = if (chapter.read) 0.55f else 1f,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Box(
                            Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)))),
                        ) {
                            Text(
                                number,
                                Modifier.padding(start = 6.dp, top = 14.dp, bottom = 2.dp),
                                fontFamily = FontFamily.Serif,
                                fontWeight = FontWeight.Bold,
                                fontSize = Glass.heading,
                            )
                        }
                        if (chapter.read) {
                            GlassIcon(
                                "check",
                                Modifier.align(Alignment.TopStart).padding(4.dp).size(20.dp).clip(CircleShape).background(Color.White).padding(3.dp),
                                tint = Color.Black,
                            )
                        }
                        if (chapter.bookmark) {
                            GlassIcon("bookmark", Modifier.align(Alignment.TopEnd).padding(4.dp).size(16.dp), tint = Glass.ice)
                        }
                        if (item.chapter.id in tablet && item.manga.isLocal().not()) {
                            GlassIcon(
                                "phone",
                                Modifier.align(Alignment.BottomEnd).padding(4.dp).size(16.dp).clip(CircleShape).background(Glass.scrim).padding(2.dp),
                            )
                        }
                    }
                    Text(
                        if (ring && currentPage.isNotEmpty()) stringResource(MR.strings.glass_vol_page, currentPage) else stringResource(if (chapter.read) MR.strings.glass_vol_read else MR.strings.glass_rset_unread),
                        color = if (ring) Glass.ice else Glass.secondary,
                        fontSize = Glass.caption,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private fun VolumeFilter.label() = when (this) {
    VolumeFilter.All -> MR.strings.glass_rset_all
    VolumeFilter.OnTablet -> MR.strings.glass_rset_tablet
    VolumeFilter.Unread -> MR.strings.glass_rset_unread
    VolumeFilter.Bookmarks -> MR.strings.glass_rset_bookmarks
}
