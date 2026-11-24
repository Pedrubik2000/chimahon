// Ported from Jiten (github.com/Sirush/Jiten, Apache-2.0, commit 44b48a4): Jiten.Parser/Parser.cs lines 4944-6011
// (compound matching across tokens, form-candidate picking, adjacent-word scoring, margin maps)
//
// Conventions (see JitenParser.kt): `internal fun JitenParser.x(...)`; C#'s Span<long>/Span<int> are LongArray/IntArray,
// `_lookupsAlt.TryGetValue(span)` is lexicon.lookup(substring); C#'s (sentenceIndex, wordIndex) keys are Pair<Int, Int>;
// diagnostics, timing and ParserCounters are left out (the bonus cache keeps only the bonus).
// BuildUserDictCsv (C# line 5981) isn't ported: it feeds
// Jiten's per-deck Sudachi user dictionary (no Sudachi Java equivalent, decks only).
package chimahon.lang.jiten

/** C#'s (int startIndex, string dictionaryForm, int wordId) tuple. */
internal data class CompoundMatch(val startIndex: Int, val dictionaryForm: String, val wordId: Int)

internal fun JitenParser.tryMatchCompounds(
    wordInfos: List<WordInfo>,
    wordIndex: Int,
    tokenHashes: LongArray,
    lastConsumedIndex: Int = -1,
    forceExpressionOnly: Boolean = false,
): CompoundMatch? {
    val verb = wordInfos[wordIndex]
    var dictForm = verb.dictionaryForm

    var maxPrefixTokens = minOf(4, wordIndex)
    var prefCumHash = LongArray(maxPrefixTokens + 1)
    var prefCumLen = IntArray(maxPrefixTokens + 1)
    for (k in 1..maxPrefixTokens) {
        val tokenIdx = wordIndex - k
        val prevLen = prefCumLen[k - 1]
        if (prevLen >= hashBasePowers.size) {
            maxPrefixTokens = k - 1
            break
        }
        prefCumHash[k] = tokenHashes[tokenIdx] * hashBasePowers[prevLen] + prefCumHash[k - 1]
        prefCumLen[k] = wordInfos[tokenIdx].text.length + prevLen
    }
    prefCumHash = prefCumHash.copyOf(maxPrefixTokens + 1)
    prefCumLen = prefCumLen.copyOf(maxPrefixTokens + 1)

    fun window(form: String, hash: Long = hiraRollingHash(form)) =
        tryMatchCompoundWindow(wordInfos, wordIndex, lastConsumedIndex, form, forceExpressionOnly, hash, prefCumHash, prefCumLen)

    if (dictForm.isEmpty()) {
        val deconjugated = deconjugator.deconjugate(KanaConverter.toHiragana(verb.text))
        if (deconjugated.isEmpty()) return null
        var selectedForm = deconjugated[0]
        for (d in 1 until deconjugated.size) {
            val f = deconjugated[d]
            if (f.text.length < selectedForm.text.length ||
                (f.text.length == selectedForm.text.length && f.text < selectedForm.text))
                selectedForm = f
        }
        dictForm = selectedForm.text.ifEmpty { verb.text }
        return window(dictForm)
    }

    var result = window(dictForm)
    // DictionaryForm may be an unreachable homograph (立って df=立てる → 役に立てる); deconjugation below finds 役に立つ.
    if (result != null && dictForm != verb.text && !deconjBasesAllow(verb.text, dictForm)) result = null
    if (result != null) return result

    if (verb.text != dictForm) {
        result = window(verb.text, tokenHashes[wordIndex])
        if (result != null) return result
    }

    // Classical 高き (df=高し) has the modern lemma only in NormalizedForm, which compounds use (気高い).
    if (verb.partOfSpeech == PartOfSpeech.IAdjective && dictForm.endsWith('し') && verb.normalizedForm.isNotEmpty() &&
        verb.normalizedForm.endsWith('い') && verb.normalizedForm != dictForm) {
        result = window(verb.normalizedForm)
        if (result != null) return result
    }

    if (dictForm in setOf("する", "ある", "いく", "行く", "なる", "くる", "来る") &&
        (verb.text.contains("ない") || verb.text.contains("なかっ") || verb.text.contains("なく") ||
            verb.text.contains("ねえ") || verb.text.contains("ねー") || verb.text.contains("ねぇ") ||
            verb.text.contains("ませ") || verb.text.endsWith('ん') || verb.text.endsWith('ず'))) {
        // Idioms are keyed under ない, so 一筋縄では+いかん must be retried as いかない.
        val negForm = when (dictForm) {
            "する" -> "しない"
            "いく" -> "いかない"
            "行く" -> "いかない"
            "なる" -> "ならない"
            "くる" -> "こない"
            "来る" -> "こない"
            else -> "ない"
        }
        result = window(negForm)
        if (result != null) return result
    }

    // ねぇ/ねー is never a key (ろくでも+ねぇ), so retry with ない; Sudachi normalizes these to 無い.
    if ((verb.partOfSpeech == PartOfSpeech.IAdjective || verb.normalizedForm == "無い" || verb.normalizedForm == "ない") &&
        dictForm.length >= 2 && (dictForm.endsWith("ねぇ") || dictForm.endsWith("ねえ") || dictForm.endsWith("ねー"))) {
        result = window(dictForm.dropLast(2) + "ない")
        if (result != null) return result
    }

    if (dictForm != verb.text && verb.text.length > dictForm.length) {
        val deconj = deconjugator.deconjugate(verb.text)
        val intermediates = compoundScratchSet
        intermediates.clear()
        for (form in deconj)
            for (seen in form.seenText)
                if (seen.length > dictForm.length && seen.length < verb.text.length) intermediates.add(seen)

        for (intermediate in sortByLengthDescending(intermediates)) {
            result = window(intermediate)
            if (result != null) return result
        }

        intermediates.clear()
        for (form in deconj)
            if (form.text.length >= 3 && form.text.length < dictForm.length && form.text.last() in DictionaryVerbEndings)
                intermediates.add(form.text)

        for (shortForm in sortByLengthDescending(intermediates)) {
            result = window(shortForm)
            if (result != null) return result
        }

        intermediates.clear()
        for (form in deconj)
            if (form.text.isNotEmpty() && form.text != dictForm && form.text.length <= dictForm.length &&
                form.text.length >= 2 && form.text.last() in DictionaryVerbEndings)
                intermediates.add(form.text)

        for (altForm in sortByLengthDescending(intermediates)) {
            result = window(altForm)
            if (result != null) return result
        }
    }

    if (dictForm == verb.text) {
        if (verb.partOfSpeech == PartOfSpeech.Verb && verb.normalizedForm.isNotEmpty() && verb.normalizedForm != dictForm) {
            result = window(verb.normalizedForm)
            if (result != null) return result

            val hiraganaNorm = KanaConverter.toHiragana(verb.normalizedForm, convertLongVowelMark = false)
            if (hiraganaNorm != verb.normalizedForm) {
                result = window(hiraganaNorm)
                if (result != null) return result
            }
        }

        if (verb.partOfSpeech != PartOfSpeech.Noun && verb.partOfSpeech != PartOfSpeech.CommonNoun &&
            verb.partOfSpeech != PartOfSpeech.Name) {
            val verbKana = KanaConverter.toHiragana(verb.text, convertLongVowelMark = false)
            val hasKanji = verb.text.any(JapaneseTextHelper::isKanji)

            if (hasKanji && verbKana.last() !in DictionaryVerbEndings) {
                val deconj = deconjugator.deconjugate(KanaConverter.toHiragana(verb.text))
                val uniqueTexts = HashSet<String>()
                for (d in deconj) if (d.text.isNotEmpty() && d.text != dictForm) uniqueTexts.add(d.text)

                for (form in sortByLengthAscending(uniqueTexts)) {
                    result = window(form)
                    if (result != null) return result
                }
            }
        }
    }

    return null
}

