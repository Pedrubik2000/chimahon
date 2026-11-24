// Ported from Jiten (github.com/Sirush/Jiten, Apache-2.0, commit 44b48a4): Jiten.Parser/Parser.cs lines 2797-3972
// (CombineCompounds, lexical adverbs, long vowels, orphaned misparses, unknown nouns, prefixes, compound verb tails,
// lookup helpers, trailing particles)
//
// Conventions (see JitenParser.kt): `internal fun JitenParser.x(...)`; C# `out` parameters become a nullable return
// (null = false); diagnostics are left out. Parser.cs lines 3219 (HasLookupForCompound) and 3677-3884 (LookupWithKanaFallback,
// KanaFallback, HasLookupWhere, AnyWithPos, AnyNonNameWithPos, GetNonNameCompoundId, GetBestNonNameFrequencyRank,
// HasNonNameLookup, HasVerbOrAdjectiveLookup, HasExpressionLookup, HasCounterSenseAvailable, IsKanaAppropriateId,
// HasKanaAppropriateLookup, HasSuruVerbLookup, HasPrioritizedNonNameLookup) are already JitenLexicon methods
// (lexicon.hasLookupWhere(text, JitenLexicon.KanaFallback.X) { ids -> ... } with ids an IntArray): call those, don't redefine.
// Static tables only this range uses (BoundPrefixKanji, CompoundVerbHeads, TrailingParticles, ArchaicOnlyPriorityFloor)
// go in this file as private top-level vals; ParticleExpressionOpeners, LexicalizedAdverbPairs and NounCompoundExclusions
// are already in JitenParser's companion (shared with other ranges).
// C#'s try/catch around KanaConverter.ToHiragana is dropped: the Kotlin conversion (JapaneseTextHelper) never throws.
package chimahon.lang.jiten

import chimahon.lang.jiten.PartOfSpeech as P

/** C#'s `sentence.Words = result`. */
private fun SentenceInfo.setWords(result: List<SentenceWord>) {
    words.clear()
    words.addAll(result)
}

internal fun JitenParser.combineCompounds(sentences: List<SentenceInfo>) {
    for (sentence in sentences) {
        if (sentence.words.size < 2) continue

        val words = sentence.words
        val wordInfos = ArrayList<WordInfo>(words.size)
        for (w in words.indices) wordInfos.add(words[w].word)

        val tokenHashes = LongArray(wordInfos.size)
        for (w in wordInfos.indices) tokenHashes[w] = hiraRollingHash(wordInfos[w].text)

        // Built right-to-left and only once a compound is found; untouched sentences keep their list.
        var result: ArrayList<SentenceWord>? = null

        var i = wordInfos.size - 1
        while (i >= 0) {
            val word = wordInfos[i]

            // Na-adjectives anchor expression windows only (見るも無残), like nouns; plain compounds would over-merge.
            if (word.partOfSpeech == P.Verb || word.partOfSpeech == P.IAdjective || word.partOfSpeech == P.Expression ||
                word.partOfSpeech == P.Suffix || word.partOfSpeech == P.Noun || word.partOfSpeech == P.CommonNoun ||
                word.partOfSpeech == P.Name || word.partOfSpeech == P.NaAdjective
            ) {
                val nounTrigger = word.partOfSpeech == P.Noun || word.partOfSpeech == P.CommonNoun ||
                    word.partOfSpeech == P.Name || word.partOfSpeech == P.NaAdjective
                var match = tryMatchCompounds(wordInfos, i, tokenHashes, forceExpressionOnly = nounTrigger)

                // Hard pins are never swallowed (ぶん in ４分の１ ≠ ４分); soft pins stay absorbable (臆病風に吹かれる).
                if (match != null) {
                    var j = match.startIndex
                    while (j <= i && match != null) {
                        if (wordInfos[j].hardPinned && wordInfos[j].preMatchedWordId != null) match = null
                        j++
                    }
                }

                if (match != null) {
                    val (startIndex, dictForm, wordId) = match

                    val startPosition = sentence.words[startIndex].position
                    var combinedLength = 0
                    for (j in startIndex..i) combinedLength += sentence.words[j].length

                    val originalText = concatWordInfoTexts(wordInfos, startIndex, i - startIndex + 1)
                    val combinedReading = concatWordInfoReadings(wordInfos, startIndex, i - startIndex + 1)

                    var conjugations: MutableList<String>? = null
                    if (originalText != dictForm) {
                        val hiraText = KanaConverter.toHiragana(originalText)
                        val hiraDictForm = KanaConverter.toHiragana(dictForm)
                        if (hiraText != hiraDictForm) {
                            val matchingForm = deconjugator.deconjugate(hiraText).firstOrNull { it.text == hiraDictForm }
                            if (matchingForm != null) conjugations = matchingForm.process.toMutableList()
                        }
                    }

                    val combinedWordInfo = WordInfo().apply {
                        text = originalText
                        dictionaryForm = dictForm
                        partOfSpeech = P.Expression
                        normalizedForm = dictForm
                        reading = KanaConverter.toHiragana(combinedReading)
                        preMatchedWordId = wordId
                        preMatchedConjugations = conjugations
                    }

                    val res = result ?: ArrayList<SentenceWord>(sentence.words.size).also {
                        for (j in wordInfos.size - 1 downTo i + 1) it.add(sentence.words[j])
                        result = it
                    }
                    res.add(SentenceWord(combinedWordInfo, startPosition, combinedLength))
                    i = startIndex - 1 // C#: i = startIndex; continue (the for loop's i--)
                    continue
                }
            }

            result?.add(sentence.words[i])
            i--
        }

        val res = result ?: continue
        res.reverse()
        sentence.setWords(res)
    }
}

