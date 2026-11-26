package eu.kanade.tachiyomi.ui.glass.settings

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassIcon
import eu.kanade.presentation.glass.Pill
import eu.kanade.presentation.glass.glassPanel
import eu.kanade.tachiyomi.data.anilist.AniListLibrary
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.anilist.AnilistApi
import eu.kanade.tachiyomi.util.system.openInBrowser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

// Canvas kumahon-anilist-login: B (Settings > Accounts card, four states) and C (the same card in place where the list is missing).

/** The same OAuth start as classic Tracking and the Setup step: back through TrackLoginActivity, which restarts MainActivity. */
private fun android.content.Context.openAniListLogin() = openInBrowser(AnilistApi.authUrl(), forceDefaultBrowser = true)

@Composable
private fun AniListWaiting(onOpenAgain: () -> Unit, onCancel: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(28.dp), color = Glass.ice, strokeWidth = 3.dp)
        Text(stringResource(MR.strings.glass_acc_waiting), fontSize = Glass.heading, fontWeight = FontWeight.SemiBold)
    }
    Text(stringResource(MR.strings.glass_acc_waiting_sub), color = Glass.secondary, fontSize = Glass.body)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Pill(stringResource(MR.strings.glass_acc_open_again), onOpenAgain, icon = "external")
        Pill(stringResource(MR.strings.action_cancel), onCancel)
    }
}

@Composable
private fun Benefit(title: String, sub: String) {
    Column {
        Text(title, fontSize = Glass.body, fontWeight = FontWeight.SemiBold)
        Text(sub, color = Glass.secondary, fontSize = Glass.caption)
    }
}

/** Settings > Accounts: signed out, signing in, signed in, login expired. */
@Composable
internal fun AniListCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tracker = remember { Injekt.get<TrackerManager>().aniList }
    val trackPrefs = remember { Injekt.get<TrackPreferences>() }
    val library = remember { Injekt.get<AniListLibrary>() }
    val signedIn by tracker.isLoggedInFlow.collectAsState(tracker.isLoggedIn)
    val expiredPref = remember { trackPrefs.trackAuthExpired(tracker) }
    val expired by expiredPref.changes().collectAsState(expiredPref.get())
    val namePref = remember { trackPrefs.anilistName() }
    val name by namePref.changes().collectAsState(namePref.get())
    // the first access reads the cache file: off the main thread
    val entries by remember { flow { emitAll(library.entries) }.flowOn(Dispatchers.IO) }.collectAsState(emptyList())
    val syncedAt by remember { flow { emitAll(library.refreshedAt) }.flowOn(Dispatchers.IO) }.collectAsState(0L)
    val error by library.error.collectAsState()
    var waiting by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(signedIn, expired) { if (signedIn && !expired) waiting = false }
    var busy by remember { mutableStateOf(false) }
    var confirmOut by remember { mutableStateOf(false) }
    LaunchedEffect(signedIn) {
        // a login made before the name was stored: the setup fetches it the same way
        if (signedIn && name.isBlank()) runCatching { tracker.viewerName() }
    }

    Column(Modifier.fillMaxWidth().glassPanel(Glass.r16).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (waiting && (!signedIn || expired)) {
            AniListWaiting(onOpenAgain = { context.openAniListLogin() }, onCancel = { waiting = false })
        } else if (!signedIn && !expired) {
            Text("AniList", fontSize = Glass.heading, fontWeight = FontWeight.SemiBold)
            Text(stringResource(MR.strings.glass_acc_intro), color = Glass.secondary, fontSize = Glass.body)
            Benefit(stringResource(MR.strings.glass_acc_b1), stringResource(MR.strings.glass_acc_b1_sub))
            Benefit(stringResource(MR.strings.glass_acc_b2), stringResource(MR.strings.glass_acc_b2_sub))
            Benefit(stringResource(MR.strings.glass_acc_b3), stringResource(MR.strings.glass_acc_b3_sub))
            Pill(
                stringResource(MR.strings.glass_setup_sign_in),
                { waiting = true; context.openAniListLogin() },
                primary = true,
                trailingIcon = "external",
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(Glass.line), contentAlignment = Alignment.Center) {
                    Text(name.take(1).uppercase().ifEmpty { "A" }, fontSize = Glass.heading)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        if (expired) "AniList" else name.ifBlank { stringResource(MR.strings.glass_setup_signed_in) },
                        fontSize = Glass.heading,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (expired) {
                        Text(stringResource(MR.strings.glass_acc_expired_badge), color = Glass.ice, fontSize = Glass.body)
                    } else if (entries.isNotEmpty()) {
                        Text(
                            stringResource(
                                MR.strings.glass_setup_anilist_counts,
                                entries.size,
                                entries.count { it.status == "CURRENT" },
                                entries.count { it.status == "PLANNING" },
                            ),
                            color = Glass.secondary,
                            fontSize = Glass.body,
                        )
                    }
                }
            }
            if (expired) {
                Text(stringResource(MR.strings.glass_acc_expired_body), color = Glass.secondary, fontSize = Glass.body)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill(
                        stringResource(MR.strings.glass_acc_sign_in_again),
                        { waiting = true; context.openAniListLogin() },
                        primary = true,
                        trailingIcon = "external",
                    )
                    Pill(stringResource(MR.strings.glass_acc_sign_out), { confirmOut = true })
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlassIcon("clock", tint = Glass.secondary)
                    Text(
                        stringResource(
                            MR.strings.glass_acc_last_sync,
                            if (syncedAt == 0L) stringResource(MR.strings.anilist_list_never) else DateUtils.getRelativeTimeSpanString(syncedAt).toString(),
                        ),
                        Modifier.weight(1f),
                        color = Glass.secondary,
                        fontSize = Glass.body,
                    )
                    Pill(
                        if (busy) stringResource(MR.strings.glass_refreshing) else stringResource(MR.strings.glass_acc_sync_now),
                        {
                            busy = true
                            scope.launch { library.refresh(); busy = false }
                        },
                        small = true,
                        enabled = !busy,
                    )
                    Pill(stringResource(MR.strings.glass_acc_sign_out), { confirmOut = true }, small = true)
                }
                error?.let { Text(stringResource(MR.strings.glass_refresh_failed, it), color = Glass.secondary, fontSize = Glass.body) }
            }
        }
    }

    if (confirmOut) {
        AlertDialog(
            onDismissRequest = { confirmOut = false },
            shape = RoundedCornerShape(Glass.r16),
            containerColor = Glass.panel,
            titleContentColor = Color.White,
            textContentColor = Glass.secondary,
            title = { Text(stringResource(MR.strings.glass_acc_sign_out_title), fontSize = Glass.heading) },
            text = { Text(stringResource(MR.strings.glass_acc_sign_out_body), fontSize = Glass.body) },
            confirmButton = {
                Pill(stringResource(MR.strings.glass_acc_sign_out), { tracker.logout(); confirmOut = false }, small = true, primary = true)
            },
            dismissButton = { Pill(stringResource(MR.strings.action_cancel), { confirmOut = false }, small = true) },
        )
    }
}

