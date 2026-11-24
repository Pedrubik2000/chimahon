package chimahon.srs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Word keys are kumapie's, byte for byte (cases of its CardIndexTest.japanese and JapaneseWordTest). Pure text, no Android. */
class WordKeyTest {
    private fun keys(word: String, reading: String? = null) = WordKey.japaneseKeys(word, reading)

    @Test fun keysOfWordFields() {
        assertEquals(listOf("私\tわたし", "私"), keys("私[わたし]:0-"))
        assertEquals(listOf("日本語\tにほんご", "日本語"), keys("日本[にほん] 語[ご]:0-"))
        assertEquals(listOf("人\tひと", "人"), keys("人[*ひと]:2-"))
        assertEquals(listOf("告白\tこくはく", "告白"), keys("告[こ*く] 白[はく]:0-"))
        assertEquals(listOf("拾う\tひろう", "拾う"), keys("拾[ひろ]\\う:0"))
        assertEquals(listOf("新しい\tあたらしい", "新しい"), keys("新[あたら]し\\い:k4"))
        assertEquals(listOf("気に入る\tきにいる", "気に入る"), keys("気[き]:0 に / 入[い]る:0"))
        assertEquals(listOf("ほぼ\tほぼ", "ほぼ"), keys("ほぼ:1 / -"))
        assertEquals(listOf("届く\tとどく", "届く"), keys("届く[とどく]:0-"))
        assertEquals(listOf("気にしない\tきにしない", "気にしない"), keys("気にしない[きにしない]"))
        assertEquals(listOf("テレビ\tてれび", "テレビ"), keys("テレビ"))
        assertEquals(listOf("船\tふね", "船"), keys("船", "ふね")) // JPDB: Word + Reading
        assertEquals(emptyList<String>(), keys(""))
        assertEquals(listOf("紅娘\t紅娘", "紅娘"), keys("紅娘[ほんgにあんg]")) // half-kana pinyin: no reading
        assertEquals("", WordKey.cleanReading("ほんgにあんgさま"))
    }

    @Test fun htmlInTheField() {
        assertEquals(listOf("届く\tとどく", "届く"), keys("<b>届</b>く[とどく]"))
        assertEquals(listOf("Wenn's\tWenn's", "Wenn's"), keys("Wenn&#39;s"))
    }

    @Test fun theWatashiCardsMove() {
        assertEquals("私[わたし]:0-", WordKey.moved("私[わたくし]:0-"))
        assertNull(WordKey.moved("私[わたし]:0-"))
        assertNull(WordKey.moved("私室[ししつ]"))
        assertEquals(listOf(WordKey.japaneseKey("私", "わたし"), "私"), keys("私[わたくし]:0-"))
    }

    @Test fun keyAndCardKey() {
        assertEquals("魔法\tまほう", WordKey.japaneseKey("魔法", "マホウ"))
        assertEquals("すごい\tすごい", WordKey.japaneseKey("すごい", ""))
        assertEquals("ja|魔法\tまほう", WordKey.cardKey("魔法[まほう]", null))
        assertNull(WordKey.cardKey("", null))
    }

    /** The key of the card kumahon stores for (word, reading): word field = furigana, Reading field = reading (Cards.keyOf). */
    private fun stored(word: String, reading: String) = WordKey.cardKey(WordKey.furigana(word, reading), reading)

    /** A card made for a word is found again by the same lookup, even where its word field is bare or has characters the key strips. */
    @Test fun lookupFindsTheCardItMade() {
        assertEquals("ja|Tシャツ\tてぃーしゃつ", WordKey.lookupKey("Tシャツ", "ティーシャツ"))
        assertEquals("ja|ＣＤ\tしーでぃー", WordKey.lookupKey("ＣＤ", "しーでぃー"))
        assertEquals("ja|JPOP\tじぇいぽっぷ", WordKey.lookupKey("J-POP", "じぇいぽっぷ"))
        for ((w, r) in listOf("Tシャツ" to "ティーシャツ", "ＣＤ" to "しーでぃー", "J-POP" to "じぇいぽっぷ", "魔法" to "まほう", "食べる" to "たべる", "すごい" to "すごい")) {
            assertEquals(stored(w, r), WordKey.lookupKey(w, r), "$w/$r")
        }
        // ordinary words keep kumapie's key, so they still match its cards
        for ((w, r) in listOf("魔法" to "まほう", "食べる" to "たべる")) assertEquals("ja|" + WordKey.japaneseKey(w, r), WordKey.lookupKey(w, r), "$w/$r")
        assertNull(WordKey.lookupKey("", ""))
    }

    /** What kumahon writes in its own cards' word field gives the key a lookup of (word, reading) asks for. */
    @Test fun furiganaKeepsTheKey() {
        assertEquals("食[た]べる", WordKey.furigana("食べる", "たべる"))
        assertEquals("お 茶[ちゃ]", WordKey.furigana("お茶", "おちゃ"))
        assertEquals("魔法[まほう]", WordKey.furigana("魔法", "まほう"))
        assertEquals("すごい", WordKey.furigana("すごい", "すごい"))
        for ((w, r) in listOf("魔法" to "まほう", "食べる" to "たべる", "お茶" to "おちゃ", "すごい" to "すごい", "気に入る" to "きにいる", "日本語" to "ニホンゴ")) {
            assertEquals(WordKey.japaneseKey(w, r), WordKey.japaneseKeys(WordKey.furigana(w, r)).first(), "$w/$r")
        }
    }
}
