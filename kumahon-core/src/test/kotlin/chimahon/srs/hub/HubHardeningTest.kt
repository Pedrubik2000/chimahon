package chimahon.srs.hub

import chimahon.srs.JdbcSqlDb
import chimahon.srs.PeerPrefs
import chimahon.srs.PeerSync
import chimahon.srs.Srs
import chimahon.srs.SqlDb
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetAddress
import java.net.Socket

/** The security fixes: nobody pairs as the hub, request size / time caps, the pending cap, a corrupt file is never overwritten. */
class HubHardeningTest {
    @TempDir lateinit var dir: File
    private val servers = mutableListOf<HubServer>()
    private val open = mutableListOf<SqlDb>()
    @AfterEach fun closeAll() { servers.forEach { it.stop() }; open.forEach { it.close() } }

    private val asked = mutableListOf<String>()

    private fun hub(deadlineMs: Long = HubServer.DEADLINE_MS): HubServer =
        HubServer(HubStore(File(dir, "hub.json"), "kumahon hub"), 0, InetAddress.getLoopbackAddress(), { d, _ -> synchronized(asked) { asked += d } }, {}, deadlineMs)
            .also { it.start(); servers += it }

    /** Sends [raw] as is and answers the status line's code (0 = closed without a reply). */
    private fun status(h: HubServer, raw: String): Int = Socket("127.0.0.1", h.localPort).use { s ->
        s.soTimeout = 10_000
        s.getOutputStream().write(raw.toByteArray()); s.getOutputStream().flush()
        val text = try { String(s.getInputStream().readBytes()) } catch (_: Exception) { "" }
        text.takeIf { it.startsWith("HTTP/1.1 ") }?.substring(9, 12)?.toInt() ?: 0
    }

    private fun pairBody(device: String, secret: String = "a".repeat(64)) = JSONObject().put("device", device).put("secret", secret).toString()

    private fun pair(h: HubServer, device: String, secret: String = "a".repeat(64)): Int {
        val b = pairBody(device, secret)
        return status(h, "POST /pair HTTP/1.1\r\nContent-Length: ${b.length}\r\n\r\n$b")
    }

    @Test fun nobodyPairsAsTheHubAndItNeverReachesTheCallback() {
        val h = hub()
        assertEquals(403, pair(h, "kumahon hub"))
        assertTrue(asked.isEmpty()); assertTrue(h.waiting().isEmpty())
        assertEquals(202, pair(h, "other")) // a real asker still gets through
        assertEquals(listOf("other"), asked)
    }

    @Test fun theHubsOwnSyncWorksWithoutAnyHttpPairing() {
        val h = hub()
        val secret = "ab".repeat(32)
        h.store.addPeer("kumahon hub", secret) // what HubHost.start does
        val store = Srs(JdbcSqlDb(File(dir, "own.db")).also { open += it }, "kumahon hub")
        val prefs = HashMap<String, String?>(mapOf(PeerSync.DEVICE_NAME to "kumahon hub", PeerSync.PAIRED to "true", PeerSync.SECRET to secret, PeerSync.HUB to "127.0.0.1:${h.localPort}"))
        val peer = PeerSync(object : PeerPrefs { override fun get(k: String) = prefs[k]; override fun put(k: String, v: String?) { prefs[k] = v } }, { store }, "m")
        assertTrue(peer.sync(), peer.status())
        assertTrue(asked.isEmpty())
    }

    @Test fun lineAndHeaderCaps() {
        val h = hub()
        assertEquals(431, status(h, "GET /" + "a".repeat(HubServer.MAX_LINE + 10) + " HTTP/1.1\r\n\r\n"))
        assertEquals(431, status(h, "GET /x HTTP/1.1\r\nX-Big: " + "a".repeat(HubServer.MAX_LINE + 10) + "\r\n\r\n"))
        assertEquals(431, status(h, "GET /x HTTP/1.1\r\n" + (0..HubServer.MAX_HEADERS).joinToString("") { "H$it: v\r\n" } + "\r\n"))
        assertEquals(404, status(h, "GET /x HTTP/1.1\r\n" + (0 until HubServer.MAX_HEADERS).joinToString("") { "H$it: v\r\n" } + "\r\n")) // exactly the cap is fine
    }

