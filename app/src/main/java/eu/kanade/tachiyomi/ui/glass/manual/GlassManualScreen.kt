package eu.kanade.tachiyomi.ui.glass.manual

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassWideButton
import eu.kanade.presentation.glass.GlassIcon
import eu.kanade.presentation.glass.GlassPage
import eu.kanade.presentation.glass.Meter
import eu.kanade.presentation.glass.Pill
import eu.kanade.presentation.glass.glassPanel
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.anilist.AniListLibrary
import eu.kanade.tachiyomi.data.coverage.CoverageMatch
import eu.kanade.tachiyomi.data.download.ManualDownload
import eu.kanade.tachiyomi.data.download.RealDebridQueue
import eu.kanade.tachiyomi.data.download.TorrentDownload
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.ui.glass.downloads.cachedCoverage
import eu.kanade.tachiyomi.util.system.openInBrowser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit
import java.util.UUID

/**
 * Download by hand (canvas `phone-manual` Manual-1): for people without Real-Debrid. Pick one way, open the hoster in
 * the browser (the wait and the captcha are theirs), come back, pick the downloaded file, import it.
 * [volumes] = the wanted volumes, [here] = those already on the device.
 */
class GlassManualScreen(
    private val mediaId: Long,
    private val volumes: List<Int>,
    private val here: List<Int>,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val model = rememberScreenModel { ManualModel(mediaId, volumes, here) }
        val step = model.step
        val options = model.options
        val option = options?.getOrNull(model.selected)?.takeIf { !model.isGone(it) }
        val many = (option?.files?.size ?: 0) > 1

        // ponytail: no "Watching Downloads" (needs all-files access or a MediaStore scan); the person picks the file
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) model.picked(uris)
        }
        val goBack = {
            when (step) {
                Step.FOUND -> model.step = Step.STEPS
                Step.STEPS -> model.step = Step.PICK
                else -> navigator.pop()
            }
            Unit
        }
        BackHandler(enabled = step == Step.FOUND || step == Step.STEPS, onBack = goBack)

        var confirmDelete by remember { mutableStateOf(false) }

        GlassPage {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 480.dp).fillMaxSize()) {
                    TopBar(model.title, stringResource(MR.strings.glass_manual_vol, CoverageMatch.ranges(volumes)), goBack)
                    Rule()
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                    ) {
                        when (step) {
                            Step.PICK -> if (options != null) {
                                PickBody(model, options)
                            } else {
                                Dim(stringResource(MR.strings.glass_manual_checking))
                            }
                            Step.STEPS -> if (option != null) StepsBody(model, option, context)
                            Step.FOUND -> FoundBody(model, context)
                            Step.DONE -> DoneBody(model, context) { confirmDelete = true }
                        }
                    }
                    Rule()
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (step) {
                            Step.PICK -> {
                                if (option != null) {
                                    GlassWideButton(
                                        stringResource(MR.strings.glass_manual_get_from, option.sourceName),
                                        { model.opened = false; model.step = Step.STEPS },
                                        Modifier.fillMaxWidth(),
                                        primary = true,
                                        icon = "chevRight",
                                    )
                                }
                                if (model.hasTorrent) {
                                    GlassWideButton(
                                        stringResource(MR.strings.glass_torrent_instead),
                                        { navigator.replace(GlassTorrentScreen(mediaId, volumes, here)) },
                                        Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                            Step.STEPS -> {
                                if (option != null && !many) {
                                    GlassWideButton(
                                        stringResource(if (model.opened) MR.strings.glass_manual_open_again else MR.strings.glass_manual_open_browser),
                                        { model.opened = true; context.openInBrowser(option.files.first().link, forceDefaultBrowser = true) },
                                        Modifier.fillMaxWidth(),
                                        primary = true,
                                        icon = "external",
                                    )
                                }
                                GlassWideButton(
                                    stringResource(if (many) MR.strings.glass_manual_pick_files else MR.strings.glass_manual_pick_file),
                                    { launcher.launch(arrayOf("*/*")) },
                                    Modifier.fillMaxWidth(),
                                    primary = many,
                                    icon = "folder",
                                )
                            }
                            Step.FOUND -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                GlassWideButton(
                                    stringResource(MR.strings.glass_manual_not_this),
                                    { model.picks = emptyList(); model.step = Step.STEPS },
                                    Modifier.weight(1f),
                                )
                                GlassWideButton(stringResource(MR.strings.glass_manual_import), model::import, Modifier.weight(2f), primary = true)
                            }
                            Step.DONE -> {
                                val row = model.row
                                val failed = row?.state == RealDebridQueue.State.FAILED || model.error != null
                                if (failed) {
                                    GlassWideButton(
                                        stringResource(MR.strings.glass_manual_pick_another),
                                        { model.releaseGrants(); model.reset(); model.step = Step.STEPS },
                                        Modifier.fillMaxWidth(),
                                    )
                                }
                                if (failed || row?.state == RealDebridQueue.State.DONE) {
                                    GlassWideButton(
                                        stringResource(MR.strings.glass_manual_back_title),
                                        { navigator.pop() },
                                        Modifier.fillMaxWidth(),
                                        primary = !failed,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                shape = RoundedCornerShape(Glass.r16),
                containerColor = Glass.panel,
                titleContentColor = Color.White,
                textContentColor = Color.White,
                title = { Text(stringResource(MR.strings.glass_manual_delete_title), fontSize = Glass.heading) },
                text = {
                    Text(
                        stringResource(MR.strings.glass_manual_delete_ask, model.picks.joinToString(", ") { it.name }),
                        fontSize = Glass.body,
                    )
                },
                confirmButton = {
                    Pill(
                        stringResource(MR.strings.glass_manual_delete),
                        onClick = { confirmDelete = false; model.deletePicked() },
                        primary = true,
                        small = true,
                    )
                },
                dismissButton = {
                    Pill(stringResource(MR.strings.action_cancel), onClick = { confirmDelete = false }, small = true)
                },
            )
        }
    }
}

private enum class Step { PICK, STEPS, FOUND, DONE }

private class Picked(val uri: Uri, val name: String, val size: Long)

private class ManualModel(
    private val mediaId: Long,
    val volumes: List<Int>,
    val here: List<Int>,
) : ScreenModel {
    private val app = Injekt.get<Application>()
    private val queue = Injekt.get<RealDebridQueue>()
    private val entry = Injekt.get<AniListLibrary>().get(mediaId)

    val title = entry?.let { it.native ?: it.romaji ?: it.english }.orEmpty()

    var options by mutableStateOf<List<ManualDownload.Option>?>(null)
    var selected by mutableIntStateOf(0)

    /** a torrent holds some of the wanted volumes: the page offers the torrent way too */
    var hasTorrent by mutableStateOf(false)

    /** links whose hoster page says the file is gone ([ManualDownload.gone]) */
    var gone by mutableStateOf<Set<String>>(emptySet())

    fun isGone(o: ManualDownload.Option) = o.files.any { it.link in gone }

    /** One plain page request per link, all at once, 8 s each; an answer that does not say "gone" (or none) = not gone. */
    private suspend fun deadLinks(links: List<String>): Set<String> = coroutineScope {
        val client = Injekt.get<NetworkHelper>().client.newBuilder().callTimeout(8, TimeUnit.SECONDS).build()
        links.map { link ->
            async {
                link.takeIf {
                    runCatching {
                        client.newCall(Request.Builder().url(link).build()).execute().use { r ->
                            ManualDownload.gone(r.code, r.request.url.toString())
                        }
                    }.getOrDefault(false)
                }
            }
        }.awaitAll().filterNotNull().toSet()
    }
    var step by mutableStateOf(Step.PICK)
    var opened by mutableStateOf(false)
    var picks by mutableStateOf<List<Picked>>(emptyList())
    var workId by mutableStateOf<UUID?>(null)

    /** The last state seen of the import's row: kept when the row goes away, so the screen never spins on nothing. */
    var row by mutableStateOf<RealDebridQueue.Row?>(null)
    var error by mutableStateOf<String?>(null)

    /** null = not tried, else whether every file was deleted */
    var deleted by mutableStateOf<Boolean?>(null)

    init {
        // The file read is off the main thread, the write on it: this model is made during composition, and a
        // write from another thread before that composition is applied is lost (the list stayed empty for good).
        screenModelScope.launch {
            val (list, dead) = withContext(Dispatchers.IO) {
                val coverage = cachedCoverage(app, mediaId)
                val torrent = TorrentDownload.options(volumes, here.toSet(), coverage).isNotEmpty()
                withContext(Dispatchers.Main) { hasTorrent = torrent }
                val list = ManualDownload.options(volumes, here.toSet(), coverage)
                list to deadLinks(ManualDownload.links(list))
            }
            gone = dead
            // an option with a dead file goes last and is never the suggestion (sortedBy is stable)
            // a file whose link is gone moves to a mirror that is not
            options = ManualDownload.alive(list, dead).sortedBy { isGone(it) }
        }
        screenModelScope.launch {
            queue.rows.collect { rows ->
                val id = workId ?: return@collect
                rows.firstOrNull { it.workId == id }?.let { row = it }
            }
        }
    }

    /** Names and sizes are read on the picker's temporary grant; the one persistable grant is taken by the queue. */
    fun picked(uris: List<Uri>) {
        screenModelScope.launch(Dispatchers.IO) {
            val cr = app.contentResolver
            picks = uris.map { uri ->
                var name = uri.lastPathSegment.orEmpty()
                var size = -1L
                runCatching {
                    cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            c.getString(0)?.let { name = it }
                            if (!c.isNull(1)) size = c.getLong(1)
                        }
                    }
                }
                Picked(uri, name, size)
            }
            step = Step.FOUND
        }
    }

    /**
     * Gives back the persistable grants [RealDebridQueue.enqueueLocal] took, once the import ended (none were taken before).
     * The job does not release them: Delete here still needs the file.
     */
    // ponytail: leaving the screen while the import still runs keeps its grants (the job needs them); no sweep of those
    fun releaseGrants() {
        val state = row?.state
        if (workId == null || (state != RealDebridQueue.State.DONE && state != RealDebridQueue.State.FAILED)) return
        picks.forEach { runCatching { app.contentResolver.releasePersistableUriPermission(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
    }

    override fun onDispose() = releaseGrants()

    fun import() {
        reset()
        step = Step.DONE
        val e = entry
        if (e == null) {
            error = ""
            return
        }
        runCatching { queue.enqueueLocal(e, volumes, picks.map { it.uri }) }
            .onSuccess { workId = it }
            .onFailure { error = it.message.orEmpty() }
    }

    /** Forgets the last import, keeps the picked files. */
    fun reset() {
        workId = null
        row = null
        error = null
        deleted = null
    }

    fun deletePicked() {
        screenModelScope.launch(Dispatchers.IO) {
            val cr = app.contentResolver
            deleted = picks.map { p -> runCatching { DocumentsContract.deleteDocument(cr, p.uri) }.getOrDefault(false) }.all { it }
        }
    }
}

@Composable
internal fun TopBar(title: String, vols: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(64.dp).padding(end = 16.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(Glass.button).clip(CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
            GlassIcon("chevLeft", size = 24.dp)
        }
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(title, fontSize = Glass.label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(vols, fontSize = Glass.body, color = Glass.secondary, maxLines = 1)
        }
    }
}

@Composable
internal fun Rule() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Glass.line))
}

