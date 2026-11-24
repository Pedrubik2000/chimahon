// Ported from Jiten (github.com/Sirush/Jiten, Apache-2.0, commit 44b48a4): Jiten.Parser/Scoring/FormCandidateSelector.cs
package chimahon.lang.jiten

/** Diagnostics are left out (C#'s optional ParserDiagnostics argument). */
internal object FormCandidateSelector {
    fun pickBestCandidate(allCandidates: MutableList<FormCandidate>, context: FormScoringContext, archaicPosTypes: Set<String>) =
        pickTopCandidates(allCandidates, context, archaicPosTypes).best

    fun pickTopCandidates(
        allCandidates: MutableList<FormCandidate>,
        context: FormScoringContext,
        archaicPosTypes: Set<String>,
    ): CandidateSelectionResult {
        if (context.isKanaSurface)
            allCandidates.removeAll { KanaScoringHelpers.isKanaSurfaceWithNoMatchingReading(context, it.word, it.form.text) }

        // A katakana surface with a script-exact non-name candidate must not fold to kanji/hiragana-only words (フル, not 降る).
        if (KanaScoringHelpers.isPureKatakanaToken(context.surface) &&
            allCandidates.any { c ->
                c.form.text == context.surface && c.word.cachedPOS.any { it != PartOfSpeech.Name && it != PartOfSpeech.Unknown }
            }) {
            allCandidates.removeAll { c ->
                c.form.text != context.surface && c.word.forms.none { KanaScoringHelpers.containsKatakana(it.text) }
            }
        }

        if (allCandidates.isEmpty()) return CandidateSelectionResult(null, null)

        // Applies the -15 POS-incompatible penalty (noun 1197950 vs adj-na 2653620 when Sudachi says NaAdjective).
        fun effectiveScore(c: FormCandidate) = ScoringPolicy.effectiveScore(c)

        var best: FormCandidate? = null
        var bestScore = Int.MIN_VALUE

        for (candidate in allCandidates) {
            candidate.setScoreTrace(FormCandidateScorer.score(candidate, context, archaicPosTypes))

            val score = effectiveScore(candidate)
            if (score > bestScore ||
                (score == bestScore && best != null && !candidate.isPosIncompatibleDirectSurface && best.isPosIncompatibleDirectSurface) ||
                (score == bestScore && best != null && candidate.isPosIncompatibleDirectSurface == best.isPosIncompatibleDirectSurface &&
                    candidate.word.wordId < best.word.wordId) ||
                (score == bestScore && best != null && candidate.word.wordId == best.word.wordId &&
                    hasPreferredConjugation(candidate, best))) {
                bestScore = score
                best = candidate
            }
        }

        best = refineBest(best, allCandidates, context)

        // POS-incompatible runners-up are skipped (unless all are) so their -15 penalty can't fake a low margin.
        var margin: Int? = null
        if (best != null) {
            var hasLegitimate = false
            var bestLegitimateScore = Int.MIN_VALUE
            var bestAnyScore = Int.MIN_VALUE

            for (c in allCandidates) {
                if (c.word.wordId == best.word.wordId) continue
                val s = effectiveScore(c)
                if (!c.isPosIncompatibleDirectSurface) {
                    hasLegitimate = true
                    if (s > bestLegitimateScore) bestLegitimateScore = s
                }
                if (s > bestAnyScore) bestAnyScore = s
            }

            val secondBest = if (hasLegitimate) bestLegitimateScore else bestAnyScore
            if (secondBest > Int.MIN_VALUE) margin = effectiveScore(best) - secondBest
        }

        return CandidateSelectionResult(best, margin)
    }

    /** Order matters: each pass sees the previous pass's pick, and null keeps it. */
    fun refineBest(best: FormCandidate?, allCandidates: List<FormCandidate>, context: FormScoringContext): FormCandidate? {
        var b = best ?: return null
        b = WordFrequencyPriors.apply(b, allCandidates, context) ?: b
        b = applyKanjiHomographPriorityCap(b, allCandidates, context) ?: b
        b = applyArchaicExactSurfaceRescue(b, allCandidates, context) ?: b
        b = applySentenceFinalInterjection(b, allCandidates, context) ?: b
        b = applyJitenMinorKanaReadingCap(b, allCandidates, context) ?: b
        b = applyContractedCopulaExactSurface(b, allCandidates, context) ?: b
        b = applyKanaSurfaceKanjiFormRemap(b, allCandidates, context) ?: b
        return b
    }

