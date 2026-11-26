package eu.kanade.tachiyomi.ui.glass.setup

import android.Manifest
import android.content.ActivityNotFoundException
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassChip
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.StatusColourSet
import eu.kanade.tachiyomi.ui.glass.title.VolState
import eu.kanade.tachiyomi.ui.glass.title.VolumeTile
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.remember
import eu.kanade.presentation.glass.Pill
import eu.kanade.presentation.glass.GlassIcon
import eu.kanade.presentation.glass.GlassToggle
import eu.kanade.presentation.glass.glassPanel
import eu.kanade.presentation.more.settings.screen.SettingsDataScreen
import eu.kanade.tachiyomi.data.anilist.AniListLibrary
import eu.kanade.tachiyomi.data.dictionary.DictionaryDownloads
import eu.kanade.tachiyomi.data.dictionary.RecommendedDictionaries
import eu.kanade.tachiyomi.data.track.anilist.AnilistApi
import eu.kanade.tachiyomi.ui.glass.dictionary.DictionaryPanel
import eu.kanade.tachiyomi.ui.glass.dictionary.Status
import eu.kanade.tachiyomi.ui.glass.setup.GlassSetupModel.Rd
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.toast
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

// The five steps and the summary of canvas `Setup-2` (the left column under the title).

@Composable
internal fun AniListStep(model: GlassSetupModel, signedIn: Boolean, entries: List<AniListLibrary.Entry>) {
    val context = LocalContext.current
    if (!signedIn) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SetupButton(
                stringResource(MR.strings.glass_setup_sign_in),
                // back through TrackLoginActivity, which restarts MainActivity with this setup still on screen
                { context.openInBrowser(AnilistApi.authUrl(), forceDefaultBrowser = true) },
                trailing = "external",
            )
            Note(stringResource(MR.strings.glass_setup_sign_in_note))
        }
    } else {
        val reading = entries.count { it.status == "CURRENT" }
        val planning = entries.count { it.status == "PLANNING" }
        DoneCard(
            model.name ?: stringResource(MR.strings.glass_setup_signed_in),
            if (entries.isEmpty()) null else stringResource(MR.strings.glass_setup_anilist_counts, reading + planning, reading, planning),
        )
    }
}

@Composable
internal fun RealDebridStep(model: GlassSetupModel) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val rd = model.rd
    val empty = model.key.isEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Label(stringResource(MR.strings.glass_setup_api_key))
        val shape = RoundedCornerShape(Glass.r12)
        Row(
            Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .height(56.dp)
                .clip(shape)
                .background(Glass.panel)
                .border(1.dp, if (rd == Rd.REJECTED) Color.White else Glass.line, shape)
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GlassIcon("key", size = 20.dp, tint = Glass.secondary)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (empty) Text(stringResource(MR.strings.glass_setup_paste_hint), color = Glass.secondary, fontSize = Glass.label, maxLines = 1)
                BasicTextField(
                    value = model.key,
                    onValueChange = model::onKey,
                    singleLine = true,
                    visualTransformation = MaskKey,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    textStyle = TextStyle(color = Color.White, fontSize = Glass.label, letterSpacing = 1.sp),
                    cursorBrush = SolidColor(Color.White),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (empty) {
                SetupButton(
                    stringResource(MR.strings.glass_setup_paste),
                    { clipboard.getText()?.text?.let(model::onKey) },
                    primary = false,
                    small = true,
                )
            } else if (model.realDebridDone) {
                GlassIcon("check", size = 20.dp, modifier = Modifier.padding(end = 8.dp))
            } else {
                // empties the field; onKey also drops the rejected / not Premium line
                Box(
                    Modifier.size(48.dp).clip(CircleShape).clickable { model.onKey("") },
                    contentAlignment = Alignment.Center,
                ) {
                    GlassIcon("close", size = 18.dp, tint = Glass.secondary, contentDescription = stringResource(MR.strings.glass_setup_clear_key))
                }
            }
        }
        when (rd) {
            Rd.REJECTED -> Alert(stringResource(MR.strings.glass_setup_rejected))
            Rd.OFFLINE -> Alert(stringResource(MR.strings.glass_setup_offline))
            Rd.FREE -> Alert(stringResource(MR.strings.glass_setup_not_premium))
            else -> {}
        }
        Row(
            Modifier.heightIn(min = 36.dp).clickable { context.openInBrowser("https://real-debrid.com/apitoken") },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(MR.strings.glass_setup_get_key), color = Glass.ice, fontSize = Glass.body)
            GlassIcon("external", size = 16.dp, tint = Glass.ice)
        }
    }
    if (rd == Rd.PREMIUM) {
        DoneCard(stringResource(MR.strings.glass_setup_key_ok), stringResource(MR.strings.glass_dl_rd_premium, model.daysLeft))
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SetupButton(
                stringResource(if (rd == Rd.CHECKING) MR.strings.glass_dl_rd_checking else MR.strings.glass_setup_check),
                model::checkKey,
                enabled = !empty && rd != Rd.CHECKING,
            )
            // since 1.2.1 volumes also come by hand and as torrents: the key can wait
            SetupButton(stringResource(MR.strings.glass_setup_not_now), model::skipRealDebrid, primary = false)
        }
        if (model.realDebridSkipped) Note(stringResource(MR.strings.glass_setup_rd_skipped))
    }
}