@Composable
internal fun Heading(text: String) {
    Text(text, fontSize = Glass.heading, fontWeight = FontWeight.SemiBold)
}

@Composable
internal fun Dim(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = Glass.body, color = Glass.secondary, modifier = modifier)
}

// pick

@Composable
private fun PickBody(model: ManualModel, options: List<ManualDownload.Option>) {
    Heading(stringResource(MR.strings.glass_manual_by_hand))
    Dim(stringResource(MR.strings.glass_manual_sub), Modifier.padding(top = 4.dp))
    if (options.isEmpty()) {
        Text(stringResource(MR.strings.glass_manual_none), fontSize = Glass.body, modifier = Modifier.padding(top = 24.dp))
        return
    }
    Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, o ->
            val dead = model.isGone(o)
            val marked = o.files.any { it.scans }
            val on = i == model.selected && !dead
            Column(
                Modifier
                    .fillMaxWidth()
                    .glassPanel()
                    .then(if (on) Modifier.border(1.dp, Glass.ice, RoundedCornerShape(Glass.r16)) else Modifier)
                    .clickable(enabled = !dead) { model.selected = i }
                    .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (i == 0 && !dead && !marked) {
                    Column(Modifier.padding(end = 8.dp)) {
                        CapsLabel(stringResource(MR.strings.glass_manual_suggested), Glass.ice)
                        Dim(
                            if (o.files.all { ManualDownload.fast(it.link) }) {
                                stringResource(MR.strings.glass_manual_why_fast, ManualDownload.host(o.files.first().link))
                            } else if (o.files.size == 1) {
                                stringResource(MR.strings.glass_manual_why_one)
                            } else {
                                stringResource(MR.strings.glass_manual_why_many, o.files.size)
                            },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            o.sourceName,
                            color = if (dead) Glass.secondary else Color.White,
                            fontSize = Glass.label,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Dim(optionLine(o), Modifier)
                    }
                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier.size(22.dp).border(2.dp, if (on) Glass.ice else if (dead) Glass.line else Color.White, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (on) Box(Modifier.size(10.dp).clip(CircleShape).background(Glass.ice))
                        }
                    }
                }
                if (dead) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        GlassIcon("alert", size = 18.dp)
                        Text(stringResource(MR.strings.glass_manual_gone), fontSize = Glass.body)
                    }
                } else if (o.adds.isNotEmpty()) {
                    val already = if (o.alreadyHere.isEmpty()) "" else " · " + stringResource(MR.strings.glass_manual_already, CoverageMatch.ranges(o.alreadyHere))
                    Text(stringResource(MR.strings.glass_manual_adds, CoverageMatch.ranges(o.adds)) + already, fontSize = Glass.body, modifier = Modifier.padding(end = 8.dp))
                }
                if (marked && !dead) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        GlassIcon("alert", size = 18.dp)
                        Text(stringResource(MR.strings.glass_manual_scans), fontSize = Glass.body, modifier = Modifier.padding(end = 8.dp))
                    }
                }
                val lacks = model.volumes.filter { it !in o.adds && it !in model.here }
                if (lacks.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        GlassIcon("alert", size = 18.dp)
                        Text(stringResource(MR.strings.glass_manual_lacks, CoverageMatch.ranges(lacks)), fontSize = Glass.body)
                    }
                }
                o.files.firstOrNull()?.let {
                    Dim(stringResource(MR.strings.glass_manual_mirror, ManualDownload.host(it.link)))
                }
            }
        }
    }
}

