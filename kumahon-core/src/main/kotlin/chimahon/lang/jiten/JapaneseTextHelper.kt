// Ported from Jiten (github.com/Sirush/Jiten, Apache-2.0, commit 44b48a4): Jiten.Core/JapaneseTextHelper.cs
package chimahon.lang.jiten

object JapaneseTextHelper {
    fun isKana(c: Char) = c in '぀'..'ゟ' || c in '゠'..'ヿ'
    fun isHiragana(c: Char) = c in '぀'..'ゟ'
    fun isKatakana(c: Char) = c in '゠'..'ヿ'

    /** WanaKana.IsKana's ranges without its allocations; false on empty. */
    fun isAllKana(s: CharSequence) = s.isNotEmpty() && s.all(::isKana)

    /** WanaKana.ToKatakana on kana-only input: the whole hiragana block shifts by 0x60. */
    fun hiraganaToKatakana(s: String): String {
        if (s.none(::isHiragana)) return s
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(if (isHiragana(c)) c + 0x60 else c)
        return sb.toString()
    }

    fun isAllHiragana(s: CharSequence) = s.isNotEmpty() && s.all(::isHiragana)
    fun isAllKatakana(s: CharSequence) = s.isNotEmpty() && s.all(::isKatakana)

    /** Katakana letter (ァ–ヺ) or ー: what a katakana word is spelled with (no middle dots or iteration marks). */
    fun isKatakanaWordChar(c: Char) = c in 'ァ'..'ヺ' || c == 'ー'

    /** Fullwidth or halfwidth Latin letter. */
    fun isLatinLetter(c: Char) = c in 'Ａ'..'Ｚ' || c in 'ａ'..'ｚ' || c in 'A'..'Z' || c in 'a'..'z'

    fun isAllLatin(s: CharSequence) = s.isNotEmpty() && s.all(::isLatinLetter)

    /** Katakana (ァ-ヶ) to hiragana; everything else (kanji, ー, ASCII) untouched. */
    fun katakanaToHiragana(s: String): String {
        if (s.isEmpty() || s.none { it in 'ァ'..'ヶ' }) return s
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(if (c in 'ァ'..'ヶ') c - 0x60 else c)
        return sb.toString()
    }

    /**
     * WanaKana's katakana-to-hiragana, guarded against its chōonpu expansion: it has no vowel for ヵヶゎヮ ヷ-ヺ ヽヾヿ
     * or after ッ/ン, so a following ー throws; then the whole text converts without long-vowel conversion.
     */
    fun toHiragana(text: String, convertLongVowelMark: Boolean = true): String {
        if (text.isEmpty()) return text
        val input = text.replace("ヶ", "ケ").replace("ヵ", "カ").replace("ゎ", "わ").replace("ヮ", "ワ")
        return try {
            WanaKana.toHiragana(input, convertLongVowelMark)
        } catch (e: WanaKana.NoVowelException) {
            WanaKana.toHiragana(input, false)
        }
    }

    fun isKanji(c: Char) = c in '一'..'鿿' || c in '㐀'..'䶿' || c in '豈'..'﫿'

    /** Code point version (Rune in C#): CJK main block, extensions A-E, compatibility ideographs. */
    fun isKanji(codePoint: Int) = codePoint in 0x4E00..0x9FFF || codePoint in 0x3400..0x4DBF ||
        codePoint in 0x20000..0x2A6DF || codePoint in 0x2A700..0x2B73F || codePoint in 0x2B740..0x2B81F ||
        codePoint in 0x2B820..0x2CEAF || codePoint in 0xF900..0xFAFF || codePoint in 0x2F800..0x2FA1F

    /** ASCII/full-width digit, kanji digit 一〜九, place marker 十百千万億兆 or zero 〇零. */
    fun isNumeralChar(c: Char) = c in '0'..'9' || c in '０'..'９' || c in "一二三四五六七八九十百千万億兆〇零"
}

/** Jiten.Core/Utils/StringExtensions.cs IsAsciiOrFullWidthLetter: checks the first char only (throws on ""). */
internal fun String.isAsciiOrFullWidthLetter(): Boolean {
    val c = this[0]
    return c in 'a'..'z' || c in 'A'..'Z' || c in 'ａ'..'ｚ' || c in 'Ａ'..'Ｚ'
}
