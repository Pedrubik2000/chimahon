package chimahon.srs.hub

import chimahon.srs.JdbcSqlDb
import chimahon.srs.PeerPrefs
import chimahon.srs.PeerSync
import chimahon.srs.Srs
import chimahon.srs.SqlDb
import chimahon.srs.WordKey
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/** [HubServer] on 127.0.0.1 (free port) against kumahon's own [PeerSync] client, as two devices, plus raw requests for the error paths. */
class HubServerTest {
    @TempDir lateinit var dir: File
    private val open = mutableListOf<SqlDb>()
    private val servers = mutableListOf<HubServer>()
    @AfterEach fun closeAll() { servers.forEach { it.stop() }; open.forEach { it.close() } }

    private fun hub(file: File = File(dir, "hub.json"), auto: Boolean = true): HubServer {
        lateinit var s: HubServer
        s = HubServer(HubStore(file, "kumahon hub"), 0, InetAddress.getLoopbackAddress(), { d, _ -> if (auto) s.allow(d) }).also { it.start(); servers += it }
        return s
    }

    private class Device(val name: String, val store: Srs, val prefs: MutableMap<String, String?>, val peer: PeerSync)

    private fun device(name: String, hub: HubServer): Device {
        val store = Srs(JdbcSqlDb(File(dir, "$name.db")).also { open += it }, name)
        val prefs = HashMap<String, String?>(mapOf(PeerSync.DEVICE_NAME to name, PeerSync.HUB to "127.0.0.1:${hub.localPort}"))
        val peer = PeerSync(object : PeerPrefs { override fun get(k: String) = prefs[k]; override fun put(k: String, v: String?) { prefs[k] = v } }, { store }, "m")
        return Device(name, store, prefs, peer)
    }

    private fun Srs.word(w: String, r: String) = addCard(WordKey.furigana(w, r), "s", "m", null, null, null, JSONObject().put("Reading", r).toString(), "kumahon")

    private fun post(hub: HubServer, path: String, body: ByteArray, headers: Map<String, String> = emptyMap(), declared: Long? = null): Triple<Int, String, String?> =
        (URL("http://127.0.0.1:${hub.localPort}$path").openConnection() as HttpURLConnection).run {
            requestMethod = "POST"; doOutput = true
            if (declared != null) setRequestProperty("Content-Length", "$declared") // the body is not sent: only the header is checked
            else setFixedLengthStreamingMode(body.size)
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            try { if (declared == null) outputStream.use { it.write(body) } } catch (_: Exception) {}
            val code = responseCode
            Triple(code, String((if (code < 400) inputStream else errorStream)?.readBytes() ?: ByteArray(0)), getHeaderField("X-Sig"))
        }

    private fun signed(hub: HubServer, d: Device, body: JSONObject, sig: String? = null): Triple<Int, String, String?> {
        val bytes = body.toString().toByteArray()
        return post(hub, "/sync", bytes, mapOf("X-Device" to d.name, "X-Sig" to (sig ?: PeerSync.sign(d.prefs[PeerSync.SECRET]!!, bytes))))
    }

    private fun file(device: String) = JSONObject().put("format", "kumapie-device-1").put("device", device).put("person", "me")

    @Test fun pairSyncAndTheFullUnionReachesTheOtherDevice() {
        val h = hub()
        val a = device("kumahon A", h); val b = device("kumahon B", h)
        val ca = a.store.word("猫", "ねこ"); val cb = b.store.word("猫", "ねこ")
        a.store.rate(ca.id, 3)
        assertTrue(a.peer.pair() is PeerSync.PairState.Paired)
        assertTrue(b.peer.pair() is PeerSync.PairState.Paired)
        assertTrue(a.peer.sync(), a.peer.status())
        assertEquals(1, h.store.eventCount())
        assertEquals(0, b.store.db.query("SELECT COUNT(*) FROM revlog") { it.int(0) }.first())
        assertTrue(b.peer.sync(), b.peer.status())
        // B's answer carried A's event (matched to B's own card by key) ...
        assertEquals(1, b.store.db.query("SELECT COUNT(*) FROM revlog WHERE card = ?", arrayOf("${cb.id}")) { it.int(0) }.first())
        // ... and A's carded entry (B sees the word as carded elsewhere)
        assertEquals(listOf("猫\tねこ"), b.store.carded().map { it.first })
        // B rates; A's next sync takes B's review (full union, nothing filtered by device)
        b.store.rate(cb.id, 4)
        assertTrue(b.peer.sync(), b.peer.status()); assertTrue(a.peer.sync(), a.peer.status())
        assertEquals(2, a.store.db.query("SELECT COUNT(*) FROM revlog WHERE card = ?", arrayOf("${ca.id}")) { it.int(0) }.first())
        // the file survives a restart: peers and events
        val again = HubStore(File(dir, "hub.json"), "kumahon hub")
        assertEquals(2, again.eventCount()); assertTrue(again.secretOf("kumahon A") != null)
    }

