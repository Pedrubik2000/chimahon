package eu.kanade.tachiyomi.ui.main

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import chimahon.srs.Cards
import chimahon.srs.HubHost
import kotlinx.coroutines.delay

/** "Allow <device>? Code NNNN": a kumapie device asks to sync with this device as the main server (Settings > Mining). */
@Composable
fun HubPairDialog() {
    val asking by HubHost.asking.collectAsState()
    // the other device re-asks every few seconds while it waits; one that stopped drops out after 20 s
    LaunchedEffect(Unit) { while (true) { delay(3_000); HubHost.refresh() } }
    val ask = asking.firstOrNull() ?: return
    val device = ask.device
    // allow / deny write the pairing file: never on the main thread
    val allow = { Cards.later { HubHost.allow(device) } }
    val deny = { Cards.later { HubHost.deny(device) } }
    AlertDialog(
        onDismissRequest = { deny() },
        title = { Text("Allow $device?") },
        text = {
            Text(
                (if (ask.known) "$device is already paired: Allow replaces its pairing. " else "") +
                    "It asks to sync with this device. Its screen shows the code ${ask.code}: allow only if the two codes match.",
            )
        },
        confirmButton = { TextButton(onClick = { allow() }) { Text("Allow") } },
        dismissButton = { TextButton(onClick = { deny() }) { Text("Deny") } },
    )
}
