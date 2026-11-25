package chimahon.releases

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate

enum class WatchStatus { READING, PLAN }

/** Declared in the order a volume moves through: the engine never moves a row back. */
enum class VolState { ANNOUNCED, RELEASED, ON_SOURCES, ON_TABLET, READ }

/** [jpTitle] is AniList's native title, editable per series; [autoFetch] null = the default (on for titles on the latest volume). */
data class WatchedSeries(
    val anilistId: Long,
    val jpTitle: String,
    val publisher: String? = null,
    val lastKnownVol: Int = 0,
    val status: WatchStatus = WatchStatus.READING,
    val autoFetch: Boolean? = null,
    /** null = true; false = the jobs skip the series (it stays in the list, hidden on the screen). */
    val watching: Boolean? = null,
)

/**
 * One auto-fetch of (series, vol): [tried] = ids of the sources already used (the next day goes to another), [fails] counts failed
 * imports, [lastDay] = the JST day it was queued, [pending] = queued and not ended yet. 3 fails = given up (Needs attention).
 */
data class FetchAttempt(
    val anilistId: Long,
    val vol: Int,
    val tried: List<Long> = emptyList(),
    val fails: Int = 0,
    val lastDay: LocalDate,
    val pending: Boolean = false,
    /** Delivered or cancelled: no more tries. Kept until the row shows ON_TABLET or later ([AutoFetchPlan.prune]), so a delivered volume is not fetched again before the engine sees it. */
    val finished: Boolean = false,
)

/** [source] = which release source gave the date; [previousDate] = the date before the last change. */
data class VolumeRow(
    val anilistId: Long,
    val vol: Int,
    val date: LocalDate?,
    val state: VolState,
    val source: String?,
    val firstSeen: LocalDate,
    val lastChecked: LocalDate,
    val previousDate: LocalDate? = null,
    val dates: Map<String, LocalDate> = emptyMap(), // latest known date per source id; empty in old files (the engine falls back to date/source)
    /** The coverage (download) source that has the volume while it is ON_SOURCES (X3DL, Excnn...); [source] is only where the date came from. */
    val fetchSource: String? = null,
)

/** [notified] = keys of the events already emitted (`id:vol:KIND`), so none repeats. */
data class ReleaseState(
    val series: List<WatchedSeries> = emptyList(),
    val rows: List<VolumeRow> = emptyList(),
    val notified: Set<String> = emptySet(),
    /** Events emitted and not yet shown (phase 4 notifies, then clears them). */
    val pendingEvents: List<ReleaseEvent> = emptyList(),
    /** The last job run (epoch ms; "weekly" / "daily" / "now") and its error message (null = it went through); the Releases screen shows them. */
    val lastRunAt: Long? = null,
    val lastRunKind: String? = null,
    val lastError: String? = null,
    /** Auto-fetch bookkeeping: attempts, and how many volumes were queued on [fetchDay] (JST; the cap is per day). */
    val attempts: List<FetchAttempt> = emptyList(),
    val fetchDay: LocalDate? = null,
    val fetchCount: Int = 0,
)

