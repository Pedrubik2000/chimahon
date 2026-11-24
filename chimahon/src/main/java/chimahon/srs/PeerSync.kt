package chimahon.srs

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * kumahon as one more peer of kumapie's device sync (its `DeviceSync`, port 47823): every device keeps its own cards, and what
 * crosses is the review history per word, as events matched by [WordKey]. We send every revlog row of our store as an event
 * (plus our store as the backup file kumapie keeps) and take the answer's events for words we have a card for.
 * kumapie must have "answer other devices" on, and the user taps Allow once on its side ([pair]).
 */
object PeerSync {
    const val DEFAULT_HUB = "127.0.0.1:47823"

    sealed interface PairState {
        data class Paired(val name: String) : PairState
        data class Waiting(val code: String) : PairState
        data class Failed(val why: String) : PairState
    }

    private const val PREFS = "kumahon_peer"
    private const val HUB = "hub"
    private const val SECRET = "secret"
    private const val PAIRED = "paired"
    private const val NAME = "name"
    private const val DEVICE_NAME = "device_name"
    private const val LAST_TIME = "last_time"
    private const val LAST_RESULT = "last_result"
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

    // ---- settings ----

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** This install's name on kumapie ("kumahon Pixel 8a"): chosen once, then kept, so another kumahon never takes this one's pairing. */
    fun deviceName(context: Context): String {
        val p = prefs(context)
        p.getString(DEVICE_NAME, null)?.ifBlank { null }?.let { return it }
        // Already paired before this existed: kumapie knows us as "kumahon"; another name would be a 403 and a new pairing.
        val name = if (paired(context)) "kumahon" else "kumahon ${Build.MODEL.orEmpty()}".trim()
        p.edit().putString(DEVICE_NAME, name).apply()
        return name
    }

    fun hub(context: Context): String = prefs(context).getString(HUB, null)?.ifBlank { null } ?: DEFAULT_HUB

    fun setHub(context: Context, address: String) = prefs(context).edit().putString(HUB, address.trim()).apply()

    fun paired(context: Context): Boolean = prefs(context).getBoolean(PAIRED, false)

    /** The settings line: what to do, or how the last sync went. */
    fun status(context: Context): String {
        val p = prefs(context)
        val result = p.getString(LAST_RESULT, null).orEmpty()
        val secret = p.getString(SECRET, null)
        if (!paired(context)) return when {
            result.isNotEmpty() -> result
            secret != null -> "Waiting: on kumapie tap Allow ${deviceName(context)}, code ${code(secret)}, then tap here again"
            else -> "Not paired: tap to pair"
        }
        val time = p.getLong(LAST_TIME, 0)
        val last = if (time == 0L) "none yet" else "${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(time)}: $result"
        return "Paired with ${p.getString(NAME, "kumapie")}. Last sync $last"
    }

    /** The last result line (null: forget it). */
    private fun say(context: Context, result: String?) = prefs(context).edit().apply {
        if (result == null) remove(LAST_RESULT).remove(LAST_TIME) else putLong(LAST_TIME, System.currentTimeMillis()).putString(LAST_RESULT, result)
    }.apply()

    // ---- network ----

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

    /**
     * Asks kumapie to allow this device. The secret is made once and kept until paired (tapping again after Allow must send the same
     * one). 200 = allowed; 202 = waiting for the user's tap on kumapie (its pending list lives in memory: pair again if it restarted).
     * Blocking.
     */
    fun pair(context: Context): PairState = synchronized(lock) {
        val p = prefs(context)
        val hub = hub(context)
        val state: PairState = try {
            val secret = p.getString(SECRET, null) ?: ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            p.edit().putString(SECRET, secret).apply()
            val a = post("http://$hub/pair", JSONObject().put("device", deviceName(context)).put("secret", secret).toString().toByteArray(), emptyMap())
            when (a.status) {
                200 -> JSONObject(String(a.body)).optString("device", "kumapie").let { name ->
                    p.edit().putBoolean(PAIRED, true).putString(NAME, name).apply()
                    PairState.Paired(name)
                }
                202 -> PairState.Waiting(code(secret))
                else -> PairState.Failed(String(a.body).ifBlank { "kumapie answered ${a.status}" })
            }
        } catch (e: Exception) {
            Log.w("kumahon", "pair with $hub failed: $e")
            PairState.Failed("Can't reach kumapie at $hub (is \"answer other devices\" on in kumapie?): ${e.message ?: e}")
        }
        say(context, (state as? PairState.Failed)?.why)
        state
    }

    /**
     * One exchange with kumapie: our events and store out, its events in. Not paired = nothing done. One at a time. Never throws:
     * false (and the reason in [status]) on any failure; a 403 means kumapie forgot us: unpaired, [pair] again. Blocking.
     */
    fun sync(context: Context): Boolean = synchronized(lock) {
        if (!paired(context)) return false
        val p = prefs(context)
        try {
            val secret = p.getString(SECRET, null) ?: return false
            val store = Cards.store(context)
            val dump = store.dump()
            val keys = HashMap<Long, String>()
            for (c in store.cards()) Cards.keyOf(c)?.let { keys[c.id] = it }
            val events = JSONArray()
            val rows = dump.getJSONArray("revlog")
            val mine = List(rows.length()) { rows.getJSONObject(it) }.sortedBy { it.getLong("id") }
            for (r in mine) keys[r.getLong("card")]?.let { k ->
                events.put(JSONObject().put("id", r.getLong("id")).put("key", k).put("time", r.getLong("time")).put("ease", r.getInt("rating")).put("device", r.optString("device")))
            }
            val device = deviceName(context)
            val body = JSONObject().put("format", FORMAT).put("device", device).put("person", "me").put("made", System.currentTimeMillis())
                .put("events", events).put("cards", JSONObject().put(Srs.LANG, dump)).toString().toByteArray(Charsets.UTF_8)
            val a = post("http://${hub(context)}/sync", body, mapOf("X-Device" to device, "X-Sig" to sign(secret, body)))
            if (a.status == 403) {
                p.edit().putBoolean(PAIRED, false).remove(SECRET).apply()
                say(context, "kumapie no longer knows this device: pair again")
                return false
            }
            if (a.status != 200) error(String(a.body).ifBlank { "kumapie answered ${a.status}" })
            check(verify(secret, a.body, a.sig)) { "wrong signature from kumapie: pair again" }
            val answer = JSONObject(String(a.body))
            // kumapie's FSRS parameters, as its own devices take them (DeviceFiles.takeFsrs): same revlog, same schedule.
            answer.optJSONObject("fsrs")?.optJSONObject(Srs.LANG)?.let { store.useFsrs(it.optString("params").ifEmpty { null }, it.optString("retention").ifEmpty { null }) }
            val theirs = answer.optJSONArray("events") ?: JSONArray()
            val incoming = List(theirs.length()) { theirs.getJSONObject(it) }
            // Our cards and revlog may have changed while the request ran: read them again before inserting.
            val ours = byKey(store.cards().map { it.id to Cards.keyOf(it) })
            val take = pick(incoming, { it.optLong("id", -1) }, { it.optString("key") }, store.revlogIds(), ours)
            if (take.isNotEmpty()) store.take(take)
            say(context, "ok: sent ${events.length()}, took ${take.size}")
            Log.i("kumahon", "sync with kumapie ok: sent ${events.length()}, took ${take.size}")
            true
        } catch (e: Exception) {
            Log.w("kumahon", "sync with kumapie failed: $e")
            say(context, e.message ?: e.toString())
            false
        }
    }
}
