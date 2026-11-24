// Ported from Jiten (github.com/Sirush/Jiten, Apache-2.0, commit 44b48a4): Jiten.Parser/Resolution/CompoundWordSelector.cs,
// DeconjugationMatcher.cs, LookupCandidateCollector.cs, RederivationHelper.cs
//
// Parser.cs's static maps are [JitenLexicon]: `lookups` (Dictionary<string, List<int>>) is lexicon.lookup(key) and
// `WordMeta` is lexicon.wordMeta(id). The per-parse wordCache (Dictionary<int, JmDictWord>) stays a Map.
package chimahon.lang.jiten

import chimahon.lang.jiten.PartOfSpeech as P

internal object CompoundWordSelector {
    /** Returns (expressionWordId, compoundWordId). */
    fun findCompoundWordIds(wordIds: List<Int>, lexicon: JitenLexicon, isKana: Boolean = true): Pair<Int?, Int?> {
        var bestExprWordId: Int? = null
        var bestExprScore = Int.MIN_VALUE
        var bestCompWordId: Int? = null
        var bestCompScore = Int.MIN_VALUE

        for (wordId in wordIds) {
            val meta = lexicon.wordMeta(wordId) ?: continue

            if (P.Expression in meta.pos) {
                val score = meta.getPriorityScore(isKana)
                if (score > bestExprScore || (score == bestExprScore && (bestExprWordId == null || wordId < bestExprWordId))) {
                    bestExprScore = score
                    bestExprWordId = wordId
                }
            }

            if (meta.pos.any(PosMapper::isValidCompoundPOS)) {
                val score = meta.getPriorityScore(isKana)
                if (score > bestCompScore || (score == bestCompScore && (bestCompWordId == null || wordId < bestCompWordId))) {
                    bestCompScore = score
                    bestCompWordId = wordId
                }
            }
        }
        return bestExprWordId to bestCompWordId
    }
}

internal object DeconjugationMatcher {
    data class DeconjMatch(val word: JmDictWord, val form: DeconjugationForm)

    /** Filters deconjugation candidates against POS compatibility and deconjugation tag validation. */
    fun filterMatches(
        candidates: Iterable<Pair<DeconjugationForm, List<Int>>>,
        wordCache: Map<Int, JmDictWord>,
        sudachiPos: P,
        strictPosCheck: Boolean = true,
    ): List<DeconjMatch> {
        val matches = ArrayList<DeconjMatch>()
        for ((form, ids) in candidates) {
            for (id in ids) {
                val word = wordCache[id] ?: continue
                if (strictPosCheck) {
                    if (!PosMapper.isJmDictCompatibleWithSudachi(word.cachedPOS, sudachiPos)) continue
                } else {
                    if (word.cachedPOS.none { it != P.Name && it != P.Unknown }) continue
                }
                val lastTag = PosMapper.getValidatableDeconjTags(form.tags.asList()).lastOrNull()
                if (lastTag != null && !PosMapper.isDeconjTagCompatibleWithJmDict(lastTag, word.partsOfSpeech)) continue
                matches.add(DeconjMatch(word, form))
            }
        }
        return matches
    }
}

internal object LookupCandidateCollector {
    /** Checks whether any lookup variant (raw, hiragana, normalized, long-vowel-stripped) has at least one match. */
    fun hasAnyMatch(lookups: JitenLexicon, text: String, includeLongVowelStripped: Boolean = false): Boolean {
        fun has(key: String) = lookups.lookup(key)?.isNotEmpty() == true
        if (has(text)) return true

        val hiragana = KanaConverter.toHiragana(text, convertLongVowelMark = false)
        if (hiragana != text && has(hiragana)) return true

        val normalized = KanaNormalizer.normalize(hiragana)
        if (normalized != hiragana && has(normalized)) return true

        if (includeLongVowelStripped && text.contains('ー')) {
            val stripped = text.replace("ー", "")
            if (stripped.isNotEmpty() && has(KanaConverter.toNormalizedHiragana(stripped))) return true
        }
        return false
    }

    /** normalizedTierGate filters normalized-only hits, which invent readings (いえー → いえい must not reach 遺影). */
    fun collectIds(
        lookups: JitenLexicon,
        text: String,
        includeKanaNormalized: Boolean = true,
        includeLongVowelStripped: Boolean = false,
        normalizedTierGate: ((Int) -> Boolean)? = null,
    ): MutableList<Int> {
        val seen = HashSet<Int>()
        val ids = ArrayList<Int>()

        fun addRange(source: IntArray, gated: Boolean = false) {
            for (id in source) {
                if (gated && normalizedTierGate != null && id !in seen && !normalizedTierGate(id)) continue
                if (seen.add(id)) ids.add(id)
            }
        }

        lookups.lookup(text)?.let { addRange(it) }

        val hiragana = KanaConverter.toHiragana(text, convertLongVowelMark = false)
        if (hiragana != text) lookups.lookup(hiragana)?.let { addRange(it) }

        if (includeKanaNormalized) {
            val normalized = KanaNormalizer.normalize(hiragana)
            if (normalized != hiragana) lookups.lookup(normalized)?.let { addRange(it, gated = true) }
        }

        if (includeLongVowelStripped && text.contains('ー')) {
            val textStripped = text.replace("ー", "")
            val endsInBar = text.endsWith('ー')
            val isShort = textStripped.length <= 2
            if (!endsInBar || !isShort) {
                val strippedHira = KanaConverter.toHiragana(textStripped)
                lookups.lookup(strippedHira)?.let { addRange(it, gated = true) }
            }
        }
        return ids
    }
}

