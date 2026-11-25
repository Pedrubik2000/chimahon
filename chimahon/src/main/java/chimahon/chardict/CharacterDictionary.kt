package chimahon.chardict


import android.content.Context
import android.text.format.DateUtils
import chimahon.HoshiDicts
import chimahon.lang.JapaneseModel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * kumahon's own character dictionary (no kumapie): what the reader opened, what the last build had, the status line, the
 * "faces" switch, and the faces themselves, looked up in a HoshiDicts session over that one dictionary. The build and the import
 * are `CharacterDictionaryJob` in :app.
 */
object CharacterDictionary {
    const val TITLE = YomitanCharacterZip.DEFAULT_TITLE

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("kumahon_chardict", Context.MODE_PRIVATE)
    private fun list(context: Context, key: String): List<String> =
        runCatching { JSONArray(prefs(context).getString(key, "[]")).let { a -> (0 until a.length()).map(a::getString) } }.getOrDefault(emptyList())

    fun facesEnabled(context: Context) = prefs(context).getBoolean("faces", true)
    fun setFacesEnabled(context: Context, on: Boolean) { prefs(context).edit().putBoolean("faces", on).apply() }

    /** Where the imported dictionary is (the folder `importDictionaryFromStream` writes). */
    fun dir(context: Context) = File(File(File(context.getExternalFilesDir(null), "dictionaries"), "term"), TITLE)

    /** The works the reader opened, in order (a Japanese title when known, else the title). */
    fun works(context: Context) = list(context, "works")
    fun builtWorks(context: Context) = list(context, "builtWorks")

    /** Time of the last build (ms), 0 = none built. Kept in memory (the draw path asks): loaded once, updated by [markBuilt]/[verify]. */
    @Volatile private var builtAtCache = -1L
    fun builtAt(context: Context): Long {
        var v = builtAtCache
        if (v < 0) { v = prefs(context).getLong("builtAt", 0); builtAtCache = v }
        return v
    }
    fun present(context: Context) = builtAt(context) > 0

    /** Disk check, off the main thread: forgets a build whose folder is gone. */
    fun verify(context: Context) {
        if (builtAt(context) > 0 && !dir(context).isDirectory) {
            prefs(context).edit().putLong("builtAt", 0).apply()
            builtAtCache = 0
        }
    }

    /** True from just before the job replaces the dictionary's files until it has marked the build: [faces] answers nothing. */
    @Volatile var importing = false

    /** The reader opened [title]: added to the set (the job comes later). True when the set changed. */
    @Synchronized
    fun note(context: Context, title: String): Boolean {
        val before = works(context)
        val after = ReadWorks.add(before, title.trim())
        if (after == before) return false
        prefs(context).edit().putString("works", JSONArray(after).toString()).apply()
        return true
    }

    /** Works AniList had no media for, workKey to when it was tried: left out of [needsRebuild] for a week. */
    private fun notFoundAt(context: Context): Map<String, Long> =
        runCatching { JSONObject(prefs(context).getString("notFound", "{}")).let { o -> o.keys().asSequence().associateWith { o.getLong(it) } } }.getOrDefault(emptyMap())

    /** [notFound] are tried now; the [resolved] ones are not "not found" any more. */
    fun recordNotFound(context: Context, notFound: List<String>, resolved: List<String> = emptyList()) {
        val map = notFoundAt(context).toMutableMap()
        resolved.forEach { map.remove(CharacterEntry.workKey(it)) }
        notFound.forEach { map[CharacterEntry.workKey(it)] = System.currentTimeMillis() }
        prefs(context).edit().putString("notFound", JSONObject(map as Map<*, *>).toString()).apply()
    }

    fun needsRebuild(context: Context): Boolean {
        val read = works(context)
        if (read.isEmpty()) return false
        val nf = notFoundAt(context)
        val now = System.currentTimeMillis()
        return ReadWorks.needsRebuild(read, builtWorks(context), nf, now) || (!present(context) && ReadWorks.pending(read, nf, now).isNotEmpty())
    }

    /** The folder as it is on disk (files, total size, newest mtime; "none" when absent): changes when an import touched it. */
    fun fingerprint(context: Context): String {
        val files = dir(context).takeIf { it.isDirectory }?.walkTopDown()?.filter { it.isFile }?.toList() ?: return "none"
        return "${files.size}/${files.sumOf { it.length() }}/${files.maxOfOrNull { it.lastModified() } ?: 0}"
    }

