// Ported from Jiten (github.com/Sirush/Jiten, Apache-2.0, commit 44b48a4): Jiten.Parser/Parser.cs lines 3973-4943
// (CombineNounCompounds, TryCombineNounVerbStemCompound, CombineExpressions(InSentence), the expression lookup gates,
// IsExpressionBoundaryTheft, CleanSentenceTokens, ApplyMisparseGates, ExcludeFinalMisparses, Concat*)
//
// Conventions (see JitenParser.kt): `internal fun JitenParser.x(...)`; C# `out` parameters become a nullable return;
// C#'s List<(WordInfo word, int position, int length)> is MutableList<SentenceWord>; diagnostics are left out.
// Already in JitenParser.kt (ported): the tables ExpressionExclusions, TwoTokenExpressionWhitelist, TwoTokenNounVerbIdioms,
// ExpressionExclusionHashes, TwoTokenExpressionWhitelistHashes (and NounCompoundExclusions) in its companion, and
// IsHashFoldSafeChar, IsHashFoldSafe, PrefixTailMayMatch (C# lines 4293-4318) plus compoundHashSet, hashBasePowers,
// maxLookupKeyLength and hiraRollingHash.
package chimahon.lang.jiten

/** C#'s `sentence.Words = result`: [SentenceInfo.words] is a val, so its contents are replaced. */
private fun SentenceInfo.replaceWords(result: List<SentenceWord>) {
    words.clear()
    words.addAll(result)
}

