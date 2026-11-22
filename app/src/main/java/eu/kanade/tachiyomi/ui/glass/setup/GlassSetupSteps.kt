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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
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
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassIcon
import eu.kanade.presentation.glass.glassPanel
import eu.kanade.presentation.more.settings.screen.SettingsDataScreen
import eu.kanade.tachiyomi.data.anilist.AniListLibrary
import eu.kanade.tachiyomi.data.track.anilist.AnilistApi
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
                .width(520.dp)
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
        SetupButton(
            stringResource(if (rd == Rd.CHECKING) MR.strings.glass_dl_rd_checking else MR.strings.glass_setup_check),
            model::checkKey,
            enabled = !empty && rd != Rd.CHECKING,
        )
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
    val name = model.dictionary
    if (name != null) {
        DoneCard(name, stringResource(MR.strings.glass_setup_dictionary_sub))
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SetupButton(
                stringResource(if (model.importing) MR.strings.glass_setup_importing else MR.strings.glass_setup_import),
                {
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
                },
                enabled = !model.importing,
                leading = "plus",
            )
            SetupButton(stringResource(MR.strings.glass_setup_not_now), model::skipDictionary, primary = false)
        }
        val error = model.importError
        Note(
            when {
                error != null -> stringResource(MR.strings.glass_setup_dictionary_failed, error)
                model.dictionarySkipped -> stringResource(MR.strings.glass_setup_dictionary_skipped)
                else -> stringResource(MR.strings.glass_setup_dictionary_note)
            },
        )
    }
    Column(Modifier.width(520.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Glass.line))
        Note(stringResource(MR.strings.glass_setup_dictionary_more), Modifier.padding(top = 8.dp))
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
        model.dictionary.orEmpty(),
    )
    val skipped = listOf(
        false,
        false,
        false,
        model.notificationsSkipped,
        model.dictionarySkipped,
    )
    val skippedText = listOf(
        "",
        "",
        "",
        stringResource(MR.strings.glass_setup_notifications_skip_row),
        stringResource(MR.strings.glass_setup_dictionary_skip_row),
    )
    val notDone = stringResource(MR.strings.glass_setup_not_done)
    val notSet = stringResource(MR.strings.glass_setup_not_set)
    // "done" for 3 and 4 includes skipped; a row is "ok" only when really set
    val ok = listOf(model.done(0), model.done(1), model.done(2), model.notificationsAllowed, model.dictionary != null)
    Column(Modifier.width(520.dp).glassPanel(Glass.r16).padding(start = 20.dp, end = 16.dp)) {
        names.forEachIndexed { i, n ->
            val fix = !ok[i] && GlassSetupLogic.needed(i)
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.08f)))
            Row(
                // chimahon: 44, not the canvas' 52: five rows fit above the buttons on a 800dp-high landscape tablet
                Modifier.fillMaxWidth().height(44.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                GlassIcon(
                    if (ok[i]) "check" else if (fix) "alert" else "minus",
                    size = 20.dp,
                    tint = if (ok[i] || fix) Color.White else Glass.secondary,
                )
                Text(n, Modifier.width(120.dp), fontSize = Glass.label, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(
                    when {
                        ok[i] -> values[i]
                        fix -> notDone
                        skipped[i] -> skippedText[i]
                        else -> notSet
                    },
                    Modifier.weight(1f),
                    color = if (fix) Color.White else Glass.secondary,
                    fontSize = Glass.body,
                    maxLines = 1,
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
 * Unlike [eu.kanade.presentation.glass.Pill] it can put the icon after the text (Continue, Sign in).
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
    val ink = when {
        !enabled -> Glass.secondary
        primary -> Color.Black
        else -> Color.White
    }
    Row(
        Modifier
            .height(if (small) 36.dp else Glass.button)
            .clip(CircleShape)
            .then(
                when {
                    !enabled -> Modifier.background(Glass.line)
                    primary -> Modifier.background(Color.White)
                    else -> Modifier.border(1.dp, Glass.line, CircleShape)
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = if (leading != null) 18.dp else 24.dp, end = if (trailing != null) 14.dp else 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        leading?.let { GlassIcon(it, size = 18.dp, tint = ink) }
        Text(
            text,
            color = ink,
            fontSize = if (small) Glass.body else Glass.label,
            fontWeight = if (primary && !small) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
        trailing?.let { GlassIcon(it, size = 20.dp, tint = ink) }
    }
}

/** 72dp panel with a check: the "done" state of every step. */
@Composable
private fun DoneCard(title: String, sub: String?) {
    Row(
        Modifier.width(520.dp).height(72.dp).glassPanel(Glass.r16).padding(horizontal = 20.dp),
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