/** Merges X的+に, curated X+に pairs and lexicalized Noun+ほど (山ほど; 三日ほど stays split). */
internal fun JitenParser.combineLexicalAdverbs(sentences: List<SentenceInfo>) {
    for (sentence in sentences) {
        val words = sentence.words
        if (words.size < 2) continue

        val result = ArrayList<SentenceWord>(words.size)
        var changed = false

        var i = 0
        while (i < words.size) {
            val word = words[i].word

            // としたら is suppositional (ためだとしたら) except after a volitional, where it's とする "try to" (押そうとしたら).
            if (i + 1 < words.size &&
                word.text == "と" && word.partOfSpeech == P.Particle &&
                words[i + 1].word.text.let { it == "したら" || it == "すれば" || it == "すると" } &&
                words[i + 1].word.dictionaryForm.let { it == "する" || it == "為る" } &&
                !(i > 0 && isVolitionalSurface(words[i - 1].word.text)) &&
                hasConjunctionEntry(word.text + words[i + 1].word.text)
            ) {
                val next = words[i + 1]
                val combinedText = word.text + next.word.text
                val combinedReading = word.reading + next.word.reading
                val merged = word.copy().apply {
                    text = combinedText
                    dictionaryForm = combinedText
                    normalizedForm = combinedText
                    reading = combinedReading
                    partOfSpeech = P.Conjunction
                    endOffset = next.word.endOffset
                }
                result.add(SentenceWord(merged, words[i].position, words[i].length + next.length))
                changed = true
                i += 2
                continue
            }

            if (i + 1 < words.size &&
                (word.partOfSpeech == P.Noun || word.partOfSpeech == P.CommonNoun ||
                    word.partOfSpeech == P.NaAdjective || word.partOfSpeech == P.NominalAdjective)
            ) {
                val next = words[i + 1]
                val isNiPair = next.word.text == "に" &&
                    (next.word.partOfSpeech == P.Particle || next.word.partOfSpeech == P.Auxiliary) &&
                    word.text.endsWith('的')
                val isCuratedPair = (next.word.text == "に" || next.word.text == "でも") &&
                    (word.text + next.word.text) in JitenParser.LexicalizedAdverbPairs
                val isHodoPair = next.word.text == "ほど" && next.word.partOfSpeech == P.Particle
                // 先に tails always merge (一足先に); には only clause-initially, since mid-clause it's case+topic (その時には).
                val isSakiNiPair = next.word.text == "先に"
                val isNiwaPair = next.word.text == "には" && next.word.partOfSpeech == P.Particle &&
                    (i == 0 || words[i - 1].word.partOfSpeech.let { it == P.Symbol || it == P.SupplementarySymbol || it == P.BlankSpace })

                if ((isNiPair || isCuratedPair || isHodoPair || isSakiNiPair || isNiwaPair) &&
                    hasLexicalAdverbEntry(word.text + next.word.text)
                ) {
                    val combinedText = word.text + next.word.text
                    val combinedReading = word.reading + next.word.reading
                    val merged = word.copy().apply {
                        text = combinedText
                        dictionaryForm = combinedText
                        normalizedForm = combinedText
                        reading = combinedReading
                        partOfSpeech = P.Adverb
                        endOffset = next.word.endOffset
                    }

                    result.add(SentenceWord(merged, words[i].position, words[i].length + next.length))
                    changed = true
                    i += 2
                    continue
                }
            }

            result.add(words[i])
            i++
        }

        if (changed) sentence.setWords(result)
    }
}

