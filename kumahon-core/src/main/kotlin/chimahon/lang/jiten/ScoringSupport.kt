// Ported from Jiten (github.com/Sirush/Jiten, Apache-2.0, commit 44b48a4): Jiten.Parser/Scoring/AdjacentWordScorer.cs,
// FuriganaHintScorer.cs, PriorityOverrides.cs, ScoringPolicy.cs, WordFrequencyPriors.cs, RubyPriorsScorer.cs (no-op)
package chimahon.lang.jiten

internal object AdjacentWordScorer {
    /** C# uint masks are Int, the ulong rule mask is Long (Grammar.kt). */
    data class AdjacentContext(
        val prevMask: Int,
        val hasPrev: Boolean,
        val prevText: String?,
        val nextMask: Int,
        val hasNext: Boolean,
        val nextText: String?,
        val applicableRuleMask: Long,
    ) {
        companion object {
            fun create(prevPOS: List<PartOfSpeech>?, prevText: String?, nextPOS: List<PartOfSpeech>?, nextText: String?): AdjacentContext {
                var prevMask = if (prevPOS != null) PosMask.fromList(prevPOS) else 0
                val hasPrev = prevPOS != null
                val nextMask = if (nextPOS != null) PosMask.fromList(nextPOS) else 0
                val hasNext = nextPOS != null

                // JMDict tags ordinals as plain nouns (第二 [n]), but counters after them read as after a numeral (第二話 = だいにわ).
                if (hasPrev && isNumericSurface(prevText)) prevMask = prevMask or PosMask.Numeral

                // Soft-rule ContextMatch depends only on context, so it is computed once per token, not per candidate.
                val applicable = TransitionRuleEngine.computeContextApplicableMask(prevMask, hasPrev, prevText, nextMask, hasNext, nextText)
                return AdjacentContext(prevMask, hasPrev, prevText, nextMask, hasNext, nextText, applicable)
            }
        }
    }

    /** Digits of any script, optionally opened by 第 or the quantity interrogative/approximator 何 or 数 (何話, 数分). */
    fun isNumericSurface(text: String?): Boolean {
        if (text.isNullOrEmpty()) return false
        var i = if (text[0] == '第' || text[0] == '何' || text[0] == '数') 1 else 0
        if (i == text.length) return text[0] == '何' || text[0] == '数'
        while (i < text.length) {
            if (!JapaneseTextHelper.isNumeralChar(text[i])) return false
            i++
        }
        return true
    }

    fun calculateContextBonus(candidate: FormCandidate, context: AdjacentContext, rulesMatched: MutableList<String>? = null): Int {
        val window = ScoringWindow(candidate, context.prevMask, context.hasPrev, context.prevText, context.nextMask,
            context.hasNext, context.nextText)
        return TransitionRuleEngine.evaluateSoftRules(window, context.applicableRuleMask, rulesMatched)
    }
}

internal object FuriganaHintScorer {
    fun score(candidate: FormCandidate, hintHiragana: String?): Int {
        if (hintHiragana == null) return 0
        for (form in candidate.word.forms) {
            if (form.formType != JmDictFormType.KanaForm) continue
            if (KanaScoringHelpers.toNormalizedHiragana(form.text, convertLongVowelMark = false) == hintHiragana) return 500
        }
        return 0
    }
}

/** Adds a "jiten" priority tag to JMdict words or single forms to win homograph ties (applied by JitenParser.getWord). */
internal object PriorityOverrides {
    private val WordLevelJitenIds = hashSetOf(
        1204860, // 各 (かく): beats 各々 おのおの by 1pt
        1300520, // ３時 (さんじ): beats 三次, whose ３時 form wins by ruby priors
        1545300, // 妖怪: beats 溶解, whose NormalizedForm bonus inflates its score
        1922120, // 兼ねない: standalone beats conjugated 兼ねる
        2579880, // コホン (ahem): beats 古本 こほん
        1571330, // 舐る (ねぶる): beats 眠る's rare ねぶる reading
        1709300, // 数度 (すうど): beats the surname すどう that Sudachi guesses
        1467400, // 忍び: beats the fern しのぶ (2179930) on bare 忍, whose ruby priors come from the name Shinobu
    )

    private val FormLevelJitenIds = hashSetOf(
        WordForm(1168660, 4), // 依る よる: beats 寄る
        WordForm(1313580, 2), // 事 こと: beats 琴
        WordForm(1495740, 2), // 付く つく: beats 点く
        WordForm(1508300, 2), // 柄 ガラ: exempts it from the short-kana gate
        WordForm(1593500, 2), // 轟々 ごうごう: beats 囂々, avoiding a margin=0 reseg
        WordForm(2013900, 4), // 赤 あか: beats 垢 and 銅
        WordForm(1529560, 1), // 無し なし: beats 梨 on the casual katakana negation ナシ
    )

    fun apply(word: JmDictWord) {
        if (word.wordId in WordLevelJitenIds) {
            val pri = word.priorities ?: emptyList()
            if ("jiten" !in pri) word.priorities = pri + "jiten"
        }
        for (form in word.forms) {
            if (WordForm(word.wordId, form.readingIndex) !in FormLevelJitenIds) continue
            val pri = form.priorities ?: emptyList()
            if ("jiten" !in pri) form.priorities = pri + "jiten"
        }
    }