/** The fold header under the card: "Other trackers", its rows follow when [open]. */
@Composable
internal fun OtherTrackersFold(open: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.padding(top = 12.dp).fillMaxWidth().glassPanel(Glass.r12).clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(MR.strings.glass_acc_other), fontSize = Glass.body, fontWeight = FontWeight.SemiBold)
            Text(stringResource(MR.strings.glass_acc_other_sub), color = Glass.secondary, fontSize = Glass.caption, maxLines = 1)
        }
        GlassIcon(if (open) "chevUp" else "chevDown", tint = Glass.secondary)
    }
}

/**
 * Where a Glass page misses the list (Home, Discover): the card in place. Signed out: the sign-in pill (then the wait);
 * expired: one line and Sign in again; signed in with an empty list: [empty] and Refresh.
 */
@Composable
internal fun AniListNeeds(text: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tracker = remember { Injekt.get<TrackerManager>().aniList }
    val library = remember { Injekt.get<AniListLibrary>() }
    val expiredPref = remember { Injekt.get<TrackPreferences>().trackAuthExpired(tracker) }
    val signedIn by tracker.isLoggedInFlow.collectAsState(tracker.isLoggedIn)
    val expired by expiredPref.changes().collectAsState(expiredPref.get())
    var waiting by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(signedIn, expired) { if (signedIn && !expired) waiting = false }
    var busy by remember { mutableStateOf(false) }
    val signIn = { waiting = true; context.openAniListLogin() }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        when {
            waiting && (!signedIn || expired) -> AniListWaiting(onOpenAgain = { context.openAniListLogin() }, onCancel = { waiting = false })
            expired -> {
                Text(stringResource(MR.strings.glass_acc_expired_line), fontSize = Glass.label)
                Pill(stringResource(MR.strings.glass_acc_sign_in_again), signIn, primary = true, trailingIcon = "external")
            }
            !signedIn -> {
                Text(text, fontSize = Glass.label)
                Pill(stringResource(MR.strings.glass_setup_sign_in), signIn, primary = true, trailingIcon = "external")
            }
            else -> {
                Text(stringResource(MR.strings.glass_acc_list_empty), fontSize = Glass.label)
                Pill(
                    if (busy) stringResource(MR.strings.glass_refreshing) else stringResource(MR.strings.glass_refresh),
                    {
                        busy = true
                        scope.launch { library.refresh(); busy = false }
                    },
                    primary = true,
                    enabled = !busy,
                )
            }
        }
    }
}