internal fun JitenParser.hasLexicalAdverbEntry(text: String): Boolean =
    lexicon.hasLookupWhere(text, JitenLexicon.KanaFallback.ToNormalizedHiragana) { lexicon.anyNonNameWithPos(it, P.Adverb) }

internal fun JitenParser.hasConjunctionEntry(text: String): Boolean =
    lexicon.hasLookupWhere(text, JitenLexicon.KanaFallback.None) { lexicon.anyNonNameWithPos(it, P.Conjunction) }

internal fun JitenParser.isVolitionalSurface(text: String): Boolean {
    if (text.length < 2 || text[text.length - 1] != 'う') return false
    return "おこそとのほもよろごぞどぼぽょ".indexOf(text[text.length - 2]) >= 0
}

internal fun JitenParser.repairLongVowels(sentences: List<SentenceInfo>) {
    for (sentence in sentences) {
        val words = sentence.words
        if (words.isEmpty()) continue

        val result = ArrayList<SentenceWord>(words.size)
        var i = words.size - 1

        while (i >= 0) {
            val word = words[i].word

            if (word.text == "ー" && word.partOfSpeech == P.SupplementarySymbol) {
                if (i == 0) {
                    i--
                    continue
                }

                // Sudachi splits あげるー into あげ+る+ー.
                if (i >= 2) {
                    val detached = words[i - 1].word
                    val verbBefore = words[i - 2].word
                    if ((detached.text == "る" || detached.text == "す") &&
                        (detached.partOfSpeech == P.Noun || detached.partOfSpeech == P.Verb || detached.partOfSpeech == P.Auxiliary) &&
                        (verbBefore.partOfSpeech == P.Verb || verbBefore.partOfSpeech == P.Auxiliary)
                    ) {
                        verbBefore.text += detached.text
                        val cLen = words[i - 2].length + words[i - 1].length
                        result.add(SentenceWord(verbBefore, words[i - 2].position, cLen))
                        i -= 3
                        continue
                    }
                }

                var found = false
                for (combineCount in minOf(4, i) downTo 1) {
                    val combined = concatTokenTexts(words, i - combineCount, combineCount)

                    val withBar = combined + "ー"
                    val withBarHiragana = KanaConverter.toHiragana(withBar)

                    if (lexicon.lookup(withBarHiragana)?.isNotEmpty() == true) {
                        val first = words[i - combineCount]
                        first.word.text = withBar
                        var cLen = 0
                        for (j in i - combineCount..i) cLen += words[j].length
                        result.add(SentenceWord(first.word, first.position, cLen))
                        i -= combineCount + 1
                        found = true
                        break
                    }

                    val withoutBarHiragana = KanaConverter.toHiragana(combined)

                    if (lexicon.lookup(withoutBarHiragana)?.isNotEmpty() == true) {
                        val first = words[i - combineCount]
                        first.word.text = combined
                        var cLen = 0
                        for (j in i - combineCount until i) cLen += words[j].length
                        result.add(SentenceWord(first.word, first.position, cLen))
                        i -= combineCount + 1
                        found = true
                        break
                    }
                }

                if (!found) {
                    val prev = words[i - 1]
                    if (!prev.word.text.endsWith('ー')) {
                        prev.word.text += "ー"
                        words[i - 1] = SentenceWord(prev.word, prev.position, prev.length + words[i].length)
                    }
                    i--
                }

                continue
            }

            if (word.text.endsWith('ー') && word.text != "ー") {
                val withoutBar = word.text.replace("ー", "")
                if (withoutBar.length > 2 && WanaKana.isKatakana(withoutBar)) {
                    result.add(words[i])
                    i--
                    continue
                }

                val maxBackward = minOf(5, i)
                var merged = false

                for (k in maxBackward downTo 1) {
                    var hasParticle = false
                    for (j in i - k until i) {
                        val w = words[j].word
                        if (w.partOfSpeech == P.Particle && w.dictionaryForm == w.text) {
                            hasParticle = true
                            break
                        }
                    }
                    if (hasParticle) continue

                    var candidateSurface = concatTokenTexts(words, i - k, k + 1)
                    candidateSurface = JitenParser.MultiLongVowelRegex.replace(candidateSurface, "ー")

                    if (tryLongVowelLookup(candidateSurface, useKanaNormalizer = false)) {
                        val first = words[i - k]
                        first.word.text = candidateSurface
                        var cLen = 0
                        for (j in i - k..i) cLen += words[j].length
                        result.add(SentenceWord(first.word, first.position, cLen))
                        i -= k + 1
                        merged = true
                        break
                    }

                    val candidateKey = candidateSurface.trimEnd('ー')
                    if (candidateKey.isNotEmpty() && tryLongVowelLookup(candidateKey)) {
                        val first = words[i - k]
                        first.word.text = candidateSurface
                        var cLen = 0
                        for (j in i - k..i) cLen += words[j].length
                        result.add(SentenceWord(first.word, first.position, cLen))
                        i -= k + 1
                        merged = true
                        break
                    }
                }

                if (!merged && i > 0 && (word.text == "るー" || word.text == "すー")) {
                    val prev = words[i - 1]
                    val kana = word.text[0].toString()
                    val combined = prev.word.text + kana

                    val shouldMerge =
                        tryLongVowelLookup(combined) || tryDeconjugatedLongVowelLookup(combined) ||
                            (word.text == "るー" && (prev.word.partOfSpeech == P.Verb || prev.word.partOfSpeech == P.Auxiliary)) ||
                            (word.text == "すー" && prev.word.text.endsWith('で'))

                    if (shouldMerge) {
                        prev.word.text = combined
                        val cLen = words[i - 1].length + words[i].length
                        result.add(SentenceWord(prev.word, prev.position, cLen))
                        i -= 2
                        merged = true
                    }
                }

                if (!merged) {
                    if (!tryLongVowelLookup(word.text, useKanaNormalizer = false)) {
                        val singleKey = word.text.trimEnd('ー')

                        if (!(singleKey.length == 2 &&
                                word.partOfSpeech != P.Auxiliary && word.partOfSpeech != P.Particle && word.partOfSpeech != P.Verb &&
                                !(word.partOfSpeech == P.Noun && !word.dictionaryForm.contains('ー')) &&
                                tryResolveAdjStem(word, singleKey)) &&
                            !tryLongVowelLookup(word.text)
                        ) {
                            if (singleKey.isNotEmpty() &&
                                (tryLongVowelLookup(singleKey) || tryDeconjugatedLongVowelLookup(singleKey))
                            ) {
                                if (word.dictionaryForm.isEmpty() ||
                                    word.dictionaryForm == word.text ||
                                    !tryLongVowelLookup(word.dictionaryForm)
                                ) {
                                    word.text = singleKey
                                }
                            }
                        }
                    }

                    result.add(words[i])
                    i--
                }

                continue
            }

            result.add(words[i])
            i--
        }

        result.reverse()
        sentence.setWords(result)
    }
}

