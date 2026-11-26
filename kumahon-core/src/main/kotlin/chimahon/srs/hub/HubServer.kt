package chimahon.srs.hub

import chimahon.srs.PeerSync
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * kumahon as kumapie's sync hub: the HTTP side of the protocol (spec C:\dojo\work\kumahon\hub-spec.md), the same hand-written HTTP/1.1
 * as kumapie's SyncListener: one connection at a time, one request each, closed after the reply, LAN peers only, 64 MiB body cap.
 * `/pair` (the user allows through [allow] / [deny] after [onPairRequest]), `/sync` (HMAC-signed both ways, merged by [store]);
 * `/setup` and anything else is 404 and `/pair` says `setup:false`, so kumapie takes the old pairing (code from the secret alone).
 *
 * [bind] null = all interfaces (the app); tests pass loopback. [start] throws (BindException) when the port is taken: no fallback port.
 */
class HubServer(
    val store: HubStore,
    private val port: Int = PORT,
    private val bind: InetAddress? = null,
    /** A device asks to be allowed (called again on each poll while it waits): show "Allow <device>, code NNNN". Runs on the server thread. */
    var onPairRequest: (device: String, code: String) -> Unit = { _, _ -> },
    private val log: (String) -> Unit = {},
    private val deadlineMs: Long = DEADLINE_MS,
    private val perMiBMs: Long = 1_000L,
) {
    companion object {
        const val PORT = 47823
        const val MAX_BODY = 64 shl 20
        const val MAX_LINE = 8 * 1024
        const val MAX_HEADERS = 64
        const val MAX_PAIR_BODY = 4 * 1024
        const val MAX_DEVICE = 100
        const val MAX_ASKING = 8
        const val DEADLINE_MS = 60_000L
        private const val DRAIN_MS = 2_000L
        private const val STALE_MS = 20_000L
        private const val DEAD_MS = 120_000L
        private const val CONFLICT_MS = 600_000L
        const val TWO = "Two devices asked with the same name: pair again."
        private const val NOT_PAIRED = "Not paired: allow this device on the other one first."
        private const val WRONG_SIG = "Wrong signature: pair the devices again."
    }

    class Asking(val device: String, val secret: String, val pub: String) {
        @Volatile var seen = 0L
        val code get() = PeerSync.code(secret)
    }

    private class Answer(val status: Int, val body: ByteArray, val sig: String? = null)

    private val asking = ArrayList<Asking>()
    private val conflicts = HashMap<String, Long>()
    private var socket: ServerSocket? = null
    private var acceptor: Thread? = null
    private var current: Socket? = null

    val localPort: Int get() = socket?.localPort ?: -1
    val running: Boolean @Synchronized get() = socket != null

    @Synchronized fun start() {
        if (socket != null) return
        val s = ServerSocket(port, 50, bind)
        socket = s
        acceptor = thread(isDaemon = true, name = "kumahon-hub") {
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                runCatching { c.use { handle(it) } }.onFailure { log("hub: $it") }
            }
        }
        log("hub: answering on port ${s.localPort}")
    }

    /** Closes the port and the request in flight, and waits for the handler to finish (a new hub on the same file must not race it). */
    fun stop() {
        val t = synchronized(this) { runCatching { socket?.close() }; socket = null; runCatching { current?.close() }; acceptor.also { acceptor = null } }
        if (t != null && t !== Thread.currentThread()) runCatching { t.join(5_000) }
    }

    /** The pairing requests seen in the last 20 s (the ones still waiting for a tap). */
    fun waiting(now: Long = System.currentTimeMillis()): List<Asking> = synchronized(asking) { asking.filter { now - it.seen <= STALE_MS } }

    /** The user said yes: [device] is paired with the secret it asked with; its next /pair poll answers 200. */
    fun allow(device: String) {
        // the file write is outside the lock: the UI polls [waiting] and must not wait for a merge
        val a = synchronized(asking) { asking.firstOrNull { it.device == device }?.also { asking.remove(it) } }
        a?.let { store.addPeer(it.device, it.secret) }
    }

    fun deny(device: String) { synchronized(asking) { asking.removeAll { it.device == device } } }

    // ---- HTTP ----

    private class HttpError(val status: Int) : Exception()

    private fun handle(c: Socket) {
        if (!c.inetAddress.let { it.isLoopbackAddress || it.isSiteLocalAddress || it.isLinkLocalAddress }) return
        c.soTimeout = minOf(30_000L, deadlineMs).toInt()
        synchronized(this) { current = c }
        var deadline = System.nanoTime() + deadlineMs * 1_000_000 // slowloris: the whole request must arrive in a minute (+1 s per MiB of body)
        var bodyRead = false
        val input = c.getInputStream().buffered()
        val a = try {
            val path = line(input, deadline).split(" ").getOrNull(1) ?: ""
            val headers = HashMap<String, String>()
            while (true) {
                val h = line(input, deadline)
                if (h.isEmpty()) break
                if (headers.size >= MAX_HEADERS) throw HttpError(431)
                headers[h.substringBefore(':').trim().lowercase()] = h.substringAfter(':').trim()
            }
            val length = headers["content-length"]?.toLongOrNull() ?: 0L
            if (length in 1..MAX_BODY) deadline += (length shr 20) * perMiBMs * 1_000_000 // a big sync on slow Wi-Fi is not cut
            when {
                length < 0 -> Answer(400, ByteArray(0))
                length > MAX_BODY -> Answer(413, ByteArray(0))
                path == "/pair" && length > MAX_PAIR_BODY -> Answer(413, ByteArray(0))
                // an unknown device is refused before its body is read (nothing allocated for a stranger)
                path == "/sync" && store.secretOf(headers["x-device"]) == null -> Answer(403, NOT_PAIRED.toByteArray())
                else -> {
                    val body = ByteArray(length.toInt())
                    var n = 0
                    while (n < body.size) {
                        if (System.nanoTime() > deadline) return
                        val r = input.read(body, n, body.size - n); if (r < 0) break; n += r
                    }
                    bodyRead = true
                    answer(path, headers["x-device"], headers["x-sig"], body)
                }
            }
        } catch (e: HttpError) { Answer(e.status, ByteArray(0)) }
        val out = c.getOutputStream()
        out.write(("HTTP/1.1 ${a.status} X\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${a.body.size}\r\n" +
            (a.sig?.let { "X-Sig: $it\r\n" } ?: "") + "Connection: close\r\n\r\n").toByteArray())
        out.write(a.body)
        out.flush()
        // a refusal sent before the body was read: let the client finish writing so it reads our answer, not a reset. At most 256 KiB
        // and 2 s on the wall clock IN TOTAL (a per-read timeout would let a dripping client hold the only thread), then close.
        if (!bodyRead) runCatching {
            c.shutdownOutput()
            val end = System.nanoTime() + DRAIN_MS * 1_000_000
            val buf = ByteArray(4096)
            var total = 0
            while (total < 256 * 1024) {
                val left = (end - System.nanoTime()) / 1_000_000
                if (left <= 0) break
                c.soTimeout = left.toInt()
                val r = input.read(buf); if (r < 0) break; total += r
            }
        }
    }

    /** One line of at most 8 KiB (431 beyond) before [deadline] (a dribbling client is dropped without a reply). */
    private fun line(input: InputStream, deadline: Long): String {
        val b = ByteArrayOutputStream()
        while (true) {
            if (System.nanoTime() > deadline) throw java.net.SocketTimeoutException("request too slow")
            val ch = input.read()
            if (ch < 0 || ch == '\n'.code) break
            if (ch != '\r'.code) b.write(ch)
            if (b.size() > MAX_LINE) throw HttpError(431)
        }
        return b.toString(Charsets.UTF_8)
    }

    private fun answer(path: String, device: String?, sig: String?, body: ByteArray): Answer = when (path) {
        "/pair" -> JSONObject(String(body, Charsets.UTF_8)).let { pair(it.getString("device"), it.getString("secret"), it.optString("pub")) }
        "/sync" -> {
            val secret = store.secretOf(device)
            when {
                secret == null -> Answer(403, NOT_PAIRED.toByteArray())
                !PeerSync.verify(secret, body, sig) -> Answer(403, WRONG_SIG.toByteArray())
                else -> runCatching {
                    val out = store.merge(JSONObject(String(body, Charsets.UTF_8))).toString().toByteArray(Charsets.UTF_8)
                    log("hub: synced $device")
                    Answer(200, out, PeerSync.sign(secret, out))
                }.getOrElse { Answer(422, (it.message ?: it.toString()).toByteArray()) }
            }
        }
        else -> Answer(404, ByteArray(0))
    }

    /** kumapie's `requested` (DeviceSync.kt:247). The code shown is the secret's alone: with `setup:false` the asker shows that one. */
    private fun pair(who: String, secret: String, pub: String, now: Long = System.currentTimeMillis()): Answer {
        // the hub's own name is public: nobody may pair as the hub (kumahon's own pairing is made locally, never over HTTP)
        if (who == store.hubName) return Answer(403, "That name is this hub's.".toByteArray())
        if (who.length > MAX_DEVICE) return Answer(400, "Device name too long.".toByteArray())
        val reply = JSONObject().put("device", store.hubName).put("setup", false).toString().toByteArray()
        if (store.secretOf(who) == secret) return Answer(200, reply)
        val ask = synchronized(asking) {
            asking.removeAll { now - it.seen > DEAD_MS }
            if (conflicts[who]?.let { now - it > CONFLICT_MS } == true) conflicts.remove(who)
            if (who in conflicts) return Answer(409, TWO.toByteArray())
            val old = asking.firstOrNull { it.device == who }
            if (old == null && asking.size >= MAX_ASKING) return Answer(409, "Too many devices asking: try again in a minute.".toByteArray())
            if (old != null && (old.secret != secret || old.pub != pub)) {
                asking.remove(old)
                if (now - old.seen <= STALE_MS || PeerSync.code(secret) == old.code) { conflicts[who] = now; return Answer(409, TWO.toByteArray()) }
            }
            (asking.firstOrNull { it.device == who } ?: Asking(who, secret, pub).also { asking.add(it) }).also { it.seen = now }
        }
        onPairRequest(ask.device, ask.code) // may allow() at once (the app's own pairing): then this very poll is a 200
        return Answer(if (store.secretOf(who) == secret) 200 else 202, reply)
    }
}