/** C#'s List.Sort is an introsort, stable below 17 items (insertion sort); sortedByDescending is always stable. */
internal fun JitenParser.sortByLengthDescending(set: Set<String>): List<String> = set.sortedByDescending { it.length }

internal fun JitenParser.sortByLengthAscending(set: Set<String>): List<String> =
    set.sortedWith(compareBy<String> { it.length }.thenBy { it })

internal fun JitenParser.tryMatchCompoundWindow(
    wordInfos: List<WordInfo>,
    wordIndex: Int,
    lastConsumedIndex: Int,
    dictForm: String,
    forceExpressionOnly: Boolean,
    dictFormHash: Long,
    prefCumHash: LongArray,
    prefCumLen: IntArray,
): CompoundMatch? {
    val dictFormLen = dictForm.length
    val maxKeyLen = maxLookupKeyLength

    for (windowSize in minOf(5, wordIndex + 1) downTo 2) {
        val startIndex = wordIndex - windowSize + 1

        if (startIndex <= lastConsumedIndex) continue

        val firstWord = wordInfos[startIndex]
        // Particles over-merge as openers (とは); curated heads like しか (Vしか+ない) are expression-only.
        val curatedParticleOpener = firstWord.partOfSpeech == PartOfSpeech.Particle &&
            firstWord.text in JitenParser.ParticleExpressionOpeners
        if ((firstWord.partOfSpeech == PartOfSpeech.Particle || firstWord.partOfSpeech == PartOfSpeech.Interjection) &&
            !curatedParticleOpener)
            continue
        if (firstWord.partOfSpeech == PartOfSpeech.Conjunction && firstWord.text.length == 1) continue
        if (firstWord.wasReclassifiedFromSuffix) continue
        // A name's honorific can't open a window (ヴァレリア様|に言わせれば ≠ ように言う).
        if (startIndex > 0 && firstWord.text in TransitionRuleSets.HonorificSuffixes &&
            wordInfos[startIndex - 1].partOfSpeech.let {
                it == PartOfSpeech.Noun || it == PartOfSpeech.CommonNoun || it == PartOfSpeech.Pronoun || it == PartOfSpeech.Name
            })
            continue

        var hasPunctuation = false
        for (j in startIndex until wordIndex) {
            if (wordInfos[j].partOfSpeech == PartOfSpeech.SupplementarySymbol) {
                hasPunctuation = true
                break
            }
        }
        if (hasPunctuation) continue

        val k = windowSize - 1
        if (k >= prefCumLen.size) continue
        val prefixLen = prefCumLen[k]
        val totalLen = prefixLen + dictFormLen
        if (totalLen > maxKeyLen) continue

        val candidateHash = prefCumHash[k] * hashBasePowers[dictFormLen] + dictFormHash
        if (!compoundHashSet.contains(candidateHash)) continue

        val expressionOnly = forceExpressionOnly || curatedParticleOpener ||
            firstWord.partOfSpeech == PartOfSpeech.Suffix || firstWord.partOfSpeech == PartOfSpeech.Auxiliary ||
            firstWord.partOfSpeech == PartOfSpeech.Adverb

        val sb = StringBuilder(totalLen)
        for (j in startIndex until startIndex + windowSize - 1) sb.append(wordInfos[j].text)
        sb.append(dictForm)
        val candidate = sb.toString()

        if (windowSize == 2 && wordInfos[startIndex].text == dictForm && wordInfos[wordIndex].text != dictForm) continue

        var exprWordId: Int? = null
        var compWordId: Int? = null

        var wordIds: List<Int>? = lexicon.lookup(candidate)?.asList()
        if (wordIds != null && wordIds.isNotEmpty()) {
            // A kanji-numeral span is a number, never a JMnedict name (四万, 二十三).
            val numeralSpan = candidate.all { it in JitenParser.NumeralKanji || it == '万' || it == '億' || it == '兆' }
            if (numeralSpan)
                wordIds = wordIds.filter { id ->
                    val m = lexicon.wordMeta(id)
                    !(m != null && m.pos.isNotEmpty() && m.pos.all { it == PartOfSpeech.Name || it == PartOfSpeech.Unknown })
                }
            if (wordIds.isNotEmpty()) {
                val isKana = candidate.all { it in 'ぁ'..'ゟ' || it in '゠'..'ヿ' }
                val ids = CompoundWordSelector.findCompoundWordIds(wordIds, lexicon, isKana)
                exprWordId = ids.first
                compWordId = ids.second
            }
        }

        if (exprWordId == null && compWordId == null) {
            if (candidate.any { it in '゠'..'ヿ' }) {
                val hiragana = String(CharArray(totalLen) { c -> candidate[c].let { if (it in 'ァ'..'ヶ') it - 0x60 else it } })
                val hiraWordIds = lexicon.lookup(hiragana)
                if (hiraWordIds != null && hiraWordIds.isNotEmpty()) {
                    val ids = CompoundWordSelector.findCompoundWordIds(hiraWordIds.asList(), lexicon, isKana = true)
                    exprWordId = ids.first
                    compWordId = ids.second
                }
            }
        }

        val matchId = if (expressionOnly) exprWordId else (exprWordId ?: compWordId)

        if (matchId != null && candidateHash in JitenParser.ExpressionExclusionHashes) continue

        // A pronoun is never a suru-noun stem: 私+してない is not 私する "use for personal gain".
        if (matchId != null && firstWord.partOfSpeech == PartOfSpeech.Pronoun && lexicon.primaryPos(matchId) == PartOfSpeech.Verb)
            continue

        if (matchId != null) return CompoundMatch(startIndex, candidate, matchId)
    }

    return null
}