internal fun JitenParser.tryLongVowelLookup(text: String, useKanaNormalizer: Boolean = true): Boolean {
    if (lexicon.lookup(text)?.isNotEmpty() == true) return true

    val hira = KanaConverter.toHiragana(text, convertLongVowelMark = false)

    if (hira != text && lexicon.lookup(hira)?.isNotEmpty() == true) return true

    if (!useKanaNormalizer) return false

    val normalized = KanaNormalizer.normalize(hira)
    return normalized != hira && lexicon.lookup(normalized)?.isNotEmpty() == true
}

/** Slang elongation as stem+い: ヤバー → ヤバい, スゴー → スゴい. */
internal fun JitenParser.tryResolveAdjStem(word: WordInfo, stem: String): Boolean {
    val hira = KanaNormalizer.normalize(KanaConverter.toHiragana(stem, convertLongVowelMark = false))

    for (form in deconjugator.deconjugate(hira)) {
        if (form.tags.any { it == "adj-i" } && form.text == hira + "い" && hasIAdjectiveLookup(form.text)) {
            word.text = stem + "い"
            word.dictionaryForm = stem + "い"
            word.partOfSpeech = P.IAdjective
            return true
        }
    }

    return false
}

/** Must be a real i-adjective; bare existence lets noun keys hijack interjections (いえー → 遺影). */
internal fun JitenParser.hasIAdjectiveLookup(text: String): Boolean =
    lexicon.hasLookupWhere(text, JitenLexicon.KanaFallback.None) { lexicon.anyWithPos(it, P.IAdjective) }

internal fun JitenParser.tryDeconjugatedLongVowelLookup(candidateKey: String): Boolean {
    val hira = KanaNormalizer.normalize(KanaConverter.toHiragana(candidateKey, convertLongVowelMark = false))

    for (form in deconjugator.deconjugate(hira))
        if (tryLongVowelLookup(form.text)) return true

    return false
}

