package chimahon.srs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Card word fields (Anki furigana form) <-> (spelling, reading). Pure text, no Android. */
class CardsKeyTest {
    @Test fun keyOfWordField() {
        assertEquals("魔法" to "まほう", CardWord.key("魔法[まほう]"))
        assertEquals("日本語" to "にほんご", CardWord.key(" 日本[にほん] 語[ご]:0-"))
        assertEquals("食べる" to "たべる", CardWord.key("食[た]べる"))
        assertEquals("すごい" to "すごい", CardWord.key("すごい"))
    }

    @Test fun batchBySize() {
        assertEquals(0, Srs.takeBySize(emptyList()))
        assertEquals(3, Srs.takeBySize(listOf(10, 20, 30), 100))                // under budget: all
        assertEquals(2, Srs.takeBySize(listOf(40, 40, 40), 100))                // the third would pass 100
        assertEquals(3, Srs.takeBySize(listOf(40, 40, 20), 100))                // exactly the budget fits
        assertEquals(1, Srs.takeBySize(listOf(500, 1, 1), 100))                 // a first row over the budget is still taken, alone
        assertEquals(1, Srs.takeBySize(listOf(500), 100))
    }

    @Test fun furiganaIsTheInverseOfKey() {
        assertEquals("食[た]べる", CardWord.furigana("食べる", "たべる"))
        assertEquals("お 茶[ちゃ]", CardWord.furigana("お茶", "おちゃ"))
        assertEquals("魔法[まほう]", CardWord.furigana("魔法", "まほう"))
        assertEquals("お茶" to "おちゃ", CardWord.key(CardWord.furigana("お茶", "おちゃ")))
        assertEquals("すごい", CardWord.furigana("すごい", "すごい"))
    }
}