internal fun JitenParser.findMatchingHint(hints: Array<FuriganaHint>?, token: WordInfo): FuriganaHint? {
    if (hints == null || token.startOffset < 0) return null
    val tokenEnd = token.startOffset + token.text.length
    for (h in hints) {
        if (h.offset < token.startOffset || h.offset >= tokenEnd) continue
        val hintEnd = h.offset + h.length
        if (hintEnd > tokenEnd) continue

        if (h.length == token.text.length) return h

        // Partial hint ({開'あ}く): append the unhinted okurigana.
        val suffixStart = h.offset - token.startOffset + h.length
        return FuriganaHint(token.startOffset, token.text.length, h.reading + token.text.substring(suffixStart))
    }
    return null
}

/** State stands in for the dedup key: pass 1 builds exactly one RederiveState per dedup key (identity equality). */
private data class ScoredCandidatesKey(
    val state: RederivationHelper.RederiveState,
    val normalizedForm: String,
    val isPossibleDependant: Boolean,
    val isArchaicSentence: Boolean,
    val isSentenceInitial: Boolean,
    val isSentenceFinal: Boolean,
    val nextIsCopula: Boolean,
    val admitCounters: Boolean,
)

private class ScoredCandidates(val candidates: List<FormCandidate>, val context: FormScoringContext)

