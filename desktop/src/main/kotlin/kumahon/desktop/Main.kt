package kumahon.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import chimahon.srs.FilePeerPrefs
import chimahon.srs.Fsrs6
import chimahon.srs.JdbcSqlDb
import chimahon.srs.PeerSync
import chimahon.srs.Srs
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import java.io.File

/** Proves the shared :kumahon-core links: interval in days after a first "good" rating. */
fun firstGoodInterval(): Int {
    val fsrs = Fsrs6()
    return fsrs.interval(fsrs.next(null, 0, 3).stability)
}

/** One store and one sync per process, in [dir] (`srs\ja.db`, `peer.properties`); [model] = this computer's name. */
class Backend(dir: File, model: String) {
    val srs = Srs(JdbcSqlDb(File(dir, "srs/ja.db").also { it.parentFile.mkdirs() }), model)
    val sync = PeerSync(FilePeerPrefs(File(dir, "peer.properties")), { srs }, model) { System.err.println(it) }
}

fun main() {
    val model = System.getenv("COMPUTERNAME")?.ifBlank { null } ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrDefault("PC")
    val backend = Backend(dataDir(System.getenv("APPDATA"), System.getProperty("user.home")), model)
    application {
        val scope = rememberCoroutineScope()
        var closing by remember { mutableStateOf(false) }
        val review = remember { ReviewModel(backend.srs, scope) } // the application's scope outlives every screen
        // a sync may bring cards: read the queue again (only happens while nothing was answered)
        val sync = remember { SyncControl(scope, backend.sync, afterSync = { review.load() }).also { c -> review.onAnswer = { c.later() } } }
        LaunchedEffect(Unit) {
            review.load()
            if (backend.sync.paired()) sync.now()
        }
        Window(
            onCloseRequest = {
                if (closing) return@Window
                closing = true
                scope.launch(Dispatchers.IO) {
                    // the blocking sync cannot be cancelled: give it a daemon thread and stop waiting after 10 s
                    if (backend.sync.paired()) Thread { backend.sync.sync() }.apply { isDaemon = true; start() }.join(10_000)
                    exitApplication()
                }
            },
            visible = !closing,
            title = "kumahon",
            state = rememberWindowState(size = DpSize(1280.dp, 800.dp)),
        ) { App(review, backend.sync, { sync.now() }) }
    }
}