internal fun JitenParser.filterOrphanedMisparses(sentences: List<SentenceInfo>) {
    for (sentence in sentences) {
        val words = sentence.words
        var anyFiltered = false
        val result = ArrayList<SentenceWord>(words.size)

        for (i in words.size - 1 downTo 0) {
            val word = words[i].word

            val nextIsLongVowel = result.isNotEmpty() && result[result.size - 1].word.text == "ー"

            // A pin is a repair stage's word decision, never noise (ソ in 親ソ; す as する, not すんだ=済んだ).
            val shouldFilter = word.text in JitenParser.MisparsesRemove &&
                word.preMatchedWordId == null &&
                !(nextIsLongVowel && word.text.length == 1 && JapaneseTextHelper.isAllKana(word.text)) &&
                !word.text.endsWith('ー')

            val isSingleKanaStutter = !nextIsLongVowel && word.text.length == 1 && JapaneseTextHelper.isAllKana(word.text) &&
                word.preMatchedWordId == null
            if (shouldFilter ||
                word.partOfSpeech == P.Noun && !nextIsLongVowel && (isSingleKanaStutter || word.text == "エナ" || word.text == "えな") ||
                word.partOfSpeech == P.Symbol && isSingleKanaStutter
            ) {
                // The shard may be a cut-off first mora (１つ|ま|ねた → 真似た), so try joining before deleting.
                val joinedDictForm = if (result.isNotEmpty()) tryJoinShredWithFollowing(word, result[result.size - 1].word) else null
                if (joinedDictForm != null) {
                    val following = result[result.size - 1]
                    val joinedText = word.text + following.word.text
                    val joinedReading = word.reading + following.word.reading
                    val joinedStart = if (word.startOffset >= 0) word.startOffset else following.word.startOffset
                    val joined = following.word.copy().apply {
                        text = joinedText
                        dictionaryForm = joinedDictForm
                        normalizedForm = joinedDictForm
                        reading = joinedReading
                        partOfSpeech = P.Verb
                        startOffset = joinedStart
                    }
                    result[result.size - 1] = SentenceWord(joined, words[i].position, words[i].length + following.length)
                    anyFiltered = true
                    continue
                }

                anyFiltered = true
                continue
            }

            result.add(words[i])
        }

        if (anyFiltered) {
            result.reverse()
            sentence.setWords(result)
        }
    }
}

/** The join must deconjugate to a verb/adjective (まねた → 真似る); noun keys bring back collisions. */
internal fun JitenParser.tryJoinShredWithFollowing(shred: WordInfo, following: WordInfo): String? {
    if (!JapaneseTextHelper.isAllKana(shred.text) || following.text.isEmpty() || !JapaneseTextHelper.isAllKana(following.text))
        return null
    if (following.partOfSpeech == P.SupplementarySymbol || following.partOfSpeech == P.Symbol ||
        following.partOfSpeech == P.Particle || following.partOfSpeech == P.Auxiliary || following.partOfSpeech == P.BlankSpace
    ) return null

    val joined = KanaConverter.toHiragana(shred.text + following.text, convertLongVowelMark = false)

    for (form in deconjugator.deconjugate(joined)) {
        if (form.process.isEmpty() || form.tags.isEmpty()) continue
        val lastTag = form.tags[form.tags.size - 1]
        if (!lastTag.startsWith('v') && lastTag != "adj-i") continue
        val ids = lexicon.lookup(form.text) ?: continue
        // The entry must be a verb/adjective (not ひむ "hymn"), uk or prioritized (真似る is ichi1), never archaic.
        if (!ids.any { id ->
                val score = lexicon.priorityScore(id, true)
                score != null &&
                    !lexicon.isTrueName(id) &&
                    (lexicon.hasPos(id, P.Verb) || lexicon.hasPos(id, P.IAdjective)) &&
                    score > (if (lexicon.isKanaAppropriateId(id)) ArchaicOnlyPriorityFloor else 0)
            }
        ) continue

        return form.text
    }

    return null
}

internal fun JitenParser.validateGrammaticalSequences(sentences: List<SentenceInfo>) {
    for (sentence in sentences)
        TransitionRuleEngine.applyHardRules(sentence.words, lexicon::hasNonNameLookup)
}