// C# line 3973
internal fun JitenParser.combineNounCompounds(sentences: List<SentenceInfo>) {
    for (sentence in sentences) {
        if (sentence.words.size < 2) continue

        val result = ArrayList<SentenceWord>(sentence.words.size)
        var i = 0

        while (i < sentence.words.size) {
            val (word, position, length) = sentence.words[i]

            if (!PosMapper.isNounForCompounding(word.partOfSpeech)) {
                // Sudachi splits でか|ぶつ, 飛び|道具, 一向|一揆 and mis-tagged kanji leads (偶|然); lookups gate the merge.
                // Hiragana adverbs are excluded: そう+なん would hit 遭難 through its reading key.
                val misparsedKanjiLead = word.text.length == 1 && JapaneseTextHelper.isKanji(word.text[0])
                val kanjiAdverbLead = word.partOfSpeech == PartOfSpeech.Adverb &&
                    word.text.any { JapaneseTextHelper.isKanji(it) || JapaneseTextHelper.isKatakana(it) }
                if ((word.partOfSpeech == PartOfSpeech.IAdjective || word.partOfSpeech == PartOfSpeech.Verb ||
                        kanjiAdverbLead || misparsedKanjiLead) &&
                    i + 1 < sentence.words.size &&
                    PosMapper.isNounForCompounding(sentence.words[i + 1].word.partOfSpeech) &&
                    !word.hardPinned && !sentence.words[i + 1].word.hardPinned
                ) {
                    val combinedText = word.text + sentence.words[i + 1].word.text
                    // uk compounds key on hiragana (出べそ, not 出ベソ); DictionaryForm keeps the surface for form scoring.
                    val matchedKey = tryLookupUsableCompound(combinedText)
                    if (matchedKey != null) {
                        val combinedReading = word.reading + sentence.words[i + 1].word.reading
                        val combinedLength = length + sentence.words[i + 1].length
                        val combinedWord = word.copy().apply {
                            text = combinedText
                            dictionaryForm = combinedText
                            partOfSpeech = PartOfSpeech.Noun
                            normalizedForm = matchedKey
                            reading = KanaConverter.toHiragana(combinedReading, convertLongVowelMark = false)
                            preMatchedWordId = null
                        }
                        result.add(SentenceWord(combinedWord, position, combinedLength))
                        i += 2
                        continue
                    }
                }

                result.add(sentence.words[i])
                i++
                continue
            }

            // A prefix mustn't eat the head below it (お|母上, not お母|上), unless the whole span is a word (お手紙).
            if (word.partOfSpeech == PartOfSpeech.Prefix && i + 2 < sentence.words.size &&
                prefixBreaksFollowingCompound(word, sentence.words[i + 1].word, sentence.words[i + 2].word)
            ) {
                result.add(sentence.words[i])
                i++
                continue
            }

            var bestMatch = 1
            var matchedNounText: String? = null
            for (windowSize in minOf(8, sentence.words.size - i) downTo 2) {
                var allValid = true
                var hasNameLikeToken = false
                for (j in 0 until windowSize) {
                    val w = sentence.words[i + j].word
                    // Counters only continue a compound, prefixes only start one (さん+なん must not become 三男).
                    val isNoun = (PosMapper.isNounForCompounding(w.partOfSpeech) &&
                        (j == 0 || w.partOfSpeech != PartOfSpeech.Prefix)) ||
                        (j > 0 && w.partOfSpeech == PartOfSpeech.Counter)
                    val isInnerConnector = j > 0 && j < windowSize - 1 &&
                        ((w.partOfSpeech == PartOfSpeech.Particle && w.text == "の") || isNameConnectorSymbol(w))
                    if (!isNoun && !isInnerConnector) {
                        allValid = false
                        break
                    }

                    // Hard pins (三つ目's ordinal 目) are never re-absorbed; soft pins are.
                    if (w.hardPinned && w.preMatchedWordId != null) {
                        allValid = false
                        break
                    }

                    if (isNoun && PosMapper.isNameLikeSudachiNoun(w.partOfSpeech, w.partOfSpeechSection1,
                            w.partOfSpeechSection2, w.partOfSpeechSection3)
                    ) hasNameLikeToken = true
                }

                if (!allValid) continue

                val combinedText = concatTokenTexts(sentence.words, i, windowSize)

                if (combinedText in JitenParser.NounCompoundExclusions) continue

                // Name-only matches need a name-like constituent; a fully-archaic entry can't absorb the span.
                var wordIds = lexicon.lookup(combinedText)
                if (wordIds != null && wordIds.isNotEmpty() && (hasNameLikeToken || hasUsableCompoundEntry(wordIds))) {
                    bestMatch = windowSize
                    matchedNounText = combinedText
                    break
                }

                val hiraganaText = KanaConverter.toHiragana(combinedText, convertLongVowelMark = false)
                if (hiraganaText != combinedText) {
                    wordIds = lexicon.lookup(hiraganaText)
                    if (wordIds != null && wordIds.isNotEmpty() && (hasNameLikeToken || hasUsableCompoundEntry(wordIds))) {
                        bestMatch = windowSize
                        matchedNounText = combinedText
                        break
                    }
                }
            }

            if (bestMatch > 1) {
                val matchPriority = getBestLookupPriority(matchedNounText!!)
                val afterMatchStart = i + bestMatch
                var shouldSkip = false

                var overlap = 1
                while (overlap < bestMatch && afterMatchStart - overlap >= i + 1) {
                    val altStart = afterMatchStart - overlap
                    val remaining = sentence.words.size - altStart
                    for (altSize in minOf(8, remaining) downTo overlap + 1) {
                        if (altStart + altSize <= afterMatchStart) continue

                        var altAllValid = true
                        for (aj in 0 until altSize) {
                            val aw = sentence.words[altStart + aj].word
                            val altInnerConnector = aj > 0 && aj < altSize - 1 &&
                                ((aw.partOfSpeech == PartOfSpeech.Particle && aw.text == "の") || isNameConnectorSymbol(aw))
                            if (!PosMapper.isNounForCompounding(aw.partOfSpeech) &&
                                !(aj > 0 && aw.partOfSpeech == PartOfSpeech.Counter) && !altInnerConnector
                            ) {
                                altAllValid = false
                                break
                            }
                        }
                        if (!altAllValid) continue

                        val altText = concatTokenTexts(sentence.words, altStart, altSize)
                        var altPriority = getBestLookupPriority(altText)
                        if (altPriority <= 0) {
                            val altHiragana = KanaConverter.toHiragana(altText, convertLongVowelMark = false)
                            if (altHiragana != altText) altPriority = getBestLookupPriority(altHiragana)
                        }

                        if (altPriority > matchPriority) {
                            var orphanViable = true
                            for (oi in i until altStart) {
                                if (getBestLookupPriority(sentence.words[oi].word.text) <= 0) {
                                    orphanViable = false
                                    break
                                }
                            }
                            if (orphanViable) {
                                shouldSkip = true
                                break
                            }
                        }
                    }
                    if (shouldSkip) break
                    overlap++
                }

                if (shouldSkip) {
                    result.add(sentence.words[i])
                    i++
                    continue
                }

                val combinedText = matchedNounText
                val combinedReading = concatTokenReadings(sentence.words, i, bestMatch)
                var combinedLength = 0
                for (j in 0 until bestMatch) combinedLength += sentence.words[i + j].length

                // Sudachi splits proper names into parts; the merged token must stay name-like.
                var sectionCarrier = sentence.words[i].word
                for (j in 0 until bestMatch) {
                    val candidate = sentence.words[i + j].word
                    if (PosMapper.isNameLikeSudachiNoun(candidate.partOfSpeech, candidate.partOfSpeechSection1,
                            candidate.partOfSpeechSection2, candidate.partOfSpeechSection3)
                    ) {
                        sectionCarrier = candidate
                        break
                    }

                    if (candidate.partOfSpeechSection1 != PartOfSpeechSection.None ||
                        candidate.partOfSpeechSection2 != PartOfSpeechSection.None ||
                        candidate.partOfSpeechSection3 != PartOfSpeechSection.None
                    ) {
                        sectionCarrier = candidate
                        break
                    }
                }

                val firstPos = sentence.words[i].word.partOfSpeech
                val combinedWord = sectionCarrier.copy().apply {
                    text = combinedText
                    dictionaryForm = combinedText
                    partOfSpeech = firstPos
                    normalizedForm = combinedText
                    reading = KanaConverter.toHiragana(combinedReading, convertLongVowelMark = false)
                    preMatchedWordId = null
                }

                result.add(SentenceWord(combinedWord, position, combinedLength))
                i += bestMatch
            } else {
                val nounVerb = tryCombineNounVerbStemCompound(sentence.words, i, word, length)
                if (nounVerb != null) {
                    result.add(SentenceWord(nounVerb.first, position, nounVerb.second))
                    i += 2
                } else {
                    result.add(sentence.words[i])
                    i++
                }
            }
        }

        sentence.replaceWords(result)
    }
}