internal fun JitenParser.findCandidate(candidates: List<FormCandidate>, wordId: Int, readingIndex: Int): FormCandidate? =
    candidates.firstOrNull { it.word.wordId == wordId && it.readingIndex == readingIndex }

internal fun JitenParser.isInfinitiveResult(word: DeckWord?): Boolean =
    word?.lastConjugation.let { it == "(infinitive)" || it == "(unstressed infinitive)" }

/** Kana なくなる is never 亡くなる "to die" (written in kanji); compounds like 耐え切れなくなった are untouched. */
internal fun JitenParser.dropImpossibleKanaReadingCandidates(candidates: List<FormCandidate>?, word: WordInfo): List<FormCandidate>? {
    if (candidates.isNullOrEmpty()) return candidates
    if (word.text.startsWith("なくな") && JapaneseTextHelper.isAllHiragana(word.text))
        return candidates.filter { it.word.wordId != 1518540 }
    return candidates
}

internal fun JitenParser.applyAdjacentScoringCore(
    sentencePairs: MutableList<MutableList<SentencePair>>,
    candidateLookup: Map<DedupKey, MutableList<FormCandidate>>? = null,
    relocatedHints: Array<FuriganaHint>? = null,
): MutableList<DeckWord> {
    val allWordIds = HashSet<Int>()
    val rederiveStates = arrayOfNulls<Array<RederivationHelper.RederiveState?>>(sentencePairs.size)
    val cachedCandidates = arrayOfNulls<Array<List<FormCandidate>?>>(sentencePairs.size)
    val rederiveCache = HashMap<DedupKey, RederivationHelper.RederiveState?>()

    // Surface text is immutable across both passes.
    val isClassicalBySentence = BooleanArray(sentencePairs.size) { isClassicalSentence(sentencePairs[it]) }

    for (si in sentencePairs.indices) {
        val sentenceWords = sentencePairs[si]
        val isArchaicPass1 = isClassicalBySentence[si]
        for (i in sentenceWords.indices) {
            val (currentInfo, currentResult, currentMargin) = sentenceWords[i]
            if (currentResult == null) continue

            val nextInfo = if (i < sentenceWords.size - 1) sentenceWords[i + 1].word else null
            val nextIsCopula = nextInfo != null && nextInfo.text in TransitionRuleSets.CopulaForms
            val nextIsForwardAnchor = nextInfo != null && nextInfo.text in TransitionRuleSets.ForwardAnchorSurfaces
            val prevNominalTarget = i > 0 && currentInfo.text in TransitionRuleSets.PrevNominalBoostSurfaces

            // A counter homograph after a numeral (第二|話) stays rescorable; only pass 2 sees the numeral.
            // A pick that is itself a numeral (ガンつく1|ワン) is already cohesive and must not reshuffle.
            val prevNumericCounter = i > 0 &&
                AdjacentWordScorer.isNumericSurface(sentenceWords[i - 1].word.text) &&
                hasCounterLookup(currentInfo.text) &&
                PartOfSpeech.Numeral !in currentResult.partsOfSpeech

            val hasHint = findMatchingHint(relocatedHints, currentInfo) != null
            val isPreMatched = currentInfo.preMatchedWordId != null && currentInfo.preMatchedCandidateWordIds == null
            if (isPreMatched && !hasHint) continue

            val prevInfo = if (i > 0) sentenceWords[i - 1].word else null
            val prevResult = if (i > 0) sentenceWords[i - 1].result else null
            val nextResult = if (i < sentenceWords.size - 1) sentenceWords[i + 1].result else null

            // An infinitive after の, a noun or another infinitive is suspect (群れ、群れ); chains cascade left to right.
            val infinitiveAfterNominal = isInfinitiveResult(currentResult) && i > 0 &&
                (prevInfo!!.text == "の" || isInfinitiveResult(prevResult) ||
                    (prevResult != null && PosMask.has(PosMask.NounLike, PosMask.fromList(prevResult.partsOfSpeech))))

            // A sentence-final verb may be an interjection homograph (来い "come!") the first pass can't see.
            val sentenceFinalVerb = i == sentenceWords.size - 1 && PartOfSpeech.Verb in currentResult.partsOfSpeech

            if (!isArchaicPass1 && !nextIsCopula && !nextIsForwardAnchor && !prevNominalTarget && !prevNumericCounter && !hasHint &&
                !infinitiveAfterNominal && !sentenceFinalVerb && ScoringPolicy.isHighConfidence(currentMargin))
                continue

            var forceRederive = hasHint || nextIsForwardAnchor || prevNominalTarget || prevNumericCounter ||
                ScoringPolicy.isLowConfidence(currentMargin)

            if (!forceRederive)
                for (k in maxOf(0, i - 3) until i) {
                    val kr = sentenceWords[k].result
                    if (kr != null && TransitionRuleSets.NounVerbCollocations.containsKey(kr.wordId)) {
                        forceRederive = true
                        break
                    }
                }

            // infinitiveAfterNominal bypasses the prefilter: its context may turn nominal only in pass 2.
            if (!forceRederive && !nextIsCopula && !isArchaicPass1 && !infinitiveAfterNominal &&
                !TransitionRuleEngine.couldAnySoftRuleApply(currentResult.partsOfSpeech, currentInfo.text,
                    prevResult?.partsOfSpeech, prevInfo?.text, nextResult?.partsOfSpeech, nextInfo?.text))
                continue

            val dedupKey = getDedupKey(currentInfo)

            if (candidateLookup != null && !hasHint) {
                val fpCandidates = candidateLookup[dedupKey]
                if (fpCandidates != null) {
                    (cachedCandidates[si] ?: arrayOfNulls<List<FormCandidate>?>(sentenceWords.size).also { cachedCandidates[si] = it })[i] =
                        fpCandidates
                    continue
                }
            }

            val state: RederivationHelper.RederiveState?
            if (rederiveCache.containsKey(dedupKey)) {
                state = rederiveCache[dedupKey]
            } else {
                state = RederivationHelper.collectRederivationIds(currentInfo, lexicon, deconjugator)
                rederiveCache[dedupKey] = state
            }
            if (state == null) continue

            (rederiveStates[si] ?: arrayOfNulls<RederivationHelper.RederiveState?>(sentenceWords.size).also { rederiveStates[si] = it })[i] =
                state
            allWordIds.addAll(state.candidateIds)
        }
    }

    val wordCache: Map<Int, JmDictWord> = try {
        if (allWordIds.isNotEmpty()) getWords(allWordIds) else emptyMap()
    } catch (_: Exception) {
        emptyMap()
    }

    var tokenCount = 0
    for (sentenceWords in sentencePairs) tokenCount += sentenceWords.size
    val corrected = ArrayList<DeckWord>(tokenCount)
    val bonusCache = HashMap<FormCandidate, Int>()
    // Shareable because base scores are context-free and the copula flag clear is keyed; bonuses stay per token.
    val scoredMemo = HashMap<ScoredCandidatesKey, ScoredCandidates>()

    for (si in sentencePairs.indices) {
        val sentenceWords = sentencePairs[si]
        val isArchaicSentence = isClassicalBySentence[si]
        val resolvedResults = arrayOfNulls<DeckWord>(sentenceWords.size)
        for (ri in sentenceWords.indices) resolvedResults[ri] = sentenceWords[ri].result

        for (i in sentenceWords.indices) {
            val (currentInfo, currentResult, currentMargin) = sentenceWords[i]
            if (currentResult == null) continue

            val matchingHint = findMatchingHint(relocatedHints, currentInfo)
            val tokenHasHint = matchingHint != null
            val nextInfo = if (i < sentenceWords.size - 1) sentenceWords[i + 1].word else null
            val nextIsCopula = nextInfo != null && nextInfo.text in TransitionRuleSets.CopulaForms

            var candidates: List<FormCandidate>? = null
            var fromFirstPassCache = false
            var memoHit = false
            var memoKey: ScoredCandidatesKey? = null
            var memoContext: FormScoringContext? = null
            val cached = cachedCandidates[si]?.get(i)
            val state = rederiveStates[si]?.get(i)
            if (cached != null) {
                candidates = cached
                fromFirstPassCache = true
            } else if (state != null) {
                // Kanji surfaces only: kana after a numeral is usually not a counter (何|か, not 箇/課).
                val admitCounters = i > 0 &&
                    AdjacentWordScorer.isNumericSurface(sentenceWords[i - 1].word.text) &&
                    currentInfo.text.any(JapaneseTextHelper::isKanji) &&
                    hasCounterLookup(currentInfo.text)

                // Hinted tokens skip the memo: the hint changes the POS filter and is per position.
                if (!tokenHasHint)
                    memoKey = ScoredCandidatesKey(state, currentInfo.normalizedForm,
                        currentInfo.hasPartOfSpeechSection(PartOfSpeechSection.PossibleDependant),
                        isArchaicSentence, i == 0, i == sentenceWords.size - 1, nextIsCopula, admitCounters)

                val memo = memoKey?.let { scoredMemo[it] }
                if (memo != null) {
                    candidates = memo.candidates
                    memoContext = memo.context
                    memoHit = true
                } else {
                    candidates = RederivationHelper.buildCandidatesFromWords(state, wordCache, skipPosFilter = tokenHasHint,
                        allowCounterCandidates = admitCounters)
                }
            }

            // A kana surface can't resolve to a kanji-only homograph (kana なくなる ≠ 亡くなる).
            if (!memoHit) candidates = dropImpossibleKanaReadingCandidates(candidates, currentInfo)

            if (candidates.isNullOrEmpty()) {
                currentInfo.resolvedWordId = currentResult.wordId
                corrected.add(currentResult)
                continue
            }

            val prevInfo = if (i > 0) sentenceWords[i - 1].word else null
            val prevResult = if (i > 0) resolvedResults[i - 1] else null
            val nextResult = if (i < sentenceWords.size - 1) sentenceWords[i + 1].result else null

            val context = AdjacentWordScorer.AdjacentContext.create(prevResult?.partsOfSpeech, prevInfo?.text,
                nextResult?.partsOfSpeech, nextInfo?.text)

            // Adjacent context (archaic, sentence edges) only affects WordPriorityScorer; interior tokens keep traces.
            val skipRescore = fromFirstPassCache && !isArchaicSentence && i > 0 && i < sentenceWords.size - 1

            val scoringContext = memoContext ?: FormScoringContext.create(
                currentInfo.text, currentInfo.dictionaryForm, currentInfo.normalizedForm,
                currentInfo.isPersonNameContext, currentInfo.reading,
                isArchaicSentence,
                isSentenceInitial = i == 0,
                isSentenceFinal = i == sentenceWords.size - 1,
                sudachiPOS = currentInfo.partOfSpeech,
                isSudachiPossibleDependant = currentInfo.hasPartOfSpeechSection(PartOfSpeechSection.PossibleDependant))

            // 来い → int 2742070, not 来る's imperative; only this pass knows the token is sentence-final.
            val sfInterjCurrent = if (scoringContext.isSentenceFinal)
                findCandidate(candidates, currentResult.wordId, currentResult.readingIndex) else null
            val interjectionFlip = sfInterjCurrent != null &&
                FormCandidateSelector.applySentenceFinalInterjection(sfInterjCurrent, candidates, scoringContext) != null

            // A confident sentence-final verb gets only the interjection override, never an adjacency flip (行けよ).
            val onlyInterjectionAllowed = scoringContext.isSentenceFinal && ScoringPolicy.isHighConfidence(currentMargin) &&
                PartOfSpeech.Verb in currentResult.partsOfSpeech

            var anyNonZeroBonus = false
            bonusCache.clear()

            var collocMap: HashMap<Int, Int>? = null
            for (k in maxOf(0, i - 3) until i) {
                val rr = resolvedResults[k] ?: continue
                val cv = TransitionRuleSets.NounVerbCollocations[rr.wordId] ?: continue
                val map = collocMap ?: HashMap<Int, Int>().also { collocMap = it }
                map[cv.first] = (map[cv.first] ?: 0) + cv.second
            }

            // The next surface (前/後) selects a homograph reading the priority scorer would miss.
            if (nextInfo != null) {
                for ((targetWordId, anchor) in TransitionRuleSets.ForwardAnchorBoosts) {
                    if (nextInfo.text in anchor.first) {
                        val map = collocMap ?: HashMap<Int, Int>().also { collocMap = it }
                        map[targetWordId] = (map[targetWordId] ?: 0) + anchor.second
                    }
                }
            }

            // A nominal on the left selects the enumerating homograph (NといいNといい).
            if (prevResult != null && currentInfo.text in TransitionRuleSets.PrevNominalBoostSurfaces &&
                PosMask.has(PosMask.NounLike, PosMask.fromList(prevResult.partsOfSpeech))) {
                for ((targetWordId, boost) in TransitionRuleSets.PrevNominalBoosts) {
                    if (boost.first != currentInfo.text) continue
                    val map = collocMap ?: HashMap<Int, Int>().also { collocMap = it }
                    map[targetWordId] = (map[targetWordId] ?: 0) + boost.second
                }
            }

            // Copula follows nominals only, so nouns must be eligible for the noun-copula synergy.
            if (nextIsCopula) for (c in candidates) c.isPosIncompatibleDirectSurface = false

            val leftDictForm = prevInfo?.dictionaryForm
            val rightDictForm = nextInfo?.dictionaryForm
            val left2DictForm = if (i >= 2) sentenceWords[i - 2].word.dictionaryForm else null
            val right2DictForm = if (i < sentenceWords.size - 2) sentenceWords[i + 2].word.dictionaryForm else null

            val hintHiragana = matchingHint?.let { KanaScoringHelpers.toNormalizedHiragana(it.reading, convertLongVowelMark = false) }
            val needsBaseScore = !skipRescore && !memoHit
            for (candidate in candidates) {
                if (needsBaseScore) candidate.setScoreTrace(FormCandidateScorer.score(candidate, scoringContext, JitenParser.ArchaicPosTypes))

                val rubyContextBonus = RubyPriorsScorer.scoreWithContext(candidate, scoringContext,
                    leftDictForm, rightDictForm, left2DictForm, right2DictForm) - candidate.rubyPriorsScore

                val collocBonus = collocMap?.get(candidate.word.wordId) ?: 0
                val furiganaBonus = FuriganaHintScorer.score(candidate, hintHiragana)

                val effectiveRubyBonus = if (collocMap != null) 0 else rubyContextBonus
                val bonus = AdjacentWordScorer.calculateContextBonus(candidate, context) + collocBonus + effectiveRubyBonus + furiganaBonus
                bonusCache[candidate] = bonus
                if (bonus != 0) anyNonZeroBonus = true
            }

            if (memoKey != null && !memoHit) scoredMemo[memoKey] = ScoredCandidates(candidates, scoringContext)

            // Archaic context changes base scores and can flip the winner.
            if (!anyNonZeroBonus && isArchaicSentence) {
                val phase2Best = candidates.maxByOrNull { it.totalScore }
                anyNonZeroBonus = phase2Best != null &&
                    (phase2Best.word.wordId != currentResult.wordId || phase2Best.readingIndex != currentResult.readingIndex)
            }

            if (!interjectionFlip && (!anyNonZeroBonus || onlyInterjectionAllowed)) {
                currentInfo.resolvedWordId = currentResult.wordId
                corrected.add(currentResult)
                continue
            }

            val getBonus: (FormCandidate) -> Int = { bonusCache[it] ?: 0 }

            var newBest = FormCandidateSelector.pickTopCandidatesWithBonus(candidates, getBonus)
            newBest = FormCandidateSelector.refineBest(newBest, candidates, scoringContext)

            if (newBest != null && (newBest.word.wordId != currentResult.wordId || newBest.readingIndex != currentResult.readingIndex)) {
                val wordIdChanged = newBest.word.wordId != currentResult.wordId
                val process = newBest.deconjForm?.process
                val conjugations = if (process != null && process.size == 1 && process[0] == "casual kind request" &&
                    "adj-na" in newBest.word.partsOfSpeech)
                    emptyList()
                else process?.toList() ?: if (wordIdChanged) emptyList() else currentResult.conjugations
                val newResult = DeckWord(
                    wordId = newBest.word.wordId, originalText = currentInfo.text,
                    readingIndex = newBest.readingIndex, occurrences = currentResult.occurrences,
                    conjugations = conjugations,
                    partsOfSpeech = newBest.word.cachedPOS.toMutableList(), origin = newBest.word.origin,
                    sudachiReading = currentInfo.reading, sudachiPartOfSpeech = currentInfo.partOfSpeech)
                currentInfo.resolvedWordId = newBest.word.wordId
                corrected.add(newResult)
                resolvedResults[i] = newResult
            } else {
                currentInfo.resolvedWordId = currentResult.wordId
                corrected.add(currentResult)
            }
        }
    }

    return corrected
}

