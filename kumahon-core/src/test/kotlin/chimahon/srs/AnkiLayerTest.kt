package chimahon.srs

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The kuma3 / AnkiDroid layer: the arithmetic next to the other layers, and notes + cards -> known words. */
class AnkiLayerTest {
    @TempDir lateinit var dir: File
    private val open = mutableListOf<SqlDb>()
    @AfterEach fun closeAll() = open.forEach { it.close() }
    private val s by lazy { Srs(JdbcSqlDb(File(dir, "a.db")).also { open += it }, "a") }

    @Test fun layerCountsOnlyWhatNoEarlierLayerHas() {
        s.replaceKnown(setOf("犬"))
        s.replaceMarked(setOf("空"))
        s.replaceAnki(setOf("犬", "空", "山", "川"), 5)
        val l = s.knownLayers()
        assertEquals(2, l.anki)
        assertEquals(4, l.total)
        assertEquals(KnownLayers(1, 0, 1, 4, 2), l)
        assertEquals(5, s.ankiTime())
    }

    @Test fun knownCheckIncludesAnkiWordsAndMarkedUnknownWins() {
        assertFalse(s.isKnownSpelling("山"))
        s.replaceAnki(setOf("山", "川"), 1)
        assertTrue(s.isKnownSpelling("山"))
        assertTrue(s.isKnown("山", "やま"))
        assertTrue("山" in s.knownKeys())
        s.mark("川", false)
        assertFalse(s.isKnownSpelling("川"))
        assertFalse("川" in s.knownKeys())
        assertEquals(1, s.knownLayers().anki)
    }

    @Test fun replaceIsWholeAndNeverSent() {
        s.replaceAnki(setOf("山"), 9)
        s.replaceAnki(setOf("川"), 10)
        assertFalse(s.isKnownSpelling("山"))
        assertTrue(s.isKnownSpelling("川"))
        val dump = s.dump().toString()
        assertFalse(dump.contains("anki"))
        assertFalse(dump.contains("川"))
    }

    private fun note(id: Long, word: String, tags: String = "") = AnkiKnown.Note(id, word, tags)

    @Test fun stabilityThresholdBestCardWins() {
        val notes = listOf(note(1, "犬"), note(2, "猫"), note(3, "鳥"))
        val cards = listOf(
            AnkiKnown.Card(1, 6.9, 100), AnkiKnown.Card(1, 7.0, 1), // best 7.0: known
            AnkiKnown.Card(2, 6.99, 400), // stability wins over a long ivl
            AnkiKnown.Card(3, null, null),
        )
        assertEquals(setOf("犬"), AnkiKnown.words(notes, cards))
    }

    @Test fun intervalFallbackWhenNoStability() {
        val notes = listOf(note(1, "犬"), note(2, "猫"), note(3, "鳥"))
        val cards = listOf(AnkiKnown.Card(1, null, 7), AnkiKnown.Card(2, null, 6), AnkiKnown.Card(3, null, -600))
        assertEquals(setOf("犬"), AnkiKnown.words(notes, cards))
    }

    @Test fun tagMakesItKnownWithoutCards() {
        val notes = listOf(note(1, "犬", "leech known-manually"), note(2, "猫", "known-manually-not"), note(3, "鳥", "Known-Manually"))
        assertEquals(setOf("犬", "鳥"), AnkiKnown.words(notes, emptyList()))
    }

    @Test fun fieldIsCleanedAndSentencesSkipped() {
        assertEquals("食べる", AnkiKnown.surface("<b>食[た]べる</b>"))
        assertEquals("お茶", AnkiKnown.surface("お 茶[ちゃ]"))
        assertEquals("犬", AnkiKnown.surface("&nbsp;犬&nbsp;"))
        assertEquals("漢字", AnkiKnown.surface("<ruby>漢字<rp>(</rp><rt>かんじ</rt><rp>)</rp></ruby>"))
        assertNull(AnkiKnown.surface(""))
        assertNull(AnkiKnown.surface("犬が走る。"))
        assertNull(AnkiKnown.surface("これは長い長い長い長い長い文です"))
        val notes = listOf(note(1, "食[た]べる"), note(2, "犬が走る。"), note(3, ""))
        val cards = listOf(1L, 2L, 3L).map { AnkiKnown.Card(it, 30.0, null) }
        assertEquals(setOf("食べる"), AnkiKnown.words(notes, cards))
    }
}
