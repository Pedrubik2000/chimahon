package chimahon.srs

import chimahon.lang.KnownImport
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress

/** [PeerSync.sync] against a fake kumapie hub on 127.0.0.1 (a real HTTP exchange, signed answers): known.ja, carded and the Jiten delta. */
class PeerSyncHubTest {
    @TempDir lateinit var dir: File
    private val open = mutableListOf<SqlDb>()
    private val hubs = mutableListOf<HttpServer>()
    @AfterEach fun closeAll() { hubs.forEach { it.stop(0) }; open.forEach { it.close() } }

    private val secret = "s3cret"

    /** kumapie's side of `known.ja` (the newer time wins, whole) and the answer it signs. [stale] answers once with an old list and takes nothing; [during] runs while a request is open. */
    private inner class Hub(var time: Long, var words: Set<String>) {
        var carded: JSONObject? = null
        var stale = false
        var during: (() -> Unit)? = null
        val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).also { srv ->
            srv.createContext("/sync") { ex ->
                val req = JSONObject(String(ex.requestBody.readBytes()))
                during?.also { during = null }?.invoke()
                val (t, w) = if (stale) { stale = false; 5L to setOf("x") } else {
                    req.optJSONObject("known")?.optJSONObject("ja")?.let { k ->
                        if (k.getLong("time") > time) { time = k.getLong("time"); words = List(k.getJSONArray("words").length()) { k.getJSONArray("words").getString(it) }.toSet() }
                    }
                    time to words
                }
                val body = JSONObject().put("events", JSONArray())
                    .put("known", JSONObject().put("ja", JSONObject().put("time", t).put("words", JSONArray(w.sorted()))))
                    .apply { carded?.let { put("carded", it) } }.toString().toByteArray()
                ex.responseHeaders.add("X-Sig", PeerSync.sign(secret, body))
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            srv.start(); hubs += srv
        }
    }

    private val store by lazy { Srs(JdbcSqlDb(File(dir, "p.db")).also { open += it }, "kumahon tab") }

    private fun peer(hub: Hub): PeerSync {
        val m = HashMap<String, String?>(mapOf(PeerSync.PAIRED to "true", PeerSync.SECRET to secret, PeerSync.DEVICE_NAME to "kumahon tab", PeerSync.HUB to "127.0.0.1:${hub.server.address.port}"))
        return PeerSync(object : PeerPrefs { override fun get(k: String) = m[k]; override fun put(k: String, v: String?) { m[k] = v } }, { store }, "m")
    }

    private fun imp(vararg w: String) = KnownImport(0, w.toSet())

    private fun syncs(p: PeerSync, n: Int = 1) = repeat(n) { assertTrue(p.sync(), p.status()) }

    @Test fun theCardedAnswerIsKeptWithoutOurOwnDevice() {
        val hub = Hub(10, setOf("k"))
        hub.carded = JSONObject()
            .put("tablet", JSONObject().put("made", 5).put("keys", JSONArray(listOf("ja|猫\tねこ"))))
            .put("kumahon tab", JSONObject().put("made", 9).put("keys", JSONArray(listOf("ja|mine"))))
        syncs(peer(hub))
        assertEquals(listOf("猫\tねこ"), store.carded().map { it.first })
    }

    @Test fun aStaleAnswerWhileOurListIsPendingKeepsTheDelta() {
        val hub = Hub(10, setOf("k")); val p = peer(hub)
        syncs(p)
        store.replaceJiten(imp("a"), 1, emptyList())
        syncs(p) // applied: {k, a} pending
        hub.stale = true
        syncs(p) // an older list comes back: our pending list and its delta stay
        assertTrue(store.pendingShared() != null)
        hub.time = System.currentTimeMillis() + 10_000_000; hub.words = setOf("k", "z") // another device's newer list, without a
        syncs(p, 2)
        assertEquals(setOf("k", "z", "a"), hub.words)
    }

    @Test fun aWordTheHubAlreadyHadFromAnotherDeviceIsNotClaimedBySlidingDown() {
        val hub = Hub(10, setOf("k")); val p = peer(hub)
        syncs(p)
        store.replaceJiten(imp("a"), 1, emptyList())
        syncs(p) // {k, a} pending, not yet taken
        hub.time = System.currentTimeMillis() + 10_000_000; hub.words = setOf("k", "a") // another device added a itself first
        syncs(p)
        store.replaceJiten(imp(), 0, emptyList())
        syncs(p, 3)
        assertEquals(setOf("k", "a"), hub.words)
    }

    @Test fun aSlideDownWhileOurListIsOnItsWayIsAppliedOnceAfterTheHubTookIt() {
        val hub = Hub(10, setOf("k")); val p = peer(hub)
        syncs(p)
        store.replaceJiten(imp("a"), 1, emptyList())
        syncs(p) // {k, a} pending
        hub.during = { store.replaceJiten(imp(), 0, emptyList()) } // the user clears the import while the request with {k, a} is open
        syncs(p)
        assertEquals(setOf("k", "a"), hub.words) // the hub took the sent list
        syncs(p, 2)
        assertEquals(setOf("k"), hub.words) // then the removal of a followed
        syncs(p)
        assertEquals(setOf("k"), hub.words)
        assertEquals(null, store.pendingShared())
    }
}