// --- ported (needed by the skeleton) ---

internal fun JitenParser.pickBestFormCandidate(
    allCandidates: MutableList<FormCandidate>,
    surface: String,
    dictionaryForm: String?,
    normalizedForm: String?,
    isNameContext: Boolean,
    sudachiReading: String? = null,
    sudachiPOS: PartOfSpeech = PartOfSpeech.Unknown,
    isSudachiPossibleDependant: Boolean = false,
    isSudachiNameGuess: Boolean = false,
): Pair<FormCandidate?, Int?> {
    val context = FormScoringContext.create(surface, dictionaryForm, normalizedForm, isNameContext, sudachiReading,
        sudachiPOS = sudachiPOS, isSudachiPossibleDependant = isSudachiPossibleDependant, isSudachiNameGuess = isSudachiNameGuess)
    val result = FormCandidateSelector.pickTopCandidates(allCandidates, context, JitenParser.ArchaicPosTypes)
    return result.best to result.marginToSecond
}

/** PreMatchedWordId is keyed: same-surface pins (帽子のツバ→鍔, ツバを飲む→唾) must not share a resolution. */
internal fun getDedupKey(wi: WordInfo) = DedupKey(wi.text, wi.partOfSpeech, wi.dictionaryForm, wi.reading, wi.isPersonNameContext,
    PosMapper.isNameLikeSudachiNoun(wi.partOfSpeech, wi.partOfSpeechSection1, wi.partOfSpeechSection2, wi.partOfSpeechSection3),
    wi.preMatchedWordId)