@Composable
internal fun StorageStep(model: GlassSetupModel) {
    val context = LocalContext.current
    val pref = model.storagePreferences.baseStorageDirectory()
    val pick = SettingsDataScreen.storageLocationPicker(pref)
    val choose: () -> Unit = {
        // as Settings › Data and storage does
        try {
            StorageManager.allowAccessStorage(context, pref) { pick.launch(null) }
        } catch (_: Exception) {
            context.toast(MR.strings.file_picker_error)
        }
    }
    if (!model.storageOk) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SetupButton(stringResource(MR.strings.glass_setup_choose_folder), choose, leading = "folder")
            Note(stringResource(MR.strings.glass_setup_folder_note))
        }
    } else {
        val free = model.freeBytes
        val series = model.seriesCount
        DoneCard(
            model.storagePath.orEmpty(),
            if (free != null && series != null) {
                stringResource(MR.strings.glass_setup_folder_line, Formatter.formatShortFileSize(context, free), series)
            } else {
                null
            },
        )
        SetupButton(stringResource(MR.strings.glass_setup_another_folder), choose, primary = false, leading = "folder")
    }
    StatusColourChoice()
}

@Composable
internal fun NotificationsStep(model: GlassSetupModel) {
    // the permission is read by the model at start and by the screen on every resume
    val requester = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        model.notificationsAllowed = granted
    }
    if (model.notificationsAllowed) {
        DoneCard(stringResource(MR.strings.glass_setup_allowed), stringResource(MR.strings.glass_setup_allowed_sub))
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SetupButton(
                stringResource(MR.strings.glass_setup_allow),
                {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        requester.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
            )
            SetupButton(stringResource(MR.strings.glass_setup_not_now), model::skipNotifications, primary = false)
        }
        if (model.notificationsSkipped) Note(stringResource(MR.strings.glass_setup_notifications_skipped))
    }
}