    @Test fun pairingWaitsForAllowThenAnswers200() {
        val h = hub(auto = false)
        val a = device("kumahon A", h)
        assertTrue(a.peer.pair() is PeerSync.PairState.Waiting)
        assertEquals(listOf("kumahon A"), h.waiting().map { it.device })
        assertEquals(PeerSync.code(a.prefs[PeerSync.SECRET]!!), h.waiting().single().code)
        h.allow("kumahon A")
        assertEquals(PeerSync.PairState.Paired("kumahon hub"), a.peer.pair())
        assertTrue(h.waiting().isEmpty())
    }

    @Test fun sameNameWithAnotherSecretIs409() {
        val h = hub(auto = false)
        val body = { s: String -> JSONObject().put("device", "x").put("secret", s).toString().toByteArray() }
        assertEquals(202, post(h, "/pair", body("a".repeat(64))).first)
        val r = post(h, "/pair", body("b".repeat(64)))
        assertEquals(409, r.first); assertEquals(HubServer.TWO, r.second)
        assertEquals(409, post(h, "/pair", body("c".repeat(64))).first)
        val reply = JSONObject(post(h, "/pair", JSONObject().put("device", "y").put("secret", "s").toString().toByteArray()).second)
        assertEquals("kumahon hub", reply.getString("device")); assertFalse(reply.getBoolean("setup"))
    }

    @Test fun progressIsKeptVerbatimPerDeviceAndTheNewerPartWins() {
        val h = hub()
        val a = device("kumahon A", h); val b = device("kumahon B", h)
        a.peer.pair(); b.peer.pair()
        val partA = JSONObject("""{"pos":{"e1":[5,100]},"watch":{"d1":30},"lookups":{"w":2},"marked":{},"extra":{"k":[1,2,3]}}""")
        val newerA = JSONObject("""{"pos":{"e1":[9,200]},"watch":{"d1":40},"lookups":{},"marked":{}}""")
        val olderA = JSONObject("""{"pos":{"e1":[1,50]},"watch":{"d1":10},"lookups":{},"marked":{}}""")
        assertEquals(200, signed(h, a, file("kumahon A").put("progress", JSONObject().put("kumahon A", partA))).first)
        // an older part for the same device does not replace it
        val r = signed(h, b, file("kumahon B").put("progress", JSONObject().put("kumahon A", olderA).put("kumahon B", partA)))
        val got = JSONObject(r.second).getJSONObject("progress")
        assertTrue(got.getJSONObject("kumahon A").similar(partA)); assertTrue(got.getJSONObject("kumahon B").similar(partA))
        // a strictly newer one does
        val r2 = signed(h, a, file("kumahon A").put("progress", JSONObject().put("kumahon A", newerA)))
        assertTrue(JSONObject(r2.second).getJSONObject("progress").getJSONObject("kumahon A").similar(newerA))
    }

