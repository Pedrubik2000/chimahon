package kumahon.desktop

import chimahon.srs.JdbcSqlDb
import chimahon.srs.FilePeerPrefs
import chimahon.srs.PeerSync
import chimahon.srs.Srs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class LogicTest {
    @Volatile private var after = 0 // written by the sync coroutine, read by the test thread

    @Test fun dataDirChoice() {
        assertEquals(File("/u/AppData/Roaming", "kumahon"), dataDir("/u/AppData/Roaming", "/u"))
        assertEquals(File("/home/a", ".kumahon"), dataDir(null, "/home/a"))
        assertEquals(File("/home/a", ".kumahon"), dataDir("  ", "/home/a"))
    }

    @Test fun reviewSession(@TempDir dir: File) = runBlocking {
        val srs = Srs(JdbcSqlDb(File(dir, "ja.db")), "TEST")
        srs.addCard("約束[やくそく]", "", "promise", null, null, null, now = 1000)
        srs.addCard("実行[じっこう]", "", "execution", null, null, null, now = 2000)
        val m = ReviewModel(srs, CoroutineScope(Dispatchers.Unconfined))
        var answers = 0
        m.onAnswer = { answers++ }
        m.load()
        assertEquals(2, m.session.queue.size)
        assertEquals(4, m.intervals.size)
        m.answer(3).join() // not shown yet: ignored
        assertEquals(0, m.session.done)
        m.show(); m.answer(1).join() // Again: back to the end
        assertEquals(2, m.session.queue.size)
        m.show(); m.answer(3).join()
        assertEquals(1, m.session.done)
        assertEquals(2, answers)
        m.undo().join()
        assertEquals(0, m.session.done)
        assertEquals(1, srs.cards().count { it.stability != null }) // the Again of the first card stays, the Good was undone
        srs.db.close() // Windows keeps the file locked otherwise
        Unit
    }

    @Test fun debouncedSyncRunsOnce(@TempDir dir: File) = runBlocking {
        val srs = Srs(JdbcSqlDb(File(dir, "ja.db")), "TEST")
        val prefs = FilePeerPrefs(File(dir, "p.properties"))
        prefs.put(PeerSync.PAIRED, "true"); prefs.put(PeerSync.SECRET, "s")
        prefs.put(PeerSync.HUB, "127.0.0.1:1") // nothing listens: sync() fails fast, but it runs and afterSync follows
        after = 0
        val control = SyncControl(CoroutineScope(Dispatchers.IO), PeerSync(prefs, { srs }, "TEST"), waitMs = 300) { after++ }
        repeat(5) { control.later(); delay(50) } // a burst of answers: each restarts the wait
        assertEquals(0, after)
        delay(1000)
        assertEquals(1, after)
        srs.db.close()
        Unit
    }

    @Test fun prefsSurviveReopen(@TempDir dir: File) {
        val f = File(dir, "p.properties")
        FilePeerPrefs(f).apply { put("secret", "abc"); put("hub", "1.2.3.4:5") }
        assertEquals("abc", FilePeerPrefs(f).get("secret"))
        assertFalse(File(dir, "p.properties.tmp").exists())
    }
}