/** Shatters lookup-less nouns so CombineNounCompounds can re-cut them (各党 + 幹部 → 各 + 党幹部). */
internal fun JitenParser.splitUnknownNounTokens(sentences: List<SentenceInfo>, protectedSurfaces: Set<String>? = null) {
    for (sentence in sentences) {
        if (sentence.words.isEmpty()) continue

        var anySplit = false
        val result = ArrayList<SentenceWord>(sentence.words.size)

        for ((word, position, length) in sentence.words) {
            if (protectedSurfaces != null && word.text in protectedSurfaces) {
                result.add(SentenceWord(word, position, length))
                continue
            }

            // 母性的な → 母性 + 的な when only the base has a lookup.
            if (word.text.length >= 3 &&
                (word.text.endsWith('的') || word.text.endsWith("的な")) &&
                !hasLookup(word.text)
            ) {
                val suffixLen = if (word.text.endsWith("的な")) 2 else 1
                val baseText = word.text.substring(0, word.text.length - suffixLen)
                val suffixText = word.text.substring(word.text.length - suffixLen)
                if (hasLookup(baseText)) {
                    val baseWord = word.copy().apply {
                        text = baseText
                        dictionaryForm = baseText
                        normalizedForm = baseText
                    }
                    val suffixWord = word.copy().apply {
                        text = suffixText
                        dictionaryForm = suffixText
                        normalizedForm = suffixText
                        partOfSpeech = P.Suffix
                        reading = if (suffixLen == 2) "テキナ" else "テキ"
                    }
                    result.add(SentenceWord(baseWord, position, baseText.length))
                    result.add(SentenceWord(suffixWord, position + baseText.length, suffixLen))
                    anySplit = true
                    continue
                }
            }

            // Digit runs (四十七) stay whole or the tail bleeds into a counter (四十 + 七分); 五万 shatters to keep 万.
            if (word.text.length >= 2 &&
                PosMapper.isNounForCompounding(word.partOfSpeech) &&
                word.text.all { JapaneseTextHelper.isKanji(it) } &&
                !word.text.all { JitenParser.NumeralKanji.contains(it) } &&
                lexicon.lookup(word.text)?.isNotEmpty() != true
            ) {
                for (j in word.text.indices) {
                    val charText = word.text[j].toString()
                    val splitWord = word.copy().apply {
                        text = charText
                        dictionaryForm = charText
                        normalizedForm = charText
                        reading = ""
                    }
                    result.add(SentenceWord(splitWord, position + j, 1))
                }

                anySplit = true
            } else {
                result.add(SentenceWord(word, position, length))
            }
        }

        if (anySplit) sentence.setWords(result)
    }
}

// Curated: free-noun kanji also carry n-pref entries yet read as the noun (前準備 → まえ, not ぜん).
private val BoundPrefixKanji: Set<Char> = hashSetOf('総', '諸', '各', '準', '副', '反', '超', '非', '未', '汎', '准', '旧')

/** A leftover bound-prefix kanji before a noun takes its prefix reading (総本部: 総 そう, not ふさ). */
internal fun JitenParser.reclassifyLeftoverPrefixChars(sentences: List<SentenceInfo>) {
    for (sentence in sentences) {
        val words = sentence.words
        for (i in 0 until words.size - 1) {
            val word = words[i].word
            if (!(word.partOfSpeech == P.Noun || word.partOfSpeech == P.CommonNoun) ||
                word.text.length != 1 ||
                word.text[0] !in BoundPrefixKanji
            ) continue

            val next = words[i + 1].word
            if (!(next.partOfSpeech == P.Noun || next.partOfSpeech == P.CommonNoun ||
                    next.partOfSpeech == P.NaAdjective || next.partOfSpeech == P.NominalAdjective)
            ) continue

            val ids = lexicon.lookup(word.text) ?: continue

            val prefixId = ids.firstOrNull { lexicon.primaryPos(it) == P.Prefix } ?: continue

            word.partOfSpeech = P.Prefix
            word.preMatchedWordId = prefixId
        }
    }
}

// Other nouns after a bare 連用形 usually start a new clause (緩み周りの, 向け呪いを), so only productive V2 heads qualify.
private val CompoundVerbHeads: Set<String> = hashSetOf(
    "回す", "回る", "合う", "合わせる", "切る", "切れる", "掛ける", "掛かる", "始める", "出す",
    "続ける", "終える", "終わる", "過ぎる", "直す", "返す", "込む", "尽くす", "通す", "抜く",
    "忘れる", "損なう", "損ねる", "残す", "遅れる", "慣れる", "飽きる",
)

/** A noun-tagged V2 stem glued to a bare 連用形 is a verb (呼び回し → 回す, not the sumo belt 回し). */
internal fun JitenParser.pinCompoundVerbTails(sentences: List<SentenceInfo>) {
    for (sentence in sentences) {
        val words = sentence.words
        for (i in 1 until words.size) {
            val (word, position, _) = words[i]
            val (prev, prevPosition, prevLength) = words[i - 1]
            if (!(word.partOfSpeech == P.Noun || word.partOfSpeech == P.CommonNoun) ||
                word.preMatchedWordId != null ||
                prevPosition + prevLength != position ||
                !isBareRenyoukei(prev)
            ) continue

            val verb = VerbStemLookup.find(word.text, word.reading, lexicon) ?: continue
            if (verb.dictionaryForm !in CompoundVerbHeads) continue

            VerbStemLookup.pin(word, verb)
        }
    }
}

