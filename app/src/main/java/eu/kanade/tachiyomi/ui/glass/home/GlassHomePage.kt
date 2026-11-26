package eu.kanade.tachiyomi.ui.glass.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.Navigator
import coil3.compose.AsyncImage
import eu.kanade.presentation.glass.CoverCard
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.Meter
import eu.kanade.presentation.glass.Pill
import eu.kanade.tachiyomi.data.download.LocalSeriesSync
import eu.kanade.tachiyomi.ui.glass.GlassTitle
import eu.kanade.tachiyomi.ui.glass.GlassTitles
import eu.kanade.tachiyomi.ui.glass.GlassTitlesModel
import eu.kanade.tachiyomi.ui.glass.Hero
import eu.kanade.tachiyomi.ui.glass.openTitle
import eu.kanade.tachiyomi.ui.glass.statusLabel
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.glass.discover.NeedsList
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/** Canvas `Main.dc.html` without the language pills and known meters. */
@Composable
fun GlassHomePage(model: GlassTitlesModel, navigator: Navigator) {
    val titles by model.titles.collectAsStateWithLifecycle()
    val list = titles ?: return
    val hero = remember(list) { GlassTitles.hero(list) }
    val reading = remember(list) { GlassTitles.reading(list) }
    val planning = remember(list) { GlassTitles.planning(list) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().height(470.dp)) {
            hero?.let { Backdrop(it.title) }
            Header(model, navigator)
            if (list.isEmpty()) {
                Box(Modifier.padding(start = 108.dp, top = 160.dp, end = 32.dp)) {
                    NeedsList(stringResource(MR.strings.glass_empty))
                }
            } else {
                hero?.let { HeroText(it, navigator) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Shelf(stringResource(MR.strings.glass_row_reading), reading, navigator)
        Spacer(Modifier.height(24.dp))
        Shelf(stringResource(MR.strings.glass_row_planning), planning, navigator)
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun Header(model: GlassTitlesModel, navigator: Navigator) {
    Row(
        Modifier.padding(start = 108.dp, top = 28.dp, end = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (model.refreshing) {
            Text(stringResource(MR.strings.glass_refreshing), color = Glass.secondary, fontSize = Glass.body)
        } else {
            Pill(stringResource(MR.strings.glass_refresh), onClick = model::refresh, small = true)
        }
        // ponytail: way back to Novels/Anime/Dictionary until the new screens exist
        Pill(stringResource(MR.strings.glass_classic_view), onClick = { navigator.push(HomeScreen) }, small = true)
        // chimahon: a fresh install over an existing folder links its series one by one, paced for AniList
        val adopting by LocalSeriesSync.adopting.collectAsStateWithLifecycle()
        adopting?.let { (done, of) ->
            Text(stringResource(MR.strings.glass_adopting, done, of), color = Glass.secondary, fontSize = Glass.body)
        }
        // chimahon: a failed refresh is in the Needs attention panel (rail), not here
    }
}

/** Banner (or the cover blurred and dimmed when AniList has none) on the right 3/4, gradients, the big cover. */
@Composable
private fun BoxScope.Backdrop(t: GlassTitle) {
    val shape = RoundedCornerShape(Glass.r10)
    val cover = t.entry.coverLarge ?: t.entry.cover
    Box(Modifier.align(Alignment.TopEnd).fillMaxWidth(0.75f).fillMaxHeight().background(Glass.panel)) {
        val banner = t.entry.banner
        AsyncImage(
            model = banner ?: cover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .then(if (banner == null) Modifier.blur(24.dp).alpha(0.5f) else Modifier),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to Glass.page,
                    0.4f to Glass.page.copy(alpha = 0.78f),
                    0.8f to Glass.page.copy(alpha = 0.1f),
                    1f to Glass.page.copy(alpha = 0.1f),
                ),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.6f to Glass.page.copy(alpha = 0f), 1f to Glass.page),
            ),
        )
        AsyncImage(
            model = cover,
            contentDescription = t.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 80.dp, top = 56.dp)
                .size(220.dp, 330.dp)
                .clip(shape)
                .border(1.dp, Glass.line, shape),
        )
    }
}

@Composable
private fun HeroText(hero: Hero, navigator: Navigator) {
    val context = LocalContext.current
    val t = hero.title
    val next = t.next
    Column(
        // end 340: clear of the 220dp cover at 80dp from the right
        Modifier.padding(start = 108.dp, top = 160.dp, end = 340.dp).widthIn(max = 560.dp),
    ) {
        Text(
            t.name,
            fontSize = Glass.hero,
            lineHeight = 48.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(heroLine(hero), color = Glass.secondary, fontSize = Glass.label, modifier = Modifier.padding(top = 8.dp))
        t.readFraction?.let { Meter(it, Modifier.padding(top = 14.dp).width(380.dp), color = Glass.ice) }
        Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (hero.resume && next != null) {
                Pill(
                    stringResource(MR.strings.glass_resume),
                    onClick = { context.startActivity(ReaderActivity.newIntent(context, t.localMangaId, next.chapterId)) },
                    primary = true,
                    icon = "play",
                )
            }
            Pill(stringResource(MR.strings.glass_volumes), onClick = { openTitle(navigator, t) }, primary = !hero.resume)
        }
    }
}

/** "Vol. 3 · page 41 · 2 of 18 volumes read" for a title to resume; else its volume count or list status. */
@Composable
internal fun heroLine(hero: Hero): String {
    val t = hero.title
    val next = t.next
    val total = t.total
    val where = when {
        hero.resume && next != null && next.lastPageRead > 0 ->
            stringResource(MR.strings.glass_hero_vol_page, GlassTitles.volumeLabel(next), next.lastPageRead + 1)
        hero.resume && next != null -> stringResource(MR.strings.glass_hero_vol, GlassTitles.volumeLabel(next))
        total != null -> stringResource(MR.strings.glass_n_volumes, total)
        else -> statusLabel(t.entry.status)
    }
    // the bar below is volume progress, not page progress: the resume line says so
    val read = if (hero.resume && next != null && total != null) {
        " · " + stringResource(MR.strings.glass_hero_volumes_read, t.volumesRead.coerceAtMost(total), total)
    } else {
        ""
    }
    return where + read
}

@Composable
private fun Shelf(name: String, titles: List<GlassTitle>, navigator: Navigator) {
    if (titles.isEmpty()) return
    Column(Modifier.padding(start = 108.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(name, fontSize = Glass.label, fontWeight = FontWeight.SemiBold)
            Text(titles.size.toString(), color = Glass.secondary, fontSize = Glass.body)
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            contentPadding = PaddingValues(end = 32.dp),
        ) {
            items(titles, key = { it.mediaId }) { t ->
                CoverCard(
                    cover = t.entry.cover,
                    title = t.name,
                    onClick = { openTitle(navigator, t) },
                    modifier = Modifier.width(110.dp),
                    progress = t.readFraction,
                )
            }
        }
    }
}
