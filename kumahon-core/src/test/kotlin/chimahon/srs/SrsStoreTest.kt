package chimahon.srs

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The store on a real SQLite file through [JdbcSqlDb] (sqlite-jdbc): the path the desktop app takes. */
class SrsStoreTest {
    @TempDir lateinit var dir: File

    private val open = mutableListOf<SqlDb>()

    // Windows can't delete the temp dir while a database file is open.
    @AfterEach fun closeAll() = open.forEach { it.close() }

    private fun store(name: String, device: String) = Srs(JdbcSqlDb(File(dir, "$name.db")).also { open += it }, device)

    private fun mine(s: Srs, now: Long) = s.addCard("魔法[まほう]", "s", "m", null, null, null, JSONObject().put("Reading", "まほう").toString(), now = now)

    @Test fun addRateUndoReplay() {
        val s = store("a", "pc")
        val c = mine(s, 1_000L)
        assertNull(c.due)
        val after = s.rate(c.id, 3, now = 2_000L)
        val rated = s.card(c.id)!!
        assertEquals(after.stability, rated.stability!!, 1e-9)
        assertEquals(2_000L, rated.lastReview)
        assertEquals(mapOf(c.id to (1 to 0)), s.counts())

        assertNull(s.undo(c.id, now = 3_000L)) // back to new
        assertNull(s.card(c.id)!!.stability)
        assertEquals(mapOf(c.id to (0 to 0)), s.counts())

        s.rate(c.id, 3, now = 4_000L)
        s.replayAll() // the cache is what the revlog says
        assertEquals(Srs.replay(listOf(2_000L to 3, 3_000L to 0, 4_000L to 3), s.fsrs)!!.state.stability, s.card(c.id)!!.stability!!, 1e-9)
        assertEquals(3, s.revlogIds().size)
        assertEquals(0, s.contexts(c.id).size)
        s.addContext(c.id, "t", "v", 3, "sentence", null, now = 5_000L)
        assertEquals("sentence", s.contexts(c.id).single().sentence)
    }

    @Test fun reviewCrossesByWordKey() {
        val a = store("a", "tablet")
        val b = store("b", "pc")
        val ca = mine(a, 1_000L)
        val cb = mine(b, 7_000L) // another id, same word
        a.rate(ca.id, 3, now = 10_000L)
        assertNull(b.card(cb.id)!!.stability)

        // What PeerSync.sync sends and picks, without the network.
        val keys = a.cards().associate { it.id to PeerSync.keyOf(it)!! }
        val rows = a.dump().getJSONArray("revlog")
        val events = List(rows.length()) { rows.getJSONObject(it) }.map { r ->
            JSONObject().put("id", r.getLong("id")).put("key", keys.getValue(r.getLong("card"))).put("time", r.getLong("time")).put("ease", r.getInt("rating")).put("device", r.optString("device"))
        }
        val ours = PeerSync.byKey(b.cards().map { it.id to PeerSync.keyOf(it) })
        val take = PeerSync.pick(events, { it.getLong("id") }, { it.getString("key") }, b.revlogIds(), ours)
        assertEquals(1, take.size)
        b.take(take)

        val got = b.card(cb.id)!!
        assertNotNull(got.stability)
        assertEquals(a.card(ca.id)!!.stability!!, got.stability!!, 1e-9)
        assertEquals(10_000L, got.lastReview)
        assertEquals(0, PeerSync.pick(events, { it.getLong("id") }, { it.getString("key") }, b.revlogIds(), ours).size) // taken once
    }
}
