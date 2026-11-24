// Ported from Jiten (github.com/Sirush/Jiten, Apache-2.0, commit 44b48a4):
// Jiten.Parser/Stages/MorphologicalAnalyser.NumeralDisambiguation.cs (complete: its helpers are shared by
// Disambiguation, RepairStages and RewriteRules; it has no pipeline stage of its own)
package chimahon.lang.jiten

// Clock hours force native readings (四時=よじ, 九時=くじ) that Sudachi's per-token onyomi can't produce.
private val HourPins: Map<String, Pair<Int, String>> = hashMapOf(
    // 一時 is absent: numeral context can't separate いちじ/いっとき/ひととき, and scoring handles o'clock.
    "二時" to (2612170 to "ニジ"),
    "三時" to (1300520 to "サンジ"),
    "四時" to (1307230 to "ヨジ"),
    "五時" to (2845367 to "ゴジ"),
    "六時" to (2583620 to "ロクジ"),
    "七時" to (2845363 to "シチジ"),
    "八時" to (2845368 to "ハチジ"),
    "九時" to (2845349 to "クジ"),
    "十時" to (2845369 to "ジュウジ"),
    "十一時" to (2845370 to "ジュウイチジ"),
    "十二時" to (1334960 to "ジュウニジ"),
    "零時" to (1557690 to "レイジ"),
)

// Duration readings for numeral+分/日/月 homographs, applied only before a temporal anchor: (WordId, ReadingIndex).
private val TemporalDurationPins: Map<String, Pair<Int, Int>> = hashMapOf(
    "一分" to (1166290 to 1),
    "１分" to (1166290 to 0),
    "十分" to (1335070 to 1),
    "１０分" to (1335070 to 0),
    "五分" to (2039350 to 1),
    "５分" to (2039350 to 0),
    "二分" to (2219180 to 1),
    "２分" to (2219180 to 0),
    "三分" to (1814040 to 1),
    "３分" to (1814040 to 0),
    "四分" to (2863218 to 1),
    "４分" to (2863218 to 0),
    "六分" to (2056150 to 1),
    "６分" to (2056150 to 0),
    "七分" to (2864067 to 1),
    "７分" to (2864067 to 0),
    "何分" to (1189320 to 0),
    "三十日" to (1300670 to 1),
    "一月" to (1162130 to 0),
)

private val TemporalAnchorTexts: Set<String> =
    hashSetOf("前", "後", "間", "ぐらい", "くらい", "ほど", "経つ", "経った", "経って", "待っ")

/** Jiten.Parser/Scoring/AdjacentWordScorer.cs IsNumericSurface (private copy until Scoring is ported). */
private fun isNumericSurface(text: String?): Boolean {
    if (text.isNullOrEmpty()) return false
    var i = if (text[0] == '第' || text[0] == '何' || text[0] == '数') 1 else 0
    if (i == text.length) return text[0] == '何' || text[0] == '数'
    while (i < text.length) {
        if (!JapaneseTextHelper.isNumeralChar(text[i])) return false
        i++
    }
    return true
}