/** "Vol. 1–5 · 1 file" / "Vol. 1, 2 · 2 files", then " · N extra volumes". */
@Composable
private fun optionLine(o: ManualDownload.Option): String {
    val vols = o.files.flatMap { it.volumes }.distinct().sorted()
    val base = if (o.files.size == 1) {
        stringResource(MR.strings.glass_manual_vols_file, CoverageMatch.ranges(vols))
    } else {
        stringResource(MR.strings.glass_manual_vols_files, vols.joinToString(", "), o.files.size)
    }
    val extra = when (o.extra.size) {
        0 -> ""
        1 -> " · " + stringResource(MR.strings.glass_manual_extra_one)
        else -> " · " + stringResource(MR.strings.glass_manual_extra, o.extra.size)
    }
    return base + extra
}

// steps

@Composable
private fun StepsBody(model: ManualModel, o: ManualDownload.Option, context: Context) {
    val many = o.files.size > 1
    val host = ManualDownload.host(o.files.first().link)
    Heading(stringResource(MR.strings.glass_manual_get_it))
    Row(
        Modifier
            .padding(top = 14.dp)
            .fillMaxWidth()
            .glassPanel()
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GlassIcon("folder", size = 22.dp)
        Column(Modifier.weight(1f)) {
            Text(o.sourceName, fontSize = Glass.body, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Dim(optionLine(o) + " · " + host)
        }
        Box(
            Modifier.height(48.dp).clip(CircleShape).clickable { model.step = Step.PICK }.padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(MR.strings.glass_manual_change), fontSize = Glass.body, color = Glass.ice)
        }
    }
    Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        NumberedStep(1, true, stringResource(MR.strings.glass_manual_step1), stringResource(MR.strings.glass_manual_step1_body, host)) {
            if (many) {
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    o.files.forEach { f ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(MR.strings.glass_manual_vol, CoverageMatch.ranges(f.volumes)), fontSize = Glass.body, fontWeight = FontWeight.SemiBold)
                                Dim(ManualDownload.host(f.link))
                            }
                            Pill(
                                stringResource(MR.strings.glass_manual_open),
                                onClick = { context.openInBrowser(f.link, forceDefaultBrowser = true) },
                                icon = "external",
                                small = true,
                            )
                        }
                    }
                }
            }
        }
        NumberedStep(
            2,
            false,
            stringResource(if (many) MR.strings.glass_manual_step2_many else MR.strings.glass_manual_step2_one),
            stringResource(MR.strings.glass_manual_step2_body),
        )
        NumberedStep(3, false, stringResource(MR.strings.glass_manual_step3), stringResource(MR.strings.glass_manual_step3_body))
    }
}