/** Rejoins noun + terminal 連用形 nominalizations Sudachi splits (目|眩まし, 気|晴らし) into a JMDict noun. C# line 4222. */
internal fun JitenParser.tryCombineNounVerbStemCompound(
    words: MutableList<SentenceWord>,
    i: Int,
    word: WordInfo,
    length: Int,
): Pair<WordInfo, Int>? {
    if (i + 1 >= words.size || words[i + 1].word.partOfSpeech != PartOfSpeech.Verb) return null

    // A following auxiliary or verb means the stem is a real predicate (朝起き|た).
    if (i + 2 < words.size &&
        (words[i + 2].word.partOfSpeech == PartOfSpeech.Auxiliary || words[i + 2].word.partOfSpeech == PartOfSpeech.Verb)
    ) return null

    val nextWord = words[i + 1].word
    val combinedText = word.text + nextWord.text
    val ids = lexicon.lookup(combinedText)
    if (ids == null || ids.isEmpty() ||
        ids.all { lexicon.isNameOnly(it) } ||
        !ids.any { id -> lexicon.wordMeta(id)?.pos?.any(PosMapper::isNounForCompounding) == true }
    ) return null

    val combinedLength = length + words[i + 1].length
    val combinedWord = word.copy().apply {
        text = combinedText
        dictionaryForm = combinedText
        partOfSpeech = PartOfSpeech.Noun
        normalizedForm = combinedText
        reading = KanaConverter.toHiragana(word.reading + nextWord.reading, convertLongVowelMark = false)
        preMatchedWordId = null
    }
    return combinedWord to combinedLength
}

// C# line 4286
internal fun JitenParser.combineExpressions(sentences: List<SentenceInfo>) {
    for (sentence in sentences) combineExpressionsInSentence(sentence)
}