@Composable
internal fun DictionaryStep(model: GlassSetupModel) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(model::importDictionary)
    }
    val pickZip: () -> Unit = {
        try {
            // the same types as Settings › Dictionaries
            picker.launch(
                arrayOf(
                    "application/zip",
                    "application/x-zip-compressed",
                    "application/x-zip",
                    "application/x-compressed",
                    "application/octet-stream",
                ),
            )
        } catch (_: ActivityNotFoundException) {
            context.toast(MR.strings.file_picker_error)
        }
    }
    val others by DictionaryDownloads.others.collectAsStateWithLifecycle()
    val states = model.downloads
    val busy = model.dictionaryDownloading
    // canvas Dict-1: rows with switches, one Download pill; the pill's label follows the switches. 12dp, not the
    // frame's 16dp, so the panel, own line, actions and licence fit a landscape tablet screen with no scroll.
    Column(Modifier.widthIn(max = 520.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DictionaryPanel(states) { entry ->
                if (busy) {
                    Status(stringResource(MR.strings.glass_dict_not_chosen))
                } else {
                    val on = entry.id in model.picked
                    val label = stringResource(MR.strings.glass_dict_switch, entry.name)
                    GlassToggle(
                        on,
                        onCheckedChange = { model.picked = if (it) model.picked + entry.id else model.picked - entry.id },
                        modifier = Modifier.semantics { contentDescription = label },
                    )
                }
            }
            // the person's own, count first so the ellipsis only cuts the name
            RecommendedDictionaries.ownSummary(others)?.let {
                Text(
                    it,
                    Modifier.padding(horizontal = 20.dp),
                    color = Glass.secondary,
                    fontSize = Glass.body,
                    lineHeight = 20.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        val idle = RecommendedDictionaries.all.filter { states[it.id] == null }
        val importing = if (model.importing) stringResource(MR.strings.glass_setup_importing) else null
        // the action row and the licence sit together, no gap: 12dp more and the licence falls under Back / Continue
        Column {
        if (!busy && idle.isNotEmpty()) {
            val chosen = idle.filter { it.id in model.picked }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SetupButton(
                    if (chosen.isEmpty()) {
                        stringResource(MR.strings.glass_setup_dict_download_none)
                    } else {
                        stringResource(MR.strings.glass_setup_dict_download, chosen.size, RecommendedDictionaries.totalMb(chosen))
                    },
                    model::downloadPicked,
                    enabled = chosen.isNotEmpty(),
                    leading = "download",
                )
                QuietButton(importing ?: stringResource(MR.strings.glass_setup_dict_import), pickZip, enabled = !model.importing)
                QuietButton(stringResource(MR.strings.glass_setup_not_now), model::skipDictionary)
            }
        } else {
            QuietButton(importing ?: stringResource(MR.strings.glass_setup_dict_import_too), pickZip, enabled = !model.importing, leading = "plus")
        }
        // one line; mining and word colours are left to Settings (no room here)
        Text(
            stringResource(MR.strings.glass_setup_dict_licence),
            color = Glass.secondary,
            fontSize = Glass.caption,
            lineHeight = 16.sp,
            modifier = Modifier.padding(start = 20.dp),
        )
        }
        val error = model.importError
        when {
            error != null -> Note(stringResource(MR.strings.glass_setup_dictionary_failed, error))
            model.dictionarySkipped && model.dictionary == null && !busy -> Note(stringResource(MR.strings.glass_setup_dictionary_skipped))
        }
    }
}

@Composable
internal fun SummaryStep(model: GlassSetupModel, names: List<String>, entries: List<AniListLibrary.Entry>) {
    val context = LocalContext.current
    val reading = entries.count { it.status == "CURRENT" }
    val planning = entries.count { it.status == "PLANNING" }
    val values = listOf(
        listOfNotNull(model.name ?: stringResource(MR.strings.glass_setup_signed_in), (reading + planning).takeIf { it > 0 }?.let { "$it manga" })
            .joinToString(" · "),
        if (model.rd == Rd.PREMIUM) {
            stringResource(MR.strings.glass_dl_rd_premium, model.daysLeft)
        } else {
            GlassSetupLogic.masked(model.key)
        },
        listOfNotNull(model.storagePath, model.freeBytes?.let { Formatter.formatShortFileSize(context, it) + " free" }).joinToString(" · "),
        stringResource(MR.strings.glass_setup_allowed),
        if (model.dictionaryDownloading) stringResource(MR.strings.glass_setup_dict_downloading_row) else model.dictionaryLine,
        stringResource(
            when {
                !model.colouringOn -> MR.strings.glass_setup_colouring_off_row
                model.paired -> MR.strings.glass_setup_colouring_on_paired_row
                else -> MR.strings.glass_setup_colouring_on_row
            },
        ),
    )
    val skipped = listOf(
        false,
        model.realDebridSkipped,
        false,
        model.notificationsSkipped,
        model.dictionarySkipped,
        model.colouringSkipped,
    )
    val skippedText = listOf(
        "",
        stringResource(MR.strings.glass_setup_rd_skip_row),
        "",
        stringResource(MR.strings.glass_setup_notifications_skip_row),
        stringResource(MR.strings.glass_setup_dictionary_skip_row),
        stringResource(MR.strings.glass_setup_colouring_skip_row),
    )
    val notDone = stringResource(MR.strings.glass_setup_not_done)
    val notSet = stringResource(MR.strings.glass_setup_not_set)
    // "done" for 1, 3, 4 and 5 includes skipped; a row is "ok" only when really set
    val ok = listOf(model.done(0), model.realDebridDone, model.done(2), model.notificationsAllowed, model.dictionary != null, !model.colouringSkipped)
    Column(Modifier.widthIn(max = 520.dp).fillMaxWidth().glassPanel(Glass.r16).padding(start = 20.dp, end = 16.dp)) {
        names.forEachIndexed { i, n ->
            val fix = !ok[i] && GlassSetupLogic.needed(i)
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.08f)))
            Row(
                // chimahon: 44, not the canvas' 52: five rows fit above the buttons on a 800dp-high landscape tablet.
                // At least 44: on a phone a value takes two lines ("Skipped · add a key in Settings").
                Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                GlassIcon(
                    if (ok[i]) "check" else if (fix) "alert" else "minus",
                    size = 20.dp,
                    tint = if (ok[i] || fix) Color.White else Glass.secondary,
                )
                Text(n, Modifier.width(132.dp), fontSize = Glass.label, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(
                    when {
                        ok[i] -> values[i]
                        i == 4 && model.dictionaryDownloading -> values[i]
                        fix -> notDone
                        skipped[i] -> skippedText[i]
                        else -> notSet
                    },
                    Modifier.weight(1f),
                    color = if (fix) Color.White else Glass.secondary,
                    fontSize = Glass.body,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (fix) {
                    SetupButton(stringResource(MR.strings.glass_setup_set_up), { model.step = i }, primary = false, small = true)
                }
            }
        }
    }
}