    /** A failed import may have left the folder half written: forget the build and the folder so nothing opens it. */
    fun reset(context: Context) {
        prefs(context).edit().putLong("builtAt", 0).apply()
        builtAtCache = 0
        dir(context).deleteRecursively()
    }

    /** The job's progress line ("" = not running) and the last failure. */
    fun setBuilding(context: Context, progress: String) { prefs(context).edit().putString("building", progress).putLong("buildingAt", System.currentTimeMillis()).apply() }
    fun building(context: Context): String = prefs(context).getString("building", "").orEmpty()
    fun setError(context: Context, message: String) { prefs(context).edit().putString("error", message).apply() }

    fun markBuilt(context: Context, works: List<String>, characters: Int, notFound: List<String> = emptyList()) {
        recordNotFound(context, notFound, works)
        val now = System.currentTimeMillis()
        prefs(context).edit().putString("builtWorks", JSONArray(works).toString()).putInt("characters", characters)
            .putLong("builtAt", now).putString("building", "").putString("error", "").apply()
        builtAtCache = now
        File(context.cacheDir, "faces").deleteRecursively() // the pictures of the old build
    }

    fun status(context: Context): String {
        val p = prefs(context)
        // a build that died with the process leaves its line behind: stale after half an hour
        p.getString("building", "").orEmpty().takeIf { it.isNotEmpty() && System.currentTimeMillis() - p.getLong("buildingAt", 0) < 30 * 60_000 }?.let { return "Building... $it" }
        val error = p.getString("error", "").orEmpty().takeIf { it.isNotEmpty() }?.let { " (last try failed: $it)" }.orEmpty()
        if (!present(context)) return (if (works(context).isEmpty()) "None: it is built when you read a manga" else "None yet, ${works(context).size} works waiting") + error
        val at = DateUtils.getRelativeTimeSpanString(builtAt(context), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        return "${p.getInt("characters", 0)} characters, built $at, from ${builtWorks(context).size} works$error"
    }

    // --- faces ---

    private var session = 0L
    private var sessionStamp = 0L
    private val missing = HashSet<String>()

    /** Closes the lookup session: call before the dictionary's files are replaced. */
    @Synchronized
    fun release() {
        if (session != 0L) HoshiDicts.destroyLookupObject(session)
        session = 0
    }

    private fun session(context: Context): Long {
        val stamp = builtAt(context)
        if (session != 0L && sessionStamp == stamp) return session
        release()
        missing.clear()
        session = HoshiDicts.createLookupObject().also {
            HoshiDicts.rebuildQuery(it, arrayOf(dir(context).absolutePath), emptyArray(), emptyArray(), emptyArray())
        }
        sessionStamp = stamp
        return session
    }

    /**
     * The characters named in [text] ([words]: its split) with their portraits' JPEG bytes. [work] is the Japanese title of the manga
     * being read (null: any work's character). Pictures are kept as files in cacheDir/faces/<hash>.jpg, as kumapie does.
     */
    @Synchronized
    fun faces(context: Context, text: String, words: List<JapaneseModel.Token>, work: String?, autoKnown: (String) -> Boolean): List<FaceMatcher.Hit> {
        if (importing || !present(context)) return emptyList()
        verify(context) // worker thread
        if (!present(context)) return emptyList()
        val s = session(context)
        val dict = object : CharacterDict {
            override fun glossaries(word: String) = HoshiDicts.query(s, word).flatMap { t -> t.glossaries.map { Glossary(it.dictName, it.glossary) } }
            override fun media(dict: String, path: String) = HoshiDicts.getMediaFile(s, dict, path)
        }
        val dir = File(context.cacheDir, "faces").apply { mkdirs() }
        val cache = { key: String, compute: () -> ByteArray? ->
            val full = "$sessionStamp\n$key"
            val name = java.security.MessageDigest.getInstance("SHA-1").digest(full.toByteArray()).joinToString("") { "%02x".format(it) }
            val file = File(dir, "$name.jpg")
            if (file.exists()) {
                file.readBytes()
            } else if (full in missing) {
                null
            } else {
                val bytes = compute()
                if (bytes != null) file.writeBytes(bytes) else missing += full
                bytes
            }
        }
        return FaceMatcher(dict, autoKnown, cache).match(text, words, work)
    }
}
