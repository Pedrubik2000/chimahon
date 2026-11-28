package eu.kanade.tachiyomi.ui.glass.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassIcon
import eu.kanade.presentation.glass.GlassPhoneBarRoom
import eu.kanade.presentation.glass.GlassRoundButton
import eu.kanade.presentation.glass.Pill
import eu.kanade.presentation.manga.components.MarkdownRender
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.updater.AppUpdateChecker
import eu.kanade.tachiyomi.data.updater.RELEASE_URL
import eu.kanade.tachiyomi.util.system.openInBrowser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import logcat.LogPriority
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.release.model.Release
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

// Canvas kumahon-whats-new, option C (Full page): the installed version's release notes, opened from the
// "Updated to v…" dialog and from Settings > About. A page of the glass shell ("whatsnew"), so the rail stays.

/** One part of the notes: its heading (null = the text before the first one), its markdown, and how many list items it has. */
internal data class NoteSection(val title: String?, val body: String, val items: Int)

private val boldLine = Regex("""^\*\*([^*]+)\*\*$""")
private val hashLine = Regex("""^#{1,6}\s+(.+)$""")

/** Release notes cut at their headings: a line that is only `**Bold**` (how kumahon's notes are written) or `## Heading`. */
internal fun noteSections(notes: String): List<NoteSection> {
    val parts = mutableListOf<Pair<String?, MutableList<String>>>(null to mutableListOf())
    for (line in notes.lines()) {
        val t = line.trim()
        val head = (boldLine.find(t) ?: hashLine.find(t))?.groupValues?.get(1)?.trim()
        if (head != null) parts += head to mutableListOf() else parts.last().second += line
    }
    return parts
        .map { (title, lines) ->
            NoteSection(
                title,
                lines.joinToString("\n").trim('\n', ' '),
                lines.count { it.trimStart().run { startsWith("- ") || startsWith("* ") } },
            )
        }
        .filter { it.title != null || it.body.isNotEmpty() }
}

@Composable
internal fun GlassWhatsNewPage(phone: Boolean, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var release by remember { mutableStateOf<Release?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        failed = false
        try {
            release = AppUpdateChecker().getInstalledReleaseNotes()
            failed = release == null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            failed = true
        }
    }
    val shown = release
    val version = (shown?.version ?: BuildConfig.VERSION_NAME).removePrefix("v")
    val openGitHub = { context.openInBrowser(shown?.releaseLink ?: RELEASE_URL) }
    val sections = remember(shown) { shown?.let { noteSections(it.info) }.orEmpty() }
    val scroll = rememberScrollState()
    val tops = remember(shown) { mutableStateMapOf<Int, Int>() }
    val wide = !phone && LocalConfiguration.current.screenWidthDp >= 900

    Column(
        Modifier
            .fillMaxSize()
            .padding(
                start = if (phone) 16.dp else 132.dp,
                top = if (phone) 12.dp else 24.dp,
                end = if (phone) 16.dp else 32.dp,
                bottom = if (phone) GlassPhoneBarRoom else 0.dp,
            ),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassRoundButton("chevLeft", onBack, description = stringResource(MR.strings.glass_back), outlined = true)
            Text(stringResource(MR.strings.glass_whats_new_crumb), Modifier.weight(1f), color = Glass.secondary, fontSize = Glass.body)
            Pill(stringResource(MR.strings.update_check_open), openGitHub, small = true, icon = "external")
        }
        Text(
            "kumahon $version",
            Modifier.padding(start = if (phone) 0.dp else 40.dp, top = if (phone) 16.dp else 28.dp),
            fontSize = if (phone) Glass.title else Glass.hero,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            // the notes of another release than the installed one (it is not published): say which is installed
            if (shown != null && version != BuildConfig.VERSION_NAME) {
                stringResource(MR.strings.glass_whats_new_installed, BuildConfig.VERSION_NAME)
            } else {
                stringResource(MR.strings.glass_about_github_sub)
            },
            Modifier.padding(start = if (phone) 0.dp else 40.dp, top = 4.dp, bottom = 20.dp),
            color = Glass.secondary,
            fontSize = Glass.body,
        )
        when {
            shown != null -> Row(Modifier.fillMaxSize().padding(start = if (phone) 0.dp else 40.dp)) {
                Column(
                    Modifier.weight(1f, fill = false).widthIn(max = 640.dp).verticalScroll(scroll).padding(bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    sections.forEachIndexed { i, s ->
                        Column(
                            Modifier.fillMaxWidth().onGloballyPositioned { tops[i] = it.positionInParent().y.toInt() },
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (s.title != null) {
                                Row(
                                    Modifier.padding(top = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Text(s.title, fontSize = Glass.heading, fontWeight = FontWeight.SemiBold)
                                    if (s.items > 0) Text("${s.items}", color = Glass.ice, fontSize = Glass.body, fontWeight = FontWeight.SemiBold)
                                }
                            }
                            if (s.body.isNotEmpty()) MarkdownRender(s.body, flavour = GFMFlavourDescriptor())
                        }
                    }
                }
                val titled = sections.withIndex().filter { it.value.title != null }
                if (wide && titled.size > 1) {
                    Column(Modifier.padding(start = 56.dp).width(220.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            stringResource(MR.strings.glass_whats_new_on_page).uppercase(),
                            Modifier.padding(bottom = 8.dp),
                            color = Glass.secondary,
                            fontSize = Glass.caption,
                            fontWeight = FontWeight.SemiBold,
                        )
                        titled.forEach { (i, s) ->
                            Text(
                                if (s.items > 0) "${s.title} · ${s.items}" else s.title!!,
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(Glass.r8))
                                    .clickable { scope.launch { scroll.animateScrollTo(tops[i] ?: 0) } }
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                                fontSize = Glass.body,
                            )
                        }
                    }
                }
            }
            failed -> Column(
                Modifier.fillMaxWidth().widthIn(max = 640.dp).padding(start = if (phone) 0.dp else 40.dp, top = 72.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                GlassIcon("alert", size = 28.dp, tint = Glass.ice)
                Text(stringResource(MR.strings.glass_whats_new_failed), fontSize = Glass.heading, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(MR.strings.glass_whats_new_failed_sub),
                    color = Glass.secondary,
                    fontSize = Glass.body,
                    textAlign = TextAlign.Center,
                )
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill(stringResource(MR.strings.glass_dl_try_again), { attempt++ }, primary = true, small = true, icon = "restore")
                    Pill(stringResource(MR.strings.update_check_open), openGitHub, small = true, icon = "external")
                }
            }
            // loading: quiet grey lines where the text will be
            else -> Column(
                Modifier.widthIn(max = 640.dp).padding(start = if (phone) 0.dp else 40.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(MR.strings.glass_whats_new_loading), color = Glass.secondary, fontSize = Glass.body)
                listOf(0.8f, 0.2f, 1f, 0.9f, 0.6f, 0.2f, 1f, 0.7f).forEach {
                    Box(Modifier.fillMaxWidth(it).height(14.dp).clip(RoundedCornerShape(Glass.r8)).background(Color.White.copy(alpha = 0.08f)))
                }
                Spacer(Modifier.height(1.dp))
            }
        }
    }
}