/** The state in one JSON file (written to a temp file, then moved over, as HubStore). An unreadable file is set aside, never overwritten. */
class ReleaseStore(private val file: File, private val log: (String) -> Unit = {}) {
    companion object {
        const val FORMAT = "kumahon-releases-1"
        private val LOCK = Any()

        fun toJson(s: ReleaseState): JSONObject = JSONObject()
            .put("format", FORMAT)
            .put("series", JSONArray(s.series.map {
                JSONObject().put("anilistId", it.anilistId).put("jpTitle", it.jpTitle).put("publisher", it.publisher ?: JSONObject.NULL)
                    .put("lastKnownVol", it.lastKnownVol).put("status", it.status.name).put("autoFetch", it.autoFetch ?: JSONObject.NULL)
                    .put("watching", it.watching ?: JSONObject.NULL)
            }))
            .put("rows", JSONArray(s.rows.map {
                JSONObject().put("anilistId", it.anilistId).put("vol", it.vol).put("date", it.date?.toString() ?: JSONObject.NULL)
                    .put("state", it.state.name).put("source", it.source ?: JSONObject.NULL).put("firstSeen", it.firstSeen.toString())
                    .put("lastChecked", it.lastChecked.toString()).put("previousDate", it.previousDate?.toString() ?: JSONObject.NULL)
                    .put("dates", JSONObject().also { o -> it.dates.forEach { (k, v) -> o.put(k, v.toString()) } })
                    .put("fetchSource", it.fetchSource ?: JSONObject.NULL)
            }))
            .put("notified", JSONArray(s.notified.sorted()))
            .put("pendingEvents", JSONArray(s.pendingEvents.map {
                JSONObject().put("kind", it.kind.name).put("anilistId", it.anilistId).put("vol", it.vol).put("source", it.source ?: JSONObject.NULL)
                    .put("oldDate", it.oldDate?.toString() ?: JSONObject.NULL).put("newDate", it.newDate?.toString() ?: JSONObject.NULL)
            }))
            .put("lastRunAt", s.lastRunAt ?: JSONObject.NULL).put("lastRunKind", s.lastRunKind ?: JSONObject.NULL).put("lastError", s.lastError ?: JSONObject.NULL)
            .put("attempts", JSONArray(s.attempts.map {
                JSONObject().put("anilistId", it.anilistId).put("vol", it.vol).put("tried", JSONArray(it.tried))
                    .put("fails", it.fails).put("lastDay", it.lastDay.toString()).put("pending", it.pending).put("finished", it.finished)
            }))
            .put("fetchDay", s.fetchDay?.toString() ?: JSONObject.NULL).put("fetchCount", s.fetchCount)

        /** Throws on anything not of this format. */
        fun fromJson(o: JSONObject): ReleaseState {
            require(o.optString("format") == FORMAT) { "Not a kumahon releases file" }
            fun str(j: JSONObject, k: String) = if (j.isNull(k)) null else j.getString(k)
            val series = o.getJSONArray("series").let { a ->
                List(a.length()) {
                    val j = a.getJSONObject(it)
                    WatchedSeries(
                        j.getLong("anilistId"), j.getString("jpTitle"), str(j, "publisher"), j.getInt("lastKnownVol"),
                        WatchStatus.valueOf(j.getString("status")), if (j.isNull("autoFetch")) null else j.getBoolean("autoFetch"),
                        if (j.isNull("watching")) null else j.getBoolean("watching"), // absent (files before phase 5) = null
                    )
                }
            }
            val rows = o.getJSONArray("rows").let { a ->
                List(a.length()) {
                    val j = a.getJSONObject(it)
                    VolumeRow(
                        j.getLong("anilistId"), j.getInt("vol"), str(j, "date")?.let(LocalDate::parse), VolState.valueOf(j.getString("state")),
                        str(j, "source"), LocalDate.parse(j.getString("firstSeen")), LocalDate.parse(j.getString("lastChecked")),
                        str(j, "previousDate")?.let(LocalDate::parse),
                        j.optJSONObject("dates")?.let { o -> o.keys().asSequence().associateWith { k -> LocalDate.parse(o.getString(k)) } }.orEmpty(),
                        str(j, "fetchSource"), // files before this have none
                    )
                }
            }
            val notified = o.getJSONArray("notified").let { a -> List(a.length()) { a.getString(it) }.toSet() }
            val pending = o.optJSONArray("pendingEvents")?.let { a ->
                List(a.length()) {
                    val j = a.getJSONObject(it)
                    ReleaseEvent(
                        EventKind.valueOf(j.getString("kind")), j.getLong("anilistId"), j.getInt("vol"), str(j, "source"),
                        str(j, "oldDate")?.let(LocalDate::parse), str(j, "newDate")?.let(LocalDate::parse),
                    )
                }
            }.orEmpty() // files before phase 2 have none
            val lastRunAt = if (o.isNull("lastRunAt")) null else o.getLong("lastRunAt") // files before the run log: absent = null
            val attempts = o.optJSONArray("attempts")?.let { a ->
                List(a.length()) {
                    val j = a.getJSONObject(it)
                    FetchAttempt(
                        j.getLong("anilistId"), j.getInt("vol"), j.getJSONArray("tried").let { t -> List(t.length()) { i -> t.getLong(i) } },
                        j.getInt("fails"), LocalDate.parse(j.getString("lastDay")), j.getBoolean("pending"), j.optBoolean("finished", false),
                    )
                }
            }.orEmpty() // files before phase 5 have none
            return ReleaseState(
                series, rows, notified, pending, lastRunAt, str(o, "lastRunKind"), str(o, "lastError"),
                attempts, str(o, "fetchDay")?.let(LocalDate::parse), o.optInt("fetchCount", 0),
            )
        }
    }

    private var state = load()

    /** The file's state, or empty (a file that does not parse is moved aside). */
    private fun load(): ReleaseState {
        if (!file.isFile) return ReleaseState()
        try {
            return fromJson(JSONObject(file.readText(Charsets.UTF_8)))
        } catch (e: Exception) {
            val bad = File(file.absoluteFile.parentFile, file.name + ".bad-" + System.currentTimeMillis())
            val moved = runCatching { file.renameTo(bad) }.getOrDefault(false)
            log("releases: ${file.name} unreadable ($e); ${if (moved) "kept as ${bad.name}" else "COULD NOT be moved aside"}, starting empty")
            return ReleaseState()
        }
    }

    @Synchronized fun get(): ReleaseState = state

    /**
     * Replaces the state with [change] of it and saves; the cached state changes only after the file is in place.
     * ponytail: one process only. Two instances on one file are serialized by the process-wide LOCK and each update starts from the
     * file, so none loses the other's write; a second process would need a file lock. get() of an older instance can be stale.
     */
    fun update(change: (ReleaseState) -> ReleaseState): ReleaseState = synchronized(LOCK) {
        val next = change(load())
        file.absoluteFile.parentFile?.mkdirs()
        val tmp = File(file.absoluteFile.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { it.write(toJson(next).toString().toByteArray(Charsets.UTF_8)); it.fd.sync() }
        Files.move(tmp.toPath(), file.absoluteFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        synchronized(this) { state = next }
        next
    }
}
