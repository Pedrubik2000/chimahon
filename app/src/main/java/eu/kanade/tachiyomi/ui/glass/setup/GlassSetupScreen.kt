package eu.kanade.tachiyomi.ui.glass.setup

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.coerceAtMost
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassIcon
import eu.kanade.presentation.glass.GlassPage
import eu.kanade.presentation.glass.glassPanel
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.anilist.AniListLibrary
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.ui.glass.GlassHomeScreen
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.storage.service.StoragePreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * The new look's first-run setup (canvas `Setup-2`, build plan phase 10): AniList, Real-Debrid, storage folder,
 * notifications, dictionary, then a summary. Root of the Navigator on a first run (see MainActivity), pushed from
 * Settings › About ([fromSettings]).
 */
class GlassSetupScreen(private val fromSettings: Boolean = false) : Screen() {

    companion object {
        /**
         * Does the first run need the setup? Not when it is done, or when nothing is missing: then it is set done
         * quietly, so a later logout or a lost folder never blocks. No network.
         */
        fun needed(context: Context): Boolean {
            val done = Injekt.get<UiPreferences>().glassSetupDone()
            if (done.get()) return false
            val facts = GlassSetupLogic.Facts(
                aniList = Injekt.get<TrackerManager>().aniList.isLoggedIn,
                realDebrid = Injekt.get<DownloadPreferences>().realDebridToken().get().isNotBlank(),
                storage = StorageManager.directoryAccessible(context, Injekt.get<StoragePreferences>().baseStorageDirectory().get()),
            )
            val shows = GlassSetupLogic.shows(glassRoot = true, done = false, facts = facts)
            if (!shows) done.set(true)
            return shows
        }
    }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model = rememberScreenModel { GlassSetupModel(fromSettings) }
        // the notification permission on every resume (granted or revoked in Android's settings), whatever the step
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner.lifecycle) {
            val observer = object : DefaultLifecycleObserver {
                override fun onResume(owner: LifecycleOwner) = model.readNotifications()
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        val signedIn by model.signedIn.collectAsStateWithLifecycle()
        val entries by model.entries.collectAsStateWithLifecycle()
        val step = model.step
        val isEnd = step == GlassSetupLogic.SUMMARY
        val missing = GlassSetupLogic.missing(model.facts)
        val imeOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        val names = listOf(
            stringResource(MR.strings.glass_setup_anilist),
            stringResource(MR.strings.glass_dl_rd),
            stringResource(MR.strings.glass_setup_storage),
            stringResource(MR.strings.glass_setup_notifications),
            stringResource(MR.strings.glass_setup_dictionary),
            stringResource(MR.strings.glass_setup_colouring),
            stringResource(MR.strings.glass_setup_known),
        )
        // the optional Word colouring and Known words steps count as looked at once they are on screen
        LaunchedEffect(step) {
            if (step == 5) model.colouringVisited = true
            if (step == 6) model.knownVisited = true
        }

        GlassPage {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                // a phone: the same steps in one narrow column, bars without their names
                val phone = maxWidth < 600.dp
                val edge = if (phone) 20.dp else 80.dp
                // seven bars of 96dp end at 884dp: below 900dp they share the width instead (no names), so a portrait tablet is not cut off
                val weighted = maxWidth < 900.dp
                val barsWidth = maxWidth - (if (phone) 96.dp else 152.dp + edge) // from the bars' start to the right margin
                // portrait tablet: no room for the covers, the column keeps its width
                val coversShown = maxWidth >= 1100.dp
                // the left side ends 24dp before the covers (580 from the right edge), start 80
                val leftWidth = maxWidth - 684.dp
                if (coversShown) {
                    // before sign-in the "covers show up here" line only on the AniList step
                    if (step == 5 || step == 6) {
                        // the manga page of mockup B stands where the covers stand
                        MangaPreview(model.colouringOn, known = step == 6, Modifier.align(Alignment.TopEnd).padding(top = 110.dp, end = 80.dp).width(500.dp))
                    } else {
                        Covers(signedIn, entries, emptyLine = signedIn || step == 0, modifier = Modifier.align(Alignment.TopEnd))
                    }
                }

                Box(
                    Modifier
                        .offset(x = edge, y = if (phone) 24.dp else 32.dp)
                        .size(if (phone) 44.dp else 56.dp)
                        .clip(CircleShape)
                        .background(Glass.bear)
                        .semantics { contentDescription = "chimahon" },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("熊", color = Color.Black, fontSize = Glass.heading, fontWeight = FontWeight.Bold)
                }
                val stepsLabel = stringResource(MR.strings.glass_setup_steps)
                Row(
                    Modifier
                        // seven bars: 96dp and 10dp apart on a tablet so they end before 900dp
                        .offset(x = if (phone) 76.dp else 152.dp, y = if (phone) 22.dp else 36.dp)
                        .then(if (weighted) Modifier.width(barsWidth) else Modifier)
                        .semantics { contentDescription = stepsLabel },
                    horizontalArrangement = Arrangement.spacedBy(if (weighted) 6.dp else 10.dp),
                ) {
                    names.forEachIndexed { i, n ->
                        val on = i == step
                        val skipped = model.skipped(i)
                        val ok = model.done(i) && !skipped
                        Column(
                            (if (weighted) Modifier.weight(1f) else Modifier.width(96.dp))
                                .height(48.dp).clickable { model.step = i }.padding(top = if (weighted) 22.dp else 8.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                (if (weighted) Modifier.fillMaxWidth() else Modifier.width(96.dp)).height(4.dp).clip(CircleShape)
                                    .background(
                                        when {
                                            on -> Glass.ice
                                            ok -> Color.White
                                            skipped -> SkippedBar
                                            else -> Glass.line
                                        },
                                    ),
                            )
                            // a phone has no room for five names: the step's title below says which one it is
                            if (!weighted) {
                                Text(
                                    n,
                                    color = if (on || ok) Color.White else Glass.secondary,
                                    fontSize = Glass.caption,
                                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }

                Column(
                    Modifier
                        // above the keyboard (the activity is edge-to-edge), so the focused key field can scroll into view
                        .imePadding()
                        // padding, not offset: the column must end above the buttons (64 + 48dp) to scroll there
                        // (behind the keyboard: no); 116 leaves 4dp, room the Dictionary step needs on a tablet
                        .padding(
                            start = edge,
                            end = if (phone) edge else 0.dp,
                            top = if (phone) 92.dp else 136.dp,
                            bottom = if (imeOpen) 0.dp else if (phone) 96.dp else 116.dp,
                        )
                        .then(
                            when {
                                phone -> Modifier.fillMaxWidth()
                                // Word colouring and Known words have cards side by side and a list: wider than the other steps
                                coversShown -> Modifier.width(leftWidth.coerceAtMost(if (step >= 5 && step != GlassSetupLogic.SUMMARY) 640.dp else 520.dp))
                                step == 5 || step == 6 -> Modifier.width((maxWidth - edge * 2).coerceAtMost(720.dp))
                                else -> Modifier.width(520.dp)
                            },
                        )
                        .verticalScroll(rememberScrollState()),
                ) {
                    Box(
                        Modifier.size(56.dp).glassPanel(Glass.r16),
                        contentAlignment = Alignment.Center,
                    ) {
                        GlassIcon(
                            if (isEnd) (if (missing.isEmpty()) "check" else "alert") else StepIcons[step],
                            size = 24.dp,
                        )
                    }
                    val label = if (isEnd) stringResource(MR.strings.glass_setup_summary) else stringResource(MR.strings.glass_setup_step, step + 1)
                    val tagColor = when {
                        isEnd -> if (missing.isEmpty()) Glass.ice else Color.White
                        GlassSetupLogic.needed(step) -> Glass.ice
                        else -> Glass.secondary
                    }
                    Text(
                        buildAnnotatedString {
                            append("$label · ") // in code: resources lose a trailing space
                            withStyle(SpanStyle(color = tagColor)) { append(GlassSetupLogic.tag(step, missing.size)) }
                        },
                        Modifier.padding(top = 24.dp),
                        color = Glass.secondary,
                        fontSize = Glass.caption,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 2.sp,
                    )
                    Text(
                        when {
                            isEnd -> stringResource(if (missing.isEmpty()) MR.strings.glass_setup_ready else MR.strings.glass_setup_almost)
                            step == 2 -> stringResource(MR.strings.glass_setup_storage_title)
                            else -> names[step]
                        },
                        Modifier.padding(top = 10.dp),
                        fontSize = Glass.hero,
                        lineHeight = 48.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(
                            when (step) {
                                0 -> MR.strings.glass_setup_anilist_why
                                1 -> MR.strings.glass_setup_rd_why
                                2 -> MR.strings.glass_setup_storage_why
                                3 -> MR.strings.glass_setup_notifications_why
                                4 -> MR.strings.glass_setup_dictionary_why
                                5 -> MR.strings.glass_setup_colouring_why
                                6 -> MR.strings.glass_setup_known_why
                                else -> if (missing.isEmpty()) MR.strings.glass_setup_ready_why else MR.strings.glass_setup_almost_why
                            },
                        ),
                        Modifier.padding(top = 10.dp),
                        color = Glass.secondary,
                        fontSize = Glass.label,
                        lineHeight = 24.sp,
                    )
                    // the Dictionary step is the tallest: 24dp there so it needs no scroll on a landscape tablet
                    Column(Modifier.padding(top = if (step == 4) 12.dp else if (step == 5 || step == 6) 20.dp else 36.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        when (step) {
                            0 -> AniListStep(model, signedIn, entries)
                            1 -> RealDebridStep(model)
                            2 -> StorageStep(model)
                            3 -> NotificationsStep(model)
                            4 -> DictionaryStep(model)
                            5 -> ColouringStep(model, phone, coversShown)
                            6 -> KnownWordsStep(model, phone, coversShown)
                            else -> SummaryStep(model, names, entries)
                        }
                    }
                }

                // Back + Continue / Open Home
                Row(
                    Modifier.align(Alignment.BottomStart)
                        .padding(start = edge, end = if (phone) edge else 0.dp, bottom = if (phone) 28.dp else 64.dp)
                        .then(if (coversShown) Modifier.widthIn(max = leftWidth) else Modifier),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (step > 0) {
                        SetupButton(stringResource(MR.strings.glass_back), { model.step = step - 1 }, primary = false, leading = "chevLeft")
                    }
                    if (!isEnd) {
                        // Word colouring and Known words are optional and never block: Continue is always on there
                        val canNext = step == 5 || step == 6 || model.done(step)
                        SetupButton(
                            stringResource(MR.strings.glass_setup_continue),
                            { when (step) { 5 -> model.colouringContinue(); 6 -> model.knownContinue(); else -> model.step = step + 1 } },
                            enabled = canNext,
                            trailing = "chevRight",
                        )
                        if (step == 5 || step == 6) {
                            QuietButton(stringResource(MR.strings.glass_setup_not_now), if (step == 5) model::skipColouring else model::skipKnown)
                        } else if (!canNext) {
                            Text(
                                stringResource(if (GlassSetupLogic.needed(step)) MR.strings.glass_setup_needed else MR.strings.glass_setup_or_not_now),
                                Modifier.padding(start = 8.dp),
                                color = Glass.secondary,
                                fontSize = Glass.body,
                            )
                        } else if (step == 4 && model.dictionaryDownloading) {
                            // canvas Dict-1: Continue is on while it downloads
                            Text(
                                stringResource(MR.strings.glass_setup_dict_keeps),
                                Modifier.padding(start = 8.dp),
                                color = Glass.secondary,
                                fontSize = Glass.body,
                            )
                        }
                    } else {
                        SetupButton(
                            // from Settings it goes back there: "Done"
                            stringResource(if (fromSettings) MR.strings.glass_setup_finish else MR.strings.glass_setup_open_home),
                            {
                                model.finish()
                                if (fromSettings) navigator.pop() else navigator.replaceAll(GlassHomeScreen)
                            },
                            enabled = missing.isEmpty(),
                            leading = if (fromSettings) "check" else "home",
                        )
                        if (missing.isNotEmpty()) {
                            Row(
                                // takes what the buttons leave and wraps there, never into the covers
                                Modifier.weight(1f, fill = false).padding(start = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                GlassIcon("alert", size = 18.dp)
                                Text(GlassSetupLogic.missingLine(missing.map { names[it] }), color = Color.White, fontSize = Glass.body)
                            }
                        }
                    }
                }
            }
        }
    }
}

private val StepIcons = listOf("person", "key", "folder", "bell", "book", "pencil", "library")

/** A step left with "Not now": between done (white) and not started ([Glass.line]). */
private val SkippedBar = Color.White.copy(alpha = 0.38f)

private val CoverX = listOf(0, 138, 276, 414)
private val CoverDy = listOf(0, 48, 16, 64)

/** Right half: 8 quiet panels before sign-in, the person's covers (Reading first) after. Anchored to the right edge. */
@Composable
private fun Covers(signedIn: Boolean, entries: List<AniListLibrary.Entry>, emptyLine: Boolean, modifier: Modifier) {
    val reading = entries.count { it.status == "CURRENT" }
    val planning = entries.count { it.status == "PLANNING" }
    val shown = (entries.filter { it.status == "CURRENT" } + entries.filter { it.status == "PLANNING" })
        .filter { it.cover != null || it.coverLarge != null }
        .take(8)
    // the canvas block: 760 wide, covers from x=700 of 1280, i.e. 580 from the right edge
    Box(modifier.width(760.dp).height(800.dp)) {
        val on = signedIn && shown.isNotEmpty()
        if (on) {
            AsyncImage(
                model = shown.first().let { it.banner ?: it.coverLarge ?: it.cover },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.width(760.dp).height(560.dp).alpha(0.45f),
            )
            Box(
                Modifier.width(760.dp).height(560.dp).background(
                    Brush.horizontalGradient(0f to Glass.page, 0.35f to Glass.page.copy(alpha = 0.85f), 1f to Glass.page.copy(alpha = 0.35f)),
                ),
            )
            Box(
                Modifier.width(760.dp).height(560.dp).background(
                    Brush.verticalGradient(0.45f to Glass.page.copy(alpha = 0f), 1f to Glass.page),
                ),
            )
        }
        val left = 760 - 580
        for (i in 0 until 8) {
            val x = left + CoverX[i % 4]
            val y = 136 + (if (i < 4) 0 else 202) + CoverDy[i % 4]
            val shape = RoundedCornerShape(Glass.r10)
            val m = Modifier.offset(x = x.dp, y = y.dp).width(124.dp).height(186.dp).clip(shape)
            val entry = shown.getOrNull(i)
            if (on && entry != null) {
                AsyncImage(
                    model = entry.coverLarge ?: entry.cover,
                    contentDescription = entry.native ?: entry.romaji,
                    contentScale = ContentScale.Crop,
                    modifier = m.background(Glass.panel).border(1.dp, Glass.line, shape),
                )
            } else {
                Box(m.alpha(0.55f).background(Glass.panel))
            }
        }
        Row(
            Modifier.offset(x = left.dp, y = 640.dp).width(538.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (on) {
                GlassIcon("check", size = 18.dp, tint = Glass.ice)
                Text(stringResource(MR.strings.glass_setup_covers_line, reading, planning), color = Glass.secondary, fontSize = Glass.body)
            } else if (emptyLine) {
                Text(stringResource(MR.strings.glass_setup_covers_empty), color = Glass.secondary, fontSize = Glass.body)
            }
        }
    }
}
