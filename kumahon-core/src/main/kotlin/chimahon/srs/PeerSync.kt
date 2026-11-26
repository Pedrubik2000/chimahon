package chimahon.srs

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The little key-value store the pairing lives in: SharedPreferences "kumahon_peer" on Android, a file on the desktop. Keys as in [PeerSync]; null = remove. */
interface PeerPrefs {
    fun get(k: String): String?
    fun put(k: String, v: String?)
}

/**
 * kumahon as one more peer of kumapie's device sync (its `DeviceSync`, port 47823): every device keeps its own cards, and what
 * crosses is the review history per word, as events matched by [WordKey]. We send every revlog row of our store as an event
 * (never card content, Pedro 2026-11-26) and take the answer's events for words we have a card for.
 * kumapie must have "answer other devices" on, and the user taps Allow once on its side ([pair]).
 *
 * [model] names this machine ("Pixel 8a"); [log] gets one line per sync or failed pairing.
 */
class PeerSync(private val prefs: PeerPrefs, private val openStore: () -> Srs, private val model: String, private val log: (String) -> Unit = {}) {
    companion object {
        const val DEFAULT_HUB = "127.0.0.1:47823"

        const val HUB = "hub"
        const val SECRET = "secret"
        const val PAIRED = "paired"
        const val NAME = "name"
        const val DEVICE_NAME = "device_name"
        const val LAST_TIME = "last_time"
        const val LAST_RESULT = "last_result"
        private const val FORMAT = "kumapie-device-1"
        private val lock = Any()

        // ---- pure parts (unit tested) ----

        /** The 4 digits the user sees on both devices: HMAC("pair") of the secret mod 10000. */
        fun code(secret: String): String = BigInteger(1, mac(secret, "pair".toByteArray())).mod(BigInteger.valueOf(10_000)).toString().padStart(4, '0')

        /** Lowercase hex HMAC-SHA256 of [body], the key being the secret's UTF-8 bytes. */
        fun sign(secret: String, body: ByteArray): String = mac(secret, body).joinToString("") { "%02x".format(it) }

        fun verify(secret: String, body: ByteArray, sig: String?): Boolean =
            sig != null && MessageDigest.isEqual(sign(secret, body).toByteArray(), sig.toByteArray())

        private fun mac(secret: String, data: ByteArray) =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")) }.doFinal(data)

        /** Card key -> the lowest card id with it (kumapie's `toAdd` rule), from (card id, key or null). */
        fun byKey(cards: Collection<Pair<Long, String?>>): Map<String, Long> = HashMap<String, Long>().also { m ->
            for ((id, key) in cards.sortedBy { it.first }) if (key != null) m.putIfAbsent(key, id)
        }

        /** The incoming events to insert: those whose id we don't have and whose key is one of our cards, with that card. */
        fun <E> pick(events: List<E>, id: (E) -> Long, key: (E) -> String, have: Set<Long>, cards: Map<String, Long>): List<Pair<E, Long>> =
            events.filter { id(it) >= 0 && id(it) !in have }.mapNotNull { e -> cards[key(e)]?.let { e to it } }

        /** The key kumapie matches this card by (`ja|spelling<TAB>reading`, from its `word` field and the `Reading` field), or null. */
        fun keyOf(card: Srs.Card): String? =
            WordKey.cardKey(card.word, runCatching { JSONObject(card.fields).optString("Reading") }.getOrDefault(""))

        /**
         * This device's part of kumapie's `progress` (Progress.kt: a part per device, the hub keeps the newest): only the marks, as
         * `marked[word] = [known, seconds]`; null without marks.
         */
        fun progress(marks: Map<String, Pair<Boolean, Long>>, device: String): JSONObject? {
            if (marks.isEmpty()) return null
            val marked = JSONObject()
            for ((w, m) in marks) marked.put(w, JSONArray().put(m.first).put(m.second))
            return JSONObject().put(device, JSONObject().put("pos", JSONObject()).put("watch", JSONObject()).put("lookups", JSONObject()).put("marked", marked))
        }

        /**
         * Our part of kumapie's `carded` (WordSync.own): {device: {made, keys}}, the whole sorted key list of our own cards each time (empty
         * when we have none, so a deleted last card clears on the hub: its merge takes the newer `made`). Never other devices' entries.
         */
        fun own(device: String, keys: Collection<String>, made: Long = System.currentTimeMillis()): JSONObject =
            JSONObject().put(device, JSONObject().put("made", made).put("keys", JSONArray(keys.distinct().sorted())))

        /** The hub address is this device ("127.0.0.1:47823", "localhost"): kumapie runs here and its provider answers. */
        fun isLocalHub(hub: String): Boolean = hub.trim().substringBefore(':').lowercase().let { it == "127.0.0.1" || it == "localhost" }

        private class Answer(val status: Int, val body: ByteArray, val sig: String?)

        private fun post(url: String, body: ByteArray, headers: Map<String, String>): Answer = (URL(url).openConnection() as HttpURLConnection).run {
            try {
                connectTimeout = 5_000
                readTimeout = 120_000
                requestMethod = "POST"
                doOutput = true
                setFixedLengthStreamingMode(body.size)
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                outputStream.use { it.write(body) }
                val code = responseCode
                Answer(code, (if (code < 400) inputStream else errorStream)?.use { it.readBytes() } ?: ByteArray(0), getHeaderField("X-Sig"))
            } finally { disconnect() }
        }
    }

