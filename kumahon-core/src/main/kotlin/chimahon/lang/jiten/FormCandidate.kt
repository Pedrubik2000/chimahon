// Ported from Jiten (github.com/Sirush/Jiten, Apache-2.0, commit 44b48a4): Jiten.Parser/Scoring/FormCandidate.cs,
// FormCandidateFactory.cs
package chimahon.lang.jiten

/** C# byte readingIndex is Int here. */
internal class FormCandidate(
    val word: JmDictWord,
    val form: JmDictWordForm,
    val readingIndex: Int,
    val targetHiragana: String,
    val deconjForm: DeconjugationForm? = null,
) {
    val formTextHiragana: String = KanaScoringHelpers.toNormalizedHiragana(form.text, convertLongVowelMark = false)

    val cachedReadingPos: Set<String> by lazy { ReadingPosHelper.getPosForReading(word, readingIndex) }

    /** Reading-restricted POS when available, else word-level POS; the set to check for grammatical class. */
    val effectivePos: Collection<String> get() = if (cachedReadingPos.isNotEmpty()) cachedReadingPos else word.partsOfSpeech

    /** RubyReadingPriors.Current?.GetKanaReading: the ruby priors aren't ported (RubyPriorsScorer), so always null. */
    val rubyReading: String? get() = null

    var scoreTrace: FormScoreTrace = FormScoreTrace()
        private set
    var isPosIncompatibleDirectSurface: Boolean = false

    val totalScore get() = scoreTrace.totalScore
    val wordScore get() = scoreTrace.wordScore
    val entryPriorityScore get() = scoreTrace.entryPriorityScore
    val formPriorityScore get() = scoreTrace.formPriorityScore
    val formFlagScore get() = scoreTrace.formFlagScore
    val surfaceMatchScore get() = scoreTrace.surfaceMatchScore
    val scriptScore get() = scoreTrace.scriptScore
    val readingMatchScore get() = scoreTrace.readingMatchScore
    val posAffinityScore get() = scoreTrace.posAffinityScore
    val rubyPriorsScore get() = scoreTrace.rubyPriorsScore

    fun setScoreTrace(trace: FormScoreTrace) {
        scoreTrace = trace
    }

    override fun toString() = "FormCandidate(${word.wordId}/$readingIndex ${form.text} $totalScore)"
}

internal data class FormScoringContext(
    val surface: String,
    val dictionaryForm: String?,
    val normalizedForm: String?,
    val isNameContext: Boolean,
    val sudachiReading: String?,
    val isKanaSurface: Boolean,
    val surfaceHiragana: String,
    val surfaceHiraganaLoose: String,
    val dictionaryFormHiragana: String?,
    val normalizedFormHiragana: String?,
    val sudachiPOS: PartOfSpeech = PartOfSpeech.Unknown,
    val isArchaicSentence: Boolean = false,
    val isSentenceInitial: Boolean = false,
    val isSentenceFinal: Boolean = false,
    val isSudachiPossibleDependant: Boolean = false,
    val isSudachiNameGuess: Boolean = false,
) {
    companion object {
        fun create(
            surface: String,
            dictionaryForm: String?,
            normalizedForm: String?,
            isNameContext: Boolean,
            sudachiReading: String?,
            isArchaicSentence: Boolean = false,
            isSentenceInitial: Boolean = false,
            isSentenceFinal: Boolean = false,
            sudachiPOS: PartOfSpeech = PartOfSpeech.Unknown,
            isSudachiPossibleDependant: Boolean = false,
            isSudachiNameGuess: Boolean = false,
        ) = FormScoringContext(
            surface, dictionaryForm, normalizedForm, isNameContext, sudachiReading,
            JapaneseTextHelper.isAllKana(surface),
            KanaScoringHelpers.toNormalizedHiragana(surface, convertLongVowelMark = false),
            KanaScoringHelpers.toNormalizedHiragana(surface, convertLongVowelMark = true),
            if (!dictionaryForm.isNullOrEmpty()) KanaScoringHelpers.toNormalizedHiragana(dictionaryForm, false) else null,
            if (!normalizedForm.isNullOrEmpty()) KanaScoringHelpers.toNormalizedHiragana(normalizedForm, false) else null,
            sudachiPOS, isArchaicSentence, isSentenceInitial, isSentenceFinal, isSudachiPossibleDependant, isSudachiNameGuess,
        )
    }
}

