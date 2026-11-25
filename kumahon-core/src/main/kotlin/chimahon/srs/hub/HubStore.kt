package chimahon.srs.hub

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.TreeMap

/**
 * The merged state of a kumapie sync hub, kept in one JSON file (kumapie's `DeviceFile.plan` rules, spec C:\dojo\work\kumahon\hub-spec.md):
 * events = union by id; progress = a part per device, the strictly newer one wins and is kept verbatim; known = the strictly newer time
 * per language; carded = the newer `made` per device; personal = the larger `time` per (lang, text); alone_* streams as kumapie.
 * Paired peers (device -> secret) live in the same file. The hub keeps no cards, media or backups (the spec allows it).
 * [hubName] is the answer's `device`: stable, and never the name of a kumapie device.
 */
class HubStore(private val file: File, val hubName: String, private val log: (String) -> Unit = {}) {
    companion object {
        const val FORMAT = "kumapie-device-1"
        private const val LAST = "alone_last_"
        private const val SAVED = "alone_saved"
        private const val SEEN = "alone_seen"
    }

    private val peers = java.util.concurrent.ConcurrentHashMap<String, String>() // read without the merge lock (the UI asks)
    private val events = TreeMap<Long, JSONObject>()
    private var progress = JSONObject()
    private var known = JSONObject()
    private var carded = JSONObject()
    private var streams = JSONObject()
    private val personal = LinkedHashMap<Pair<String, String>, JSONObject>()