internal fun lookupResult(wi: WordInfo, resultLookup: Map<DedupKey, Pair<DeckWord?, Int?>>): Pair<DeckWord?, Int?> =
    resultLookup[getDedupKey(wi)] ?: (null to null)

internal fun JitenParser.applyAdjacentScoring(
    sentences: List<SentenceInfo>,
    processedResults: List<ProcessedWord>,
    candidateLookup: Map<DedupKey, MutableList<FormCandidate>>? = null,
    relocatedHints: Array<FuriganaHint>? = null,
): MutableList<DeckWord> {
    var pos = 0
    val sentencePairs = buildSentencePairs(sentences) {
        if (pos >= processedResults.size) null to null
        else processedResults[pos++].let { it.word to it.margin }
    }
    return applyAdjacentScoringCore(sentencePairs, candidateLookup, relocatedHints)
}

internal fun JitenParser.applyAdjacentScoringFromLookup(
    sentences: List<SentenceInfo>,
    resultLookup: Map<DedupKey, Pair<DeckWord?, Int?>>,
    candidateLookup: Map<DedupKey, MutableList<FormCandidate>>? = null,
    relocatedHints: Array<FuriganaHint>? = null,
): MutableList<DeckWord> =
    applyAdjacentScoringCore(buildSentencePairs(sentences) { lookupResult(it, resultLookup) }, candidateLookup, relocatedHints)

