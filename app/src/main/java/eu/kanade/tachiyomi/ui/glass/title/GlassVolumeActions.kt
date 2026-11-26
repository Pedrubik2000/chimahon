package eu.kanade.tachiyomi.ui.glass.title

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassChip
import eu.kanade.presentation.glass.GlassIcon
import eu.kanade.presentation.glass.Pill
import eu.kanade.tachiyomi.data.coverage.CoverageMatch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

// Volume actions of the glass title page (canvas kumahon-volume-actions, option C): the long-press popover, Select
// mode's toolbar (tablet) or dock (phone), and the delete sheet. State lives in GlassTitleScreen.

/** A cell in Select mode: [reading] = the locked volume being read (its file can't go). */
internal data class CellSelect(val picked: Boolean, val reading: Boolean)

/** What the popover of one volume does ([locked] = volumes whose file never goes). */
internal class VolumeActions(
    val open: (LocalVolume) -> Unit,
    val readUpTo: (Int) -> Unit,
    val setRead: (LocalVolume, Boolean) -> Unit,
    val bookmark: (LocalVolume) -> Unit,
    val delete: (Int) -> Unit,
    val selectMore: (Int) -> Unit,
    val locked: Set<Int>,
    val size: suspend (Int) -> Long,
)

/** The popover next to a cover held down: Open, Mark read up to here, Mark read/unread, Bookmark, Delete file, Select more. */
@Composable
internal fun VolumeMenu(expanded: Boolean, onDismiss: () -> Unit, number: Int, local: LocalVolume, actions: VolumeActions) {
    var bytes by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(expanded) { if (expanded) bytes = actions.size(number) }
    GlassMenu(expanded, onDismiss) {
        Text(
            bytes?.let { stringResource(MR.strings.glass_title_vol_here, number, GlassTitleState.sizeText(it)) }
                ?: stringResource(MR.strings.glass_title_vol_not_here, number),
            color = Glass.secondary,
            fontSize = Glass.caption,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        MenuItem(stringResource(MR.strings.action_open)) { onDismiss(); actions.open(local) }
        MenuItem(stringResource(MR.strings.glass_title_read_up_to)) { onDismiss(); actions.readUpTo(number) }
        MenuItem(stringResource(if (local.read) MR.strings.glass_title_mark_unread else MR.strings.glass_title_mark_read)) {
            onDismiss()
            actions.setRead(local, !local.read)
        }
        MenuItem(stringResource(if (local.bookmark) MR.strings.glass_title_unbookmark else MR.strings.glass_title_bookmark)) {
            onDismiss()
            actions.bookmark(local)
        }
        if (number !in actions.locked) {
            MenuItem(stringResource(MR.strings.glass_title_delete_file, GlassTitleState.sizeText(bytes ?: 0L))) {
                onDismiss()
                actions.delete(number)
            }
        }
        MenuItem(stringResource(MR.strings.glass_title_select_more)) { onDismiss(); actions.selectMore(number) }
    }
}

/** A cover's overlay: Select mode's tick (and the "Reading" label), else a bookmark mark. */
@Composable
internal fun BoxScope.CellOverlay(select: CellSelect?, bookmarked: Boolean) {
    if (select != null) {
        if (!select.reading) Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp)
                .padding(bottom = 4.dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(if (select.picked) Glass.ice else Glass.scrim)
                .border(1.5.dp, if (select.picked) Glass.ice else Color.White.copy(alpha = 0.8f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (select.picked) GlassIcon("check", size = 14.dp, tint = Color.Black)
        }
        if (select.reading) {
            Text(
                stringResource(MR.strings.glass_title_reading),
                fontSize = Glass.caption,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp)
                    .clip(CircleShape)
                    .background(Glass.scrim)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    } else if (bookmarked) {
        GlassIcon("bookmark", size = 16.dp, modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp))
    }
}

/** Select mode's header: ✕ and "N selected", [actions] beside them (tablet; the phone has a dock), the quick picks below. */
@Composable
internal fun SelectBar(title: String, onClose: () -> Unit, actions: (@Composable () -> Unit)?, picks: @Composable () -> Unit) {
    val shape = RoundedCornerShape(Glass.r16)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Glass.panel).border(1.dp, Glass.line, shape).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                GlassIcon("close", size = 18.dp)
            }
            Text(title, fontSize = Glass.body, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
            actions?.invoke()
        }
        picks()
    }
}

/** Quick picks: All, Read, On tablet unread, Not fetched, and a range (tap first, tap last). */
@Composable
internal fun PickRow(
    cells: List<VolumeCell>,
    locked: Set<Int>,
    picked: Set<Int>,
    ranging: Boolean,
    rangeFrom: Int?,
    onPick: (Set<Int>) -> Unit,
    onRange: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                GlassTitleState.Pick.ALL to MR.strings.glass_title_pick_all,
                GlassTitleState.Pick.READ to MR.strings.glass_title_pick_read,
                GlassTitleState.Pick.UNREAD_HERE to MR.strings.glass_title_pick_unread_here,
                GlassTitleState.Pick.NOT_FETCHED to MR.strings.glass_title_pick_not_fetched,
            ).forEach { (kind, label) ->
                val set = GlassTitleState.pick(cells, kind, locked)
                if (set.isNotEmpty()) GlassChip(stringResource(label, set.size), selected = picked == set, onClick = { onPick(set) })
            }
            GlassChip(stringResource(MR.strings.glass_title_pick_range), selected = ranging, onClick = onRange)
        }
        if (ranging) {
            Text(
                if (rangeFrom == null) {
                    stringResource(MR.strings.glass_title_range_first)
                } else {
                    stringResource(MR.strings.glass_title_range_last, rangeFrom)
                },
                color = Glass.ice,
                fontSize = Glass.caption,
            )
        }
    }
}

