package eu.kanade.tachiyomi.ui.glass.setup

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import chimahon.anki.AnkiDroidBridge
import chimahon.srs.AnkiKnown
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mihon.feature.stats.anki.AnkiKnownWords
import mihon.feature.stats.anki.AnkiKnownWords.State
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * What the kuma3 / AnkiDroid source row shows and does; [tap] reads again (or asks the permission), [toggle] turns the layer on or off.
 * [available] = a build is installed (the switch works; without its permission the first read waits for it).
 */
internal class AnkiKnownUi(
    val on: Boolean,
    val available: Boolean,
    val subtitle: String,
    val tap: () -> Unit,
    val toggle: (Boolean) -> Unit,
    val days: Int,
    val setDays: (Int) -> Unit,
)

@Composable
internal fun rememberAnkiKnown(): AnkiKnownUi {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // everything about packages, permissions and prefs is read off the main thread into [snap] (null until the first read)
    var snap by remember { mutableStateOf<AnkiKnownWords.Snapshot?>(null) }
    var on by remember { mutableStateOf(false) }
    var last by remember { mutableStateOf(0L) }
    var words by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var days by remember { mutableStateOf(AnkiKnown.KNOWN_DAYS.toInt()) } // the saved choice arrives with the first snapshot
    var pending by remember { mutableStateOf(false) } // the user switched it on before the permission: read once it is granted
    fun read() {
        busy = true
        error = null
        pending = false
        scope.launch {
            try {
                words = AnkiKnownWords.refresh(context)
                last = AnkiKnownWords.snapshot(context).last
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            }
            busy = false
        }
    }
    // The permission is granted in a dialog: look again every few seconds, but only while the screen is up and only while it matters
    // (no permission yet, or a read running); once the build is ready and nothing runs, one look is enough.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val s = AnkiKnownWords.snapshot(context)
                if (snap == null) { on = s.enabled; days = s.days }
                snap = s
                if (!busy) { last = s.last; words = s.count }
                if (s.state == State.READY && pending && !busy) read()
                if (s.state == State.READY && !busy && !pending) break
                delay(3_000)
            }
        }
    }
    fun enable(v: Boolean) {
        on = v
        pending = v && snap?.state == State.NEEDS_PERMISSION
        scope.launch {
            AnkiKnownWords.setEnabled(context, v)
            if (!v) { words = 0; last = 0; return@launch }
            when (snap?.state) {
                State.READY -> read()
                State.NEEDS_PERMISSION -> context.activity()?.let { AnkiDroidBridge(context).requestPermission(it) }
                else -> Unit
            }
        }
    }
    val state = snap?.state
    val name = snap?.name ?: ""
    val sub = when {
        snap == null -> ""
        busy -> stringResource(MR.strings.anki_known_reading, name)
        error != null -> stringResource(MR.strings.anki_known_failed, error!!)
        state == State.NOT_INSTALLED -> stringResource(MR.strings.anki_known_not_installed)
        state == State.NEEDS_PERMISSION -> stringResource(MR.strings.anki_known_needs_permission, name)
        on && last > 0 -> stringResource(
            MR.strings.anki_known_last, name,
            DateUtils.getRelativeTimeSpanString(last, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString().lowercase(),
            words,
        )
        else -> stringResource(MR.strings.anki_known_found, name)
    }
    return AnkiKnownUi(
        on = on,
        available = state == State.READY || state == State.NEEDS_PERMISSION,
        subtitle = sub,
        tap = { if (state == State.READY && !busy || state == State.NEEDS_PERMISSION) enable(true) },
        toggle = { v -> if (state == State.READY || state == State.NEEDS_PERMISSION) enable(v) },
        days = days,
        setDays = { d ->
            days = d
            AnkiKnownWords.setDays(context, d)
            if (on && state == State.READY && !busy) read()
        },
    )
}

private fun Context.activity(): Activity? = generateSequence(this) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()

/** The Settings row under the known-words tally: title, status line, a switch (tapping the row reads again), and the stability chips. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AnkiKnownRow(modifier: Modifier = Modifier) {
    val ui = rememberAnkiKnown()
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { ui.tap() }.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(MR.strings.anki_known_title), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(ui.subtitle, color = LocalContentColor.current.copy(alpha = 0.62f), fontSize = 13.sp)
            }
            Switch(ui.on, { ui.toggle(it) }, enabled = ui.available)
        }
        Text(
            stringResource(MR.strings.anki_known_days),
            Modifier.padding(horizontal = 16.dp),
            color = LocalContentColor.current.copy(alpha = 0.62f),
            fontSize = 13.sp,
        )
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AnkiKnown.DAY_CHOICES.forEach { d ->
                FilterChip(selected = ui.days == d, onClick = { ui.setDays(d) }, label = { Text("$d") })
            }
        }
    }
}