    fun applyAll(words: Iterable<JmDictWord>) = words.forEach(::apply)
}

internal object ScoringPolicy {
    const val PosIncompatiblePenalty = 15
    const val LowConfidenceThreshold = 15
    const val HighConfidenceThreshold = 40

    fun isHighConfidence(margin: Int?) = margin != null && margin >= HighConfidenceThreshold
    fun isLowConfidence(margin: Int?) = margin != null && margin < LowConfidenceThreshold

    fun effectiveScore(c: FormCandidate) = c.totalScore - if (c.isPosIncompatibleDirectSurface) PosIncompatiblePenalty else 0
}

/**
 * A gate, not a reranker: deck-derived frequencies flip a kana homograph only at ≥10× (カイロ: 懐炉 over Cairo).
 * Jiten fills [current] from its decks' observed word frequencies (not public); null = skip, as Jiten does without them.
 */
internal object WordFrequencyPriors {
    @Volatile var current: Map<Int, Double>? = null

    private const val MinRatio = 10.0
    private const val MinChallengerFrequency = 1e-6
    private const val MaxScoreGap = 20

    fun apply(best: FormCandidate, allCandidates: List<FormCandidate>, context: FormScoringContext): FormCandidate? {
        val frequencies = current ?: return null

        // Kanji homographs (主 ぬし/しゅ) are reading ambiguities owned by the ruby priors, not word frequency.
        if (!context.isKanaSurface) return null

        // Conjugated or fold-matched picks carry morphological evidence a word-level prior can't outweigh.
        if (context.surface != best.form.text || !best.deconjForm?.process.isNullOrEmpty()) return null

        // An unobserved best is often a rare but correct mimetic, not evidence of a misparse.
        val bestFrequency = frequencies[best.word.wordId] ?: return null

        if (KanaScoringHelpers.isInflectableVerbOrAdj(best.word.partsOfSpeech)) return null

        val bestEffective = ScoringPolicy.effectiveScore(best)

        var challenger: FormCandidate? = null
        var challengerFrequency = 0.0

        for (candidate in allCandidates) {
            if (candidate.word.wordId == best.word.wordId) continue
            if (context.surface != candidate.form.text) continue
            if (!candidate.deconjForm?.process.isNullOrEmpty()) continue
            if (candidate.isPosIncompatibleDirectSurface && !best.isPosIncompatibleDirectSurface) continue

            val frequency = frequencies[candidate.word.wordId] ?: continue
            if (frequency < MinChallengerFrequency || frequency < bestFrequency * MinRatio) continue

            // A clear scorer preference stands (封 must not steal ふう from 風 across a 27-point gap).
            if (bestEffective - ScoringPolicy.effectiveScore(candidate) > MaxScoreGap) continue

            if (KanaScoringHelpers.isInflectableVerbOrAdj(candidate.word.partsOfSpeech)) continue

            // Bound morphemes (がち, counters, particles) owe their frequency to attached usage, not standalone tokens.
            if (hasBoundMorphemeTag(candidate.word)) continue

            // The prior only transfers via the word's primary kana reading (風 かぜ must not steal ふう).
            if (!matchesPrimaryKanaForm(candidate)) continue

            if (challenger == null || frequency > challengerFrequency ||
                (frequency == challengerFrequency && candidate.totalScore > challenger.totalScore)) {
                challenger = candidate
                challengerFrequency = frequency
            }
        }
        return challenger
    }

    private fun hasBoundMorphemeTag(word: JmDictWord) = word.partsOfSpeech.any {
        it == "suf" || it == "n-suf" || it == "pref" || it == "n-pref" || it == "aux" || it == "aux-v" || it == "aux-adj" ||
            it == "prt" || it == "ctr"
    }

    private fun matchesPrimaryKanaForm(candidate: FormCandidate): Boolean {
        for (form in candidate.word.forms) {
            if (form.formType != JmDictFormType.KanaForm) continue
            return KanaScoringHelpers.toNormalizedHiragana(form.text, convertLongVowelMark = false) == candidate.formTextHiragana
        }
        return false
    }
}

/**
 * Jiten's RubyPriorsScorer/RubyReadingPriors score kanji readings from a 30 MB corpus table
 * (Shared/resources/ruby_reading_priors.msgpack). Not ported: every score is 0, as in Jiten when the file is missing
 * (RubyReadingPriors.Current == null). ponytail: port it (and ship the table) if kanji reading picks need it.
 */
internal object RubyPriorsScorer {
    @Suppress("UNUSED_PARAMETER")
    fun score(candidate: FormCandidate, context: FormScoringContext): Int = 0

    @Suppress("UNUSED_PARAMETER")
    fun scoreWithContext(
        candidate: FormCandidate, context: FormScoringContext, leftDictForm: String?, rightDictForm: String?,
        left2DictForm: String? = null, right2DictForm: String? = null,
    ): Int = 0
}