/** Read, Unread, Mark (bookmark), Get and Delete files of the picked volumes; each shows only when it has something to act on. */
@Composable
internal fun SelectActions(
    hasLocal: Boolean,
    allMarked: Boolean,
    canGet: Boolean,
    canDelete: Boolean,
    onRead: () -> Unit,
    onUnread: () -> Unit,
    onMark: () -> Unit,
    onGet: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (hasLocal) {
            GlassChip(stringResource(MR.strings.glass_title_act_read), selected = false, onClick = onRead, leading = "check")
            GlassChip(stringResource(MR.strings.glass_title_act_unread), selected = false, onClick = onUnread, leading = "undo")
            GlassChip(
                stringResource(if (allMarked) MR.strings.glass_title_act_unmark else MR.strings.glass_title_act_mark),
                selected = false,
                onClick = onMark,
                leading = "bookmark",
            )
        }
        if (canGet) GlassChip(stringResource(MR.strings.glass_title_act_get), selected = false, onClick = onGet, leading = "download")
        if (canDelete) GlassChip(stringResource(MR.strings.glass_title_act_delete), selected = true, onClick = onDelete)
    }
}

/**
 * The confirm sheet of a file delete: every volume as a numbered box (white = goes, ice ring = the one being read,
 * outlined = stays on the tablet, faint = not on the tablet), what goes and what stays, and the promise.
 */
@Composable
internal fun BoxScope.DeleteSheet(
    cells: List<VolumeCell>,
    goes: Set<Int>,
    reading: Int?,
    bytes: Long,
    onlyHere: List<Int>,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val sum = GlassTitleState.deleteSummary(cells, goes, reading)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable(onClick = onCancel))
    val shape = RoundedCornerShape(Glass.r16)
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .padding(16.dp)
            .widthIn(max = 720.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(Glass.panel)
            .border(1.dp, Glass.line, shape)
            .clickable(enabled = false) {}
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (sum.goes.size == 1) {
                    stringResource(MR.strings.glass_title_delete_ask_one)
                } else {
                    stringResource(MR.strings.glass_title_delete_ask, sum.goes.size)
                },
                fontSize = Glass.heading,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Pill(stringResource(MR.strings.action_cancel), onClick = onCancel, small = true)
            Pill(
                stringResource(MR.strings.glass_title_sheet_delete, sum.goes.size, GlassTitleState.sizeText(bytes)),
                onClick = onConfirm,
                primary = true,
                small = true,
                enabled = sum.goes.isNotEmpty(),
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            cells.forEach { c ->
                val n = c.number
                val box = Modifier.size(26.dp).clip(RoundedCornerShape(4.dp))
                val (modifier, ink) = when {
                    n in sum.goes -> box.background(Color.White) to Color.Black
                    n in sum.reading -> box.border(2.dp, Glass.ice, RoundedCornerShape(4.dp)) to Color.White
                    n in sum.stays -> box.border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(4.dp)) to Color.White
                    else -> box to Glass.secondary
                }
                Box(modifier, contentAlignment = Alignment.Center) { Text("$n", color = ink, fontSize = Glass.caption) }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (sum.goes.isNotEmpty()) {
                Text(
                    stringResource(MR.strings.glass_title_sheet_goes, CoverageMatch.ranges(sum.goes), GlassTitleState.sizeText(bytes)),
                    fontSize = Glass.caption,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (sum.reading.isNotEmpty()) {
                Text(stringResource(MR.strings.glass_title_sheet_reading, CoverageMatch.ranges(sum.reading)), color = Glass.ice, fontSize = Glass.caption)
            }
            if (sum.stays.isNotEmpty()) {
                Text(stringResource(MR.strings.glass_title_sheet_stays, CoverageMatch.ranges(sum.stays)), color = Glass.secondary, fontSize = Glass.caption)
            }
            if (sum.queued.isNotEmpty()) {
                Text(stringResource(MR.strings.glass_title_sheet_queue, CoverageMatch.ranges(sum.queued)), color = Glass.secondary, fontSize = Glass.caption)
            }
            // a mark kept only by the row (read here, above AniList's count) goes with the file: say so
            when {
                onlyHere.size == 1 -> Text(stringResource(MR.strings.glass_title_read_only_here_one, onlyHere.first()), color = Glass.red, fontSize = Glass.caption)
                onlyHere.isNotEmpty() ->
                    Text(stringResource(MR.strings.glass_title_read_only_here, CoverageMatch.ranges(onlyHere)), color = Glass.red, fontSize = Glass.caption)
            }
            Text(stringResource(MR.strings.glass_title_sheet_only_files), color = Glass.secondary, fontSize = Glass.caption)
        }
    }
}
