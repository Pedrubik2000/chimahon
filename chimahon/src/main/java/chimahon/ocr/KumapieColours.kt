package chimahon.ocr

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Word colours from kumapie (its `WordColours` provider), the same as its subtitles: red unknown, green due today in
 * kuma3, blue mined and never reviewed, known words keep the normal text colour. One colour per character of the text.
 */
object KumapieColours {
    /** kumapie's phone app, release then debug build. */
    val PACKAGES = listOf("io.github.pedrubik2000.kumapie.mobile", "io.github.pedrubik2000.kumapie.mobile.debug")

    // Darker than kumapie's subtitle colours: these draw on the OCR boxes' white background.
    private val COLOURS = mapOf("u" to 0xFFD32F2F.toInt(), "d" to 0xFF2E7D32.toInt(), "b" to 0xFF1565C0.toInt())
    /** kumapie reads kuma3's due queue once a minute; ask again after that. */
    private const val MAX_AGE_MS = 60_000L

    private class Entry(val colours: IntArray?, val time: Long)
    private val cache = LruCache<String, Entry>(500)
    private val asking = ConcurrentHashMap.newKeySet<String>()
    private val worker = Executors.newSingleThreadExecutor()

    /** The text's colours (0 = normal) when known, else null; asks kumapie in the background and calls [ready] once it answered. */
    fun of(context: Context, text: String, ready: () -> Unit): IntArray? {
        val hit = cache.get(text)
        if (hit == null || System.currentTimeMillis() - hit.time > MAX_AGE_MS) {
            if (asking.add(text)) {
                worker.execute {
                    val colours = runCatching { ask(context.applicationContext, text) }
                        .onFailure { Log.w("KumapieColours", "kumapie didn't answer: $it") }.getOrNull()
                    cache.put(text, Entry(colours, System.currentTimeMillis()))
                    asking.remove(text)
                    if (colours != null) ready()
                }
            }
        }
        return hit?.colours
    }

    /** Forgets every text's colours: after a rating, Undo or a new card the next draw asks kumapie again. */
    fun clear() = cache.evictAll()

    /**
     * kumapie's rating row (as Hoshi's popup): [bodyJson] `{action, word, reading, ease?, ms?, rated?, known?, text?}` goes to
     * its provider's `call` ("state", "mark", "rate", "undo", "reload"); answers
     * `{state?, marked?, rated?: {notes, cards, undo}, undone?}`,
     * or "null" when kumapie can't (not installed, no permission). Rating and Undo need kuma3's permission, which
     * chimahon holds for mining. Blocking: call it off the UI thread.
     */
    fun call(context: Context, bodyJson: String): String = runCatching {
        val authority = authority(context) ?: return@runCatching "null"
        val body = JSONObject(bodyJson)
        val action = body.getString("action")
        val extras = Bundle().apply {
            putString("word", body.optString("word"))
            putString("reading", body.optString("reading"))
            putInt("ease", body.optInt("ease"))
            putLong("ms", body.optLong("ms"))
            putBoolean("known", body.optBoolean("known"))
            putString("text", body.optString("text"))
            body.optJSONObject("rated")?.let { r ->
                val notes = r.getJSONArray("notes")
                putLongArray("notes", LongArray(notes.length()) { notes.getLong(it) })
                putInt("cards", r.getInt("cards"))
                putString("undo", r.getString("undo"))
            }
        }
        val out = context.contentResolver.call(Uri.parse("content://$authority"), action, null, extras)
            ?: return@runCatching "null"
        if (action != "state") clear()
        JSONObject().apply {
            out.getString("state")?.let { put("state", it) }
            if (out.containsKey("marked")) put("marked", out.getBoolean("marked"))
            out.getLongArray("notes")?.let { notes ->
                put("rated", JSONObject().put("notes", JSONArray(notes.toList())).put("cards", out.getInt("cards")).put("undo", out.getString("undo")))
            }
            if (out.containsKey("undone")) put("undone", out.getBoolean("undone"))
        }.toString()
    }.onFailure { Log.w("KumapieColours", "kumapie call failed: $it") }.getOrDefault("null")

    private fun authority(context: Context): String? =
        PACKAGES.firstOrNull { context.packageManager.resolveContentProvider("$it.words", 0) != null }?.let { "$it.words" }

    private fun ask(context: Context, text: String): IntArray? {
        val authority = authority(context) ?: return null
        val out = IntArray(text.length)
        context.contentResolver.query(Uri.parse("content://$authority/colours"), null, null, arrayOf(text), null)?.use { c ->
            while (c.moveToNext()) {
                val colour = COLOURS[c.getString(2)] ?: continue
                val start = c.getInt(0)
                for (i in start until minOf(start + c.getInt(1), text.length)) out[i] = colour
            }
        }
        return out
    }
}
