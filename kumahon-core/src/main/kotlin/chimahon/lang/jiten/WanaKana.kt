// Ported from Jiten (github.com/Sirush/Jiten, Apache-2.0, commit 44b48a4): stands in for the WanaKana-net NuGet
// package (WanaKanaShaapu) that Jiten.Core/JapaneseTextHelper.cs and Jiten.Parser call.
package chimahon.lang.jiten

/**
 * The WanaKana calls Jiten makes, for kana/kanji input (WanaKana JS v5 semantics). Romaji is not converted: where
 * WanaKana would run toKana (all romaji, or kana mixed with romaji) the text is only lower-cased (JMdict/JMnedict forms
 * hold no ASCII letters; toKana would also turn romaji into kana). tools/kumapie/jiten_lexicon.py mirrors [toHiragana] for the lookup keys: change both together.
 */
object WanaKana {
    private const val HIRAGANA_START = 0x3041
    private const val HIRAGANA_END = 0x3096
    private const val KATAKANA_START = 0x30A1
    private const val KATAKANA_END = 0x30FC
    private const val LONG_DASH = 'ー'

    /** The vowel kana a following ー becomes (オー -> おう: the o-row lengthens with う, as WanaKana does). */
    private val LONG_VOWEL = HashMap<Char, Char>().apply {
        for (c in "あかさたなはまやらわがざだばぱぁゃゎ") put(c, 'あ')
        for (c in "いきしちにひみりぎじぢびぴぃゐ") put(c, 'い')
        for (c in "うくすつぬふむゆるぐずづぶぷぅゅゔ") put(c, 'う')
        for (c in "えけせてねへめれげぜでべぺぇゑ") put(c, 'え')
        for (c in "おこそとのほもよろをごぞどぼぽぉょ") put(c, 'う')
    }

    /** WanaKana-net throws here (no vowel for ッ, ン, ヷ...); JapaneseTextHelper catches it. */
    class NoVowelException(text: String) : IllegalStateException("no vowel before ー in $text")

    fun isCharKatakana(c: Char) = c.code in KATAKANA_START..KATAKANA_END
    fun isCharHiragana(c: Char) = c == LONG_DASH || c.code in HIRAGANA_START..HIRAGANA_END
    fun isCharKana(c: Char) = isCharHiragana(c) || isCharKatakana(c)
    fun isCharKanji(c: Char) = c.code in 0x4E00..0x9FAF || c == '々'
    private fun isCharRomaji(c: Char) = c.code <= 0x7F || c.code in 0x0100..0x0101 || c.code in 0x0112..0x0113 ||
        c.code in 0x012A..0x012B || c.code in 0x014C..0x014D || c.code in 0x016A..0x016B ||
        c.code in 0x2018..0x2019 || c.code in 0x201C..0x201D

    /** Non-empty and every char katakana (ァ..ー, so ・ and ー count). */
    fun isKatakana(s: String) = s.isNotEmpty() && s.all(::isCharKatakana)
    fun isHiragana(s: String) = s.isNotEmpty() && s.all(::isCharHiragana)
    fun isKana(s: String) = s.isNotEmpty() && s.all(::isCharKana)
    fun isKanji(s: String) = s.isNotEmpty() && s.all(::isCharKanji)

    /** Throws [NoVowelException] where WanaKana-net throws (a ー it can't expand when [convertLongVowelMark]). */
    fun toHiragana(input: String, convertLongVowelMark: Boolean = true): String {
        val hira = katakanaToHiragana(input, convertLongVowelMark)
        return if (runsToKana(input)) hira.lowercase() else hira
    }

    fun toKatakana(input: String): String {
        val src = if (runsToKana(input)) input.lowercase() else input
        val sb = StringBuilder(src.length)
        for (c in src) sb.append(if (c != LONG_DASH && c != '・' && c.code in HIRAGANA_START..HIRAGANA_END) c + 0x60 else c)
        return sb.toString()
    }

    /** isRomaji(input) or isMixed(input): kana and romaji together (kanji alone don't count). */
    private fun runsToKana(input: String) =
        input.isNotEmpty() && (input.all(::isCharRomaji) || (input.any(::isCharKana) && input.any(::isCharRomaji)))

    private fun katakanaToHiragana(input: String, convertLongVowelMark: Boolean): String {
        val sb = StringBuilder(input.length)
        var prev = '\u0000'
        for ((i, c) in input.withIndex()) {
            when {
                // ・, a leading ー and ヶヵ (kana used as symbols) pass through untouched
                c == '・' || (c == LONG_DASH && i < 1) || c == 'ヶ' || c == 'ヵ' -> sb.append(c)
                convertLongVowelMark && prev != '\u0000' && c == LONG_DASH ->
                    sb.append(LONG_VOWEL[prev] ?: throw NoVowelException(input))
                c != LONG_DASH && isCharKatakana(c) -> {
                    prev = c - 0x60
                    sb.append(prev)
                }
                else -> {
                    prev = '\u0000'
                    sb.append(c)
                }
            }
        }
        return sb.toString()
    }
}
