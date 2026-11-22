package eu.kanade.tachiyomi.ui.glass.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import eu.kanade.presentation.glass.GlassIcon
import eu.kanade.presentation.glass.GlassPhoneBarRoom
import eu.kanade.presentation.glass.Meter
import eu.kanade.presentation.glass.Pill
import eu.kanade.tachiyomi.data.download.LocalSeriesSync
import eu.kanade.tachiyomi.ui.glass.GlassTitle
import eu.kanade.tachiyomi.ui.glass.GlassTitles
import eu.kanade.tachiyomi.ui.glass.GlassTitlesModel
import eu.kanade.tachiyomi.ui.glass.Hero
import eu.kanade.tachiyomi.ui.glass.openTitle
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.setting.SettingsScreen
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Canvas `PhoneHero-2`: a pager over the titles with a volume to resume (the next one peeks at the right edge),
 * stat chips, then the Reading and Plan to read rows. [problems] = how many things need attention (0 = no pill).
 */
@Composable
fun GlassPhoneHomePage(
    model: GlassTitlesModel,
    navigator: Navigator,
    problems: Int,
    onProblems: () -> Unit,
    onSeeAll: () -> Unit,
) {
    val titles by model.titles.collectAsStateWithLifecycle()
    val list = titles ?: return
    val slides = remember(list) {
        GlassTitles.heroes(list).map { Hero(it, resume = true) }.ifEmpty { listOfNotNull(GlassTitles.hero(list)) }
    }
    val reading = remember(list) { GlassTitles.reading(list) }
    val planning = remember(list) { GlassTitles.planning(list) }
    val pager = rememberPagerState { slides.size }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = GlassPhoneBarRoom)) {
        Box(Modifier.fillMaxWidth().height(if (slides.isEmpty()) 64.dp else 520.dp)) {
            if (slides.isNotEmpty()) {
                HorizontalPager(
                    state = pager,
                    // the next title peeks 24dp in at the right edge
                    contentPadding = PaddingValues(end = if (slides.size > 1) 28.dp else 0.dp),
                    pageSpacing = 4.dp,
                    modifier = Modifier.fillMaxSize(),
                ) { i -> Slide(slides[i], first = i == 0, navigator) }
            }
            Row(
                Modifier.fillMaxWidth().height(64.dp).padding(start = 16.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("kumahon", fontSize = Glass.label, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                Spacer(Modifier.weight(1f))
                if (problems > 0) {
                    Pill(stringResource(MR.strings.glass_problems_title), onClick = onProblems, icon = "alert", small = true)
                }
            }
        }
        if (slides.size > 1) {
            Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.Center) {
                slides.forEachIndexed { i, hero ->
                    val on = i == pager.currentPage
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).clickable { scope.launch { pager.animateScrollToPage(i) } },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(if (on) 24.dp else 8.dp, 8.dp)
                                .clip(CircleShape)
                                .background(if (on) Glass.ice else Color.White.copy(alpha = 0.4f)),
                        )
                    }
                }
            }
        } else {
            Spacer(Modifier.height(16.dp))
        }

        if (list.isEmpty()) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(MR.strings.glass_empty), fontSize = Glass.label)
                Pill(
                    stringResource(MR.strings.glass_open_tracking),
                    onClick = { navigator.push(SettingsScreen(SettingsScreen.Destination.Tracking)) },
                    primary = true,
                )
            }
        } else {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Stat(reading.size, stringResource(MR.strings.glass_phone_stat_reading))
                Stat(list.sumOf { it.volumesRead }, stringResource(MR.strings.glass_phone_stat_read), Glass.ice)
                Stat(list.sumOf { it.volumesHere }, stringResource(MR.strings.glass_phone_stat_here))
            }
        }
        // a fresh install over an existing folder links its series one by one, paced for AniList
        val adopting by LocalSeriesSync.adopting.collectAsStateWithLifecycle()
        adopting?.let { (done, of) ->
            Text(
                stringResource(MR.strings.glass_adopting, done, of),
                color = Glass.secondary,
                fontSize = Glass.body,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
            )
        }
        Shelf(stringResource(MR.strings.glass_row_reading), reading, isReading = true, navigator, onSeeAll)
        Shelf(stringResource(MR.strings.glass_row_planning), planning, isReading = false, navigator, onSeeAll)

        Row(Modifier.padding(start = 16.dp, top = 32.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (model.refreshing) {
                Text(
                    stringResource(MR.strings.glass_refreshing),
                    color = Glass.secondary,
                    fontSize = Glass.body,
                    modifier = Modifier.height(Glass.chip).padding(top = 10.dp),
                )
            } else {
                Pill(stringResource(MR.strings.glass_refresh), onClick = model::refresh, small = true)
            }
            // ponytail: way back to Novels/Anime/Dictionary until the new screens exist
            Pill(stringResource(MR.strings.glass_classic_view), onClick = { navigator.push(HomeScreen) }, small = true)
        }
    }
}