@Composable
private fun NumberedStep(n: Int, on: Boolean, title: String, body: String, extra: @Composable () -> Unit = {}) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .then(if (on) Modifier.background(Color.White) else Modifier.border(1.dp, Glass.line, CircleShape)),
            contentAlignment = Alignment.Center,
        ) {
            Text(n.toString(), fontSize = Glass.body, fontWeight = FontWeight.SemiBold, color = if (on) Color.Black else Color.White)
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = Glass.label, fontWeight = FontWeight.SemiBold)
            Dim(body, Modifier.padding(top = 2.dp))
            extra()
        }
    }
}

// found

@Composable
private fun FoundBody(model: ManualModel, context: Context) {
    Heading(stringResource(if (model.picks.size > 1) MR.strings.glass_manual_found_many else MR.strings.glass_manual_found))
    Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        model.picks.forEach { p ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Glass.r16))
                    .background(Glass.panel)
                    .border(1.dp, Glass.ice, RoundedCornerShape(Glass.r16))
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                GlassIcon("check", size = 22.dp, tint = Glass.ice)
                Column(Modifier.weight(1f)) {
                    Text(p.name, fontSize = Glass.label, fontWeight = FontWeight.SemiBold)
                    if (p.size >= 0) Dim(Formatter.formatShortFileSize(context, p.size), Modifier.padding(top = 2.dp))
                }
            }
        }
    }
    Column(Modifier.padding(top = 12.dp).fillMaxWidth().glassPanel().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(MR.strings.glass_manual_adds_row), fontSize = Glass.body)
            Text(stringResource(MR.strings.glass_manual_vol, CoverageMatch.ranges(model.volumes)), fontSize = Glass.body, fontWeight = FontWeight.SemiBold)
        }
        Dim(stringResource(MR.strings.glass_manual_adds_note), Modifier.padding(top = 4.dp))
    }
}