internal object RederivationHelper {
    class RederiveState(
        val wordInfo: WordInfo,
        val text: String,
        val textInHiragana: String,
        val directIds: List<Int>? = null,
        val hiraganaIds: List<Int>? = null,
        val candidateIds: MutableList<Int>,
        val deconjMatches: MutableList<Pair<DeconjugationForm, List<Int>>>? = null,
        val isVerbPath: Boolean = false,
    )

    fun collectRederivationIds(wordInfo: WordInfo, lookups: JitenLexicon, deconjugator: Deconjugator): RederiveState? {
        val text = wordInfo.text
        val textInHiragana = KanaConverter.toHiragana(text, convertLongVowelMark = false)

        val constrainedIds = wordInfo.preMatchedCandidateWordIds
        if (!constrainedIds.isNullOrEmpty()) {
            return RederiveState(wordInfo, text, textInHiragana, candidateIds = ArrayList(constrainedIds), isVerbPath = false)
        }

        val directIds = lookups.lookup(text)?.asList()
        val hiraganaIds = lookups.lookup(textInHiragana)?.asList()

        val candidateIds = LookupCandidateCollector.collectIds(lookups, text)

        if (wordInfo.partOfSpeech in setOf(P.Verb, P.IAdjective, P.Auxiliary, P.NaAdjective, P.Expression)) {
            val normalizedText = KanaNormalizer.normalize(KanaConverter.toHiragana(text))
            val deconjugated = deconjugator.deconjugate(normalizedText)

            val deconjMatches = ArrayList<Pair<DeconjugationForm, List<Int>>>(deconjugated.size)
            val seen = HashSet(candidateIds)

            for (form in deconjugated) {
                val lookup = lookups.lookup(form.text) ?: continue
                deconjMatches.add(form to lookup.asList())
                for (id in lookup) if (seen.add(id)) candidateIds.add(id)
            }

            // C#'s List.Sort is unstable; sortByDescending is stable (equal-length forms keep deconjugation order).
            deconjMatches.sortByDescending { it.first.text.length }

            if (candidateIds.isEmpty()) return null

            return RederiveState(wordInfo, text, textInHiragana, directIds, hiraganaIds, candidateIds, deconjMatches, isVerbPath = true)
        }

        if (candidateIds.isEmpty()) return null

        return RederiveState(wordInfo, text, textInHiragana, directIds, hiraganaIds, candidateIds, isVerbPath = false)
    }

    /** C#'s BuildCandidatesFromWords: FormCandidateFactory.EnumerateCandidateForms with allowLooseLvmMatch. */
    fun buildCandidatesFromWords(
        state: RederiveState,
        wordCache: Map<Int, JmDictWord>,
        skipPosFilter: Boolean = false,
        allowCounterCandidates: Boolean = false,
    ): List<FormCandidate> = buildCandidatesFromWords(state, wordCache, skipPosFilter, allowCounterCandidates,
        { word, target, deconjForm, surface ->
            FormCandidateFactory.enumerateCandidateForms(word, target, allowLooseLvmMatch = true, deconjForm = deconjForm, surface = surface)
        },
        { it.isPosIncompatibleDirectSurface = true })

    /**
     * The generic body: EnumerateCandidateForms comes in as [enumerateCandidateForms] (word, target hiragana, deconjForm,
     * surface) and setting a candidate's IsPosIncompatibleDirectSurface as [markPosIncompatibleDirectSurface].
     */
    fun <C> buildCandidatesFromWords(
        state: RederiveState,
        wordCache: Map<Int, JmDictWord>,
        skipPosFilter: Boolean = false,
        allowCounterCandidates: Boolean = false,
        enumerateCandidateForms: (word: JmDictWord, target: String, deconjForm: DeconjugationForm?, surface: String) -> List<C>,
        markPosIncompatibleDirectSurface: (C) -> Unit,
    ): List<C> {
        val allCandidates = ArrayList<C>()

        if (state.isVerbPath) {
            val matchedWordIds = HashSet<Int>()
            for (m in DeconjugationMatcher.filterMatches(state.deconjMatches!!, wordCache, state.wordInfo.partOfSpeech)) {
                allCandidates.addAll(enumerateCandidateForms(m.word, m.form.text, m.form, state.text))
                matchedWordIds.add(m.word.wordId)
            }

            for (id in ((state.directIds ?: emptyList()) + (state.hiraganaIds ?: emptyList())).distinct()) {
                if (id in matchedWordIds) continue
                val word = wordCache[id] ?: continue
                if (word.cachedPOS.none { it != P.Name && it != P.Unknown }) continue
                val isPosIncompat = matchedWordIds.isNotEmpty() &&
                    !PosMapper.isJmDictCompatibleWithSudachi(word.cachedPOS, state.wordInfo.partOfSpeech)
                val forms = enumerateCandidateForms(word, state.textInHiragana, null, state.text)
                if (isPosIncompat) forms.forEach(markPosIncompatibleDirectSurface)
                allCandidates.addAll(forms)
            }
            return allCandidates
        }

        for (id in state.candidateIds) {
            val word = wordCache[id] ?: continue
            if (!skipPosFilter) {
                val isNameWord = word.cachedPOS.all { it == P.Name || it == P.Unknown }
                // ctr fails the context-free POS check vs Suffix; allowCounterCandidates admits it after numerals (第二|話 → わ).
                if (!PosMapper.isJmDictCompatibleWithSudachi(word.cachedPOS, state.wordInfo.partOfSpeech) &&
                    !(state.wordInfo.isPersonNameContext && isNameWord) &&
                    !(allowCounterCandidates && P.Counter in word.cachedPOS))
                    continue
            }
            allCandidates.addAll(enumerateCandidateForms(word, state.textInHiragana, null, state.text))
        }
        return allCandidates
    }
}
