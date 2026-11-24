package chimahon.srs

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.random.Random

/**
 * kumahon's own cards and reviews: a port of kumapie's Srs (same FSRS-6, same tables and sync JSON, so both apps
 * compute the same schedule from the same revlog), Japanese only: one `srs/ja.db`.
 *
 * `revlog` and `exposures` are append-only (triggers refuse UPDATE and DELETE): a card's FSRS state on its row is a
 * cache of [replay]ing its revlog in time order with [Fsrs6], so phone, tablet and TV sync by appending only.
 * Undo appends a rating-0 row that cancels the card's last standing rating.
 */
class Srs(val db: SqlDb, private val device: String) {
    init {
        if (db.query("PRAGMA user_version") { it.int(0) }.first() < 1) db.tx { SCHEMA.forEach { db.exec(it) }; db.exec("PRAGMA user_version = 1") }
        LOCAL.forEach { db.exec(it) }
    }

    data class Card(
        val id: Long, val note: Long, val ord: Int, val word: String, val sentence: String, val meaning: String,
        val fields: String, val tags: String, val show: String?, val episode: String?, val scene: Int?,
        val created: Long, val modified: Long, val stability: Double?, val difficulty: Double?, val lastReview: Long?, val due: Long?,
    )

    /** Where a card was met (kumahon only, never synced): page and sentence of a manga volume; [contexts] lists them. */
    data class CardContext(val id: Long, val card: Long, val time: Long, val title: String?, val volume: String?,
                           val page: Int?, val sentence: String?, val image: String?)

    /**
     * Where a rating happened: subs = shown|blurred|hidden, heard = times heard since the card's last rating.
     * [scene] (the `scene` column of cards, revlog and exposures): where the line starts in the episode, in ms, so a
     * subtitle fix can't lose it. Rows from before 2026-11-17 hold a scene number there (they are never rewritten);
     * nothing reads the column back.
     */
    data class Watch(val episode: String? = null, val scene: Int? = null, val subs: String? = null,
                     val lookedUp: Boolean? = null, val speed: Double? = null, val heard: Int? = null)

    /** FSRS-6 with the parameters and this language's retention fitted on the PC (`meta`), else the defaults. */
    val fsrs: Fsrs6 get() = scheduler ?: run {
        val w = meta("params")?.let { JSONArray(it) }?.let { a -> DoubleArray(a.length()) { a.getDouble(it) } } ?: Fsrs6.DEFAULT
        val r = meta("retention")?.let { JSONObject(it).optDouble(LANG, 0.9) } ?: 0.9
        Fsrs6(w, r).also { scheduler = it }
    }
    @Volatile private var scheduler: Fsrs6? = null

    fun meta(key: String): String? = db.query("SELECT value FROM meta WHERE key = ?", arrayOf(key)) { it.string(0) }.firstOrNull()

    /** Every card but the deleted ones (tag `kumapie::deleted`: the sync has no deletes, a newer row carries the tag; duplicates, user 2026-11-17). */
    fun cards(): List<Card> = db.query("SELECT $CARD_COLS FROM cards WHERE (' ' || tags || ' ') NOT LIKE '% $DELETED %'") { card(it) }

    /** Cards due by [now] (never-rated ones too): rated ones first, oldest due first, then the never-rated, oldest created first (kumapie's ownReview order, without its audio and picture filter). */
    fun due(now: Long): List<Card> = cards().filter { (it.due ?: 0L) <= now }.sortedWith(compareBy({ it.due == null }, { it.due ?: it.created }))

    fun card(id: Long): Card? = db.query("SELECT $CARD_COLS FROM cards WHERE id = ?", arrayOf("$id")) { card(it) }.firstOrNull()

