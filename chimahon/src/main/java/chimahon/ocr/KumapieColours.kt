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

    /** A character's name in a text: where it starts and the character's picture, cut to a circle. */
    class Face(val start: Int, val picture: android.graphics.Bitmap)
    private val faceCache = LruCache<String, List<Face>>(500)
    private val pictures = ConcurrentHashMap<String, android.graphics.Bitmap>()

    /**
     * The characters' names in [text] with a picture in kumapie's character dictionary (its provider's `faces`), as its
     * subtitles show them; null until kumapie answered ([ready] is called then, when there are any).
     */
    fun faces(context: Context, text: String, ready: () -> Unit): List<Face>? {
        val work = work
        val key = "${work.orEmpty()}\n$text"
        faceCache.get(key)?.let { return it }
        if (asking.add("faces:$key")) {
            worker.execute {
                val faces = runCatching { askFaces(context.applicationContext, text, work) }
                    .onFailure { Log.w("KumapieColours", "kumapie faces: $it") }.getOrNull().orEmpty()
                faceCache.put(key, faces)
                asking.remove("faces:$key")
                if (faces.isNotEmpty()) ready()
            }
        }
        return null
    }

    /** The Japanese title of the manga being read: kumapie gives faces only to its characters (null: any of that name). */
    @Volatile private var work: String? = null
    private val works = ConcurrentHashMap<String, String>()
    private val JAPANESE = Regex("[\\u3040-\\u30ff\\u4e00-\\u9fff]")

    /**
     * The reader opened [title]. Its Japanese title: the description's first line when that is one (the Real-Debrid
     * import puts AniList's there), the title itself when Japanese, else AniList's best match, asked once per run.
     */
    fun reading(title: String, description: String?) {
        val first = description?.lineSequence()?.firstOrNull()?.trim()
        work = listOfNotNull(first, title).firstOrNull { it.length <= 60 && '。' !in it && JAPANESE.containsMatchIn(it) }
            ?: works[title]
        Log.i("KumapieColours", "reading $title: work $work")
        if (work != null || title.isBlank()) return
        worker.execute {
            runCatching { aniListNative(title) }.onFailure { Log.w("KumapieColours", "AniList: $it") }.getOrNull()
                ?.let { works[title] = it; work = it; Log.i("KumapieColours", "reading $title: AniList says $it") }
        }
    }

    private fun aniListNative(title: String): String? {
        val query = "query(\$q: String) { Media(search: \$q, type: MANGA) { title { native } } }"
        val body = JSONObject().put("query", query).put("variables", JSONObject().put("q", title)).toString()
        val c = java.net.URL("https://graphql.anilist.co").openConnection() as java.net.HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Accept", "application/json")
        c.outputStream.use { it.write(body.toByteArray()) }
        if (c.responseCode == 404) return null // no match
        val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        return json.getJSONObject("data").optJSONObject("Media")?.optJSONObject("title")?.optString("native")?.takeIf { it.isNotBlank() }
    }

    private fun askFaces(context: Context, text: String, work: String?): List<Face> {
        val authority = authority(context) ?: return emptyList()
        val out = ArrayList<Face>()
        val args = if (work == null) arrayOf(text) else arrayOf(text, work)
        context.contentResolver.query(Uri.parse("content://$authority/faces"), null, null, args, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(2)
                val picture = pictures["${work.orEmpty()}\n$name"] ?: context.contentResolver
                    .openInputStream(Uri.parse("content://$authority/face?name=" + Uri.encode(name) +
                        (work?.let { "&work=" + Uri.encode(it) } ?: "")))
                    ?.use { android.graphics.BitmapFactory.decodeStream(it) }?.let(::circle)
                    ?.also { pictures["${work.orEmpty()}\n$name"] = it } ?: continue
                out += Face(c.getInt(0), picture)
            }
        }
        if (out.isNotEmpty()) Log.d("KumapieColours", "faces: ${out.size} in ${text.length} chars, work $work")
        return out
    }

    /** The top square of a portrait (the face), as a circle. */
    private fun circle(src: android.graphics.Bitmap): android.graphics.Bitmap {
        val size = minOf(src.width, src.height)
        val out = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val shader = android.graphics.BitmapShader(src, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
        shader.setLocalMatrix(android.graphics.Matrix().apply { setTranslate(-(src.width - size) / 2f, 0f) })
        paint.shader = shader
        android.graphics.Canvas(out).drawCircle(size / 2f, size / 2f, size / 2f, paint)
        return out
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
    /** The sentence (OCR text block) of the popup's current lookup, set by the popup; sent along with "sense". */
    @Volatile var sentence: String = ""

    fun call(context: Context, bodyJson: String): String = runCatching {
        val authority = authority(context) ?: return@runCatching "null"
        val body = JSONObject(bodyJson)
        val action = body.getString("action")
        if (action == "sense") {
            // The AI sense box: kumapie's "sense" (check = does this word get a box, else the vote); its JSON as is.
            val ask = Bundle().apply {
                putString("word", body.optString("word"))
                putString("reading", body.optString("reading"))
                putString("sentence", sentence)
                putBoolean("check", body.optBoolean("check"))
            }
            return@runCatching context.contentResolver.call(Uri.parse("content://$authority"), "sense", null, ask)
                ?.getString("json") ?: "null"
        }
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

    /**
     * A card for [deck] goes to kumapie first: kumapie keeps the Mined decks' cards of a person it has them for (its
     * own Anki, text only). Its note id, or null: not kumapie's (another deck, kumapie absent or not set up for it),
     * so the caller adds it to Anki as before. Blocking.
     */
    fun mine(context: Context, deck: String, fields: Map<String, String>, tags: List<String>): Long? = runCatching {
        val authority = authority(context) ?: return@runCatching null
        val extras = Bundle().apply {
            putString("deck", deck)
            putString("fields", JSONObject(fields).toString())
            putString("tags", tags.joinToString(" "))
        }
        context.contentResolver.call(Uri.parse("content://$authority"), "mine", null, extras)?.takeIf { it.containsKey("note") }?.getLong("note")
    }.onFailure { Log.w("KumapieColours", "kumapie mine: $it") }.getOrNull()

    /** An overwrite of note [note] in [deck] done in kumapie's store; false: not kumapie's card, Anki does it. Blocking. */
    fun update(context: Context, deck: String, note: Long, fields: Map<String, String>): Boolean = runCatching {
        val authority = authority(context) ?: return@runCatching false
        val extras = Bundle().apply {
            putString("deck", deck)
            putLong("note", note)
            putString("fields", JSONObject(fields).toString())
        }
        context.contentResolver.call(Uri.parse("content://$authority"), "update", null, extras)?.getBoolean("updated") == true
    }.onFailure { Log.w("KumapieColours", "kumapie update: $it") }.getOrDefault(false)

    private fun authority(context: Context): String? =
        PACKAGES.firstOrNull { context.packageManager.resolveContentProvider("$it.words", 0) != null }?.let { "$it.words" }

    /**
     * kumapie's words in [text] as its colours rows (start, length, status u/d/b), for the reader's page-words list;
     * null when kumapie can't answer. Blocking: call it off the UI thread.
     */
    fun words(context: Context, text: String): List<Triple<Int, Int, String>>? = runCatching<List<Triple<Int, Int, String>>?> {
        val authority = authority(context) ?: return@runCatching null
        buildList<Triple<Int, Int, String>> {
            context.contentResolver.query(Uri.parse("content://$authority/colours"), null, null, arrayOf(text), null)?.use { c ->
                while (c.moveToNext()) add(Triple(c.getInt(0), c.getInt(1), c.getString(2)))
            }
        }
    }.onFailure { Log.w("KumapieColours", "kumapie words: $it") }.getOrNull()

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

    /**
     * [text] read by kumapie's ずんだもん (its provider's `say`), as WAV bytes; null when it has no Japanese, or kumapie
     * can't (not installed, no permission, voice not downloaded). Blocking: call it off the UI thread.
     */
    fun say(context: Context, text: String): ByteArray? = runCatching {
        if (text.none { it.code in 0x3040..0x30FF || it.code in 0x4E00..0x9FFF }) return null
        val authority = authority(context) ?: return null
        val uri = Uri.parse("content://$authority/say").buildUpon().appendQueryParameter("text", text.trim()).build()
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }.onFailure { Log.w("KumapieColours", "kumapie didn't say it: $it") }.getOrNull()
}