internal fun JitenParser.isBareRenyoukei(word: WordInfo): Boolean =
    word.partOfSpeech == P.Verb &&
        (tryGodanDictForm(word.text) == word.dictionaryForm || word.text + "る" == word.dictionaryForm)

private val TrailingParticles: Set<Char> =
    hashSetOf('か', 'よ', 'ね', 'な', 'ぞ', 'ぜ', 'わ', 'さ', 'の', 'に', 'と', 'も', 'で', 'は', 'が')

internal fun JitenParser.hasLookup(text: String): Boolean =
    lexicon.hasLookupWhere(text, JitenLexicon.KanaFallback.ToNormalizedHiragana) { it.isNotEmpty() }

internal fun JitenParser.getBestLookupPriority(text: String): Int {
    val ids = lexicon.lookup(text)
    if (ids == null || ids.isEmpty()) return -1
    var best = -1
    for (id in ids) {
        val score = lexicon.priorityScore(id, JapaneseTextHelper.isAllKana(text)) ?: continue
        if (score > best) best = score
    }
    return best
}

/** Rejects DictionaryForm homographs the surface can't conjugate from; no bases means allow. */
internal fun JitenParser.deconjBasesAllow(surface: String, dictForm: String): Boolean {
    val forms = deconjugator.deconjugate(surface)
    var anyRealBase = false
    for (f in forms) {
        if (f.process.isEmpty()) continue
        anyRealBase = true
        if (f.text == dictForm) return true
    }
    return !anyRealBase
}

/** Non-edge positions only: バッカ → バカ, うるっさい → うるさい. */
internal fun JitenParser.removeInternalSokuon(text: String): String {
    if (text.length < 3) return text
    val sb = StringBuilder(text.length)
    for (i in text.indices) {
        if (i > 0 && i < text.length - 1 && (text[i] == 'っ' || text[i] == 'ッ')) continue
        sb.append(text[i])
    }
    return if (sb.length == text.length) text else sb.toString()
}

internal fun JitenParser.hasPrioritizedMeta(id: Int): Boolean = (lexicon.priorityScore(id, true) ?: return false) > 0

// Unmarked archaic entries score about -350, all else above the name floor (-50); した+もの must not become 下物.
private const val ArchaicOnlyPriorityFloor = -100

// One id must be both non-name and non-archaic; script-blind since the uk delta (10) never crosses the floor.
internal fun JitenParser.hasUsableCompoundEntry(ids: IntArray): Boolean {
    for (id in ids) {
        if (lexicon.isNameOnly(id)) continue
        val score = lexicon.priorityScore(id, false) ?: continue
        if (score > ArchaicOnlyPriorityFloor) return true
    }
    return false
}

internal fun JitenParser.prefixBreaksFollowingCompound(prefix: WordInfo, head: WordInfo, following: WordInfo): Boolean {
    if (!PosMapper.isNounForCompounding(following.partOfSpeech) || following.text.isEmpty()) return false

    return tryLookupUsableCompound(head.text + following.text) != null &&
        !lexicon.containsKey(prefix.text + head.text + following.text)
}

// matchedKey becomes the dictionary form: a uk compound only resolves under its hiragana spelling.
internal fun JitenParser.tryLookupUsableCompound(text: String): String? {
    val ids = lexicon.lookup(text)
    if (ids != null && ids.isNotEmpty() && hasUsableCompoundEntry(ids)) return text

    val hiragana = KanaConverter.toHiragana(text, convertLongVowelMark = false)
    if (hiragana != text) {
        val hiraIds = lexicon.lookup(hiragana)
        if (hiraIds != null && hiraIds.isNotEmpty() && hasUsableCompoundEntry(hiraIds)) return hiragana
    }

    return null
}

/** Strips small vowels echoing the preceding vowel row (きゃぁ → きゃ); foreign-mora smalls (ふぁ) stay. */
internal fun JitenParser.tryRemoveEchoedSmallVowels(text: String): String? {
    var sb: StringBuilder? = null
    for (i in text.indices) {
        val c = text[i]
        val vowel = smallVowelKanaVowel(c)
        if (i > 0 && vowel != '\u0000' && kanaEndsWithVowel(text[i - 1], vowel)) {
            if (sb == null) sb = StringBuilder(text.substring(0, i))
            continue
        }

        sb?.append(c)
    }

    return sb?.toString()
}