internal fun buildSentencePairs(
    sentences: List<SentenceInfo>,
    lookupResult: (WordInfo) -> Pair<DeckWord?, Int?>,
): MutableList<MutableList<SentencePair>> {
    val result = ArrayList<MutableList<SentencePair>>(sentences.size)
    for (sentence in sentences) {
        val pairs = ArrayList<SentencePair>(sentence.words.size)
        for (sw in sentence.words) {
            if (sw.word.partOfSpeech == PartOfSpeech.SupplementarySymbol) continue
            val (word, margin) = lookupResult(sw.word)
            pairs.add(SentencePair(sw.word, word, margin))
        }
        result.add(pairs)
    }
    return result
}

internal fun buildMarginMap(sentences: List<SentenceInfo>, processedWithMargins: List<ProcessedWord>): MutableMap<Pair<Int, Int>, Int?> {
    val map = HashMap<Pair<Int, Int>, Int?>()
    var flatIndex = 0
    for (si in sentences.indices) {
        for (wi in sentences[si].words.indices) {
            if (sentences[si].words[wi].word.partOfSpeech == PartOfSpeech.SupplementarySymbol) continue
            if (flatIndex < processedWithMargins.size) map[si to wi] = processedWithMargins[flatIndex].margin
            flatIndex++
        }
    }
    return map
}

internal fun buildMarginMapFromLookup(
    sentences: List<SentenceInfo>,
    resultLookup: Map<DedupKey, Pair<DeckWord?, Int?>>,
): MutableMap<Pair<Int, Int>, Int?> {
    val map = HashMap<Pair<Int, Int>, Int?>()
    for (si in sentences.indices) {
        for (wi in sentences[si].words.indices) {
            val word = sentences[si].words[wi].word
            if (word.partOfSpeech == PartOfSpeech.SupplementarySymbol) continue
            resultLookup[getDedupKey(word)]?.let { map[si to wi] = it.second }
        }
    }
    return map
}

internal fun isRiskyNameEntry(surface: String) = surface.length <= 2 && WanaKana.isHiragana(surface)