    sealed interface PairState {
        data class Paired(val name: String) : PairState
        data class Waiting(val code: String) : PairState
        data class Failed(val why: String) : PairState
    }

    // ---- settings ----

    /** This install's name on kumapie ("kumahon Pixel 8a"): chosen once, then kept, so another kumahon never takes this one's pairing. */
    fun deviceName(): String {
        prefs.get(DEVICE_NAME)?.ifBlank { null }?.let { return it }
        // Already paired before this existed: kumapie knows us as "kumahon"; another name would be a 403 and a new pairing.
        val name = if (paired()) "kumahon" else "kumahon $model".trim()
        prefs.put(DEVICE_NAME, name)
        return name
    }

    fun hub(): String = prefs.get(HUB)?.ifBlank { null } ?: DEFAULT_HUB

    fun setHub(address: String) = prefs.put(HUB, address.trim())

    fun paired(): Boolean = prefs.get(PAIRED) == "true"

    /** When the last sync (or pairing) attempt ended, ms; 0 = never. */
    fun lastSyncTime(): Long = prefs.get(LAST_TIME)?.toLongOrNull() ?: 0L

    /** The settings line: what to do, or how the last sync went. */
    fun status(): String {
        val result = prefs.get(LAST_RESULT).orEmpty()
        val secret = prefs.get(SECRET)
        if (!paired()) return when {
            result.isNotEmpty() -> result
            secret != null -> "Waiting: on kumapie tap Allow ${deviceName()}, code ${code(secret)}, then tap here again"
            else -> "Not paired: tap to pair"
        }
        val time = prefs.get(LAST_TIME)?.toLongOrNull() ?: 0L
        val last = if (time == 0L) "none yet" else "${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(time)}: $result"
        return "Paired with ${prefs.get(NAME) ?: "kumapie"}. Last sync $last"
    }

    /** The last result line (null: forget it). */
    private fun say(result: String?) {
        if (result == null) { prefs.put(LAST_RESULT, null); prefs.put(LAST_TIME, null) }
        else { prefs.put(LAST_TIME, System.currentTimeMillis().toString()); prefs.put(LAST_RESULT, result) }
    }

    // ---- network ----

    /**
     * Asks kumapie to allow this device. The secret is made once and kept until paired (tapping again after Allow must send the same
     * one). 200 = allowed; 202 = waiting for the user's tap on kumapie (its pending list lives in memory: pair again if it restarted).
     * Blocking.
     */
    fun pair(): PairState = synchronized(lock) {
        val hub = hub()
        val state: PairState = try {
            val secret = prefs.get(SECRET) ?: ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            prefs.put(SECRET, secret)
            val a = post("http://$hub/pair", JSONObject().put("device", deviceName()).put("secret", secret).toString().toByteArray(), emptyMap())
            when (a.status) {
                200 -> JSONObject(String(a.body)).optString("device", "kumapie").let { name ->
                    prefs.put(PAIRED, "true"); prefs.put(NAME, name); openStore().forgetShared()
                    PairState.Paired(name)
                }
                202 -> PairState.Waiting(code(secret))
                else -> PairState.Failed(String(a.body).ifBlank { "kumapie answered ${a.status}" })
            }
        } catch (e: Exception) {
            log("pair with $hub failed: $e")
            PairState.Failed("Can't reach kumapie at $hub (is \"answer other devices\" on in kumapie?): ${e.message ?: e}")
        }
        say((state as? PairState.Failed)?.why)
        state
    }

