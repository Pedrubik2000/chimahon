// Ported from Jiten (github.com/Sirush/Jiten, Apache-2.0, commit 44b48a4): Jiten.Parser/KanaConverter.cs,
// Jiten.Parser/KanaNormalizer.cs
package chimahon.lang.jiten

/** Jiten's two-generation result cache is left out: the conversion is a char loop, cheaper than a map probe on a phone. */
internal object KanaConverter {
    fun toNormalizedHiragana(text: String): String = KanaNormalizer.normalize(toHiragana(text, convertLongVowelMark = false))

    fun toHiragana(text: String): String = toHiragana(text, convertLongVowelMark = true)

    fun toHiragana(text: String, convertLongVowelMark: Boolean): String {
        if (isAlreadyHiragana(text)) return text
        tryFoldKatakana(text, convertLongVowelMark)?.let { return it }
        return JapaneseTextHelper.toHiragana(text, convertLongVowelMark)
    }

    /** Plain kana/kanji folds here; ヵヶヮゎ match JapaneseTextHelper's spellings; anything else goes to WanaKana. */
    fun tryFoldKatakana(text: String, convertLongVowelMark: Boolean): String? {
        for (c in text) {
            if (c in 'ぁ'..'ゔ' || JapaneseTextHelper.isKanji(c) || c in 'ァ'..'ヶ') continue
            if (c == 'ー' && !convertLongVowelMark) continue
            return null
        }
        val out = CharArray(text.length)
        for ((i, c) in text.withIndex()) {
            out[i] = when (c) {
                'ヵ' -> 'か'
                'ヶ' -> 'け'
                'ヮ', 'ゎ' -> 'わ'
                in 'ァ'..'ヴ' -> c - 0x60
                else -> c
            }
        }
        return String(out)
    }

    // ゎ is excluded: JapaneseTextHelper rewrites it to わ.
    private fun isAlreadyHiragana(text: String): Boolean {
        for (c in text) {
            if (c in 'ぁ'..'ゔ' && c != 'ゎ') continue
            if (JapaneseTextHelper.isKanji(c)) continue
            return false
        }
        return true
    }
}

object KanaNormalizer {
    private val KanaToVowel = HashMap<Char, Char>().apply {
        for (c in "おこそとのほもよろをごぞどぼぽょオコソトノホモヨロヲゴゾドボポョ") put(c, 'う')
        for (c in "うくすつぬふむゆるぐずづぶぷゅウクスツヌフムユルグズヅブプュ") put(c, 'う')
        for (c in "えけせてねへめれげぜでべぺエケセテネヘメレゲゼデベペ") put(c, 'え')
        for (c in "いきしちにひみりぎじぢびぴイキシチニヒミリギジヂビピ") put(c, 'い')
        for (c in "あかさたなはまやらわがざだばぱゃアカサタナハマヤラワガザダバパャ") put(c, 'あ')
    }

    /** Every ー after a kana becomes that kana's vowel; a leading ー or one after a non-kana stays. */
    fun normalize(input: String): String {
        if (input.isEmpty() || input.indexOf('ー') == -1) return input
        val out = CharArray(input.length)
        for ((i, c) in input.withIndex())
            out[i] = if (c == 'ー' && i > 0) KanaToVowel[input[i - 1]] ?: 'ー' else c
        return String(out)
    }
}