// C# line 4339
internal fun JitenParser.combineExpressionsInSentence(sentence: SentenceInfo) {
    if (sentence.words.size < 3) return

    val words = sentence.words
    val tokenHashes = LongArray(words.size)
    val tokenLens = IntArray(words.size)
    val tokenSafe = BooleanArray(words.size)
    for (w in words.indices) {
        val text = words[w].word.text
        tokenHashes[w] = hiraRollingHash(text)
        tokenLens[w] = text.length
        tokenSafe[w] = isHashFoldSafe(text)
    }

    val cumHash = LongArray(9)
    val cumLen = IntArray(9)
    val cumLenOk = BooleanArray(9)

    var result: MutableList<SentenceWord>? = null
    var i = 0

    while (i < sentence.words.size) {
        var bestMatch = 1
        var matchedText: String? = null

        val maxWindow = minOf(8, sentence.words.size - i)
        var cumBuilt = 0
        var safeUpTo = 0
        cumHash[0] = 0
        cumLen[0] = 0
        cumLenOk[0] = true

        for (windowSize in maxWindow downTo 2) {
            var hasParticle = false
            var hasSupplementarySymbol = false

            for (j in 0 until windowSize) {
                val w = sentence.words[i + j].word
                if (w.partOfSpeech == PartOfSpeech.SupplementarySymbol) {
                    hasSupplementarySymbol = true
                    break
                }

                if (w.partOfSpeech == PartOfSpeech.Particle) hasParticle = true
            }

            if (hasSupplementarySymbol) continue

            // Whitelist-only: no POS gate separates compositional pairs (すると) from units (どちらも).
            if (windowSize == 2) {
                // A hash miss proves a whitelist miss, skipping the concat allocation.
                var pairText: String? = null
                if (tokenLens[i + 1] >= hashBasePowers.size ||
                    (tokenHashes[i] * hashBasePowers[tokenLens[i + 1]] + tokenHashes[i + 1]) in
                    JitenParser.TwoTokenExpressionWhitelistHashes
                ) pairText = concatTokenTexts(sentence.words, i, 2)

                if (pairText != null && pairText in JitenParser.TwoTokenExpressionWhitelist) {
                    // The conjunction とはいえ never continues into ない (とは言えない).
                    val blockedByNegation = (pairText == "とはいえ" || pairText == "とは言え") &&
                        i + 2 < sentence.words.size &&
                        sentence.words[i + 2].word.text.let { it == "ない" || it == "ません" || it == "ん" || it == "なかった" }
                    // いいや = "nope" only clause-initially; 天気もいいや is いい+や.
                    val blockedNonInitial = pairText == "いいや" && i > 0
                    // Rhetorical ものか follows a predicate (負ける、ものか); after a nominal it's もの+か (どのようなものか).
                    var blockedMonoKa = false
                    if (pairText == "ものか" || pairText == "もんか") {
                        var p = i - 1
                        while (p >= 0 && sentence.words[p].word.partOfSpeech.let {
                                it == PartOfSpeech.SupplementarySymbol || it == PartOfSpeech.Symbol ||
                                    it == PartOfSpeech.BlankSpace
                            }
                        ) p--
                        blockedMonoKa = !(p >= 0 && sentence.words[p].word.partOfSpeech.let {
                            it == PartOfSpeech.Verb || it == PartOfSpeech.IAdjective || it == PartOfSpeech.Auxiliary
                        })
                    }

                    if (!blockedByNegation && !blockedNonInitial && !blockedMonoKa &&
                        !isExpressionBoundaryTheft(sentence.words, i, 2)
                    ) {
                        bestMatch = 2
                        matchedText = pairText
                        break
                    }
                } else {
                    val idiomDictForm = JitenParser.TwoTokenNounVerbIdioms[
                        sentence.words[i].word.text to sentence.words[i + 1].word.dictionaryForm]
                    if (idiomDictForm != null && !isExpressionBoundaryTheft(sentence.words, i, 2)) {
                        bestMatch = 2
                        matchedText = idiomDictForm
                        break
                    }
                }

                continue
            }

            if (!hasParticle) continue

            if (cumBuilt < windowSize) {
                for (k in cumBuilt + 1..windowSize) {
                    val t = i + k - 1
                    cumLen[k] = cumLen[k - 1] + tokenLens[t]
                    val ok = cumLenOk[k - 1] && cumLen[k] <= maxLookupKeyLength
                    cumLenOk[k] = ok
                    cumHash[k] = if (ok) cumHash[k - 1] * hashBasePowers[tokenLens[t]] + tokenHashes[t] else 0
                    if (safeUpTo == k - 1 && tokenSafe[t]) safeUpTo = k
                }

                cumBuilt = windowSize
            }

            val windowSafe = safeUpTo >= windowSize
            val surfaceMayMatch = !windowSafe ||
                (cumLenOk[windowSize] &&
                    (compoundHashSet.contains(cumHash[windowSize]) ||
                        cumHash[windowSize] in JitenParser.ExpressionExclusionHashes))

            var matched = false
            var combinedText: String? = null

            if (surfaceMayMatch) {
                val surface = concatTokenTexts(sentence.words, i, windowSize)
                combinedText = surface

                // Katakana variants too (コレは → これは).
                if (surface in JitenParser.ExpressionExclusions ||
                    KanaConverter.toHiragana(surface, convertLongVowelMark = false) in JitenParser.ExpressionExclusions
                ) continue

                matched = isExpressionLookupMatch(surface)

                if (!matched) matched = isMultiWordAdverbMatch(surface)

                // Clusters tagged conj/prt/aux, not exp (それ|と|も), which the gates above miss.
                if (!matched && windowSize == 3) matched = isFunctionClusterMatch(surface)
            }

            if (!matched) {
                val lastWord = sentence.words[i + windowSize - 1].word
                if ((lastWord.partOfSpeech == PartOfSpeech.Verb || lastWord.partOfSpeech == PartOfSpeech.IAdjective) &&
                    lastWord.dictionaryForm.isNotEmpty() &&
                    lastWord.dictionaryForm != lastWord.text
                ) {
                    val dictForm = lastWord.dictionaryForm
                    val prefixSafe = safeUpTo >= windowSize - 1
                    val prefixLenOk = cumLenOk[windowSize - 1]
                    val prefixHash = cumHash[windowSize - 1]
                    val prefixLen = cumLen[windowSize - 1]

                    if (prefixTailMayMatch(prefixSafe, prefixLenOk, prefixHash, prefixLen, dictForm)) {
                        val prefix = concatTokenTexts(sentence.words, i, windowSize - 1)
                        val dictCandidate = prefix + dictForm
                        if (isExpressionLookupMatch(dictCandidate)) {
                            matched = true
                            combinedText = dictCandidate
                        }
                    }

                    // DictionaryForm skips to the base (取られ → 取る), missing entries like 呆気に取られる.
                    if (!matched && windowSize <= 4) {
                        var prefix: String? = null
                        for (form in deconjugator.deconjugate(lastWord.text)) {
                            if (form.process.size > 2 || form.text == lastWord.text || form.text == dictForm) continue
                            if (!prefixTailMayMatch(prefixSafe, prefixLenOk, prefixHash, prefixLen, form.text)) continue
                            if (prefix == null) prefix = concatTokenTexts(sentence.words, i, windowSize - 1)
                            val candidate = prefix + form.text
                            if (isExpressionLookupMatch(candidate)) {
                                matched = true
                                combinedText = candidate
                                break
                            }
                        }
                    }
                }
                // Sudachi lexicalises なくなる as its own verb, so 元も子もなくなる needs its ない tail rebuilt.
                else if (lastWord.partOfSpeech == PartOfSpeech.Verb && lastWord.dictionaryForm == "なくなる") {
                    val naiCandidate = concatTokenTexts(sentence.words, i, windowSize - 1) + "ない"
                    if (isExpressionLookupMatch(naiCandidate)) {
                        matched = true
                        combinedText = naiCandidate
                    }
                }
            }

            // The completion paths rebuild combinedText (好まない → ことを好む) past the surface exclusion check.
            if (matched &&
                (combinedText!! in JitenParser.ExpressionExclusions ||
                    KanaConverter.toHiragana(combinedText, convertLongVowelMark = false) in JitenParser.ExpressionExclusions)
            ) continue

            if (matched && isExpressionBoundaryTheft(sentence.words, i, windowSize)) continue

            if (matched) {
                bestMatch = windowSize
                matchedText = combinedText
                break
            }
        }

        if (bestMatch == 1 && i + 1 < sentence.words.size) {
            val nextW = sentence.words[i + 1].word
            if (nextW.text == "と" && nextW.partOfSpeech == PartOfSpeech.Particle) {
                var advMayMatch = true
                if (tokenSafe[i]) {
                    val advLen = tokenLens[i] + 1
                    advMayMatch = tokenLens[i] <= maxLookupKeyLength &&
                        advLen <= maxLookupKeyLength &&
                        compoundHashSet.contains(tokenHashes[i] * HASH_BASE + 'と'.code)
                }

                if (advMayMatch) {
                    val adverbCandidate = sentence.words[i].word.text + "と"
                    val adverbHiragana = KanaConverter.toHiragana(adverbCandidate, convertLongVowelMark = false)
                    val advWordIds = lexicon.lookup(adverbCandidate) ?: lexicon.lookup(adverbHiragana)
                    if (advWordIds != null && advWordIds.isNotEmpty() && lexicon.anyWithPos(advWordIds, PartOfSpeech.Adverb)) {
                        bestMatch = 2
                        matchedText = adverbCandidate
                    }
                }
            }
        }

        if (bestMatch > 1) {
            val dictForm = matchedText!!
            val originalText = concatTokenTexts(sentence.words, i, bestMatch)
            val combinedReading = concatTokenReadings(sentence.words, i, bestMatch)
            var combinedLength = 0
            for (j in 0 until bestMatch) combinedLength += sentence.words[i + j].length

            val combinedWord = WordInfo().apply {
                text = originalText
                dictionaryForm = dictForm
                partOfSpeech = PartOfSpeech.Expression
                normalizedForm = dictForm
                reading = KanaConverter.toHiragana(combinedReading, convertLongVowelMark = false)
                preMatchedWordId = null
            }

            val r = result ?: ArrayList<SentenceWord>(sentence.words.size).also {
                for (j in 0 until i) it.add(sentence.words[j])
                result = it
            }
            r.add(SentenceWord(combinedWord, sentence.words[i].position, combinedLength))
            i += bestMatch
        } else {
            result?.add(sentence.words[i])
            i++
        }
    }

    result?.let { sentence.replaceWords(it) }
}