internal data class CandidateSelectionResult(val best: FormCandidate?, val marginToSecond: Int?) {
    val isLowConfidence get() = marginToSecond != null && marginToSecond < ScoringPolicy.LowConfidenceThreshold
    val isMediumConfidence get() = marginToSecond != null &&
        marginToSecond >= ScoringPolicy.LowConfidenceThreshold && marginToSecond < ScoringPolicy.HighConfidenceThreshold
    val isHighConfidence get() = marginToSecond != null && marginToSecond >= ScoringPolicy.HighConfidenceThreshold
}

internal data class FormScoreTrace(
    val wordScore: Int = 0,
    val entryPriorityScore: Int = 0,
    val formPriorityScore: Int = 0,
    val formFlagScore: Int = 0,
    val surfaceMatchScore: Int = 0,
    val scriptScore: Int = 0,
    val readingMatchScore: Int = 0,
    val posAffinityScore: Int = 0,
    val identityPenaltyApplied: Boolean = false,
    val rubyPriorsScore: Int = 0,
) {
    val totalScore: Int
        get() = wordScore + entryPriorityScore + formPriorityScore + formFlagScore + surfaceMatchScore + scriptScore +
            readingMatchScore + posAffinityScore + rubyPriorsScore
}

internal object FormCandidateFactory {
    fun enumerateCandidateForms(
        word: JmDictWord,
        targetHiragana: String,
        allowLooseLvmMatch: Boolean,
        deconjForm: DeconjugationForm? = null,
        surface: String? = null,
    ): List<FormCandidate> {
        var candidates = ArrayList<FormCandidate>()
        val targetNormalized = KanaNormalizer.normalize(targetHiragana)
        val targetLoose = if (allowLooseLvmMatch) KanaNormalizer.normalize(KanaConverter.toHiragana(targetHiragana)) else null
        val targetStripped = if (allowLooseLvmMatch && 'ー' in targetHiragana) targetHiragana.replace("ー", "") else null

        for (form in word.forms) {
            if (form.readingIndex > 255) continue

            val formHiragana = KanaConverter.toHiragana(form.text, convertLongVowelMark = false)
            val formNormalized = KanaNormalizer.normalize(formHiragana)

            var phoneticMatch = formNormalized == targetNormalized

            if (!phoneticMatch && allowLooseLvmMatch) {
                val formLoose = KanaNormalizer.normalize(KanaConverter.toHiragana(form.text))
                phoneticMatch = formLoose == targetLoose

                if (!phoneticMatch && targetStripped != null) {
                    val formStripped = formHiragana.replace("ー", "")
                    phoneticMatch = targetStripped.isNotEmpty() && targetStripped == formStripped
                }
            }

            if (!phoneticMatch) continue

            candidates.add(FormCandidate(word, form, form.readingIndex, targetHiragana, deconjForm))
        }

        // A Latin surface must not reach a kanji word via a coincidental reading (ＭＡＯ→まお→苧); dropping beats mis-tagging.
        // Latin loanwords (ＣＤ) survive: JMDict files their Latin headword as a kanji form, but it holds no CJK ideograph.
        if (candidates.isNotEmpty() && surface != null && JapaneseTextHelper.isAllLatin(surface) &&
            word.forms.any { KanaScoringHelpers.containsKanji(it.text) })
            return emptyList()

        // Search-only/obsolete forms drop only when an alternative survives; a form the surface spells exactly stays.
        if (candidates.size <= 1) return candidates

        val nonSearchOnly = candidates.filterTo(ArrayList()) { !it.form.isSearchOnly || it.form.text == surface }
        if (nonSearchOnly.isNotEmpty()) candidates = nonSearchOnly

        val nonObsolete = candidates.filterTo(ArrayList()) { !it.form.isObsolete || it.form.text == surface }
        if (nonObsolete.isNotEmpty()) candidates = nonObsolete

        return candidates
    }

    fun hasKanaReadingMatch(word: JmDictWord, sudachiReading: String, allowStemMatch: Boolean = false): Boolean {
        val readingHiragana = KanaConverter.toHiragana(sudachiReading, convertLongVowelMark = false)
        return word.forms.filter { it.formType == JmDictFormType.KanaForm }.any { f ->
            val formHiragana = KanaConverter.toHiragana(f.text, convertLongVowelMark = false)
            formHiragana == readingHiragana || (allowStemMatch && formHiragana.startsWith(readingHiragana))
        }
    }

    fun isSuruNounWithoutExpression(posTags: List<String>): Boolean {
        var hasNoun = false
        var hasSuru = false
        var hasExpression = false
        for (tag in posTags) {
            when (tag) {
                "n", "n-adv", "n-t" -> hasNoun = true
                "vs", "vs-s", "vs-i", "vs-c" -> hasSuru = true
                "exp" -> hasExpression = true
            }
        }
        return hasNoun && hasSuru && !hasExpression
    }
}
