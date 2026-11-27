package eu.kanade.tachiyomi.ui.glass.settings

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassChip
import eu.kanade.presentation.glass.GlassIcon
import eu.kanade.presentation.glass.Pill
import eu.kanade.presentation.glass.glassPanel
import eu.kanade.presentation.more.settings.screen.SettingsDataScreen
import eu.kanade.presentation.more.settings.screen.data.CreateBackupScreen
import eu.kanade.presentation.more.settings.screen.data.RestoreBackupScreen
import eu.kanade.presentation.util.relativeTimeSpanString
import eu.kanade.tachiyomi.data.backup.create.BackupCreateJob
import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreJob
import eu.kanade.tachiyomi.data.sync.SyncDataJob
import eu.kanade.tachiyomi.util.system.DeviceUtil
import eu.kanade.tachiyomi.util.system.toast
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.storage.service.StorageManager.Companion.allowAccessStorage
import tachiyomi.domain.storage.service.StoragePreferences
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.core.common.i18n.stringResource as contextString
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** The automatic-backup intervals shown as chips (hours; 0 = off). The Classic list has more; these are the canvas five. */
private val IntervalHours = listOf(6, 12, 24, 168, 0)

/**
 * Canvas `option-b` card 3: Backup and restore at the top of Settings > Library. Calls the same things the
 * Classic Data screen does: [CreateBackupScreen], [RestoreBackupScreen] (after the same file chooser), the same
 * [BackupPreferences.backupInterval] + [BackupCreateJob.setupTask], the same storage-location picker, [SyncDataJob].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GlassBackupCard() {
    val context = LocalContext.current
    val navigator = LocalNavigator.currentOrThrow
    val backupPrefs = remember { Injekt.get<BackupPreferences>() }
    val storagePrefs = remember { Injekt.get<StoragePreferences>() }
    val syncPrefs = remember { Injekt.get<SyncPreferences>() }
    val last by backupPrefs.lastAutoBackupTimestamp().collectAsState()
    val interval by backupPrefs.backupInterval().collectAsState()
    val syncService by syncPrefs.syncService().collectAsState()
    val lastSync by syncPrefs.lastSyncTimestamp().collectAsState()
    val storagePref = storagePrefs.baseStorageDirectory()
    val pickLocation = SettingsDataScreen.storageLocationPicker(storagePref)
    val location = SettingsDataScreen.storageLocationText(storagePref)

    val chooseBackup = rememberLauncherForActivityResult(
        object : ActivityResultContracts.GetContent() {
            override fun createIntent(context: Context, input: String): Intent =
                Intent.createChooser(super.createIntent(context, input), context.contextString(MR.strings.file_select_backup))
        },
    ) {
        if (it == null) {
            context.toast(MR.strings.file_null_uri_error)
        } else {
            navigator.push(RestoreBackupScreen(it.toString()))
        }
    }
    val restore: () -> Unit = {
        if (!BackupRestoreJob.isRunning(context)) {
            if (DeviceUtil.isMiui && DeviceUtil.isMiuiOptimizationDisabled()) context.toast(MR.strings.restore_miui_warning)
            chooseBackup.launch("*/*")
        } else {
            context.toast(MR.strings.restore_in_progress)
        }
    }

    // the card's own width decides: the chip row (five intervals) plus its label need about 760dp
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val narrow = maxWidth < 760.dp
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // status row: last backup, Back up now, Restore
        val statusText: @Composable (Modifier) -> Unit = { m ->
            Column(m, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(MR.strings.label_backup), fontSize = Glass.label, fontWeight = FontWeight.SemiBold)
                Text(
                    if (last == 0L) stringResource(MR.strings.glass_backup_never) else stringResource(MR.strings.last_auto_backup_info, relativeTimeSpanString(last)),
                    color = Glass.secondary,
                    fontSize = Glass.body,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        val pills: @Composable () -> Unit = {
            Pill(stringResource(MR.strings.glass_backup_now), { navigator.push(CreateBackupScreen()) }, primary = true, icon = "upload")
            Pill(stringResource(MR.strings.glass_backup_restore), restore, icon = "restore")
        }
        if (narrow) {
            Column(Modifier.fillMaxWidth().glassPanel(Glass.r16).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlassIcon("shield", size = 24.dp, tint = Glass.ice)
                    statusText(Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { pills() }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().glassPanel(Glass.r16).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GlassIcon("shield", size = 24.dp, tint = Glass.ice)
                statusText(Modifier.weight(1f))
                pills()
            }
        }
        BackupRow(narrow, "clock", stringResource(MR.strings.pref_backup_interval)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                IntervalHours.forEach { h ->
                    GlassChip(intervalLabel(h), selected = interval == h, onClick = {
                        backupPrefs.backupInterval().set(h)
                        BackupCreateJob.setupTask(context, h)
                    })
                }
            }
        }
        BackupRow(narrow, "folder", stringResource(MR.strings.pref_storage_location)) {
            Text(location, Modifier.weight(1f, fill = false), color = Glass.secondary, fontSize = Glass.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Pill(stringResource(MR.strings.glass_backup_change), {
                try {
                    allowAccessStorage(context, storagePref) { pickLocation.launch(null) }
                } catch (_: Exception) {
                    context.toast(MR.strings.file_picker_error)
                }
            }, small = true)
        }
        // only when the app has sync set up
        if (syncService != 0) {
            BackupRow(narrow, "restore", stringResource(MR.strings.glass_backup_sync)) {
                if (lastSync > 0L) {
                    Text(
                        stringResource(SYMR.strings.last_synchronization, relativeTimeSpanString(lastSync)),
                        Modifier.weight(1f, fill = false),
                        color = Glass.secondary,
                        fontSize = Glass.caption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Pill(stringResource(SYMR.strings.pref_sync_now), {
                    if (!SyncDataJob.isRunning(context)) SyncDataJob.startNow(context, manual = true) else context.toast(SYMR.strings.sync_in_progress)
                }, small = true)
            }
        }
    }
    }
}

@Composable
private fun intervalLabel(hours: Int): String = stringResource(
    when (hours) {
        6 -> MR.strings.update_6hour
        12 -> MR.strings.update_12hour
        24 -> MR.strings.update_24hour
        168 -> MR.strings.update_weekly
        else -> MR.strings.off
    },
)

/** Icon + label on the left, [content] on the right, in a 56dp hairline row. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BackupRow(narrow: Boolean, icon: String, label: String, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    if (narrow) {
        // narrow: the label on top, the controls under it, so a wrapping chip row cannot squeeze the label
        Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassIcon(icon, size = 20.dp, tint = Glass.secondary)
                Text(label, fontSize = Glass.body, maxLines = 2)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
        }
        return
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GlassIcon(icon, size = 20.dp, tint = Glass.secondary)
        Text(label, Modifier.weight(1f), fontSize = Glass.body, maxLines = 2)
        content()
    }
}