    /** A kana surface reached via a kanji NormalizedForm (ちょっとぉ → 一寸) gets its primary kana form. */
    fun applyKanaSurfaceKanjiFormRemap(best: FormCandidate, allCandidates: List<FormCandidate>, context: FormScoringContext): FormCandidate? {
        if (!context.isKanaSurface) return null
        if (best.form.formType != JmDictFormType.KanjiForm) return null

        for (c in allCandidates)
            if (c.word.wordId == best.word.wordId && c.form.formType == JmDictFormType.KanaForm) return null

        var kanaForm: JmDictWordForm? = null
        for (f in best.word.forms) {
            if (f.formType != JmDictFormType.KanaForm || f.readingIndex > 255) continue
            if (f.isSearchOnly || f.isObsolete) continue
            if (kanaForm == null || f.readingIndex < kanaForm.readingIndex) kanaForm = f
        }
        if (kanaForm == null) return null

        val remapped = FormCandidate(best.word, kanaForm, kanaForm.readingIndex, best.targetHiragana, best.deconjForm)
        remapped.setScoreTrace(best.scoreTrace)
        return remapped
    }

    /** Contracted copula じゃ reached via だ (2089020) prefers a direct-surface copula entry (じゃ 2851029). */
    fun applyContractedCopulaExactSurface(best: FormCandidate, allCandidates: List<FormCandidate>, context: FormScoringContext): FormCandidate? {
        if (!context.isKanaSurface) return null
        if (context.surface == best.form.text) return null
        if (best.word.partsOfSpeech.none { it == "cop" }) return null

        for (c in allCandidates) {
            if (c.word.wordId == best.word.wordId) continue
            if (context.surface != c.form.text) continue
            if (!c.deconjForm?.process.isNullOrEmpty()) continue
            if (c.word.partsOfSpeech.any { it == "cop" }) return c
        }
        return null
    }

    /** The jiten boost is a kanji word's weight; on its minor kana reading (遂 つい) a frequent kana entry wins. */
    fun applyJitenMinorKanaReadingCap(best: FormCandidate, allCandidates: List<FormCandidate>, context: FormScoringContext): FormCandidate? {
        if (!context.isKanaSurface) return null
        if (!best.deconjForm?.process.isNullOrEmpty() || context.surface != best.form.text) return null
        if (best.word.priorities?.contains("jiten") != true) return null
        if (best.form.formType != JmDictFormType.KanaForm) return null
        if (best.word.partsOfSpeech.any { it == "uk" }) return null
        if (best.word.forms.none { it.formType == JmDictFormType.KanjiForm }) return null

        for (c in allCandidates) {
            if (c.word.wordId == best.word.wordId) continue
            if (!c.deconjForm?.process.isNullOrEmpty() || context.surface != c.form.text) continue
            if (c.word.forms.any { it.formType == JmDictFormType.KanjiForm }) continue
            if (PartOfSpeech.Name in c.word.cachedPOS) continue
            if (!KanaScoringHelpers.hasFrequencyMarker(c.word.priorities, includeJiten = false)) continue
            return c
        }
        return null
    }

    /** Sentence-final 来い is the interjection, not 来る's imperative; mixed entries (出来た exp/int) don't qualify. */
    fun applySentenceFinalInterjection(best: FormCandidate, allCandidates: List<FormCandidate>, context: FormScoringContext): FormCandidate? {
        if (!context.isSentenceFinal) return null
        val bestProcess = best.deconjForm?.process
        if (bestProcess.isNullOrEmpty() || !KanaScoringHelpers.isInflectableVerbOrAdj(best.word.partsOfSpeech)) return null

        for (candidate in allCandidates) {
            if (candidate.word.wordId == best.word.wordId) continue
            if (!candidate.deconjForm?.process.isNullOrEmpty()) continue
            if (context.surface != candidate.form.text) continue
            if (candidate.word.cachedPOS.any { it != PartOfSpeech.Interjection && it != PartOfSpeech.Unknown }) continue
            if (isUnattestedInterjectionOverPastForm(candidate.word, bestProcess.asList())) continue
            if (candidate.word.partsOfSpeech.any { it == "int" }) return candidate
        }
        return null
    }

    private val PublicFrequencyMarkers = arrayOf("ichi1", "ichi2", "news1", "news2", "spec1", "spec2", "gai1", "gai2")

