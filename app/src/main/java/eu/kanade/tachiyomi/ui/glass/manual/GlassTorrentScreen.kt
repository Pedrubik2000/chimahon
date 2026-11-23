package eu.kanade.tachiyomi.ui.glass.manual

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassIcon
import eu.kanade.presentation.glass.GlassPage
import eu.kanade.presentation.glass.Meter
import eu.kanade.presentation.glass.glassPanel
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.anilist.AniListLibrary
import eu.kanade.tachiyomi.data.coverage.CoverageMatch
import eu.kanade.tachiyomi.data.download.RealDebridQueue
import eu.kanade.tachiyomi.data.download.TorrentDownload
import eu.kanade.tachiyomi.ui.glass.downloads.cachedCoverage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.UUID

/**
 * Download as a torrent, on this device, without Real-Debrid (Torrent 1: one list, best first): pick a torrent,
 * read what a torrent means (the IP is visible), then the import job fetches it with the app's own torrent engine.
 * [volumes] = the wanted volumes, [here] = those already on the device.
 */
class GlassTorrentScreen(
    private val mediaId: Long,
    private val volumes: List<Int>,
    private val here: List<Int>,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model = rememberScreenModel { TorrentModel(mediaId, volumes, here) }
        val step = model.step
        val options = model.options
        val option = options?.getOrNull(model.selected)
        val row = model.row
        val failed = row?.state == RealDebridQueue.State.FAILED || model.error != null
        val finished = failed || row?.state == RealDebridQueue.State.DONE

        val goBack = {
            // leaving while it runs is fine: the download goes on, the Downloads page shows it
            if (step == TorrentStep.NOTICE) model.step = TorrentStep.PICK else navigator.pop()
            Unit
        }
        BackHandler(enabled = step == TorrentStep.NOTICE, onBack = goBack)

        GlassPage {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 480.dp).fillMaxSize()) {
                    TopBar(model.title, stringResource(MR.strings.glass_manual_vol, CoverageMatch.ranges(volumes)), goBack)
                    Rule()
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
                        when (step) {
                            TorrentStep.PICK -> if (options != null) PickTorrent(model, options)
                            TorrentStep.NOTICE -> Notice()
                            TorrentStep.RUN -> Running(model)
                        }
                    }
                    Rule()
                    Column(
                        Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        when (step) {
                            TorrentStep.PICK -> if (option != null) {
                                WideButton(
                                    stringResource(MR.strings.glass_torrent_get),
                                    { model.step = TorrentStep.NOTICE },
                                    Modifier.fillMaxWidth(),
                                    primary = true,
                                    icon = "chevRight",
                                )
                            }
                            TorrentStep.NOTICE -> if (option != null) {
                                WideButton(
                                    stringResource(MR.strings.glass_torrent_start),
                                    { model.start(option) },
                                    Modifier.fillMaxWidth(),
                                    primary = true,
                                )
                            }
                            TorrentStep.RUN -> {
                                if (failed) {
                                    WideButton(
                                        stringResource(MR.strings.glass_torrent_pick_another),
                                        { model.reset(); model.step = TorrentStep.PICK },
                                        Modifier.fillMaxWidth(),
                                    )
                                }
                                if (finished) {
                                    WideButton(
                                        stringResource(MR.strings.glass_manual_back_title),
                                        { navigator.pop() },
                                        Modifier.fillMaxWidth(),
                                        primary = !failed,
                                    )
                                } else if (model.workId != null) {
                                    WideButton(stringResource(MR.strings.glass_torrent_cancel), model::cancel, Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class TorrentStep { PICK, NOTICE, RUN }

private class TorrentModel(
    private val mediaId: Long,
    val volumes: List<Int>,
    val here: List<Int>,
) : ScreenModel {
    private val app = Injekt.get<Application>()
    private val queue = Injekt.get<RealDebridQueue>()
    private val entry = Injekt.get<AniListLibrary>().get(mediaId)

    val title = entry?.let { it.native ?: it.romaji ?: it.english }.orEmpty()

    var options by mutableStateOf<List<TorrentDownload.Option>?>(null)
    var selected by mutableIntStateOf(0)
    var step by mutableStateOf(TorrentStep.PICK)
    var workId by mutableStateOf<UUID?>(null)

    /** The last state seen of the download's row: kept when the row goes away. */
    var row by mutableStateOf<RealDebridQueue.Row?>(null)
    var error by mutableStateOf<String?>(null)

    init {
        // read off the main thread, written on it: a write from another thread while this model is made during
        // composition is lost (see GlassManualScreen)
        screenModelScope.launch {
            options = withContext(Dispatchers.IO) { TorrentDownload.options(volumes, here.toSet(), cachedCoverage(app, mediaId)) }
        }
        screenModelScope.launch {
            queue.rows.collect { rows ->
                val id = workId ?: return@collect
                rows.firstOrNull { it.workId == id }?.let { row = it }
            }
        }
    }

    fun start(o: TorrentDownload.Option) {
        reset()
        step = TorrentStep.RUN
        val e = entry
        if (e == null) {
            error = ""
            return
        }
        // queued for the wanted volumes the torrent holds: only their files are fetched
        runCatching { queue.enqueueTorrent(e, o.adds, o.sourceId, o.magnet) }
            .onSuccess { workId = it }
            .onFailure { error = it.message.orEmpty() }
    }

    fun cancel() {
        workId?.let(queue::cancel)
    }

    fun reset() {
        workId = null
        row = null
        error = null
    }
}

@Composable
private fun PickTorrent(model: TorrentModel, options: List<TorrentDownload.Option>) {
    Heading(stringResource(MR.strings.glass_torrent_title))
    Dim(stringResource(MR.strings.glass_torrent_sub), Modifier.padding(top = 4.dp))
    if (options.isEmpty()) {
        Text(stringResource(MR.strings.glass_torrent_none), fontSize = Glass.body, modifier = Modifier.padding(top = 24.dp))
        return
    }
    Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, o ->
            val on = i == model.selected
            Column(
                Modifier
                    .fillMaxWidth()
                    .glassPanel()
                    .then(if (on) Modifier.border(1.dp, Glass.ice, RoundedCornerShape(Glass.r16)) else Modifier)
                    .clickable { model.selected = i }
                    .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (i == 0 && o.seeders != 0) CapsLabel(stringResource(MR.strings.glass_manual_suggested), Glass.ice)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            listOfNotNull(stringResource(MR.strings.glass_manual_vol, CoverageMatch.ranges(o.volumes)), o.size)
                                .joinToString(" · "),
                            fontSize = Glass.label,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Dim(o.sourceName)
                    }
                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier.size(22.dp).border(2.dp, if (on) Glass.ice else Color.White, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (on) Box(Modifier.size(10.dp).clip(CircleShape).background(Glass.ice))
                        }
                    }
                }
                val already = if (o.alreadyHere.isEmpty()) {
                    ""
                } else {
                    " · " + stringResource(MR.strings.glass_manual_already, CoverageMatch.ranges(o.alreadyHere))
                }
                Text(
                    stringResource(MR.strings.glass_manual_adds, CoverageMatch.ranges(o.adds)) + already,
                    fontSize = Glass.body,
                    modifier = Modifier.padding(end = 8.dp),
                )
                val lacks = model.volumes.filter { it !in o.adds && it !in model.here }
                if (lacks.isNotEmpty()) {
                    Dim(stringResource(MR.strings.glass_manual_lacks, CoverageMatch.ranges(lacks)))
                }
                when (o.seeders) {
                    null -> Dim(stringResource(MR.strings.glass_torrent_seeders_unknown))
                    0 -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        GlassIcon("alert", size = 18.dp)
                        Text(stringResource(MR.strings.glass_torrent_seeders_none), fontSize = Glass.body)
                    }
                    else -> Dim(stringResource(MR.strings.glass_torrent_seeders, o.seeders))
                }
            }
        }
    }
}