    /** A word just mined: a new card (blue until its first rating). */
    fun addCard(word: String, sentence: String, meaning: String, show: String?, episode: String?, scene: Int?,
                fields: String = "{}", tags: String = "", now: Long = System.currentTimeMillis()): Card {
        var id = now
        while (true) {
            val v = mapOf(
                "id" to id, "note" to id, "ord" to 0, "lang" to LANG, "word" to word, "sentence" to sentence,
                "meaning" to meaning, "fields" to fields, "tags" to tags, "show" to show, "episode" to episode,
                "scene" to scene, "created" to now, "modified" to now,
            )
            try { append("cards", v); return card(id)!! } catch (_: SqlConstraintException) { id++ }
        }
    }

    /** One rating (1-4) from the player: appended to the revlog with its context; answers the card's new state. */
    fun rate(cardId: Long, rating: Int, watch: Watch = Watch(), answerMs: Long? = null, now: Long = System.currentTimeMillis()): Fsrs6.State {
        require(rating in 1..4)
        val c = requireNotNull(card(cardId)) { "no card $cardId" }
        val before = c.stability?.let { Fsrs6.State(it, c.difficulty!!) }
        val days = c.lastReview?.let { elapsedDays(it, now) } ?: 0
        val r = before?.let { fsrs.retrievability(days.toDouble(), it.stability) }
        val after = fsrs.next(before, days, rating)
        // Scenes it was heard in (played to their end, on any device) since its last rating, or since it was mined.
        val heard = watch.heard ?: db.query("SELECT COUNT(*) FROM exposures WHERE card = ? AND time > ?",
            arrayOf("$cardId", "${c.lastReview ?: c.created}")) { it.int(0) }.firstOrNull() ?: 0
        db.tx {
            append("revlog", mapOf(
                "id" to newId(now), "card" to cardId, "time" to now, "rating" to rating, "answer_ms" to answerMs,
                "device" to device, "s_before" to before?.stability, "d_before" to before?.difficulty,
                "s_after" to after.stability, "d_after" to after.difficulty, "r" to r,
                "episode" to watch.episode, "scene" to watch.scene, "subs" to watch.subs,
                "looked_up" to watch.lookedUp?.let { if (it) 1 else 0 }, "speed" to watch.speed, "heard" to heard,
            ))
            cache(cardId, after, now)
        }
        return after
    }

    /** Cancels the card's last standing rating (a rating-0 row); answers its state now (null = new again). */
    fun undo(cardId: Long, now: Long = System.currentTimeMillis()): Fsrs6.State? {
        val c = requireNotNull(card(cardId)) { "no card $cardId" }
        val rows = history(cardId)
        if (replay(rows, fsrs) == null) return null // nothing standing to undo
        val back = replay(rows + (now to 0), fsrs)
        db.tx {
            append("revlog", mapOf(
                "id" to newId(now), "card" to cardId, "time" to now, "rating" to 0, "device" to device,
                "s_before" to c.stability, "d_before" to c.difficulty,
                "s_after" to back?.state?.stability, "d_after" to back?.state?.difficulty,
            ))
            if (back == null) cache(cardId, null, null) else cache(cardId, back.state, back.lastReview)
        }
        return back?.state
    }

    /** A carded word seen in a scene that played to its end without being rated. */
    fun expose(cardId: Long, episode: String?, scene: Int?, subs: String?, now: Long = System.currentTimeMillis()) {
        append("exposures", mapOf("id" to newId(now), "card" to cardId, "time" to now, "episode" to episode, "scene" to scene, "subs" to subs))
    }

    /** A card met in a manga volume (local only, never synced); answers the context's id. */
    fun addContext(card: Long, title: String?, volume: String?, page: Int?, sentence: String?, image: String?, now: Long = System.currentTimeMillis()): Long =
        db.tx {
            insert("contexts", mapOf("card" to card, "time" to now, "title" to title, "volume" to volume, "page" to page, "sentence" to sentence, "image" to image))
            db.query("SELECT last_insert_rowid()") { it.long(0) }.first()
        }

    /** Where [card] was met, newest first. */
    fun contexts(card: Long): List<CardContext> = db.query("SELECT id, card, time, title, volume, page, sentence, image FROM contexts WHERE card = ? ORDER BY time DESC, id DESC", arrayOf("$card"))
        { c -> CardContext(c.long(0), c.long(1), c.long(2), c.stringOrNull(3), c.stringOrNull(4), if (c.isNull(5)) null else c.int(5), c.stringOrNull(6), c.stringOrNull(7)) }

