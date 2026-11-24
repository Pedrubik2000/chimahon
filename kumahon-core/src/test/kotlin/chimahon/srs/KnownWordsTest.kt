package chimahon.srs

import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** kumapie's known words from its sync answer: imported, marked, and review events replayed (reviewed = known). */
class KnownWordsTest {
    @TempDir lateinit var dir: File
    private val open = mutableListOf<SqlDb>()
    @AfterEach fun closeAll() = open.forEach { it.close() }

    private val DAY = 86_400_000L
    private fun ev(id: Long, key: String, day: Int, ease: Int) = """{"id":$id,"key":"$key","time":${day * DAY},"ease":$ease,"device":"x"}"""

    private fun answer(events: List<String> = emptyList(), words: String = "", marked: String = "", marked2: String = "") = JSONObject(
        """{"known":{"ja":{"time":1,"words":[$words]}},"progress":{"tab":{"marked":{$marked}},"pc":{"marked":{$marked2}}},"events":[${events.joinToString(",")}]}""")

    @Test fun importedMarkedAndReviewedWordsAreKnown() {
        // 犬: reviewed three times. 猫: reviewed once (low stability): known all the same, as in kumapie (any card not new).
        val events = listOf(
            ev(1, "ja|犬\tいぬ", 0, 3), ev(2, "ja|犬\tいぬ", 10, 3), ev(3, "ja|犬\tいぬ", 40, 3),
            ev(4, "ja|猫\tねこ", 0, 3), ev(5, "de|hund", 0, 3),
        )
        val known = KnownWords.of(answer(events, """"魔法","食べる"""", """"鳥":[true,5],"空":[true,5],"en:cat":[true,5]"""), Fsrs6())
        assertTrue(KnownWords.isKnown(known, "魔法", "まほう"))
        assertTrue(KnownWords.isKnown(known, "魔法", "")) // imported: any reading
        assertTrue(KnownWords.isKnown(known, "鳥", "とり")) // marked
        assertTrue(KnownWords.isKnown(known, "犬", "イヌ")) // katakana reading, same key as the hiragana one
        assertTrue(KnownWords.isKnown(known, "犬", "けん")) // kumapie colours by spelling: its index holds the spelling too
        assertTrue(KnownWords.isKnown(known, "猫", "ねこ")) // one review, low stability
        assertFalse(known.any { "cat" in it || "hund" in it }) // other languages' marks and cards stay out
    }

    @Test fun anUndoneRatingAndAnUnmarkAreNotKnown() {
        // 犬: rated then undone = never reviewed. 猫: three ratings, two undone = one left = reviewed.
        val events = listOf(ev(1, "ja|犬	いぬ", 0, 3), ev(2, "ja|犬	いぬ", 1, 0),
            ev(3, "ja|猫	ねこ", 0, 3), ev(4, "ja|猫	ねこ", 10, 3), ev(5, "ja|猫	ねこ", 40, 3), ev(6, "ja|猫	ねこ", 40, 0), ev(7, "ja|猫	ねこ", 40, 0))
        val known = KnownWords.of(answer(events, "", """"鳥":[true,5],"空":[true,5]""", """"空":[false,9]"""), Fsrs6())
        assertFalse(KnownWords.isKnown(known, "犬", "いぬ"))
        assertTrue(KnownWords.isKnown(known, "猫", "ねこ"))
        assertTrue("鳥" in known)
        assertFalse("空" in known)
    }

    @Test fun kanaWordsAndKatakanaReadingsMatchLikeKumapieKeys() {
        val known = KnownWords.of(answer(listOf(ev(1, "ja|みんな\tみんな", 0, 3), ev(2, "ja|みんな\tみんな", 20, 3), ev(3, "ja|みんな\tみんな", 80, 3))), Fsrs6())
        assertTrue(KnownWords.isKnown(known, "みんな", "みんな"))
        assertTrue(KnownWords.isKnown(known, "みんな", "")) // kana-only: the word is its reading
        assertTrue(KnownWords.isKnown(known, "みんな", "ミンナ"))
    }

    @Test fun storeReplacesTheListOnEachSyncInOneGo() {
        val s = Srs(JdbcSqlDb(File(dir, "k.db")).also { open += it }, "pc")
        s.replaceKnown(setOf("魔法", "犬\tいぬ"))
        assertEquals(setOf("魔法", "犬\tいぬ"), s.knownKeys())
        assertTrue(s.isKnown("犬", "いぬ")); assertTrue(s.isKnown("魔法", "まほう"))
        s.replaceKnown(setOf("犬\tいぬ")) // 魔法 is gone from kumapie's list
        assertEquals(setOf("犬\tいぬ"), s.knownKeys())
        assertFalse(s.isKnown("魔法", "まほう"))
        assertTrue(s.isKnown("犬", "イヌ"))
    }

    @Test fun hubTest() {
        assertTrue(PeerSync.isLocalHub(PeerSync.DEFAULT_HUB))
        assertTrue(PeerSync.isLocalHub("localhost:47823"))
        assertFalse(PeerSync.isLocalHub("192.168.1.20:47823"))
        assertFalse(PeerSync.isLocalHub("100.64.0.2:47823"))
    }
}