    init {
        if (file.isFile) try {
            val o = JSONObject(file.readText(Charsets.UTF_8))
            o.optJSONObject("peers")?.let { p -> p.keys().forEach { peers[it] = p.getString(it) } }
            o.optJSONArray("events")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { events[it.getLong("id")] = it } }
            o.optJSONObject("progress")?.let { progress = it }
            o.optJSONObject("known")?.let { known = it }
            o.optJSONObject("carded")?.let { carded = it }
            o.optJSONObject("streams")?.let { streams = it }
            o.optJSONArray("personal")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { personal[it.getString("lang") to it.getString("text")] = it } }
        } catch (e: Exception) {
            // never overwrite what we could not read: set it aside, start empty
            peers.clear(); events.clear(); personal.clear(); progress = JSONObject(); known = JSONObject(); carded = JSONObject(); streams = JSONObject()
            val bad = File(file.absoluteFile.parentFile, file.name + ".bad-" + System.currentTimeMillis())
            val moved = runCatching { file.renameTo(bad) }.getOrDefault(false)
            log("hub: ${file.name} unreadable ($e); ${if (moved) "kept as ${bad.name}" else "COULD NOT be moved aside"}, starting empty")
        }
    }

    fun secretOf(device: String?): String? = device?.let { peers[it] }?.ifEmpty { null }

    @Synchronized fun addPeer(device: String, secret: String) { peers[device] = secret; save() }

    @Synchronized fun eventCount(): Int = events.size

    /**
     * Merges one `/sync` request and answers with the whole merged state (the full event union). Throws on a bad file (the server
     * answers 422 with the message); nothing changes then, because every part is merged into copies and swapped in at the end.
     */
    @Synchronized fun merge(req: JSONObject): JSONObject {
        require(req.optString("format") == FORMAT) { "Not a kumapie export file" }
        req.getString("device")
        require(req.optString("person", "me") == "me") { "This file is ${req.optString("person")}'s and this hub is me's: not merged." }

        val newProgress = JSONObject(progress.toString())
        req.optJSONObject("progress")?.let { t ->
            for (k in t.keys()) { val p = t.getJSONObject(k); if (newProgress.optJSONObject(k)?.let { newer(p, it) } != false) newProgress.put(k, p) }
        }
        val newKnown = JSONObject(known.toString())
        req.optJSONObject("known")?.let { t ->
            for (l in t.keys()) {
                val k = t.getJSONObject(l)
                if (k.getLong("time") > (newKnown.optJSONObject(l)?.optLong("time") ?: 0L)) {
                    newKnown.put(l, JSONObject().put("time", k.getLong("time")).put("words", k.optJSONArray("words") ?: JSONArray()))
                }
            }
        }
        val newCarded = JSONObject(carded.toString())
        req.optJSONObject("carded")?.let { t ->
            for (d in t.keys()) { val c = t.getJSONObject(d); if (c.getLong("made") > (newCarded.optJSONObject(d)?.optLong("made", -1) ?: -1L)) newCarded.put(d, c) }
        }
        val newPersonal = LinkedHashMap(personal)
        req.optJSONArray("personal")?.let { a ->
            for (i in 0 until a.length()) {
                val e = a.getJSONObject(i)
                val key = e.getString("lang") to e.getString("text")
                if (e.optLong("time") >= (newPersonal[key]?.optLong("time") ?: Long.MIN_VALUE)) newPersonal[key] = e
            }
        }
        val newStreams = streams(streams, req.optJSONObject("streams") ?: JSONObject())
        val newEvents = TreeMap(events)
        req.optJSONArray("events")?.let { a ->
            for (i in 0 until a.length()) {
                val e = a.getJSONObject(i)
                // the four fields kumapie reads must exist: a half event is a 422, never stored
                newEvents[e.getLong("id")] = JSONObject().put("id", e.getLong("id")).put("key", e.getString("key")).put("time", e.getLong("time"))
                    .put("ease", e.getInt("ease")).put("device", e.optString("device"))
            }
        }
        val added = newEvents.size - events.size

        progress = newProgress; known = newKnown; carded = newCarded; streams = newStreams
        personal.clear(); personal.putAll(newPersonal)
        events.clear(); events.putAll(newEvents)
        save()
        return answer(added)
    }

    private fun answer(added: Int) = JSONObject().put("format", FORMAT).put("device", hubName).put("person", "me").put("made", System.currentTimeMillis())
        .put("progress", progress).put("known", known).put("personal", JSONArray(personal.values.toList())).put("streams", streams)
        .put("events", JSONArray(events.values.toList())).put("carded", carded)
        .put("moved", JSONArray().put(JSONObject().put("what", "Review events").put("add", added).put("change", 0).put("keep", events.size - added)))

    private fun save() {
        val o = JSONObject().put("peers", JSONObject(peers as Map<*, *>)).put("progress", progress).put("known", known).put("carded", carded).put("streams", streams)
            .put("personal", JSONArray(personal.values.toList())).put("events", JSONArray(events.values.toList()))
        file.absoluteFile.parentFile?.mkdirs()
        val tmp = File(file.absoluteFile.parentFile, file.name + ".tmp")
        tmp.writeText(o.toString(), Charsets.UTF_8)
        Files.move(tmp.toPath(), file.absoluteFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    // ---- kumapie's rules, ported (DeviceFile.kt streams / newer / age) ----

    private fun streams(mine: JSONObject, theirs: JSONObject): JSONObject {
        val out = JSONObject(mine.toString())
        fun at(v: Any?) = (v as? String)?.let { runCatching { JSONObject(it).optLong("at") }.getOrNull() } ?: -1L
        val newer = theirs.keys().asSequence().filter { it.startsWith(LAST) && at(theirs.get(it)) > at(mine.opt(it)) }.toList()
        newer.forEach { out.put(it, theirs.getString(it)) }
        val titles = newer.map { it.removePrefix(LAST) }.toSet()
        for (k in theirs.keys()) {
            val v = theirs.get(k)
            when {
                k.startsWith(LAST) -> Unit
                k == SAVED -> {
                    val had = objects(JSONArray((mine.opt(k) as? String) ?: "[]"))
                    val ids = had.map { it.optString("id") }.toSet()
                    val add = objects(JSONArray(v.toString())).filter { it.optString("id") !in ids }
                    if (add.isNotEmpty()) out.put(k, JSONArray(had + add).toString())
                }
                k == SEEN -> {
                    val had = (mine.opt(k) as? JSONArray)?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty().toSet()
                    val all = had + ((v as? JSONArray)?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty())
                    if (all != had) out.put(k, JSONArray(all.toList()))
                }
                else -> {
                    val place = k.startsWith("alone_pos_") || k.startsWith("alone_len_")
                    if (!mine.has(k) || (place && k.drop(10) in titles && mine.get(k).toString() != v.toString())) out.put(k, v)
                }
            }
        }
        return out
    }

    private fun objects(a: JSONArray) = List(a.length()) { a.getJSONObject(it) }

    private fun newer(a: JSONObject, b: JSONObject) = age(a).let { x -> age(b).let { y -> x.first > y.first || (x.first == y.first && x.second > y.second) } }

    private fun age(p: JSONObject): Pair<Long, Double> {
        var t = 0L
        fun each(name: String, time: (Any) -> Long) { p.optJSONObject(name)?.let { o -> for (k in o.keys()) t = maxOf(t, runCatching { time(o.get(k)) }.getOrDefault(0)) } }
        each("pos") { (it as JSONArray).optLong(1) }
        each("marked") { (it as JSONArray).optLong(1) }
        each("ranges") { (it as JSONArray).optLong(0) }
        each("done") { (it as Number).toLong() }
        each("unwatched") { (it as Number).toLong() }
        val watch = p.optJSONObject("watch")?.let { o -> o.keys().asSequence().sumOf { o.optDouble(it, 0.0) } } ?: 0.0
        return t to watch
    }
}