    /** A card's text changed here (Improve, a fixed sentence): newest edit wins on sync. */
    fun updateCard(id: Long, word: String, sentence: String, meaning: String, fields: String, tags: String, now: Long = System.currentTimeMillis()) {
        db.tx {
            db.exec("UPDATE cards SET word = ?, sentence = ?, meaning = ?, fields = ?, tags = ?, modified = ? WHERE id = ?",
                arrayOf(word, sentence, meaning, fields, tags, now, id))
            insert("outbox", mapOf("kind" to "cards", "id" to id), "OR IGNORE")
        }
    }

    // kumapie's moveWords (CardIndex.MOVED, a rename table for old cards) is dropped: kumahon has no old cards.

    /** Card id -> (ratings standing, Agains), as kuma3's reps and lapses. */
    fun counts(): Map<Long, Pair<Int, Int>> = HashMap<Long, Pair<Int, Int>>().apply {
        db.query("SELECT card, SUM(CASE WHEN rating > 0 THEN 1 ELSE -1 END), SUM(rating = 1) FROM revlog GROUP BY card") { c -> put(c.long(0), c.int(1) to c.int(2)) }
    }

    /** The cached state of these cards (null = all) again from their revlog, after a sync brought other devices' rows. */
    fun replayAll(only: Set<Long>? = null) {
        // The history is read inside the transaction that writes the cache: a rate() committed in between can't be overwritten by a stale state.
        db.tx {
            val rows = HashMap<Long, MutableList<Pair<Long, Int>>>()
            db.query("SELECT card, time, rating FROM revlog ORDER BY time, id") { c ->
                if (only == null || c.long(0) in only) rows.getOrPut(c.long(0)) { mutableListOf() }.add(c.long(1) to c.int(2))
                Unit
            }
            for ((id, r) in rows) replay(r, fsrs).let { cache(id, it?.state, it?.lastReview) }
        }
    }

    fun revlogIds(): Set<Long> = db.query("SELECT id FROM revlog") { it.long(0) }.toSet()

    fun take(events: List<Pair<JSONObject, Long>>) {
        db.tx {
            for ((e, card) in events) insert("revlog", mapOf(
                "id" to e.getLong("id"), "card" to card, "time" to e.getLong("time"), "rating" to e.getInt("ease"), "device" to e.optString("device"),
            ), "OR IGNORE")
        }
        replayAll(events.map { it.second }.toSet())
    }

    fun useFsrs(params: String?, retention: String?) {
        if (meta("params") == params && meta("retention") == retention) return
        for ((k, v) in listOf("params" to params, "retention" to retention))
            if (v == null) db.exec("DELETE FROM meta WHERE key = ?", arrayOf(k)) else insert("meta", mapOf("key" to k, "value" to v), "OR REPLACE")
        scheduler = null
        replayAll()
    }

    /** Replaces the words kumapie counts as known ([KnownWords]) in one transaction. */
    fun replaceKnown(entries: Set<String>) = db.tx {
        db.exec("DELETE FROM known_words")
        for (e in entries) db.exec("INSERT OR IGNORE INTO known_words (entry) VALUES (?)", arrayOf(e))
    }

    fun knownKeys(): Set<String> = db.query("SELECT entry FROM known_words") { it.string(0) }.toSet()

    /** Is this word (and reading) in kumapie's known list ([KnownWords.isKnown])? */
    fun isKnown(word: String, reading: String): Boolean =
        db.query("SELECT 1 FROM known_words WHERE entry IN (?, ?)", arrayOf(word, WordKey.japaneseKey(word, reading))) { it.int(0) }.isNotEmpty()

    private fun insert(table: String, v: Map<String, Any?>, or: String = "") =
        db.exec("INSERT $or INTO $table (${v.keys.joinToString()}) VALUES (${v.keys.joinToString { "?" }})", v.values.toTypedArray())

