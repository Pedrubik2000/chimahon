// Ported from Jiten (github.com/Sirush/Jiten, Apache-2.0, commit 44b48a4): Jiten.Parser/Stages/TokenStage.cs
package chimahon.lang.jiten

internal enum class TokenStageGroup { Split, Repair, Combine, Cleanup, Disambiguation }

/** C#'s [Flags] uint enum as Int bit constants (bit 31 makes TextKaratte negative; only bit tests matter). */
internal object TokenFeatures {
    const val None = 0
    const val Prefix = 1 shl 0
    const val Suffix = 1 shl 1
    const val Auxiliary = 1 shl 2
    const val Interjection = 1 shl 3
    const val AuxVerbStem = 1 shl 4
    const val ConjParticle = 1 shl 5
    const val NumericAmount = 1 shl 6
    const val AdvParticle = 1 shl 7
    const val Dependant = 1 shl 8
    const val VerbLike = 1 shl 9
    const val LongVowelMark = 1 shl 10
    const val EndsWithTsu = 1 shl 11
    const val TextTanSuffix = 1 shl 12
    const val TextTanka = 1 shl 13
    const val TextHasa = 1 shl 14
    const val TextTawake = 1 shl 15
    const val TextTatte = 1 shl 16
    const val TextRan = 1 shl 17
    const val InflectableBase = 1 shl 18
    const val OovGarbage = 1 shl 19
    const val TextSakki = 1 shl 20
    const val KanaRepetition = 1 shl 21
    const val DictKiru = 1 shl 22
    const val HiraganaOovBlob = 1 shl 23
    const val AdverbEndsTo = 1 shl 24
    const val TextTobashi = 1 shl 25
    const val VerbKaeru = 1 shl 26
    const val GeminateSuffixShape = 1 shl 27
    const val KatakanaRun = 1 shl 28
    const val CompoundBoundaryShape = 1 shl 29
    const val SingleKanjiNoun = 1 shl 30
    const val TextKaratte = 1 shl 31
}

/** A pipeline stage; candidate stages get the positions the feature scan found for [requiredFeatures]. */
internal class TokenStage(
    val name: String,
    val group: TokenStageGroup,
    private val process: (MutableList<WordInfo>) -> MutableList<WordInfo>,
    val requiredFeatures: Int = TokenFeatures.None,
    private val candidateProcess: ((MutableList<WordInfo>, List<Int>) -> MutableList<WordInfo>)? = null,
) {
    val usesCandidatePositions: Boolean get() = candidateProcess != null

    fun apply(input: MutableList<WordInfo>, scan: TokenFeatureScan? = null): MutableList<WordInfo> =
        if (candidateProcess == null) process(input)
        else candidateProcess.invoke(input, (scan ?: TokenFeatureScanner.scanWithCandidates(input)).candidates(requiredFeatures))
}

internal class TokenFeatureScan(val features: Int, private val candidates: Map<Int, List<Int>>) {
    fun candidates(feature: Int): List<Int> = candidates[feature] ?: emptyList()
}

internal object TokenFeatureScanner {
    fun scan(tokens: List<WordInfo>): Int = scanCore(tokens, null)

    fun scanWithCandidates(tokens: List<WordInfo>): TokenFeatureScan {
        val candidates = HashMap<Int, MutableList<Int>>(4)
        return TokenFeatureScan(scanCore(tokens, candidates), candidates)
    }

    private fun isKatakanaRun(text: String) = text.all(JapaneseTextHelper::isKatakanaWordChar)

