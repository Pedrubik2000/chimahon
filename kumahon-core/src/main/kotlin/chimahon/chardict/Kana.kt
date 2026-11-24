package chimahon.chardict

/**
 * Low-level kana conversion: romaji to hiragana, katakana <-> hiragana, kanji detection.
 * Port of Bee's `kana.rs` (MIT, see NOTICE.md): same outputs for the same input.
 */
object Kana {
    private fun isKanji(c: Int): Boolean =
        c in 0x4E00..0x9FFF || c in 0x3400..0x4DBF || c in 0x20000..0x2A6DF || c in 0x2A700..0x2B73F ||
            c in 0x2B740..0x2B81F || c in 0x2B820..0x2CEAF || c in 0x2CEB0..0x2EBEF || c in 0x30000..0x3134F ||
            c in 0x31350..0x323AF || c in 0xF900..0xFAFF || c in 0x2F800..0x2FA1F ||
            c == 0x3005 // 々 repeats the kanji before it, so it counts as kanji in names

    private fun isHiragana(c: Int) = c in 0x3041..0x3096
    private fun isKatakana(c: Int) = c in 0x30A1..0x30F6 || c == 0x30FC

    fun containsKanji(text: String): Boolean = text.codePoints().anyMatch { isKanji(it) }

    fun containsJapanese(text: String): Boolean = text.codePoints().anyMatch { isKanji(it) || isHiragana(it) || isKatakana(it) }

    /** Katakana U+30A1..U+30F6 minus 0x60 is its hiragana. */
    fun kataToHira(text: String): String = shift(text, 0x30A1..0x30F6, -0x60)

    /** Hiragana U+3041..U+3096 plus 0x60 is its katakana. */
    fun hiraToKata(text: String): String = shift(text, 0x3041..0x3096, 0x60)

    private fun shift(text: String, range: IntRange, by: Int): String {
        val sb = StringBuilder(text.length)
        text.codePoints().forEach { sb.appendCodePoint(if (it in range) it + by else it) }
        return sb.toString()
    }

    /** Apostrophes, hyphen and dot are syllable boundary markers in romaji ("Shin'ichi" is しんいち): skipped. */
    private fun isSyllableBoundary(c: Char) = c == '\'' || c == '’' || c == '‘' || c == '-' || c == '.'

    private fun isConsonant(c: Char) = c in "bcdfghjklmnpqrstvwxyz"

    private fun isVowelOrY(c: Char) = c in "aiueoy"

    /**
     * Romaji to hiragana: double consonants (っ), special n, 3/2/1 character sequences, boundary markers.
     * Spaces are kept; digits and other punctuation are dropped; unknown letters pass through.
     */
    fun alphabetToKana(input: String): String {
        val chars = input.lowercase(java.util.Locale.ROOT).toCharArray()
        val result = StringBuilder()
        var i = 0
        while (i < chars.size) {
            val c = chars[i]
            if (isSyllableBoundary(c)) { i++; continue }
            val ascii = c in 'a'..'z' || c in 'A'..'Z'
            if (!ascii && c != ' ') { i++; continue }
            if (c == ' ') { result.append(' '); i++; continue }
            // 1. double consonant
            if (i + 1 < chars.size && chars[i] == chars[i + 1] && isConsonant(chars[i])) { result.append('っ'); i++; continue }
            // 2. three, 3. two characters
            if (i + 3 <= chars.size) {
                val k = ROMAJI[String(chars, i, 3)]
                if (k != null) { result.append(k); i += 3; continue }
            }
            if (i + 2 <= chars.size) {
                val k = ROMAJI[String(chars, i, 2)]
                if (k != null) { result.append(k); i += 2; continue }
            }
            // 4. n not followed by a vowel or y is ん
            if (chars[i] == 'n') {
                val next = chars.getOrNull(i + 1)
                if (next == null || !isVowelOrY(next)) { result.append('ん'); i++; continue }
            }
            // 5. a vowel, or an unknown letter passed through
            result.append(ROMAJI[chars[i].toString()] ?: chars[i].toString())
            i++
        }
        return result.toString()
    }
}
