package eu.kanade.presentation.reader.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassChip
import eu.kanade.presentation.glass.GlassIcon
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.ui.reader.chapter.ReaderChapterItem
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

enum class VolumeFilter { All, OnTablet, Unread, Bookmarks }

/** The chapters [filter] keeps; [onTablet] is asked only for [VolumeFilter.OnTablet] (it can hit the disk). */
fun <T> List<T>.filterVolumes(filter: VolumeFilter, chapter: (T) -> Chapter, onTablet: (T) -> Boolean): List<T> =
    when (filter) {
        VolumeFilter.All -> this
        VolumeFilter.OnTablet -> filter(onTablet)
        VolumeFilter.Unread -> filter { !chapter(it).read }
        VolumeFilter.Bookmarks -> filter { chapter(it).bookmark }
    }

/** Chapter/volume list (A5): a Glass panel on the left, mirror of the settings; tap loads the chapter, the flag toggles its bookmark. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GlassVolumeList(
    chapters: List<ReaderChapterItem>,
    currentPage: String,
    onClose: () -> Unit,
    onClickChapter: (Chapter) -> Unit,
    onBookmark: (Chapter) -> Unit,
) {
    var filter by remember { mutableStateOf(VolumeFilter.All) }
    val downloadManager: DownloadManager = remember { Injekt.get() }
    val shown = remember(chapters, filter) {
        chapters.filterVolumes(filter, { it.chapter }) {
            it.manga.isLocal() || downloadManager.isChapterDownloaded(
                it.chapter.name, it.chapter.scanlator, it.chapter.url, it.manga.ogTitle, it.manga.source,
            )
        }
    }
    val state = rememberLazyListState(chapters.indexOfFirst { it.isCurrent }.coerceAtLeast(0))

    GlassSidePanel(onLeft = true, onClose = onClose) {
        GlassPanelTitle(stringResource(MR.strings.glass_rset_chapters, chapters.size), onClose)
        FlowRow(Modifier.padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            VolumeFilter.entries.forEach {
                GlassChip(stringResource(it.label()), selected = it == filter, onClick = { filter = it })
            }
        }
        LazyColumn(Modifier.weight(1f), state = state) {
            items(shown, key = { "glass-chapter-${it.chapter.id}" }) { item ->
                val current = item.isCurrent
                val ink = if (current) Color.Black else if (item.chapter.read) Glass.secondary else Color.White
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Glass.r10))
                        .then(if (current) Modifier.background(Color.White) else Modifier)
                        .clickable { onClickChapter(item.chapter) }
                        .padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                        Text(item.chapter.name, color = ink, fontSize = Glass.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        item.chapter.scanlator?.takeIf { it.isNotBlank() }?.let {
                            Text(it, color = ink.copy(alpha = 0.62f), fontSize = Glass.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (current && currentPage.isNotEmpty()) {
                        Text("p. $currentPage", color = ink, fontSize = Glass.caption)
                    }
                    Row(
                        Modifier.size(44.dp).clickable { onBookmark(item.chapter) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        GlassIcon(
                            "bookmark",
                            Modifier.size(18.dp),
                            tint = if (item.chapter.bookmark) Glass.ice else ink.copy(alpha = 0.4f),
                        )
                    }
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