    /** A row this device made: stored, and queued for the PC. */
    private fun append(table: String, v: Map<String, Any?>) {
        db.tx {
            insert(table, v)
            insert("outbox", mapOf("kind" to table, "id" to v["id"]), "OR IGNORE")
        }
    }

    fun dump(): JSONObject = JSONObject().apply {
        for (t in listOf("meta", "cards", "revlog", "exposures")) put(t, JSONArray(db.query("SELECT * FROM $t") { json(it) }))
    }

    private fun json(c: SqlRow) = JSONObject().apply {
        for (i in 0 until c.columnCount) put(c.name(i), c.value(i) ?: JSONObject.NULL)
    }

    private fun history(cardId: Long) = db.query("SELECT time, rating FROM revlog WHERE card = ? ORDER BY time, id", arrayOf("$cardId")) { c -> c.long(0) to c.int(1) }

    private fun cache(cardId: Long, s: Fsrs6.State?, lastReview: Long?) {
        db.exec("UPDATE cards SET stability = ?, difficulty = ?, last_review = ?, due = ? WHERE id = ?", arrayOf(
            s?.stability, s?.difficulty, lastReview,
            if (s == null || lastReview == null) null else lastReview + fsrs.interval(s.stability) * DAY, cardId))
    }

    data class Replayed(val state: Fsrs6.State, val lastReview: Long)