// C# line 4640
internal fun JitenParser.isExpressionLookupMatch(text: String): Boolean =
    lexicon.hasLookupWhere(text, JitenLexicon.KanaFallback.ToHiraganaKeepLongVowelMark) { ids -> ids.any(lexicon::isExpression) }

// C# line 4644
internal fun JitenParser.isMultiWordAdverbMatch(text: String): Boolean =
    lexicon.hasLookupWhere(text, JitenLexicon.KanaFallback.ToHiraganaKeepLongVowelMark) { ids ->
        lexicon.anyWithPos(ids, PartOfSpeech.Adverb)
    }

/** Clusters JMDict tags prt/conj/aux rather than exp (それとも, ものか). C# line 4649. */
internal fun JitenParser.isFunctionClusterMatch(text: String): Boolean =
    lexicon.hasLookupWhere(text, JitenLexicon.KanaFallback.ToHiraganaKeepLongVowelMark) { ids ->
        lexicon.anyWithPos(ids, PartOfSpeech.Particle) ||
            lexicon.anyWithPos(ids, PartOfSpeech.Conjunction) ||
            lexicon.anyWithPos(ids, PartOfSpeech.Auxiliary)
    }

/** Rejects merges stealing a neighbour's token: a name's honorific (ヴァレリア様|に言わせれば) or だ of だろう. C# line 4656. */
internal fun JitenParser.isExpressionBoundaryTheft(words: List<SentenceWord>, start: Int, windowSize: Int): Boolean {
    val first = words[start].word
    if (start > 0 && first.text in TransitionRuleSets.HonorificSuffixes &&
        words[start - 1].word.partOfSpeech.let {
            it == PartOfSpeech.Noun || it == PartOfSpeech.CommonNoun || it == PartOfSpeech.Pronoun
        }
    ) return true

    val nextIdx = start + windowSize
    if (nextIdx < words.size) {
        val next = words[nextIdx].word
        if (next.partOfSpeech != PartOfSpeech.Particle && next.partOfSpeech != PartOfSpeech.Auxiliary &&
            next.partOfSpeech != PartOfSpeech.SupplementarySymbol
        ) {
            val concat = words[start + windowSize - 1].word.text + next.text
            if (isCohesiveFunctionWord(concat)) return true
        }
    }

    return false
}

