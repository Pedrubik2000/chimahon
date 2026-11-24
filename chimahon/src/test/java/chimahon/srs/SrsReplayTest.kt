package chimahon.srs

import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

/** Srs's replay, undo and study day (plan step 4a). */
class SrsReplayTest {
    private val f = Fsrs6()
    private val day = Srs.DAY

    @Test fun undoCancelsTheLastStandingRating() {
        val good = listOf(0L to 3, 2 * day to 3)
        val expected = Srs.replay(good, f)
        assertEquals(expected, Srs.replay(good + (3 * day to 1) + (3 * day + 1 to 0), f)) // Again, then undo it
        assertEquals(Srs.replay(good.take(1), f), Srs.replay(good + (3 * day to 0), f))   // undo the second Good
        assertNull(Srs.replay(listOf(0L to 3, 1L to 0), f))                                  // back to new
        assertNull(Srs.replay(listOf(5L to 0), f))                                           // nothing to undo
    }

    @Test fun studyDayEndsAt4am() {
        val z = ZoneId.of("America/New_York")
        fun at(h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, 16, h, m).atZone(z).toInstant().toEpochMilli()
        assertEquals(at(4) + day, Srs.nextDay(at(4), z))
        assertEquals(at(4), Srs.nextDay(at(3, 59), z))
        assertEquals(at(4) + day, Srs.nextDay(at(23), z))
    }

    /**
     * Every reviewed card of a real import gets the S, D and due tools\srs\import_kuma3.py (py-fsrs) gave it. The
     * export holds Pedro's history, so it stays out of the repo: tools\srs\replay_check.py writes it, SRS_REPLAY_TSV
     * names it; skipped without it (CI).
     */
    @Test fun replaysTheImport() {
        val file = System.getenv("SRS_REPLAY_TSV")?.let(::File)
        assumeTrue(file?.isFile == true, "SRS_REPLAY_TSV not set")
        val lines = file!!.readLines()
        val (params, retention) = lines[0].split('\t').let { JSONArray(it[0]) to JSONObject(it[1]) }
        val w = DoubleArray(params.length()) { params.getDouble(it) }
        val fsrs = retention.keys().asSequence().associateWith { Fsrs6(w, retention.getDouble(it)) }
        for (line in lines.drop(1)) {
            val (id, lang, hist, s, d, due) = line.split('\t')
            val rows = hist.split(',').map { it.split(':').let { (t, r) -> t.toLong() to r.toInt() } }
            val got = Srs.replay(rows, fsrs.getValue(lang))!!
            assertEquals(s.toDouble(), got.state.stability, 1e-6, "card $id S")
            assertEquals(d.toDouble(), got.state.difficulty, 1e-6, "card $id D")
            assertEquals(due.toLong(), got.lastReview + fsrs.getValue(lang).interval(got.state.stability) * day, "card $id due")
        }
    }

    private operator fun <T> List<T>.component6() = this[5]
}