internal fun applyCounterPins(wordInfos: MutableList<WordInfo>, i: Int) {
    val word = wordInfos[i]

    if (word.text == "つ" && word.partOfSpeech == PartOfSpeech.Suffix) word.partOfSpeech = PartOfSpeech.Counter

    if (word.partOfSpeech == PartOfSpeech.Suffix && word.hasPartOfSpeechSection(PartOfSpeechSection.Counter))
        word.partOfSpeech = PartOfSpeech.Counter

    // 人 after a numeral is にん; both じん and にん fit a Suffix tag (６５億人) and scoring picks じん.
    if (word.text == "人" && word.partOfSpeech == PartOfSpeech.Suffix && i > 0 && isNumeralToken(wordInfos[i - 1]))
        word.partOfSpeech = PartOfSpeech.Counter

    // 度 after a numeral is the counter ど (１０度), never たび.
    if (word.text == "度" && word.preMatchedWordId == null && i > 0 && isNumericSurface(wordInfos[i - 1].text)) {
        word.preMatchedWordId = 1445160
        word.preMatchedReadingIndex = 0
    }

    // Suffix 色 is いろ (空色) except after a numeral (三色); しょく compounds (特色) are matched whole.
    if (word.text == "色" && word.partOfSpeech == PartOfSpeech.Suffix && !(i > 0 && isNumeralToken(wordInfos[i - 1])))
        word.partOfSpeech = PartOfSpeech.Noun

    // Sudachi tags いつ as numeral 五 before a counter (いつ匹); standalone いつ is 何時, as 五 lives only in compounds (五日).
    if (word.text == "いつ" && word.partOfSpeech == PartOfSpeech.Noun &&
        word.partOfSpeechSection1 == PartOfSpeechSection.Numeral)
        word.partOfSpeech = PartOfSpeech.Pronoun

    // A lone counter 重 before a noun is the prefix じゅう "heavy" (重戦車); real counters stay compounds (二重).
    // Stays code: depends on the Suffix→Counter reassignment above, which a Cleanup rewrite rule would precede.
    if (word.text == "重" && word.partOfSpeech == PartOfSpeech.Counter && i + 1 < wordInfos.size &&
        (wordInfos[i + 1].partOfSpeech == PartOfSpeech.Noun || wordInfos[i + 1].partOfSpeech == PartOfSpeech.CommonNoun)) {
        word.partOfSpeech = PartOfSpeech.Prefix
        word.reading = "ジュウ"
        word.dictionaryForm = "重"
        word.normalizedForm = "重"
        word.preMatchedWordId = 2108240
    }

    // Kana め after a counted number (２回め) is ordinal 目, which the suffix filter drops; 奴/め follows plain nouns (馬鹿め).
    if (word.text == "め" && word.partOfSpeech == PartOfSpeech.Suffix && i > 0) {
        fun startsWithNumber(t: String) = t.isNotEmpty() && JapaneseTextHelper.isNumeralChar(t[0])
        fun isNumeral(w: WordInfo) =
            isNumeralToken(w) || w.hasPartOfSpeechSection(PartOfSpeechSection.Amount) || startsWithNumber(w.text)

        val prevCtr = wordInfos[i - 1]
        val prevIsCounterLike = prevCtr.partOfSpeech == PartOfSpeech.Counter ||
            prevCtr.hasPartOfSpeechSection(PartOfSpeechSection.Counter) ||
            prevCtr.hasPartOfSpeechSection(PartOfSpeechSection.PossibleCounterWord)
        // CombineAmounts may fuse the number in (２回|め) and drop the counter tag, or leave it separate (３|機|め).
        val fusedAmount = prevCtr.partOfSpeech in setOf(PartOfSpeech.Noun, PartOfSpeech.CommonNoun, PartOfSpeech.Counter, PartOfSpeech.Numeral) &&
            startsWithNumber(prevCtr.text) && prevCtr.text.length > 1
        val counterAfterNumber = prevIsCounterLike && i > 1 && isNumeral(wordInfos[i - 2])
        if (fusedAmount || counterAfterNumber) pinOrdinalMe(word)
    }

    // 目 after a 〜つ counter (三つ目) is ordinal "-th", not the compound noun "three-eyed being".
    if (word.text == "目" && word.partOfSpeech == PartOfSpeech.Suffix && i > 0 &&
        wordInfos[i - 1].text.endsWith("つ") && wordInfos[i - 1].text.length > 1 &&
        takesOrdinalMeAfterTsu(wordInfos[i - 1].text[0]))
        pinOrdinalMe(word)
}