// C# line 4680
internal fun JitenParser.isCohesiveFunctionWord(text: String): Boolean =
    lexicon.hasLookupWhere(text, JitenLexicon.KanaFallback.ToHiraganaKeepLongVowelMark) { ids ->
        lexicon.anyWithPos(ids, PartOfSpeech.Auxiliary) ||
            lexicon.anyWithPos(ids, PartOfSpeech.Adverb) ||
            lexicon.anyWithPos(ids, PartOfSpeech.Conjunction) ||
            lexicon.anyWithPos(ids, PartOfSpeech.Particle) ||
            lexicon.anyWithPos(ids, PartOfSpeech.Expression)
    }

// C# line 4688
internal fun JitenParser.cleanSentenceTokens(sentences: List<SentenceInfo>) {
    for (sentence in sentences) {
        for ((word, _, _) in sentence.words) {
            // Symbols stay as boundary markers.
            if (word.partOfSpeech == PartOfSpeech.SupplementarySymbol) continue

            word.text = JitenParser.TokenCleanRegex.replace(word.text, "")
            word.text = JitenParser.SmallTsuLongVowelRegex.replace(word.text, "")
        }

        sentence.words.removeAll { it.word.text.isBlank() && it.word.partOfSpeech != PartOfSpeech.SupplementarySymbol }
    }
}

/** C#'s kept tuple (flatIdx, word, pi, ni, prevDropped, nextDropped). */
private data class KeptGateWord(
    val flatIdx: Int,
    val word: DeckWord,
    val pi: Int,
    val ni: Int,
    val prevDropped: Boolean,
    val nextDropped: Boolean,
)

/** C#'s EvaluateAt result: the decision plus its out pi/ni. */
private data class GateEvaluation(val decision: MisparseDecision, val pi: Int, val ni: Int)