    /**
     * One exchange with kumapie: our events and card keys out, its events in. Not paired = nothing done. One at a time. Never throws:
     * false (and the reason in [status]) on any failure; a 403 means kumapie forgot us: unpaired, [pair] again. Blocking.
     */
    fun sync(): Boolean = synchronized(lock) {
        if (!paired()) return false
        try {
            val secret = prefs.get(SECRET) ?: return false
            val store = openStore()
            val dump = store.dump()
            val keys = HashMap<Long, String>()
            for (c in store.cards()) keyOf(c)?.let { keys[c.id] = it }
            val events = JSONArray()
            val rows = dump.getJSONArray("revlog")
            val mine = List(rows.length()) { rows.getJSONObject(it) }.sortedBy { it.getLong("id") }
            for (r in mine) keys[r.getLong("card")]?.let { k ->
                events.put(JSONObject().put("id", r.getLong("id")).put("key", k).put("time", r.getLong("time")).put("ease", r.getInt("rating")).put("device", r.optString("device")))
            }
            val device = deviceName()
            // no "cards": card content never leaves kumahon, only rating events and card keys (Pedro 2026-11-26)
            val body = JSONObject().put("format", FORMAT).put("device", device).put("person", "me").put("made", System.currentTimeMillis())
                .put("events", events).put("carded", own(device, keys.values)).apply { progress(store.myMarks(), device)?.let { put("progress", it) } }
                .apply { store.pendingShared()?.let { (t, w) -> put("known", JSONObject().put(Srs.LANG, JSONObject().put("time", t).put("words", JSONArray(w)))) } }
                .toString().toByteArray(Charsets.UTF_8)
            val a = post("http://${hub()}/sync", body, mapOf("X-Device" to device, "X-Sig" to sign(secret, body)))
            if (a.status == 403) {
                prefs.put(PAIRED, "false"); prefs.put(SECRET, null)
                say("kumapie no longer knows this device: pair again")
                return false
            }
            if (a.status != 200) error(String(a.body).ifBlank { "kumapie answered ${a.status}" })
            check(verify(secret, a.body, a.sig)) { "wrong signature from kumapie: pair again" }
            val answer = JSONObject(String(a.body))
            // kumapie's FSRS parameters, as its own devices take them (DeviceFiles.takeFsrs): same revlog, same schedule.
            answer.optJSONObject("fsrs")?.optJSONObject(Srs.LANG)?.let { store.useFsrs(it.optString("params").ifEmpty { null }, it.optString("retention").ifEmpty { null }) }
            answer.optJSONObject("carded")?.let { store.takeCarded(it, device) }
            val theirs = answer.optJSONArray("events") ?: JSONArray()
            val incoming = List(theirs.length()) { theirs.getJSONObject(it) }
            // Our cards and revlog may have changed while the request ran: read them again before inserting.
            val ours = byKey(store.cards().map { it.id to keyOf(it) })
            val take = pick(incoming, { it.optLong("id", -1) }, { it.optString("key") }, store.revlogIds(), ours)
            if (take.isNotEmpty()) store.take(take)
            // The words kumapie counts as known (an older kumapie without these keys: the list stays as it was).
            if (answer.has("events") || answer.has("known")) {
                store.takeShared(answer.optJSONObject("known")?.optJSONObject(Srs.LANG)?.optLong("time") ?: 0, KnownWords.words(answer))
                val known = KnownWords.of(answer, store.fsrs, store.sharedWords().takeIf { answer.has("known") })
                store.replaceKnown(known)
                store.replaceMarked(KnownWords.marks(answer.optJSONObject("progress"), store.myMarks()))
                log("known words from kumapie: ${known.size}")
            }
            say("ok: sent ${events.length()}, took ${take.size}")
            log("sync with kumapie ok: sent ${events.length()}, took ${take.size}")
            true
        } catch (e: Exception) {
            log("sync with kumapie failed: $e")
            say(if (e is java.net.ConnectException) {
                "kumapie is not answering: open kumapie once, or turn on its \"Also while kumapie is closed\" switch (sync settings). Trying again later"
            } else e.message ?: e.toString())
            false
        }
    }
}