    companion object {
        const val DAY = 86_400_000L

        /** kumahon is Japanese only; the `lang` column and the sync JSON keep the code so rows stay compatible with kumapie. */
        const val LANG = "ja"

        /** Row id for revlog and exposures: ms * 1000 + a random 0..999, so two devices never collide. */
        fun newId(timeMs: Long = System.currentTimeMillis()) = timeMs * 1000 + Random.nextInt(1000)

        /** Whole days between two ratings, as py-fsrs counts them (24 h periods). */
        fun elapsedDays(from: Long, to: Long) = Math.floorDiv(to - from, DAY).toInt().coerceAtLeast(0)

        /**
         * A card's state from its revlog (time, rating) in time order: a rating 0 cancels the last standing rating;
         * the standing ones go through [Fsrs6] one by one. Null = never rated (or every rating undone).
         */
        fun replay(rows: List<Pair<Long, Int>>, fsrs: Fsrs6): Replayed? {
            val standing = ArrayList<Pair<Long, Int>>()
            for (r in rows) if (r.second == 0) standing.removeLastOrNull() else standing.add(r)
            var out: Replayed? = null
            for ((t, rating) in standing) {
                val days = out?.let { elapsedDays(it.lastReview, t) } ?: 0
                out = Replayed(fsrs.next(out?.state, days, rating), t)
            }
            return out
        }

        /** Start of the next study day (4 am local, as Anki): a card is due today when its due is before this. */
        fun nextDay(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Long {
            val t = Instant.ofEpochMilli(now).atZone(zone)
            val four = t.toLocalDate().atTime(LocalTime.of(4, 0)).atZone(zone)
            return (if (t.isBefore(four)) four else four.plusDays(1)).toInstant().toEpochMilli()
        }

        /** State of one card: "d" due today, "b" never rated (blue), "k" studied and not due. */
        fun state(due: Long?, now: Long = System.currentTimeMillis()): String = when {
            due == null -> "b"
            due < nextDay(now) -> "d"
            else -> "k"
        }

        /** The tag of a deleted card ([cards] skips it). */
        const val DELETED = "kumapie::deleted"
        private const val CARD_COLS = "id, note, ord, word, sentence, meaning, fields, tags, show, episode, scene, created, modified, stability, difficulty, last_review, due"

        private fun card(c: SqlRow) = Card(
            c.long(0), c.long(1), c.int(2), c.string(3), c.string(4), c.string(5), c.string(6), c.string(7),
            c.stringOrNull(8), c.stringOrNull(9), if (c.isNull(10)) null else c.int(10), c.long(11), c.long(12),
            if (c.isNull(13)) null else c.double(13), if (c.isNull(14)) null else c.double(14),
            if (c.isNull(15)) null else c.long(15), if (c.isNull(16)) null else c.long(16),
        )

        private fun SqlRow.stringOrNull(i: Int) = if (isNull(i)) null else string(i)

        /** kumahon only, never synced, run on EVERY open (IF NOT EXISTS: a db file made before a table was added gets it too): where a card was met. */
        val LOCAL = listOf(
            """CREATE TABLE IF NOT EXISTS contexts (
                id INTEGER PRIMARY KEY, card INTEGER NOT NULL, time INTEGER NOT NULL,
                title TEXT, volume TEXT, page INTEGER, sentence TEXT, image TEXT)""",
            "CREATE INDEX IF NOT EXISTS contexts_card ON contexts(card)",
            // What kumapie's last sync answer says is known ([KnownWords]): replaced whole on each sync.
            "CREATE TABLE IF NOT EXISTS known_words (entry TEXT PRIMARY KEY)",
        )

        val SCHEMA = listOf(
            // FSRS parameters ("params", JSON array of 21), "retention" ({lang: r}) and where they came from.
            "CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
            // word = the Mined note's Word field as is (German keeps its forms: "verstehe[→ verstehen]");
            // ord: kuma3's card ordinal; fields = the note's other text fields (JSON); tags space-separated as in
            // Anki (known-manually = known). Text only: no images, audio or video. modified = newest edit wins on sync.
            """CREATE TABLE cards (
                id INTEGER PRIMARY KEY, note INTEGER NOT NULL, ord INTEGER NOT NULL DEFAULT 0, lang TEXT NOT NULL,
                word TEXT NOT NULL, sentence TEXT NOT NULL DEFAULT '', meaning TEXT NOT NULL DEFAULT '',
                fields TEXT NOT NULL DEFAULT '{}', tags TEXT NOT NULL DEFAULT '', show TEXT, episode TEXT, scene INTEGER,
                created INTEGER NOT NULL, modified INTEGER NOT NULL,
                stability REAL, difficulty REAL, last_review INTEGER, due INTEGER)""",
            "CREATE UNIQUE INDEX cards_note_ord ON cards(note, ord)",
            "CREATE INDEX cards_word ON cards(word)",
            // One rating (1-4; 0 = undo of the card's last standing rating). time/answer_ms in ms; s/d before (null =
            // new) and after (null = new again after an undo); r = predicted recall then. Context: subs =
            // shown|blurred|hidden; looked_up 0/1 before rating; heard = times heard since last rating.
            """CREATE TABLE revlog (
                id INTEGER PRIMARY KEY, card INTEGER NOT NULL, time INTEGER NOT NULL, rating INTEGER NOT NULL,
                answer_ms INTEGER, device TEXT NOT NULL,
                s_before REAL, d_before REAL, s_after REAL, d_after REAL, r REAL,
                episode TEXT, scene INTEGER, subs TEXT, looked_up INTEGER, speed REAL, heard INTEGER)""",
            "CREATE INDEX revlog_card ON revlog(card, time)",
            // A carded word seen in a scene that played to its end without being rated.
            """CREATE TABLE exposures (
                id INTEGER PRIMARY KEY, card INTEGER NOT NULL, time INTEGER NOT NULL,
                episode TEXT, scene INTEGER, subs TEXT)""",
            "CREATE INDEX exposures_card ON exposures(card, time)",
            // Device only: rows made here and not yet taken by the PC (the offline queue). Empty on the PC.
            "CREATE TABLE outbox (kind TEXT NOT NULL, id INTEGER NOT NULL, PRIMARY KEY (kind, id))",
        ) + listOf("revlog", "exposures").flatMap { t ->
            listOf("UPDATE", "DELETE").map { op ->
                "CREATE TRIGGER ${t}_no_${op.lowercase()} BEFORE $op ON $t BEGIN SELECT RAISE(ABORT, '$t is append-only'); END"
            }
        }
    }
}