@Composable
private fun Notice() {
    Heading(stringResource(MR.strings.glass_torrent_notice_title))
    Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            MR.strings.glass_torrent_notice_ip,
            MR.strings.glass_torrent_notice_share,
            MR.strings.glass_torrent_notice_data,
        ).forEach { line ->
            Text(
                stringResource(line),
                fontSize = Glass.body,
                modifier = Modifier.fillMaxWidth().glassPanel().padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun Running(model: TorrentModel) {
    val row = model.row
    val ok = row?.state == RealDebridQueue.State.DONE
    val failed = row?.state == RealDebridQueue.State.FAILED || model.error != null
    Column(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        when {
            ok -> {
                Box(Modifier.size(56.dp).border(2.dp, Glass.ice, CircleShape), contentAlignment = Alignment.Center) {
                    GlassIcon("check", size = 28.dp, tint = Glass.ice)
                }
                Text(
                    stringResource(MR.strings.glass_torrent_done, CoverageMatch.ranges(row!!.delivered.ifEmpty { row.volumes })),
                    fontSize = Glass.heading,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            failed -> {
                GlassIcon("alert", size = 40.dp)
                Text(
                    row?.reason ?: model.error?.ifEmpty { null } ?: stringResource(MR.strings.glass_torrent_failed),
                    fontSize = Glass.label,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            else -> {
                Text(
                    stringResource(
                        if (row?.state == RealDebridQueue.State.QUEUED) MR.strings.glass_manual_waiting else MR.strings.glass_torrent_downloading,
                    ),
                    fontSize = Glass.heading,
                    fontWeight = FontWeight.SemiBold,
                )
                row?.step?.let { Dim(it, Modifier.padding(top = 4.dp)) }
                if (row != null && row.total > 0) {
                    Meter(row.bytes.toFloat() / row.total, Modifier.fillMaxWidth().padding(top = 16.dp))
                }
                Dim(stringResource(MR.strings.glass_torrent_leave), Modifier.padding(top = 24.dp))
            }
        }
    }
}