    /** A past form states a proposition, so an int homograph needs frequency evidence (やった spec1 yes, 来た no). */
    fun isUnattestedInterjectionOverPastForm(word: JmDictWord, deconjProcess: List<String>): Boolean {
        if ("past" !in deconjProcess) return false
        if (word.cachedPOS.any { it != PartOfSpeech.Interjection && it != PartOfSpeech.Unknown }) return false
        if (word.partsOfSpeech.none { it == "int" }) return false
        val pri = word.priorities
        return pri == null || pri.none { it in PublicFrequencyMarkers || it.startsWith("nf") }
    }

    /** Rescues an exact-surface archaic word (あらざる) from its -350 only when all else is junk (聞ける stays 聞く). */
    fun applyArchaicExactSurfaceRescue(best: FormCandidate, allCandidates: List<FormCandidate>, context: FormScoringContext): FormCandidate? {
        if (best.word.isFullyArchaic || ScoringPolicy.effectiveScore(best) >= 100 || context.surface.length < 3) return null

        var archExact: FormCandidate? = null
        for (c in allCandidates)
            if (c.word.isFullyArchaic && c.surfaceMatchScore >= 300 && (archExact == null || c.totalScore > archExact.totalScore))
                archExact = c

        return if (archExact != null && archExact.totalScore + 300 > ScoringPolicy.effectiveScore(best)) archExact else null
    }

    /** Candidates must already be scored via FormCandidateScorer.score. */
    fun pickTopCandidatesWithBonus(allCandidates: List<FormCandidate>, bonusFunc: (FormCandidate) -> Int): FormCandidate? {
        if (allCandidates.isEmpty()) return null

        var best: FormCandidate? = null
        var bestAdjusted = Int.MIN_VALUE

        for (candidate in allCandidates) {
            var bonus = bonusFunc(candidate)
            if (candidate.isPosIncompatibleDirectSurface && bonus > 0) bonus = 0
            val adjusted = ScoringPolicy.effectiveScore(candidate) + bonus
            if (adjusted > bestAdjusted ||
                (adjusted == bestAdjusted && best != null && candidate.word.wordId < best.word.wordId) ||
                (adjusted == bestAdjusted && best != null && candidate.word.wordId == best.word.wordId &&
                    hasPreferredConjugation(candidate, best))) {
                bestAdjusted = adjusted
                best = candidate
            }
        }
        return best
    }

    /** An unprioritized pick that won only via Sudachi's +70 reading yields to a prioritized rival (歩兵 ほへい). */
    fun applyKanjiHomographPriorityCap(best: FormCandidate, allCandidates: List<FormCandidate>, context: FormScoringContext): FormCandidate? {
        if (context.isKanaSurface) return null
        if (best.readingMatchScore <= 0) return null
        if (best.entryPriorityScore > 0) return null
        if (context.surface != best.form.text) return null
        if (!best.deconjForm?.process.isNullOrEmpty()) return null

        val bestEffective = ScoringPolicy.effectiveScore(best)

        var rival: FormCandidate? = null
        var rivalEffective = Int.MIN_VALUE
        for (c in allCandidates) {
            if (c.word.wordId == best.word.wordId) continue
            if (c.form.text != context.surface) continue
            if (c.entryPriorityScore <= 0) continue
            if (c.readingMatchScore > 0) continue
            if (!c.deconjForm?.process.isNullOrEmpty()) continue
            if (c.isPosIncompatibleDirectSurface && !best.isPosIncompatibleDirectSurface) continue
            // JMDict priorities often land on the wrong homograph (里 り has ichi1), so ruby priors backing Sudachi veto the flip.
            if (c.rubyPriorsScore < best.rubyPriorsScore) continue

            val effective = ScoringPolicy.effectiveScore(c)
            if (effective + best.readingMatchScore >= bestEffective && effective > rivalEffective) {
                rival = c
                rivalEffective = effective
            }
        }
        return rival
    }

    private fun hasPreferredConjugation(candidate: FormCandidate, current: FormCandidate): Boolean {
        val candidateProcess = candidate.deconjForm?.process
        val currentProcess = current.deconjForm?.process
        if (candidateProcess.isNullOrEmpty() || currentProcess.isNullOrEmpty()) return false

        val candidateIsInfinitive = candidateProcess.last().let { it == "(infinitive)" || it == "(unstressed infinitive)" }
        val currentIsInfinitive = currentProcess.last().let { it == "(infinitive)" || it == "(unstressed infinitive)" }
        return candidateIsInfinitive && !currentIsInfinitive
    }
}