internal fun JitenParser.smallVowelKanaVowel(c: Char): Char = when (c) {
    'ぁ', 'ァ' -> 'a'
    'ぃ', 'ィ' -> 'i'
    'ぅ', 'ゥ' -> 'u'
    'ぇ', 'ェ' -> 'e'
    'ぉ', 'ォ' -> 'o'
    else -> '\u0000'
}

/**
 * C# checks the last letter of WanaKana.ToRomaji(kana). For one kana that is its vowel row (vowelRowOf in
 * TextProcessing.kt), plus the kana vowelRowOf leaves out (ゐゑゎ and katakana ヰヱヮヵヶ: wi, we, wa, ka, ke);
 * ん, っ, ー and non-kana end in no vowel.
 */
internal fun JitenParser.kanaEndsWithVowel(kana: Char, vowel: Char): Boolean {
    val row = vowelRowOf(kana)
    val last = when {
        row >= 0 -> "aiueo"[row]
        kana in "ゐヰ" -> 'i'
        kana in "ゑヱヶ" -> 'e'
        kana in "ゎヮヵ" -> 'a'
        else -> return false
    }
    return last == vowel
}

/** Contains hiragana and no katakana: a script a katakana-only loanword is never written in. */
internal fun JitenParser.isHiraganaSurface(text: String): Boolean {
    var hasHiragana = false
    for (c in text) {
        if (c in 'ぁ'..'ゖ') hasHiragana = true
        else if (c in 'ァ'..'ヺ') return false
    }

    return hasHiragana
}

/** Primary POS only (話, 回); a minor counter sense (何, 差し) must not make a token counter-rescorable. */
internal fun JitenParser.hasCounterLookup(text: String): Boolean =
    lexicon.hasLookupWhere(text, JitenLexicon.KanaFallback.None) { ids -> ids.any { lexicon.primaryPos(it) == P.Counter } }

private val StripKeepPos: Set<P> = hashSetOf(
    P.Particle, P.Auxiliary, P.Verb, P.IAdjective, P.SupplementarySymbol, P.Symbol, P.Conjunction, P.Adnominal,
    P.Prefix, P.BlankSpace, P.Name, P.Suffix, P.NounSuffix, P.Counter, P.Numeral, P.Filler,
)

internal fun JitenParser.stripTrailingParticles(sentences: List<SentenceInfo>) {
    for (sentence in sentences) {
        var anySplit = false
        val result = ArrayList<SentenceWord>(sentence.words.size)

        for ((word, position, length) in sentence.words) {
            if (word.text.length < 3 || word.preMatchedWordId != null || word.partOfSpeech in StripKeepPos) {
                result.add(SentenceWord(word, position, length))
                continue
            }

            if (hasLookup(word.text)) {
                result.add(SentenceWord(word, position, length))
                continue
            }

            // MA-merged tokens (遠慮しないで) aren't lookup keys but deconjugate fine in ProcessWord.
            if (word.dictionaryForm != word.text && word.dictionaryForm.isNotEmpty() && hasLookup(word.dictionaryForm)) {
                result.add(SentenceWord(word, position, length))
                continue
            }

            val text = word.text
            val textHira = KanaConverter.toNormalizedHiragana(text)

            // A name-only remainder doesn't count: ブリタニカ is not ブリタニ+か.
            val lastChar = textHira[textHira.length - 1]
            val hasTrailingParticle = textHira.length >= 3 && lastChar in TrailingParticles
            val remainderKnown = hasTrailingParticle &&
                (lexicon.hasNonNameLookup(text.substring(0, text.length - 1)) ||
                    (textHira.length >= 5 &&
                        deconjugator.deconjugate(textHira.substring(0, textHira.length - 1)).any { lexicon.hasNonNameLookup(it.text) }))
            if (remainderKnown) {
                val remainder = text.substring(0, text.length - 1)
                val particleStr = text.substring(text.length - 1)
                val remainderWord = word.copy().apply {
                    this.text = remainder
                    dictionaryForm = remainder
                    normalizedForm = remainder
                }
                val particleWord = WordInfo().apply {
                    this.text = particleStr
                    dictionaryForm = particleStr
                    partOfSpeech = P.Particle
                }
                result.add(SentenceWord(remainderWord, position, length - 1))
                result.add(SentenceWord(particleWord, position + length - 1, 1))
                anySplit = true
                continue
            }

            result.add(SentenceWord(word, position, length))
        }

        if (anySplit) sentence.setWords(result)
    }
}

// Sudachi emits ・/＝ as symbols (サン＝テグジュペリ); enumerations (リンゴ・ミカン) have no entry and never merge.
internal fun JitenParser.isNameConnectorSymbol(w: WordInfo): Boolean =
    w.partOfSpeech == P.SupplementarySymbol && (w.text == "・" || w.text == "＝")