/** C#'s FixNumeralReadings(wordInfos, ref int i): returns the new i (moved when a numeral token is merged or split out). */
internal fun MorphologicalAnalyser.fixNumeralReadings(wordInfos: MutableList<WordInfo>, index: Int): Int {
    var i = index
    val word = wordInfos[i]

    // 後 before a numeral or quantity adverb is アト "more" (後何年, 後少し).
    if (word.text == "後" && word.reading == "ゴ" && i + 1 < wordInfos.size &&
        (isNumeralToken(wordInfos[i + 1]) || wordInfos[i + 1].text.startsWith("もう") ||
            wordInfos[i + 1].text in setOf("少し", "すこし", "ちょっと", "わずか")))
        word.reading = "アト"

    // 項 after a numeral is the counter こう (第２項), not うなじ "nape".
    if (word.text == "項" && word.reading == "コウ" && i > 0 && isNumeralToken(wordInfos[i - 1]))
        word.preMatchedWordId = 1282980

    // 体 between 何/a numeral and 目 is the counter たい (何体目), not からだ.
    if (word.text == "体" && word.reading == "タイ" && i > 0 && i + 1 < wordInfos.size &&
        (wordInfos[i - 1].text == "何" || isNumeralToken(wordInfos[i - 1])) && wordInfos[i + 1].text == "目")
        word.preMatchedWordId = 1409150

    // Numeral+分/日/月 before a temporal anchor is a duration (十分後 = じっぷん); punctuation blocks it (十分、間を置いて).
    if (word.preMatchedWordId == null &&
        (TemporalDurationPins.containsKey(word.text) || word.text == "分" || word.text == "日" || word.text == "月")) {
        var anchorIdx = i + 1
        while (anchorIdx < wordInfos.size && wordInfos[anchorIdx].partOfSpeech == PartOfSpeech.BlankSpace) anchorIdx++
        val anchored = anchorIdx < wordInfos.size &&
            (wordInfos[anchorIdx].text in TemporalAnchorTexts ||
                wordInfos[anchorIdx].text.startsWith("待っ") || wordInfos[anchorIdx].text.startsWith("経っ"))
        if (anchored) {
            val durationPin = TemporalDurationPins[word.text]
            if (durationPin != null) {
                word.preMatchedWordId = durationPin.first
                word.preMatchedReadingIndex = durationPin.second
            } else if ((word.text == "分" || word.text == "日" || word.text == "月") && i > 0) {
                val pairPin = TemporalDurationPins[wordInfos[i - 1].text + word.text]
                if (pairPin != null) {
                    absorbPreviousNumeral(wordInfos, i, wordInfos[i - 1].reading + word.reading, pairPin.first)
                    word.preMatchedReadingIndex = pairPin.second
                    i--
                }
            }
        }
    }

    // Numeral + 時 merges here; split, the recombiner steals digits (十二|時 → 十 + 二時) and 四+時 reads シジ.
    if (word.text == "時" && word.reading == "ジ" && word.preMatchedWordId == null && i > 0) {
        val hourPin = HourPins[wordInfos[i - 1].text + "時"]
        if (hourPin != null) {
            absorbPreviousNumeral(wordInfos, i, hourPin.second, hourPin.first)
            i--
        }
    }

    // In N分のN (四分の一) 分 is the part-noun ぶん, not minutes; frames that are entries (三分の一) stay whole.
    if (word.preMatchedWordId == null && word.text.length >= 2 && word.text.endsWith('分') &&
        word.text.dropLast(1).all(JapaneseTextHelper::isNumeralChar) &&
        i + 2 < wordInfos.size && wordInfos[i + 1].text == "の" &&
        wordInfos[i + 2].text.isNotEmpty() && JapaneseTextHelper.isNumeralChar(wordInfos[i + 2].text[0]) &&
        hasNonNameCompoundLookup?.invoke(word.text + "の" + wordInfos[i + 2].text) != true) {
        val numeralText = word.text.dropLast(1)
        val reading = word.reading
        val numeralReading = when {
            reading.endsWith("ブン") -> reading.dropLast(2)
            reading.endsWith("プン") || reading.endsWith("フン") -> reading.dropLast(2)
            else -> ""
        }
        val numeral = word.copy().apply {
            text = numeralText
            dictionaryForm = numeralText
            normalizedForm = numeralText
            this.reading = numeralReading
            endOffset = if (word.startOffset >= 0) word.startOffset + numeralText.length else -1
            partOfSpeech = PartOfSpeech.Noun
        }
        word.text = "分"
        word.dictionaryForm = "分"
        word.normalizedForm = "分"
        word.startOffset = numeral.endOffset
        pinFractionBun(word)
        wordInfos.add(i, numeral)
        i++
    }

    // Gated on the frame, not the reading: Sudachi reads a split 分 inconsistently (フン in 七|分|の|一).
    if (word.text == "分" && word.preMatchedWordId == null && i > 0 && i + 2 < wordInfos.size &&
        wordInfos[i - 1].text.isNotEmpty() && wordInfos[i - 1].text.all(JapaneseTextHelper::isNumeralChar) &&
        wordInfos[i + 1].text == "の" &&
        wordInfos[i + 2].text.isNotEmpty() && JapaneseTextHelper.isNumeralChar(wordInfos[i + 2].text[0]) &&
        hasNonNameCompoundLookup?.invoke(wordInfos[i - 1].text + "分の" + wordInfos[i + 2].text) != true)
        pinFractionBun(word)

    return i
}

private fun pinOrdinalMe(word: WordInfo) {
    word.dictionaryForm = "目"
    word.normalizedForm = "目"
    word.preMatchedWordId = 1604890
    word.preMatchedReadingIndex = 0
    word.hardPinned = true
}

private fun pinFractionBun(word: WordInfo) {
    word.reading = "ブン"
    word.preMatchedWordId = 1502860
    word.hardPinned = true
}

/** The caller must step i back. */
private fun absorbPreviousNumeral(wordInfos: MutableList<WordInfo>, i: Int, reading: String, wordId: Int) {
    val word = wordInfos[i]
    val numeral = wordInfos[i - 1]
    word.text = numeral.text + word.text
    word.reading = reading
    word.startOffset = numeral.startOffset
    word.partOfSpeech = PartOfSpeech.Noun
    word.dictionaryForm = word.text
    word.normalizedForm = word.text
    word.preMatchedWordId = wordId
    wordInfos.removeAt(i - 1)
}

internal fun isNumeralToken(w: WordInfo): Boolean =
    w.partOfSpeech == PartOfSpeech.Numeral || w.hasPartOfSpeechSection(PartOfSpeechSection.Numeral)

// 一つ目/二つ目 have their own ordinal entries.
internal fun takesOrdinalMeAfterTsu(c: Char): Boolean =
    c != '一' && c != '二' && c != '１' && c != '２' && JapaneseTextHelper.isNumeralChar(c)