    private fun scanCore(tokens: List<WordInfo>, candidates: MutableMap<Int, MutableList<Int>>?): Int {
        var f = TokenFeatures.None
        var prevText = ""

        fun addCandidate(feature: Int, position: Int) {
            f = f or feature
            if (candidates == null) return
            val positions = candidates.getOrPut(feature) { ArrayList() }
            if (positions.isEmpty() || positions.last() != position) positions.add(position)
        }

        for ((index, w) in tokens.withIndex()) {
            when (w.partOfSpeech) {
                PartOfSpeech.Prefix -> f = f or TokenFeatures.Prefix
                PartOfSpeech.Suffix -> f = f or TokenFeatures.Suffix
                PartOfSpeech.Auxiliary -> f = f or TokenFeatures.Auxiliary
                PartOfSpeech.Interjection -> f = f or TokenFeatures.Interjection
                PartOfSpeech.Verb, PartOfSpeech.IAdjective, PartOfSpeech.NaAdjective -> f = f or TokenFeatures.InflectableBase
                else -> {}
            }
            f = f or sectionFeature(w.partOfSpeechSection1) or sectionFeature(w.partOfSpeechSection2) or
                sectionFeature(w.partOfSpeechSection3)

            val text = w.text
            if ('ー' in text) f = f or TokenFeatures.LongVowelMark
            // ッ included: RepairClippedAdjective accepts a katakana sokuon token too
            if (text.isNotEmpty() && (text.last() == 'っ' || text.last() == 'ッ')) f = f or TokenFeatures.EndsWithTsu

            // Only candidate stages need positions; plain rescans skip these string walks
            if (candidates != null) {
                if (text.endsWith('っ') || text.endsWith("っぱ") || text.endsWith("っぷ") ||
                    (text.length > 3 && text.endsWith("っぷり")))
                    addCandidate(TokenFeatures.GeminateSuffixShape, index)
                if (text.isNotEmpty() && isKatakanaRun(text)) addCandidate(TokenFeatures.KatakanaRun, index)
                if ((text.length == 2 || text.length == 3) && text.last() in "上中内外前後間下先際的") {
                    if (index > 0) addCandidate(TokenFeatures.CompoundBoundaryShape, index - 1)
                }
                if (w.partOfSpeech == PartOfSpeech.Expression && text.length == 3 &&
                    text.substring(0, 2) in setOf("その", "この", "あの", "どの") && JapaneseTextHelper.isKanji(text[2]))
                    addCandidate(TokenFeatures.CompoundBoundaryShape, index)
                if (index > 0 && w.partOfSpeech == PartOfSpeech.Expression && text.length > 2 &&
                    JapaneseTextHelper.isKanji(text[0]) && text.substring(1).any { it == 'を' || it == 'が' || it == 'に' })
                    addCandidate(TokenFeatures.CompoundBoundaryShape, index - 1)
                if (index > 0 && text.length == 1 && JapaneseTextHelper.isKanji(text[0])) {
                    val pt = tokens[index - 1].text
                    if (pt.length == 2 && JapaneseTextHelper.isKanji(pt[0]) && JapaneseTextHelper.isKanji(pt[1]))
                        addCandidate(TokenFeatures.CompoundBoundaryShape, index - 1)
                }
                if (index > 0 && w.partOfSpeech == PartOfSpeech.Verb && text.length >= 2 && text[1] == 'し' &&
                    JapaneseTextHelper.isKanji(text[0])) {
                    val st = tokens[index - 1].text
                    if (st.isNotEmpty() && JapaneseTextHelper.isKanji(st.last()))
                        addCandidate(TokenFeatures.CompoundBoundaryShape, index - 1)
                }
                if (text.length >= 2 && text.last() == 'っ' && index + 1 < tokens.size && tokens[index + 1].text.length >= 3)
                    addCandidate(TokenFeatures.CompoundBoundaryShape, index)
                // A stranded okurigana leaves the stem tagged Noun or Verb (探[Noun]|しっス vs 捜[Verb]|しっ|ス)
                if (text.length == 1 && JapaneseTextHelper.isKanji(text[0]) &&
                    (w.partOfSpeech == PartOfSpeech.Noun || w.partOfSpeech == PartOfSpeech.CommonNoun ||
                        w.partOfSpeech == PartOfSpeech.Verb))
                    addCandidate(TokenFeatures.SingleKanjiNoun, index)
            }
            // って fused inside a token after mora theft (ケン|カって, 寒|さって) also feeds RepairQuotativeTte
            if (text.length >= 3 && (text[0] in 'ぁ'..'ゖ' || text[0] in 'ァ'..'ヺ' || text[0] in '一'..'鿿') && "って" in text)
                f = f or TokenFeatures.EndsWithTsu

            when (text) {
                "たん" -> if (w.partOfSpeech == PartOfSpeech.Suffix) f = f or TokenFeatures.TextTanSuffix
                "たんか" -> if (w.partOfSpeech == PartOfSpeech.Noun) f = f or TokenFeatures.TextTanka
                // たか mis-tokenised as 鷹/高 (言い過ぎ|たか) is past た + か, handled by the same repair stage
                "たか" -> if (w.partOfSpeech == PartOfSpeech.Noun) f = f or TokenFeatures.TextTanka
                "はさ" -> if (w.partOfSpeech == PartOfSpeech.Noun) f = f or TokenFeatures.TextHasa
                "たわけ" -> f = f or TokenFeatures.TextTawake
                "たって", "だって" -> if (w.hasPartOfSpeechSection(PartOfSpeechSection.ConjunctionParticle)) f = f or TokenFeatures.TextTatte
                "だな" -> if (w.partOfSpeech == PartOfSpeech.Noun) f = f or TokenFeatures.TextTatte
                "かって" -> if (w.partOfSpeech == PartOfSpeech.Adverb) f = f or TokenFeatures.TextTatte
                "いたって" -> if (w.partOfSpeech == PartOfSpeech.Adverb) f = f or TokenFeatures.TextTatte
                "らん" -> f = f or TokenFeatures.TextRan
                "からって" -> f = f or TokenFeatures.TextKaratte
                "さっ" -> f = f or TokenFeatures.TextSakki
                // 飛ばし heads the counter-compound 段飛ばし; RepairDanTobashi re-checks the numeral+段 head
                "飛ばし" -> f = f or TokenFeatures.TextTobashi
                // The emphatic prefix ど arrives as Adverb (truncated どう); CombinePrefixes re-checks precisely
                "ど" -> if (w.partOfSpeech == PartOfSpeech.Adverb) f = f or TokenFeatures.Prefix
            }

            if (w.dictionaryForm == "切る") f = f or TokenFeatures.DictKiru
            // Superset: RepairIntensifierKaeru splits only OOV X返る, not entries like 静まり返る
            if (w.partOfSpeech == PartOfSpeech.Verb && w.normalizedForm.endsWith("返る")) f = f or TokenFeatures.VerbKaeru
            if (w.partOfSpeech == PartOfSpeech.Adverb && text.length >= 2 && text.last() == 'と') f = f or TokenFeatures.AdverbEndsTo

            // Cheap superset of CollapseReduplicatedMimetic's 3+ unit runs, alone (ごろごろごろ) or across tokens (ごろごろ|ごろ)
            if ((f and TokenFeatures.KanaRepetition) == 0 && isUnitRepetition(text)) {
                if (text.length >= 6 || (prevText.length >= 2 && isUnitRepetition(prevText) &&
                        prevText[0] == text[0] && prevText[1] == text[1]))
                    f = f or TokenFeatures.KanaRepetition
            }
            prevText = text

            if (w.text.length >= 3 && (w.partOfSpeech == PartOfSpeech.Noun || w.partOfSpeech == PartOfSpeech.CommonNoun ||
                    w.partOfSpeech == PartOfSpeech.Interjection || w.partOfSpeech == PartOfSpeech.Filler) &&
                (f and TokenFeatures.OovGarbage) == 0 && w.text.all { it in '぀'..'ゟ' })
                f = f or TokenFeatures.OovGarbage

            if (w.text.length >= 5 && (w.partOfSpeech == PartOfSpeech.Noun || w.partOfSpeech == PartOfSpeech.CommonNoun) &&
                (f and TokenFeatures.HiraganaOovBlob) == 0 && w.text.all { it in '぀'..'ゟ' || it == 'ー' })
                f = f or TokenFeatures.HiraganaOovBlob
        }
        return f
    }