/** One pager page: the cover full-bleed, then label, title, where you are, volume progress, Resume + Volumes. */
@Composable
private fun Slide(hero: Hero, first: Boolean, navigator: Navigator) {
    val context = LocalContext.current
    val t = hero.title
    val next = t.next
    Box(Modifier.fillMaxSize().background(Glass.panel)) {
        AsyncImage(
            model = t.entry.coverLarge ?: t.entry.cover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Glass.page.copy(alpha = 0.55f),
                    0.18f to Glass.page.copy(alpha = 0f),
                    0.34f to Glass.page.copy(alpha = 0f),
                    0.62f to Glass.page.copy(alpha = 0.72f),
                    1f to Glass.page,
                ),
            ),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(start = 16.dp, end = 40.dp, bottom = 12.dp)) {
            if (hero.resume) {
                Text(
                    stringResource(if (first) MR.strings.glass_phone_continue else MR.strings.glass_phone_also),
                    color = Color.White.copy(alpha = 0.86f),
                    fontSize = Glass.caption,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 2.sp,
                )
            }
            Text(
                t.name,
                fontSize = Glass.title,
                lineHeight = 40.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                heroLine(hero),
                color = Color.White.copy(alpha = 0.86f),
                fontSize = Glass.body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            t.readFraction?.let { Meter(it, Modifier.padding(top = 10.dp).fillMaxWidth(), color = Glass.ice) }
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hero.resume && next != null) {
                    Pill(
                        stringResource(if (next.lastPageRead > 0) MR.strings.glass_resume else MR.strings.glass_phone_start),
                        onClick = { context.startActivity(ReaderActivity.newIntent(context, t.localMangaId, next.chapterId)) },
                        primary = true,
                        icon = "play",
                    )
                }
                Pill(
                    stringResource(MR.strings.glass_volumes),
                    onClick = { openTitle(navigator, t) },
                    primary = !hero.resume,
                    icon = "list",
                )
            }
        }
    }
}

@Composable
private fun Stat(n: Int, label: String, color: Color = Color.White) {
    Row(
        Modifier
            .height(Glass.chip)
            .clip(CircleShape)
            .background(Glass.panel)
            .border(1.dp, Glass.line, CircleShape)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(n.toString(), color = color, fontSize = Glass.label, fontWeight = FontWeight.Bold)
        Text(label, color = Glass.secondary, fontSize = Glass.body, maxLines = 1)
    }
}

/** "Vol. 4 next · 16/18 here" for a Reading title, "16 vols · none here" for a planned one. */
@Composable
private fun posterLine(t: GlassTitle, isReading: Boolean): String {
    val total = t.total
    val here = when {
        t.volumesHere == 0 -> stringResource(MR.strings.glass_phone_none_here)
        total != null -> stringResource(MR.strings.glass_phone_here, t.volumesHere, total)
        else -> stringResource(MR.strings.glass_phone_here_n, t.volumesHere)
    }
    val first = when {
        !isReading -> total?.let { stringResource(MR.strings.glass_discover_vols, it) }
        total == null || t.volumesRead < total -> stringResource(MR.strings.glass_phone_next_vol, t.volumesRead + 1)
        else -> null
    }
    return listOfNotNull(first, here).joinToString(" · ")
}

@Composable
private fun Shelf(name: String, titles: List<GlassTitle>, isReading: Boolean, navigator: Navigator, onSeeAll: () -> Unit) {
    if (titles.isEmpty()) return
    Row(
        Modifier.padding(top = 24.dp).fillMaxWidth().height(48.dp).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, fontSize = Glass.heading, fontWeight = FontWeight.Bold, modifier = Modifier.alignByBaseline())
        Text(
            titles.size.toString(),
            color = Glass.secondary,
            fontSize = Glass.body,
            modifier = Modifier.padding(start = 8.dp).alignByBaseline(),
        )
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.height(48.dp).clip(CircleShape).clickable(onClick = onSeeAll).padding(start = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(MR.strings.glass_phone_see_all), color = Glass.ice, fontSize = Glass.body)
            GlassIcon("chevRight", size = 18.dp, tint = Glass.ice)
        }
    }
    LazyRow(
        Modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        items(titles, key = { it.mediaId }) { t ->
            CoverCard(
                cover = t.entry.cover,
                title = t.name,
                onClick = { openTitle(navigator, t) },
                modifier = Modifier.width(112.dp),
                progress = t.readFraction.takeIf { isReading },
                meter = {
                    Text(
                        posterLine(t, isReading),
                        color = Glass.secondary,
                        fontSize = Glass.caption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}
