package chimahon.ocr

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.LruCache
import chimahon.chardict.CharacterDictionary
import chimahon.lang.JapaneseModel
import chimahon.lang.WordColourer
import chimahon.srs.Cards
import chimahon.srs.PeerSync
import chimahon.srs.Srs
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Word colours, the same as kumapie's subtitles: red unknown, green due today, blue mined and never reviewed, known words keep the
 * normal text colour. One colour per character of the text. kumahon colours ON ITS OWN ([WordColourer]: its own split, its own cards,
 * the known list and marks kumapie's sync brought), on every device; kumapie's provider answers only faces, sense, marks and mining.
 */
object KumapieColours {
    /** kumapie's phone app, release then debug build. */
    val PACKAGES = listOf("io.github.pedrubik2000.kumapie.mobile", "io.github.pedrubik2000.kumapie.mobile.debug")

    // Darker than kumapie's subtitle colours: these draw on the OCR boxes' white background.
    private val COLOURS = mapOf("u" to 0xFFD32F2F.toInt(), "d" to 0xFF2E7D32.toInt(), "b" to 0xFF1565C0.toInt())
    /** The colours are read again after a minute (cards fall due), as kumapie's provider did. */
    private const val MAX_AGE_MS = 60_000L

    // Word colouring: the switch (default on: existing users keep their colours), the optional dojo server Jiten's two files come from
    // (<server>/api/models/<file>; empty = Sudachi alone), the dictionary and the colourer over kumahon's own store.
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("kumahon_colours", Context.MODE_PRIVATE)
    fun enabled(context: Context): Boolean = prefs(context).getBoolean("wordColouring", true)
    fun setEnabled(context: Context, on: Boolean) { prefs(context).edit().putBoolean("wordColouring", on).apply(); clear() }
    fun server(context: Context): String = prefs(context).getString("colouringServer", "").orEmpty()
    fun setServer(context: Context, url: String) { prefs(context).edit().putString("colouringServer", url.trim()).apply() }

    private var japanese: JapaneseModel? = null
    private var colourer: WordColourer? = null

    @Synchronized
    private fun colourer(context: Context): WordColourer {
        val app = context.applicationContext
        val model = japanese ?: JapaneseModel(java.io.File(app.filesDir, "sudachi"), server = { server(app) }).also { japanese = it }
        return colourer ?: WordColourer({ Cards.store(app) }, model::words, model::autoKnown, model::spellings).also { colourer = it }
    }

    private val downloading = java.util.concurrent.atomic.AtomicBoolean()
    /** Own thread: a download of minutes must never hold the worker that colours pages, asks faces and AniList. */
    private val downloader = Executors.newSingleThreadExecutor { r -> Thread(r, "kumahon-dictionary").apply { isDaemon = true } }
    /** No new attempt before this time (ms): after a failure, and after Jiten's files were thrown away. */
    @Volatile private var retryAt = 0L
    private const val RETRY_MS = 10 * 60_000L

    /** Pages (views) and lists to redraw / reload when the dictionary arrives or the colours change; each removes itself when it goes. */
    private val redraws = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
    private val dictionaryReady = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
    /** [listener] runs when colours may have changed (a sync, a rating, a mark, the dictionary): a page view invalidates itself. Returns the remover. */
    fun onRedraw(listener: () -> Unit): () -> Unit { redraws += listener; return { redraws -= listener } }
    /** [listener] runs once the dictionary has been downloaded (the page words list loads again). Returns the remover. */
    fun onDictionary(listener: () -> Unit): () -> Unit { dictionaryReady += listener; return { dictionaryReady -= listener } }

    private fun metered(context: Context): Boolean =
        (context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager)?.isActiveNetworkMetered != false

    /**
     * The dictionary (Sudachi, then Jiten's files when a server is set) is fetched on its own thread, one download at a time,
     * never twice within [RETRY_MS] of a failure. The 80 MB Sudachi dictionary waits for an unmetered network (Wi-Fi).
     */
    private fun download(context: Context) {
        val model = japanese ?: return
        if (System.currentTimeMillis() < retryAt || System.currentTimeMillis() - model.droppedAt < RETRY_MS) return
        if (metered(context)) return // Sudachi's 80 MB, Jiten's 45 MB and the daily look at the release: Wi-Fi only
        if (!downloading.compareAndSet(false, true)) return
        downloader.execute {
            try {
                val had = model.isReady
                model.download()
                model.failure?.let { Log.w("KumapieColours", "Japanese dictionary: $it"); retryAt = System.currentTimeMillis() + RETRY_MS }
                if (model.isReady && !had) {
                    clear()
                    dictionaryReady.forEach { it() }
                }
            } finally { downloading.set(false) }
        }
    }

    /** kumapie's answer for one text: its rows (start, length, status) and the colours drawn from them. */
    private class Entry(val rows: List<Triple<Int, Int, String>>?, val colours: IntArray?, val time: Long)
    private val cache = LruCache<String, Entry>(500)
    private val asking = ConcurrentHashMap.newKeySet<String>()
    private val worker = Executors.newSingleThreadExecutor()

    /** The text's colours (0 = normal) when known, else null; asks kumapie in the background and calls [ready] once it answered. */
    fun of(context: Context, text: String, ready: () -> Unit): IntArray? {
        if (!enabled(context)) return null
        val hit = cache.get(text)
        if (hit == null || System.currentTimeMillis() - hit.time > MAX_AGE_MS) {
            if (asking.add(text)) {
                worker.execute {
                    val colours = ask(context.applicationContext, text)?.colours
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
     * The characters' names in [text] with a picture, as kumapie's subtitles show them: from kumahon's OWN character dictionary
     * ([CharacterDictionary]) once one is built, from kumapie's provider (`faces`) only while none is. Not gated by the colouring
     * switch, only by its own ("Show character faces"). Null until answered ([ready] is called then, when there are any).
     */
    fun faces(context: Context, text: String, ready: () -> Unit): List<Face>? {
        if (!CharacterDictionary.facesEnabled(context)) return null
        val work = work
        val stamp = CharacterDictionary.builtAt(context) // 0: none built, kumapie answers
        val key = "$stamp\n${work.orEmpty()}\n$text"
        faceCache.get(key)?.let { return it }
        if (System.currentTimeMillis() >= notReadyUntil && asking.add("faces:$key")) {
            val app = context.applicationContext // not the Activity: the queued task must not hold it
            worker.execute {
                val faces = runCatching { if (stamp > 0) ownFaces(app, text, work, stamp) else askFaces(app, text, work) }
                    .onFailure { Log.w("KumapieColours", "faces: $it") }.let { if (it.isFailure && faceFailures.merge(key, 1, Int::plus)!! >= MAX_FACE_FAILURES) emptyList() else it.getOrNull() }
                // null = not answered (model still downloading, dictionary importing) or an exception: not cached, asked again after 5 s of draws;
                // the model finishing its download calls clear(), which redraws every page. The 3rd exception for a text is an answer (none): cached.
                if (faces != null) faceCache.put(key, faces) else { notReadyUntil = System.currentTimeMillis() + NOT_READY_MS; android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ ready() }, NOT_READY_MS) } // one redraw when the window ends, so a static page gets its faces
                asking.remove("faces:$key")
                if (!faces.isNullOrEmpty()) ready()
            }
        }
        return null
    }

    /** The Japanese title of the manga being read: kumapie gives faces only to its characters (null: any of that name). */
    @Volatile private var work: String? = null
    private val works = ConcurrentHashMap<String, String>()
    private val faceFailures = ConcurrentHashMap<String, Int>() // exceptions per faces key; cleared with the caches
    private const val MAX_FACE_FAILURES = 3
    @Volatile private var readingTitle = ""
    private val aniListFailedAt = ConcurrentHashMap<String, Long>()
    private const val ANILIST_RETRY_MS = 5 * 60_000L
    private val JAPANESE = Regex("[\\u3040-\\u30ff\\u4e00-\\u9fff]")

    /**
     * The reader opened [title]. Its Japanese title: the description's first line when that is one (the Real-Debrid
     * import puts AniList's there), the title itself when Japanese, else AniList's best match, asked once per run.
     */
    fun reading(context: Context, title: String, description: String?, onNoted: () -> Unit = {}) {
        val first = description?.lineSequence()?.firstOrNull()?.trim()
        work = listOfNotNull(first, title).firstOrNull { it.length <= 60 && '。' !in it && JAPANESE.containsMatchIn(it) }
            ?: works[title]?.takeIf { it.isNotEmpty() } // "": AniList had no match
        readingTitle = title
        Log.i("KumapieColours", "reading $title: work $work")
        if (title.isBlank()) return
        // the character dictionary's set of works: noted once the Japanese title is known (the plain title first would add a second entry for the same manga)
        work?.let { CharacterDictionary.note(context.applicationContext, it); onNoted(); return }
        if (works[title] == "") return // AniList said no match: not asked again, not noted again
        if (System.currentTimeMillis() - (aniListFailedAt[title] ?: 0L) < ANILIST_RETRY_MS) return // failed a moment ago: not hammered
        val app = context.applicationContext // not the Activity: the queued task must not hold it
        worker.execute {
            val answer = runCatching { aniListNative(title) }.onFailure { Log.w("KumapieColours", "AniList: $it") }
            if (answer.isFailure) { aniListFailedAt[title] = System.currentTimeMillis(); return@execute } // transient: nothing noted, the next open asks again
            val native = answer.getOrNull()
            works[title] = native.orEmpty()
            native?.let { if (readingTitle == title) work = it; Log.i("KumapieColours", "reading $title: AniList says $it") }
            CharacterDictionary.note(app, native ?: title); onNoted() // AniList has no match: the title it is
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

    @Volatile private var notReadyUntil = 0L
    private const val NOT_READY_MS = 5_000L
    private val missLogged = ConcurrentHashMap.newKeySet<String>() // texts whose not-ready miss was logged (cleared with the caches)

    private fun missOnce(text: String): Boolean { if (missLogged.size > 500) missLogged.clear(); return missLogged.add(text) }

    private fun ownFaces(context: Context, text: String, work: String?, stamp: Long): List<Face>? {
        colourer(context) // creates [japanese]
        val model = japanese!!
        val words = model.words(text) ?: run { download(context); if (missOnce(text)) Log.d("KumapieColours", "ownFaces: Japanese model not ready"); return null }
        val hits = CharacterDictionary.faces(context, text, words, work, model::autoKnownName) ?: run { if (missOnce(text)) Log.d("KumapieColours", "ownFaces: character dictionary not ready"); return null }
        Log.d("KumapieColours", "ownFaces: ${text.length} chars, ${words.size} tokens, ${hits.size} hits, work $work")
        if (Log.isLoggable("KumapieColours", Log.VERBOSE)) Log.d("KumapieColours", "ownFaces: \"${text.take(40)}\" [${words.joinToString(" ") { "${it.surface}/${it.base}/${it.begin}" }}], hits ${hits.map { it.name + "@" + it.start }}")
        return hits.mapNotNull { hit ->
            val key = "$stamp\n${work.orEmpty()}\n${hit.name}"
            val picture = pictures[key]
                ?: android.graphics.BitmapFactory.decodeByteArray(hit.portrait, 0, hit.portrait.size)?.let(::circle)?.also { pictures[key] = it }
                ?: return@mapNotNull null
            Face(hit.start, picture)
        }
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

    /** Forgets every text's colours (and the colourer's view of the store): after a sync, a rating, Undo or a new card the next draw colours again. */
    fun clear() { cache.evictAll(); faceCache.evictAll(); faceFailures.clear(); missLogged.clear(); notReadyUntil = 0; colourer?.reload(); redraws.forEach { it() } }

    /**
     * kumapie's rating row (as Hoshi's popup): [bodyJson] `{action, word, reading, ease?, ms?, rated?, known?, text?}` goes to
     * its provider's `call` ("state", "mark", "rate", "undo", "reload"); answers
     * `{state?, marked?, rated?: {notes, cards, undo}, undone?}`,
     * or "null" when kumapie can't (not installed, no permission). Rating and Undo need kuma3's permission, which
     * chimahon holds for mining. Blocking: call it off the UI thread.
     */
    /** The sentence (OCR text block) of the popup's current lookup, set by the popup; sent along with "sense". */
    @Volatile var sentence: String = ""

    /** The manga title and chapter of the reader's open OCR popup (null: none is open, so a rating saves no context). Set by the popup. */
    @Volatile var place: Pair<String, String>? = null

    /** Where a rating happened, saved as a context of the card. */
    private class Where(val title: String, val volume: String?, val page: Int?, val sentence: String)

    /**
     * The context of a rating: the body's optional `sentence`, `title`, `volume`, `page` (kumapie's Bundle reads only the
     * named extras, so these never reach it), else the open popup's sentence and [place]. Null without a sentence or a title.
     */
    private fun whereOf(body: JSONObject): Where? {
        val said = body.optString("sentence").ifBlank { sentence }.trim()
        val title = body.optString("title").ifBlank { place?.first.orEmpty() }
        if (said.isEmpty() || title.isEmpty()) return null
        val volume = body.optString("volume").ifBlank { place?.second.orEmpty() }.ifEmpty { null }
        return Where(title, volume, body.optInt("page", -1).takeIf { it >= 0 }, said)
    }

    private val OWN_ACTIONS = setOf("state", "rate", "undo", "mark")

    /** kumapie's provider can be reached (its app is installed); false = kumahon's own store answers ratings. */
    fun present(context: Context): Boolean = authority(context) != null

    /** [call] without kumapie: the word's card in kumahon's own store answers "state", "rate" and "undo" in kumapie's JSON shape. */
    private fun own(context: Context, body: JSONObject): String {
        val action = body.getString("action")
        if (action != "state" && action != "rate" && action != "undo" && action != "mark") return "null" // reload, sense: kumapie only, as before
        val srs = Cards.store(context)
        if (action == "mark") {
            // No kumapie here: the mark lives in kumahon's store and goes to the hub in the next sync (progress.<device>.marked)
            val word = body.optString("word").takeIf { it.isNotBlank() } ?: return "null"
            val known = body.optBoolean("known")
            srs.mark(word, known)
            clear()
            Cards.later { Cards.sync(context) }
            return JSONObject().put("marked", known).toString()
        }
        val card = Cards.find(context, body.optString("word"), body.optString("reading"))
            // no card: nothing rated, as before; but a word on kumapie's known list (synced) answers "k"
            ?: return if (action == "state" && srs.isKnown(body.optString("word"), body.optString("reading"))) """{"state":"k"}""" else "null"
        val out = JSONObject()
        when (action) {
            "state" -> out.put("state", Srs.state(card.due))
            "rate" -> {
                srs.rate(card.id, body.optInt("ease"), answerMs = body.optLong("ms").takeIf { it > 0 })
                out.put("state", Srs.state(srs.card(card.id)?.due))
                out.put("rated", JSONObject().put("notes", JSONArray().put(card.id)).put("cards", 1).put("undo", card.id.toString()))
                whereOf(body)?.let { Cards.addContext(context, card.id, it.title, it.volume, it.page, it.sentence) }
            }
            "undo" -> {
                val id = body.optJSONObject("rated")?.optString("undo")?.toLongOrNull()
                if (id == card.id && card.lastReview != null) { srs.undo(card.id); out.put("undone", true) }
            }
        }
        if (action != "state") clear()
        return out.toString()
    }

    /**
     * kumahon is paired with a kumapie on ANOTHER device (hub not 127.0.0.1/localhost): word state (popup) comes from kumahon's own
     * cards and the known list that sync brought, even when a kumapie is installed here. The tablet (hub 127.0.0.1) keeps its provider.
     */
    fun remoteHub(context: Context): Boolean = runCatching {
        Cards.peer(context).let { it.paired() && !PeerSync.isLocalHub(it.hub()) }
    }.getOrDefault(false)

    fun call(context: Context, bodyJson: String): String = runCatching {
        val body = JSONObject(bodyJson)
        val action = body.getString("action")
        val authority = authority(context)?.takeIf { !(remoteHub(context) && action in OWN_ACTIONS) }
            ?: return@runCatching own(context, body)
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
        // kumapie wrote the mark; the own colours read kumahon's store: the same mark there at once (the next sync brings it anyway)
        if (action == "mark" && out.containsKey("marked") && body.optString("word").isNotBlank()) {
            Cards.store(context).setMarked(body.optString("word"), out.getBoolean("marked"))
            clear()
            Cards.later { Cards.sync(context) }
        }
        val answer = JSONObject().apply {
            out.getString("state")?.let { put("state", it) }
            if (out.containsKey("marked")) put("marked", out.getBoolean("marked"))
            out.getLongArray("notes")?.let { notes ->
                put("rated", JSONObject().put("notes", JSONArray(notes.toList())).put("cards", out.getInt("cards")).put("undo", out.getString("undo")))
            }
            if (out.containsKey("undone")) put("undone", out.getBoolean("undone"))
        }
        // kumapie wrote the rating (or took it back); kumahon writes no row for it: it makes sure it has its own card of the word
        // (no meaning at hand here: empty) with this context, then one sync brings kumapie's event onto that card.
        if (action == "rate" && (answer.optJSONObject("rated")?.optJSONArray("notes")?.length() ?: 0) > 0) {
            val word = body.optString("word")
            val reading = body.optString("reading")
            val where = whereOf(body)
            Cards.later {
                if (word.isNotBlank()) Cards.addMined(context, word, reading, where?.sentence.orEmpty(), "", where?.title, where?.volume, where?.page)
                Cards.sync(context)
            }
        } else if (action == "undo" && answer.optBoolean("undone")) {
            Cards.later { Cards.sync(context) }
        }
        answer.toString()
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
     * [text]'s words as colours rows (start, length, status u/d/b/k), for the reader's page-words list; null when colouring is off or
     * the dictionary isn't downloaded yet (its download starts). The same answer (and cache) the page draws with [of], so the list
     * never calls a word new that the page shows known. Blocking: call it off the UI thread.
     */
    fun words(context: Context, text: String): List<Triple<Int, Int, String>>? {
        if (!enabled(context)) return null
        cache.get(text)?.takeIf { System.currentTimeMillis() - it.time <= MAX_AGE_MS && it.rows != null }?.let { return it.rows }
        return ask(context.applicationContext, text)?.rows
    }

    /**
     * Colours [text] with [WordColourer] and caches the answer. Until the dictionary is here (its download starts) kumapie's own
     * answer when its provider is present ([askKumapie]), else null. Null (nothing cached) when colouring is off. Blocking.
     */
    private fun ask(context: Context, text: String): Entry? {
        if (!enabled(context)) return null
        val colouring = colourer(context)
        val model = japanese ?: return null
        if (!model.isReady) { download(context); return askKumapie(context, text) }
        if (model.splitterWanted()) download(context) // Jiten's files still to come (or a day since the release was read): fetched meanwhile
        val rows = runCatching { colouring.rows(text).map { Triple(it.start, it.length, it.state) } }
            .onFailure { Log.w("KumapieColours", "own colours failed: $it") }.getOrNull() ?: return null
        return entry(text, rows)
    }

    private fun entry(text: String, rows: List<Triple<Int, Int, String>>?): Entry {
        val colours = rows?.let {
            IntArray(text.length).also { out ->
                for ((start, length, status) in it) {
                    val colour = COLOURS[status] ?: continue
                    for (i in maxOf(start, 0) until minOf(start + length, text.length)) out[i] = colour
                }
            }
        }
        return Entry(rows, colours, System.currentTimeMillis()).also { cache.put(text, it) }
    }

    /** kumapie's provider colours for [text] (rows start, length, status), while the own dictionary is missing; null when it can't answer. Blocking. */
    private fun askKumapie(context: Context, text: String): Entry? {
        val rows = runCatching<List<Triple<Int, Int, String>>?> {
            val authority = authority(context) ?: return@runCatching null
            buildList<Triple<Int, Int, String>> {
                context.contentResolver.query(Uri.parse("content://$authority/colours"), null, null, arrayOf(text), null)?.use { c ->
                    while (c.moveToNext()) add(Triple(c.getInt(0), c.getInt(1), c.getString(2)))
                }
            }
        }.onFailure { Log.w("KumapieColours", "kumapie didn't answer: $it") }.getOrNull() ?: return null
        return entry(text, rows)
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