    /** Kana text repeating its leading 2-char unit (ごろごろ); a cheap superset the consuming stage re-checks. */
    private fun isUnitRepetition(s: String): Boolean {
        if (s.length < 2 || (s.length and 1) != 0) return false
        for (i in s.indices) {
            val c = s[i]
            if (c < '぀' || c > 'ヿ') return false
            if (i >= 2 && c != s[i and 1]) return false
        }
        return true
    }

    private fun sectionFeature(section: PartOfSpeechSection): Int = when (section) {
        PartOfSpeechSection.AuxiliaryVerbStem -> TokenFeatures.AuxVerbStem
        PartOfSpeechSection.ConjunctionParticle -> TokenFeatures.ConjParticle
        PartOfSpeechSection.Amount, PartOfSpeechSection.Numeral -> TokenFeatures.NumericAmount
        PartOfSpeechSection.AdverbialParticle -> TokenFeatures.AdvParticle
        PartOfSpeechSection.Dependant, PartOfSpeechSection.PossibleDependant -> TokenFeatures.Dependant
        PartOfSpeechSection.VerbLike -> TokenFeatures.VerbLike or TokenFeatures.InflectableBase
        PartOfSpeechSection.Suffix -> TokenFeatures.Suffix
        PartOfSpeechSection.PossibleSuru, PartOfSpeechSection.PossibleVerbSuruNoun -> TokenFeatures.InflectableBase
        else -> TokenFeatures.None
    }
}
