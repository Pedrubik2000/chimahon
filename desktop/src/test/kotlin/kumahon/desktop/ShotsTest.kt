package kumahon.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import chimahon.srs.FilePeerPrefs
import chimahon.srs.JdbcSqlDb
import chimahon.srs.PeerSync
import chimahon.srs.Srs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Renders the screens offscreen (no window) at 1280x800 into C:\dojo\work\kumahon-desktop\shots. */
class ShotsTest {
    private val out = File("C:/dojo/work/kumahon-desktop/shots").also { it.mkdirs() }

    private fun shot(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        val scene = ImageComposeScene(1280, 800, Density(1f), content = content)
        try {
            var image = scene.render(0)
            for (i in 1..5) image = scene.render(i * 100_000_000L) // let effects and state settle
            val bytes = image.encodeToData(EncodedImageFormat.PNG)!!.bytes
            File(out, name).writeBytes(bytes)
            assertTrue(bytes.size > 5_000)
        } finally { scene.close() }
    }

    private fun peer(dir: File, srs: Srs) = PeerSync(FilePeerPrefs(File(dir, "peer.properties")), { srs }, "DESKTOP-TEST")

    @Test fun screens(@TempDir dir: File) = runBlocking {
        val srs = Srs(JdbcSqlDb(File(dir, "ja.db")), "DESKTOP-TEST")
        srs.addCard("約束[やくそく]", " 約束[やくそく]を 守[まも]る", "promise", null, null, null, now = 1000)
        srs.addCard("実行[じっこう]", "", "execution", null, null, null, now = 2000)
        val review = ReviewModel(srs, CoroutineScope(Dispatchers.Unconfined))
        review.load()
        val sync = peer(dir, srs)
        shot("review-front.png") { App(review, sync, { true }) }
        review.show()
        shot("review-answer.png") { App(review, sync, { true }) }

        val emptySrs = Srs(JdbcSqlDb(File(dir, "empty.db")), "DESKTOP-TEST")
        val empty = ReviewModel(emptySrs, CoroutineScope(Dispatchers.Unconfined))
        empty.load()
        shot("review-empty.png") { App(empty, sync, { true }) }

        FilePeerPrefs(File(dir, "peer.properties")).apply { put("secret", "abc"); put("hub", "192.168.1.20:47823") }
        val waiting = peer(dir, srs)
        shot("settings.png") { App(empty, waiting, { true }, initialTab = Tab.Settings) }
        srs.db.close(); emptySrs.db.close() // Windows keeps the files locked otherwise
        Unit
    }
}