// C# line 4707 (JmDictCache.GetWordsAsync = getWordsWithCache(ids, null))
internal fun JitenParser.applyMisparseGates(sentences: List<SentenceInfo>, corrected: MutableList<DeckWord>): MutableList<DeckWord> {
    if (corrected.isEmpty()) return corrected

    val wordIds = corrected.map { it.wordId }.distinct()
    val wordData = getWordsWithCache(wordIds, null)

    val flatTokens = ArrayList<WordInfo>()
    val sentenceInitial = ArrayList<Boolean>()
    // Symbols in the gap before each token, so gates see its frame; whitespace is transparent, sentence ends are hard.
    val symbolsBefore = ArrayList<String>()
    var pendingSymbols = ""
    for (sentence in sentences) {
        var first = true
        // After an opening quote the next content token is initial, even after a speaker tag (【テオドール】「ああ).
        var afterOpeningQuote = false
        for ((word, _, _) in sentence.words) {
            if (word.partOfSpeech == PartOfSpeech.SupplementarySymbol) {
                if (word.text.isNotEmpty() && word.text.last() in JitenParser.OpeningQuoteChars) afterOpeningQuote = true
                pendingSymbols += word.text
                continue
            }
            if (word.partOfSpeech == PartOfSpeech.BlankSpace) {
                // Whitespace keeps no gap of its own, or neighbour walks would double-count symbols.
                flatTokens.add(word)
                sentenceInitial.add(first || afterOpeningQuote)
                symbolsBefore.add("")
                continue
            }
            flatTokens.add(word)
            sentenceInitial.add(first || afterOpeningQuote)
            symbolsBefore.add(pendingSymbols)
            pendingSymbols = ""
            first = false
            afterOpeningQuote = false
        }
        pendingSymbols += "\n"
    }
    val trailingSymbols = pendingSymbols

    val droppedByGate = HashMap<Int, String>()
    var ci = 0

    // A stutter-dedup drop's surviving twin is vocabulary (はいはい), not burst material.
    fun isGateDropped(idx: Int): Boolean = idx >= 0 && idx < flatTokens.size &&
        droppedByGate[idx].let { gate -> gate != null && gate != "kana-stutter-before-word" }

    // pi/ni are the neighbours whose drop state the decision read; -1 when it read none.
    fun evaluateAt(i: Int, deckWord: DeckWord): GateEvaluation {
        val token = flatTokens[i]

        // Rewrite-rule pins (え|っつった) are never SFX; PreMatchedWordId alone isn't enough (ぐすっ → 具す would return).
        if (token.pinnedByRewriteRule && token.preMatchedWordId != null) return GateEvaluation(MisparseDecision(), -1, -1)

        if (!MisparseGates.mayBeKanaFragment(token.text) &&
            (deckWord.originalText.isEmpty() || !MisparseGates.mayBeKanaFragment(deckWord.originalText))
        ) return GateEvaluation(MisparseDecision(), -1, -1)

        val jmWord = wordData[deckWord.wordId]
        val (isUk, hasKanji, readingIsIchi, attestsForm, attestsLiterally) = MisparseGates.getWordFlags(
            jmWord, deckWord.readingIndex,
            if (deckWord.originalText.isNotEmpty()) deckWord.originalText else token.text,
        )

        // Empty remnants are skipped as neighbours, but their symbol gaps still count.
        var pi = i - 1
        var spaceBefore = false
        while (pi >= 0 && (flatTokens[pi].text.isEmpty() || flatTokens[pi].partOfSpeech == PartOfSpeech.BlankSpace)) {
            if (flatTokens[pi].partOfSpeech == PartOfSpeech.BlankSpace) spaceBefore = true
            pi--
        }
        val prev = if (pi >= 0) flatTokens[pi] else null

        var ni = i + 1
        var symAfter = ""
        var spaceAfter = false
        while (ni < flatTokens.size) {
            symAfter += symbolsBefore[ni]
            val niIsBlank = flatTokens[ni].partOfSpeech == PartOfSpeech.BlankSpace
            if (niIsBlank) spaceAfter = true
            if (flatTokens[ni].text.isNotEmpty() && !niIsBlank) break
            ni++
        }
        if (ni >= flatTokens.size) symAfter += trailingSymbols
        val next = if (ni < flatTokens.size) flatTokens[ni] else null

        val prevDropped = isGateDropped(pi)
        val nextDropped = isGateDropped(ni)

        // Scraps flanking a token form one blob (ず|がんっ); a non-word blob has no word fragments.
        // Symbols split blobs (って、くっさ), and a vowel after a verb is its elongation (わかる|う).
        val prevIsShard = (MisparseGates.isKanaShardNeighbour(prev) || prevDropped) &&
            symbolsBefore[i].isEmpty() && !spaceBefore
        var nextIsShard = (MisparseGates.isKanaShardNeighbour(next) || nextDropped) &&
            symAfter.isEmpty() && !spaceAfter
        if (nextIsShard && next != null &&
            (token.partOfSpeech == PartOfSpeech.Verb || token.partOfSpeech == PartOfSpeech.IAdjective) &&
            next.text.all { it in "あいうえおぁぃぅぇぉーっッ" }
        ) nextIsShard = false
        var blobUnattested = false
        if (prevIsShard || nextIsShard) {
            val blob = (if (prevIsShard) prev!!.text else "") +
                (if (deckWord.originalText.isNotEmpty()) deckWord.originalText else token.text) +
                (if (nextIsShard) next!!.text else "")
            blobUnattested = !lexicon.containsKey(blob)
        }

        val ctx = MisparseGateContext(token, deckWord, prev, next, isUk, hasKanji, readingIsIchi,
            sentenceInitial[i],
            symbolsBefore[i],
            symAfter,
            attestsForm,
            attestsLiterally,
            blobUnattested,
            prevDropped,
            nextDropped)

        return GateEvaluation(MisparseGates.evaluate(ctx), pi, ni)
    }

    val kept = ArrayList<KeptGateWord>(corrected.size)
    for (i in flatTokens.indices) {
        if (ci >= corrected.size) break
        if (flatTokens[i].resolvedWordId == null) continue

        val deckWord = corrected[ci++]
        val (decision, pi, ni) = evaluateAt(i, deckWord)
        if (decision.isMisparsed) {
            droppedByGate[i] = decision.gateId ?: ""
            continue
        }

        kept.add(KeptGateWord(i, deckWord, pi, ni, isGateDropped(pi), isGateDropped(ni)))
    }

    // A drop can orphan its neighbour (ず|がんっ: がん falls, then ず), so re-check until stable.
    for (pass in 0 until 3) {
        var changed = false
        for (k in kept.indices.reversed()) {
            val (fi, deckWord, pi, ni, prevDropped, nextDropped) = kept[k]
            // Gates read other decisions only through these two flags; unchanged flags repeat the keep.
            if (isGateDropped(pi) == prevDropped && isGateDropped(ni) == nextDropped) continue

            val (decision, newPi, newNi) = evaluateAt(fi, deckWord)
            if (!decision.isMisparsed) {
                kept[k] = KeptGateWord(fi, deckWord, newPi, newNi, isGateDropped(newPi), isGateDropped(newNi))
                continue
            }

            droppedByGate[fi] = decision.gateId ?: ""
            kept.removeAt(k)
            changed = true
        }
        if (!changed) break
    }

    val result = ArrayList<DeckWord>(corrected.size)
    for (kw in kept) {
        flatTokens[kw.flatIdx].keptForm = WordForm(kw.word.wordId, kw.word.readingIndex)
        result.add(kw.word)
    }

    while (ci < corrected.size) result.add(corrected[ci++])

    return result
}