    @Test fun knownCardedPersonalAndStreamsMerge() {
        val h = hub()
        val a = device("kumahon A", h); a.peer.pair()
        fun known(t: Long, vararg w: String) = JSONObject().put("ja", JSONObject().put("time", t).put("words", JSONArray(w.toList())))
        fun carded(d: String, made: Long, vararg k: String) = JSONObject().put(d, JSONObject().put("made", made).put("keys", JSONArray(k.toList())))
        fun pers(t: Long, m: String) = JSONArray().put(JSONObject().put("lang", "ja").put("text", "w").put("target", "").put("reading", "").put("meaning", m).put("time", t).put("deleted", false))
        signed(h, a, file("kumahon A").put("known", known(10, "x")).put("carded", carded("D", 5, "k1")).put("personal", pers(5, "old"))
            .put("streams", JSONObject().put("alone_seen", JSONArray(listOf("a"))).put("alone_saved", """[{"id":"1"}]""")))
        // equal/older known time, older carded, older personal: nothing replaced; streams are unions
        val r = JSONObject(signed(h, a, file("kumahon A").put("known", known(10, "y")).put("carded", carded("D", 4, "k2")).put("personal", pers(4, "older"))
            .put("streams", JSONObject().put("alone_seen", JSONArray(listOf("b"))).put("alone_saved", """[{"id":"2"}]"""))).second)
        assertEquals(listOf("x"), r.getJSONObject("known").getJSONObject("ja").getJSONArray("words").toList())
        assertEquals(listOf("k1"), r.getJSONObject("carded").getJSONObject("D").getJSONArray("keys").toList())
        assertEquals("old", r.getJSONArray("personal").getJSONObject(0).getString("meaning"))
        assertEquals(setOf("a", "b"), r.getJSONObject("streams").getJSONArray("alone_seen").toList().toSet())
        assertEquals(2, JSONArray(r.getJSONObject("streams").getString("alone_saved")).length())
        // newer ones replace (an empty key list clears: replace semantics)
        val r2 = JSONObject(signed(h, a, file("kumahon A").put("known", known(11, "y")).put("carded", carded("D", 6)).put("personal", pers(6, "new"))).second)
        assertEquals(listOf("y"), r2.getJSONObject("known").getJSONObject("ja").getJSONArray("words").toList())
        assertEquals(0, r2.getJSONObject("carded").getJSONObject("D").getJSONArray("keys").length())
        assertEquals("new", r2.getJSONArray("personal").getJSONObject(0).getString("meaning"))
    }

    @Test fun theAnswerIsSignedWithThePeerSecretAndFormatDeviceAndPersonAreSet() {
        val h = hub()
        val a = device("kumahon A", h); a.peer.pair()
        val r = signed(h, a, file("kumahon A").put("events", JSONArray().put(JSONObject().put("id", 1).put("key", "ja|k").put("time", 2).put("ease", 3).put("device", "kumahon A"))))
        assertEquals(200, r.first)
        assertTrue(PeerSync.verify(a.prefs[PeerSync.SECRET]!!, r.second.toByteArray(), r.third))
        val o = JSONObject(r.second)
        assertEquals("kumapie-device-1", o.getString("format")); assertEquals("kumahon hub", o.getString("device")); assertEquals("me", o.getString("person"))
        assertFalse(o.has("cards"))
        assertEquals("kumahon", o.getString("app"))
    }

    @Test fun refusals() {
        val h = hub()
        val a = device("kumahon A", h); a.peer.pair()
        val ok = file("kumahon A").toString().toByteArray()
        assertEquals(403, post(h, "/sync", ok, mapOf("X-Device" to "nobody", "X-Sig" to PeerSync.sign("s", ok))).first) // unpaired
        assertEquals(403, post(h, "/sync", ok).first) // no header at all
        assertEquals(403, signed(h, a, file("kumahon A"), sig = "00".repeat(32)).first) // bad signature
        assertEquals(422, signed(h, a, JSONObject().put("format", "other").put("device", "x")).first)
        assertEquals(422, signed(h, a, file("kumahon A").put("person", "pedro")).first)
        assertEquals(422, signed(h, a, file("kumahon A").put("events", JSONArray().put(JSONObject().put("id", 1)))).first)
        assertEquals(0, h.store.eventCount()) // the bad ones changed nothing
        java.net.Socket("127.0.0.1", h.localPort).use { s ->
            s.getOutputStream().write("POST /sync HTTP/1.1\r\nContent-Length: ${HubServer.MAX_BODY + 1L}\r\n\r\n".toByteArray())
            assertTrue(String(s.getInputStream().readBytes()).startsWith("HTTP/1.1 413 X"))
        }
        assertEquals(404, post(h, "/setup", ByteArray(0)).first)
        assertEquals(404, post(h, "/sync?x=1", ok).first)
    }
}
