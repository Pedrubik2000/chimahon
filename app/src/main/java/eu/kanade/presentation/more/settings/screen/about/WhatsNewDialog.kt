@file:Suppress("PropertyName")

package eu.kanade.presentation.more.settings.screen.about

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.Pill
import eu.kanade.tachiyomi.BuildConfig
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/** "Updated to v…" after an update. [glass] = the new look is on: the dark panel and pills of its other dialogs (Pedro, 2026-11-28). */
@Composable
fun WhatsNewDialog(
    onDismissRequest: () -> Unit,
    onOpenWhatsNew: () -> Unit = {},
    glass: Boolean = false,
) {
    if (glass) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            shape = RoundedCornerShape(Glass.r16),
            containerColor = Glass.panel,
            titleContentColor = Color.White,
            textContentColor = Glass.secondary,
            title = { Text(text = stringResource(MR.strings.updated_version, BuildConfig.VERSION_NAME), fontSize = Glass.heading) },
            text = { Text(text = AboutScreen.getVersionName(withBuildDate = true), fontSize = Glass.body) },
            confirmButton = { Pill(stringResource(MR.strings.action_ok), onDismissRequest, primary = true, small = true) },
            dismissButton = { Pill(stringResource(MR.strings.whats_new), onOpenWhatsNew, small = true) },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(MR.strings.updated_version, BuildConfig.VERSION_NAME)) },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
        // KMK -->
        dismissButton = {
            TextButton(onClick = onOpenWhatsNew) {
                Text(text = stringResource(MR.strings.whats_new))
            }
        },
        text = { Text(text = AboutScreen.getVersionName(withBuildDate = true)) },
        // KMK <--
    )
}

@Preview
@Composable
fun WhatsNewDialogPreview() {
    WhatsNewDialog({})
}