/**
 * The setup's pill: white primary or outlined; disabled = the 18% fill with 62% text (canvas Continue / Open Home).
 * A [Pill] with the setup's argument order (icon before the text = [leading], after = [trailing]).
 */
@Composable
internal fun SetupButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    primary: Boolean = true,
    leading: String? = null,
    trailing: String? = null,
    small: Boolean = false,
) {
    Pill(text, onClick, primary = primary, icon = leading, small = small, enabled = enabled, trailingIcon = trailing)
}

/** 72dp panel with a check: the "done" state of every step. */
@Composable
private fun DoneCard(title: String, sub: String?) {
    Row(
        Modifier.widthIn(max = 520.dp).fillMaxWidth().height(72.dp).glassPanel(Glass.r16).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        GlassIcon("check", size = 20.dp)
        Column {
            Text(title, fontSize = Glass.label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            sub?.let { Text(it, color = Glass.secondary, fontSize = Glass.body, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** Canvas Dict-1's quiet text button ("Import a .zip instead", "Not now"): no fill, no outline, 14sp. */
@Composable
internal fun QuietButton(text: String, onClick: () -> Unit, enabled: Boolean = true, leading: String? = null) {
    Row(
        Modifier
            .height(Glass.button)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val ink = if (enabled) Color.White else Glass.secondary
        leading?.let { GlassIcon(it, size = 18.dp, tint = ink) }
        Text(text, color = ink, fontSize = Glass.body, maxLines = 1)
    }
}

@Composable
private fun Note(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = Glass.secondary, fontSize = Glass.body)
}

@Composable
private fun Alert(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GlassIcon("alert", size = 18.dp)
        Text(text, color = Color.White, fontSize = Glass.body)
    }
}

@Composable
private fun Label(text: String) {
    Text(text, color = Glass.secondary, fontSize = Glass.caption, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
}

/** The key as dots but its last 4 characters, same length (so the cursor maps 1:1). */
private object MaskKey : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(AnnotatedString(GlassSetupLogic.masked(text.text)), OffsetMapping.Identity)
}

/** The volume status colour set, as chips with a preview of the picked set (On device, Failed, Not asked for). */
@Composable
private fun StatusColourChoice() {
    val pref = remember { Injekt.get<UiPreferences>().glassStatusColours() }
    val set by pref.collectAsState()
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(MR.strings.glass_status_colours),
            color = Glass.secondary,
            fontSize = Glass.caption,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusColourSet.entries.forEach {
                GlassChip(stringResource(it.titleRes), selected = it == set, onClick = { pref.set(it) })
            }
            Spacer(Modifier.weight(1f))
            listOf(VolState.HERE, VolState.FAILED, VolState.NONE).forEach {
                VolumeTile(it, null, null, Modifier.width(26.dp), fraction = 0.6f)
            }
        }
    }
}