    @Test fun pairBodyDeviceNameAndPendingCaps() {
        val h = hub()
        assertEquals(413, status(h, "POST /pair HTTP/1.1\r\nContent-Length: ${HubServer.MAX_PAIR_BODY + 1}\r\n\r\n")) // refused before reading the body
        assertEquals(400, pair(h, "d".repeat(HubServer.MAX_DEVICE + 1)))
        assertEquals(202, pair(h, "d".repeat(HubServer.MAX_DEVICE)))
        repeat(HubServer.MAX_ASKING - 1) { assertEquals(202, pair(h, "dev$it")) }
        assertEquals(409, pair(h, "one too many"))
        assertEquals(202, pair(h, "dev0")) // one already waiting can keep asking
    }

    @Test fun anUnknownDeviceIsRefusedBeforeItsBodyIsRead() {
        val h = hub()
        // 50 MB declared, none sent: a 403 right away (a read of the body would hang until the timeout)
        assertEquals(403, status(h, "POST /sync HTTP/1.1\r\nX-Device: stranger\r\nContent-Length: 50000000\r\n\r\n"))
    }

    @Test fun aSlowClientIsDroppedAtTheDeadline() {
        val h = hub(deadlineMs = 600)
        val t0 = System.nanoTime()
        Socket("127.0.0.1", h.localPort).use { s ->
            s.soTimeout = 10_000
            val out = s.getOutputStream()
            // one byte every 150 ms, never finishing the headers
            val closed = try { "POST /sync HTTP/1.1\r\nX-A: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa".forEach { out.write(it.code); out.flush(); Thread.sleep(150) }; false } catch (_: Exception) { true }
            val reply = if (closed) -1 else try { s.getInputStream().read() } catch (_: Exception) { -1 }
            assertEquals(-1, reply) // closed without an answer
        }
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 8_000)
        assertEquals(404, status(h, "GET /x HTTP/1.1\r\n\r\n")) // and the server still answers the next one
    }

    @Test fun aClientDrippingAfterARefusalDoesNotHoldTheServer() {
        val h = hub()
        val drip = Socket("127.0.0.1", h.localPort)
        val t0 = System.nanoTime()
        drip.getOutputStream().write("POST /sync HTTP/1.1\r\nX-Device: stranger\r\nContent-Length: 50000000\r\n\r\n".toByteArray())
        val dripper = Thread { runCatching { while (!drip.isClosed) { drip.getOutputStream().write(1); drip.getOutputStream().flush(); Thread.sleep(300) } } }.also { it.isDaemon = true; it.start() }
        try {
            // the next client is served after the drain's 2 s at the latest, though the first keeps sending for much longer
            assertEquals(404, status(h, "GET /x HTTP/1.1\r\n\r\n"))
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 4_000, "served after ${(System.nanoTime() - t0) / 1_000_000} ms")
        } finally { drip.close(); dripper.join(2_000) }
    }

    @Test fun aCorruptHubFileIsSetAsideNotOverwritten() {
        val f = File(dir, "hub.json")
        f.writeText("{\"peers\":{\"a\":\"s\"},\"events\":[{\"id\":1,\"key\"") // cut off mid-write
        val log = mutableListOf<String>()
        val s = HubStore(f, "kumahon hub") { log += it }
        assertEquals(0, s.eventCount()); assertEquals(null, s.secretOf("a"))
        assertEquals(1, log.size)
        val bad = dir.listFiles()!!.single { it.name.startsWith("hub.json.bad-") }
        assertTrue(bad.readText().startsWith("{\"peers\""))
        assertFalse(f.exists())
        s.addPeer("b", "x") // the next save writes a new file; the corrupt copy stays as it was
        assertTrue(f.exists()); assertTrue(bad.readText().startsWith("{\"peers\""))
    }
}
