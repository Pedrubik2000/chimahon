package chimahon.chardict

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Bee's `kana.rs` tests (MIT, see NOTICE.md): the 150 `assert_eq!(fn("in"), "out")` cases as a table, and the kanji ranges. */
class KanaTest {
    @Test fun casesFromBeesTests() {
        assertTrue(KANA_CASES.size >= 150)
        for ((fn, input, expected) in KANA_CASES) {
            val actual = when (fn) {
                "alphabetToKana" -> Kana.alphabetToKana(input)
                "kataToHira" -> Kana.kataToHira(input)
                "hiraToKata" -> Kana.hiraToKata(input)
                else -> error(fn)
            }
            assertEquals(expected, actual, "$fn(\"$input\")")
        }
    }

    @Test fun containsKanji() {
        for (s in listOf("漢字", "漢a", "a漢", "須々木", "㐀", "豈", "𠀀", "𰀀", "丽", "「漢字」")) {
            assertTrue(Kana.containsKanji(s), s)
        }
        for (s in listOf("kana", "ひらがな", "カタカナ", "abc123", "", "䷿", "ꀀ", "「ひらがな」", "123!@#$%", "①②③")) {
            assertFalse(Kana.containsKanji(s), s)
        }
    }

    @Test fun roundTrip() {
        val original = "あいうえおかきくけこ"
        assertEquals(original, Kana.kataToHira(Kana.hiraToKata(original)))
        assertEquals("アイウ", Kana.hiraToKata("あいう"))
    }

    @Test fun alphabetToKanaSpecialCases() {
        assertEquals("しんいち", Kana.alphabetToKana("Shin'ichi"))
        assertEquals("しにち", Kana.alphabetToKana("Shinichi"))
        assertEquals("らっぱ", Kana.alphabetToKana("rappa"))
        assertEquals("ひみこ", Kana.alphabetToKana("HIMIKO"))
    }
}