// done

@Composable
private fun DoneBody(model: ManualModel, context: Context, askDelete: () -> Unit) {
    val row = model.row
    val ok = row?.state == RealDebridQueue.State.DONE
    val failed = row?.state == RealDebridQueue.State.FAILED || model.error != null
    // some of the volumes arrived and some did not: what arrived first, then what was missing
    val partial = failed && row?.delivered?.isNotEmpty() == true
    Column(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        when {
            ok || partial -> {
                Box(Modifier.size(56.dp).border(2.dp, Glass.ice, CircleShape), contentAlignment = Alignment.Center) {
                    GlassIcon("check", size = 28.dp, tint = Glass.ice)
                }
                // a finished job that delivered nothing copied nothing: every volume was here already
                val hereAlready = row!!.delivered.isEmpty()
                Text(
                    if (hereAlready) {
                        stringResource(MR.strings.glass_manual_already_here, CoverageMatch.ranges(model.volumes))
                    } else {
                        stringResource(MR.strings.glass_manual_added, CoverageMatch.ranges(row.delivered))
                    },
                    fontSize = Glass.heading,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp),
                )
                if (row.missing.isNotEmpty()) {
                    Dim(stringResource(MR.strings.glass_manual_not_in_file, CoverageMatch.ranges(row.missing)), Modifier.padding(top = 4.dp))
                } else if (partial) {
                    row.reason?.let { Dim(it, Modifier.padding(top = 4.dp)) }
                }
            }
            failed -> {
                GlassIcon("alert", size = 40.dp)
                Text(
                    row?.reason ?: model.error?.ifEmpty { null } ?: stringResource(MR.strings.glass_manual_failed),
                    fontSize = Glass.label,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            else -> {
                // ponytail: a row that never shows up reads as "Importing…" for good; a timeout needs a clock here
                Text(
                    stringResource(if (row?.state == RealDebridQueue.State.QUEUED) MR.strings.glass_manual_waiting else MR.strings.glass_manual_importing),
                    fontSize = Glass.heading, fontWeight = FontWeight.SemiBold)
                row?.step?.let { Dim(it, Modifier.padding(top = 4.dp)) }
                if (row != null && row.total > 0) {
                    Meter(row.bytes.toFloat() / row.total, Modifier.fillMaxWidth().padding(top = 16.dp))
                }
            }
        }
    }
    if (ok || partial) {
        Row(
            Modifier.padding(top = 32.dp).fillMaxWidth().glassPanel().padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(MR.strings.glass_manual_delete_title), fontSize = Glass.body)
                Text(model.picks.joinToString(", ") { it.name }, fontSize = Glass.caption, color = Glass.secondary)
                when (model.deleted) {
                    true -> Dim(stringResource(MR.strings.glass_manual_deleted))
                    false -> Dim(stringResource(MR.strings.glass_manual_delete_failed))
                    null -> Unit
                }
            }
            if (model.deleted != true) Pill(stringResource(MR.strings.glass_manual_delete), onClick = askDelete, small = true)
        }
    }
}

@Composable
internal fun CapsLabel(text: String, color: Color = Glass.secondary) {
    Text(
        text.uppercase(),
        color = color,
        fontSize = Glass.caption,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.5.sp,
        maxLines = 1,
    )
}