// --- ported (needed by the skeleton) ---

internal fun JitenParser.excludeFinalMisparses(words: Iterable<DeckWord>): MutableList<DeckWord> {
    if (JitenParser.ExcludedMisparses.isEmpty()) return words.toMutableList()
    return words.filterTo(ArrayList()) { WordForm(it.wordId, it.readingIndex) !in JitenParser.ExcludedMisparses }
}

internal inline fun <T> concatSlice(list: List<T>, start: Int, count: Int, selector: (T) -> String): String {
    if (count == 1) return selector(list[start])
    val sb = StringBuilder()
    for (idx in 0 until count) sb.append(selector(list[start + idx]))
    return sb.toString()
}

internal fun concatTokenTexts(tokens: List<SentenceWord>, start: Int, count: Int) = concatSlice(tokens, start, count) { it.word.text }

internal fun concatTokenReadings(tokens: List<SentenceWord>, start: Int, count: Int) =
    concatSlice(tokens, start, count) { it.word.reading }

internal fun concatWordInfoTexts(wordInfos: List<WordInfo>, start: Int, count: Int) = concatSlice(wordInfos, start, count) { it.text }

internal fun concatWordInfoReadings(wordInfos: List<WordInfo>, start: Int, count: Int) =
    concatSlice(wordInfos, start, count) { it.reading }
