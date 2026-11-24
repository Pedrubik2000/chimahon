package chimahon.srs

/**
 * kumapie's word key, byte for byte (kumapie `CardIndex.japaneseKeys`, `japaneseKey`, `moved`, `plain`; `JapaneseLookup.hiragana`,
 * `cleanReading`; `DeviceFile.wordKey`): the one thing both apps agree on, since reviews cross between them as events
 * matched by this key. Pure Kotlin (no org.json, no Android) so a unit test runs it.
 */
object WordKey {
    private val MOVED = mapOf("私[わたくし]" to "私[わたし]")
    private val RUBY = Regex("""([^\s\[\]]+)\[([^\]]*)]""")

    /** A card's `word` field (with the note's Reading field, null = none) -> its keys: "spelling\treading", then the spelling alone. */
    fun japaneseKeys(wordField: String, reading: String? = null): List<String> {
        val field = moved(wordField) ?: wordField
        val s = plain(field).replace(Regex(""":[0-9A-Za-z\-]*"""), "").replace(Regex("""[/*\\-]"""), "")
        val spelling = s.replace(RUBY, "$1").replace(Regex("""\s"""), "")
        if (spelling.isEmpty()) return emptyList()
        val read = reading?.let(::plain)?.trim()?.takeIf { it.isNotEmpty() } ?: s.replace(RUBY, "$2").replace(Regex("""\s"""), "")
        return listOf(japaneseKey(spelling, read), spelling)
    }

    /** "私[わたくし]:0-" -> "私[わたし]:0-"; null when the field didn't move. */
    fun moved(word: String): String? = MOVED.entries.firstOrNull { word.startsWith(it.key) }?.let { it.value + word.substring(it.key.length) }

    /** A word's key: as written, tab, its reading in hiragana (kana-only words: the word itself). */
    fun japaneseKey(written: String, reading: String): String = written + "\t" + hiragana(cleanReading(reading).ifBlank { written })

    /** The key kumapie sends for a card (`ja|` + first key), null when the word field is empty. */
    fun cardKey(wordField: String, reading: String?): String? = japaneseKeys(wordField, reading).firstOrNull()?.let { "ja|$it" }

    /**
     * The key a card kumahon makes for (word, reading) has: its `word` field is [furigana], its Reading field the reading. The one
     * function [cardKey] gives for such a card, so a lookup always finds the card it made (and, for ordinary words, kumapie's).
     */
    fun lookupKey(word: String, reading: String): String? = cardKey(furigana(word, reading), reading)

    fun hiragana(s: String): String = buildString { for (c in s) append(if (c in 'ァ'..'ヶ') c - 0x60 else c) }

    /** Half-kana pinyin and the like (latin letters among kana) are no reading. */
    fun cleanReading(r: String): String =
        if (r.any { it in 'a'..'z' || it in 'A'..'Z' } && r.any { it in 'ぁ'..'ゖ' || it in 'ァ'..'ヶ' }) "" else r

    private fun plain(field: String): String = field.replace(Regex("<[^>]*>"), "")
        .replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() }
        .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    /**
     * The `word` field for a simple word (the form kumahon writes for its own cards): no kanji or no reading -> the word;
     * else the kana shared at both ends stays outside and the rest is bracketed (`食べる`,`たべる` -> `食[た]べる`;
     * `お茶`,`おちゃ` -> `お 茶[ちゃ]`). [japaneseKeys] of it gives [japaneseKey] of (word, reading).
     */
    fun furigana(word: String, reading: String): String {
        if (reading.isEmpty() || word.none { it.isKanji() }) return word
        val w = hiragana(word)
        val r = hiragana(reading)
        var p = 0
        while (p < w.length && p < r.length && w[p] == r[p] && !word[p].isKanji()) p++
        var q = 0
        while (p + q < w.length && p + q < r.length && w[w.length - 1 - q] == r[r.length - 1 - q] && !word[word.length - 1 - q].isKanji()) q++
        val base = word.substring(p, word.length - q)
        val ruby = reading.substring(p, reading.length - q)
        if (base.isEmpty() || ruby.isEmpty()) return "$word[$reading]"
        return word.substring(0, p) + (if (p > 0) " " else "") + base + "[" + ruby + "]" + word.substring(word.length - q)
    }

    private fun Char.isKanji() = this in '一'..'鿿' || this in '㐀'..'䶿' || this == '々'
}
