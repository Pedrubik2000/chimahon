// Ported from Jiten (github.com/Sirush/Jiten, Apache-2.0, commit 44b48a4): Jiten.Parser/Stages/MorphologicalAnalyser.CombineStages.cs
package chimahon.lang.jiten

internal fun MorphologicalAnalyser.combineInflections(words: MutableList<WordInfo>): MutableList<WordInfo> {
    val wordInfos = words
    if (wordInfos.size < 2) return wordInfos

    var result: MutableList<WordInfo>? = null
    val cachedDeconjugate: (String) -> List<DeconjugationForm> = { pipelineCachedDeconjugate(it) }

    var i = 0
    while (i < wordInfos.size) {
        val word = wordInfos[i]

        val isBase = (PosMapper.isInflectableBase(word.partOfSpeech) ||
            word.hasPartOfSpeechSection(PartOfSpeechSection.PossibleSuru) ||
            word.hasPartOfSpeechSection(PartOfSpeechSection.PossibleVerbSuruNoun) ||
            (word.partOfSpeech == PartOfSpeech.Suffix &&
                word.hasPartOfSpeechSection(PartOfSpeechSection.VerbLike))) &&
            word.normalizedForm != "物" &&
            !word.hasPartOfSpeechSection(PartOfSpeechSection.AuxiliaryVerbStem)

        if (!isBase) {
            result?.add(word)
            i++
            continue
        }

        var currentWord = word
        var isCopy = false
        val baseIndex = i

        var currentDictForm = currentWord.dictionaryForm
        val currentNormForm = currentWord.normalizedForm
        var currentPOS = currentWord.partOfSpeech
        var currentDictFormHiragana = KanaNormalizer.normalize(KanaConverter.toHiragana(currentDictForm))

        while (i + 1 < wordInfos.size) {
            val nextWord = wordInfos[i + 1]

            if (shouldStopMerging(currentWord, nextWord, wordInfos, i, currentPOS)) break

            var isValidPart = PosMapper.isInflectionPart(nextWord.partOfSpeech) ||
                nextWord.hasPartOfSpeechSection(PartOfSpeechSection.AuxiliaryVerbStem) ||
                nextWord.hasPartOfSpeechSection(PartOfSpeechSection.ConjunctionParticle) ||
                nextWord.hasPartOfSpeechSection(PartOfSpeechSection.Dependant) ||
                nextWord.hasPartOfSpeechSection(PartOfSpeechSection.PossibleDependant)

            // Sudachi tags やれ as an interjection; after a て-form it is auxiliary やる's imperative.
            if (!isValidPart && nextWord.text == "やれ" && nextWord.partOfSpeech == PartOfSpeech.Interjection &&
                currentWord.text.endsWith('て'))
                isValidPart = true

            // Sudachi tags colloquial ねえ (ない) as the noun 姉; after a て/で-form it is the negative.
            if (!isValidPart && nextWord.text == "ねえ" && nextWord.partOfSpeech == PartOfSpeech.Noun &&
                (currentWord.text.endsWith('て') || currentWord.text.endsWith('で')))
                isValidPart = true

            // Steals そう from そうだ/そうか when it forms a valid inflection (新しそうだ → 新しそう + だ).
            if (!isValidPart && (nextWord.text == "そうだ" || nextWord.text == "そうか")) {
                val stealCandidate = currentWord.text + "そう"
                val stealHiragana = KanaNormalizer.normalize(KanaConverter.toHiragana(stealCandidate))
                val stealForms = cachedDeconjugate(stealHiragana)

                val stealTarget = if (currentPOS == PartOfSpeech.Noun) currentDictFormHiragana + "する"
                else currentDictFormHiragana

                if (containsText(stealForms, stealTarget)) {
                    if (result == null) result = copyAccumulatorUpTo(wordInfos, baseIndex)
                    if (!isCopy) { currentWord = currentWord.copy(); isCopy = true }
                    currentWord.text = stealCandidate
                    currentWord.reading += WanaKana.toKatakana("そう")
                    if (currentPOS == PartOfSpeech.Noun) {
                        currentWord.dictionaryForm = currentDictForm + "する"
                        currentPOS = PartOfSpeech.Verb
                    }

                    currentWord.partOfSpeech = currentPOS
                    currentDictForm = currentWord.dictionaryForm

                    val remainder = if (nextWord.text == "そうだ") "だ" else "か"
                    wordInfos[i + 1] = WordInfo().apply {
                        text = remainder; dictionaryForm = remainder
                        partOfSpeech = if (remainder == "だ") PartOfSpeech.Auxiliary else PartOfSpeech.Particle
                        reading = remainder
                    }
                    // i is not advanced: the remainder is processed as its own token by the outer loop.
                    break
                }
            }

            // Sudachi tags なさそう as NaAdjective, failing isValidPart, yet it attaches to the negative stem (食べなさそう).
            if (!isValidPart && nextWord.dictionaryForm == "なさそう") {
                val stealCandidate = currentWord.text + nextWord.text
                val stealHiragana = KanaNormalizer.normalize(KanaConverter.toHiragana(stealCandidate))
                val stealForms = cachedDeconjugate(stealHiragana)

                val stealTarget = if (currentPOS == PartOfSpeech.Noun) currentDictFormHiragana + "する"
                else currentDictFormHiragana

                if (containsText(stealForms, stealTarget)) {
                    if (result == null) result = copyAccumulatorUpTo(wordInfos, baseIndex)
                    if (!isCopy) { currentWord = currentWord.copy(); isCopy = true }
                    currentWord.text = stealCandidate
                    currentWord.endOffset = nextWord.endOffset
                    currentWord.reading += nextWord.reading
                    if (currentPOS == PartOfSpeech.Noun) {
                        currentWord.dictionaryForm = currentDictForm + "する"
                        currentPOS = PartOfSpeech.Verb
                    }

                    currentWord.partOfSpeech = currentPOS
                    currentDictForm = currentWord.dictionaryForm
                    i++
                    break
                }
            }

            // Kansai negative せん (しない) is tagged noun/prefix by Sudachi; after a suru base it inflects (卑下せん).
            if (!isValidPart && nextWord.text == "せん" &&
                currentWord.hasPartOfSpeechSection(PartOfSpeechSection.PossibleSuru))
                isValidPart = true

            if (!isValidPart) break

            var merged = false
            var newDictForm: String? = null

            val targetHiragana = if (currentPOS == PartOfSpeech.Noun) currentDictFormHiragana + "する"
            else currentDictFormHiragana

            val lookup = hasCompoundLookup
            val forms = pipelineCachedDeconjugateConcat(currentWord.text, nextWord.text)
            var scenarioAMatch = containsText(forms, targetHiragana) &&
                (lookup == null || lookup(currentDictForm) ||
                    (currentNormForm != currentDictForm && lookup(currentNormForm)))

            // Noun + す before べき is する+べき (帰投 + すべき), not a short causative; godan -す verbs (愛す) still merge.
            if (scenarioAMatch && currentPOS == PartOfSpeech.Noun && nextWord.text == "す" &&
                i + 2 < wordInfos.size &&
                (wordInfos[i + 2].text == "べき" || wordInfos[i + 2].dictionaryForm == "べし") &&
                lookup?.invoke(currentWord.text + "す") != true)
                scenarioAMatch = false

            if (scenarioAMatch) {
                if (currentPOS == PartOfSpeech.NaAdjective) {
                    val matchForm = findByText(forms, targetHiragana)!!
                    var hasVerbStemTag = false
                    for (t in matchForm.tags)
                        if (t.startsWith("stem-") && t != "stem-adj-base") { hasVerbStemTag = true; break }

                    if (hasVerbStemTag) {
                        var verbForm: DeconjugationForm? = null
                        for (f in forms) {
                            if (f.text != targetHiragana && f.tags.isNotEmpty() &&
                                f.tags.last().startsWith('v') &&
                                lookup != null && lookup(f.text)) { verbForm = f; break }
                        }

                        if (verbForm != null) {
                            merged = true
                            newDictForm = verbForm.text
                            currentPOS = PartOfSpeech.Verb
                        }
                    } else {
                        merged = true
                    }
                } else {
                    merged = true
                    if (currentPOS == PartOfSpeech.Noun) {
                        newDictForm = currentDictForm + "する"
                        currentPOS = PartOfSpeech.Verb
                    }
                    // C# has an empty branch here for IAdjective + suffix さ.
                }
            } else if (currentPOS == PartOfSpeech.Noun &&
                currentWord.hasPartOfSpeechSection(PartOfSpeechSection.PossibleVerbSuruNoun)) {
                val bareTarget = currentDictFormHiragana
                if (containsTextWithTag(forms, bareTarget, "stem-adj-base") &&
                    (lookup == null || lookup(currentDictForm) ||
                        (currentNormForm != currentDictForm && lookup(currentNormForm)))) {
                    merged = true
                    currentPOS = PartOfSpeech.NaAdjective
                }
            } else if (currentPOS == PartOfSpeech.Verb &&
                !currentWord.text.endsWith('て') &&
                !currentWord.text.endsWith('で') &&
                !currentWord.text.endsWith("たく") &&
                !currentWord.text.endsWith("なく") &&
                !currentWord.text.endsWith("たり") &&
                !currentWord.text.endsWith("だり") &&
                nextWord.dictionaryForm !in AuxiliaryVerbs &&
                (nextWord.hasPartOfSpeechSection(PartOfSpeechSection.VerbLike) ||
                    (nextWord.partOfSpeech == PartOfSpeech.Verb &&
                        nextWord.hasPartOfSpeechSection(PartOfSpeechSection.PossibleDependant)) ||
                    nextWord.partOfSpeech == PartOfSpeech.Suffix)) {
                val suffixDict = KanaNormalizer.normalize(KanaConverter.toHiragana(nextWord.dictionaryForm))
                var match = findEndingWith(forms, suffixDict)

                if (match == null && nextWord.partOfSpeech == PartOfSpeech.Suffix) {
                    val verbDict = tryGodanDictForm(suffixDict)
                    if (verbDict != null) match = findEndingWith(forms, verbDict)
                }

                if (match != null && (lookup == null || compoundExistsInLookup(match.text, cachedDeconjugate))) {
                    merged = true
                    newDictForm = match.text
                    currentPOS = if (match.tags.isNotEmpty() && match.tags.last() == "adj-i") PartOfSpeech.IAdjective
                    else PartOfSpeech.Verb
                }
            }

            if (merged) {
                if (result == null) result = copyAccumulatorUpTo(wordInfos, baseIndex)
                if (!isCopy) { currentWord = currentWord.copy(); isCopy = true }
                currentWord.text = currentWord.text + nextWord.text
                currentWord.endOffset = nextWord.endOffset
                currentWord.reading += nextWord.reading
                currentWord.partOfSpeech = currentPOS
                currentWord.isMergedInflection = true
                if (newDictForm != null) currentWord.dictionaryForm = newDictForm
                currentDictForm = currentWord.dictionaryForm
                currentDictFormHiragana = KanaNormalizer.normalize(KanaConverter.toHiragana(currentDictForm))
                i++
            } else {
                break
            }
        }

        result?.add(currentWord)
        i++
    }

    return result ?: wordInfos
}

private fun findByText(forms: List<DeconjugationForm>, target: String): DeconjugationForm? {
    for (i in forms.indices) if (forms[i].text == target) return forms[i]
    return null
}

private fun containsTextWithTag(forms: List<DeconjugationForm>, target: String, tag: String): Boolean {
    for (i in forms.indices) if (forms[i].text == target && tag in forms[i].tags) return true
    return false
}

private fun findEndingWith(forms: List<DeconjugationForm>, suffix: String): DeconjugationForm? {
    for (i in forms.indices)
        if (forms[i].text.endsWith(suffix) && forms[i].text.length > suffix.length) return forms[i]
    return null
}

private fun shouldStopMerging(
    currentWord: WordInfo, nextWord: WordInfo, wordInfos: List<WordInfo>, i: Int, currentPOS: PartOfSpeech,
): Boolean {
    // Negative stem な may merge before すぎる (わからなすぎる).
    var isNegativeStemBeforeDependant = false
    if (nextWord.text == "な" && nextWord.partOfSpeech == PartOfSpeech.Auxiliary && nextWord.dictionaryForm == "ない" &&
        i + 2 < wordInfos.size) {
        val afterNa = wordInfos[i + 2]
        isNegativeStemBeforeDependant =
            (afterNa.hasPartOfSpeechSection(PartOfSpeechSection.PossibleDependant) ||
                afterNa.hasPartOfSpeechSection(PartOfSpeechSection.Dependant)) &&
                (afterNa.dictionaryForm == "すぎる" || afterNa.dictionaryForm == "過ぎる")
    }

    if (nextWord.text in setOf("は", "よ", "し", "を", "が", "か", "ください", "かな")) return true
    if (nextWord.text == "な" && !isNegativeStemBeforeDependant) return true

    // Standalone よう is always 様; volitional よう arrives inside the verb token.
    if (nextWord.text == "よう" && nextWord.dictionaryForm == "よう") return true

    // いけ after ちゃ/じゃ/きゃ/にゃ is obligation/prohibition, not a compound.
    if (nextWord.dictionaryForm == "いける" &&
        (currentWord.text.endsWith("ちゃ") || currentWord.text.endsWith("じゃ") ||
            currentWord.text.endsWith("きゃ") || currentWord.text.endsWith("にゃ")))
        return true

    if (nextWord.text == "ん" && (nextWord.dictionaryForm == "の" || nextWord.dictionaryForm == "ん")) return true

    val isTte = nextWord.text == "って" && nextWord.dictionaryForm == "って"

    // An imperative has no te-form, so a following って is quotative (来い|って).
    if (isTte && currentWord.isImperative) return true

    // って before ん/んだ/んです is quotative, not te-form.
    if (nextWord.text == "って" && i + 2 < wordInfos.size &&
        wordInfos[i + 2].text in setOf("ん", "んだ", "んです"))
        return true

    // Quotative って stays split before a kanji or kana quote verb (かな+って+思ったら); つか+って+ください re-merges.
    if (isTte && i + 2 < wordInfos.size && isQuoteTakingVerb(wordInfos[i + 2])) return true

    // って before a noun, punctuation or end (なくなった+って+話) is quotative; re-merging leaves an unresolvable blob.
    if (isTte &&
        (i + 2 >= wordInfos.size ||
            wordInfos[i + 2].partOfSpeech.let {
                it == PartOfSpeech.Noun || it == PartOfSpeech.CommonNoun || it == PartOfSpeech.SupplementarySymbol
            }))
        return true

    // って before a sentence-ending particle (待つ+って+さ) is quotative; re-merging leaves an unresolvable blob.
    if (isTte && i + 2 < wordInfos.size &&
        wordInfos[i + 2].partOfSpeech == PartOfSpeech.Particle &&
        wordInfos[i + 2].text in setOf("さ", "よ", "ね", "わ", "ぞ", "ぜ"))
        return true

    // Benefactives stay separate (させて|いただきます); the て+貰う deconj rules serve Dependant-path merges only.
    if ((currentWord.text.endsWith('て') || currentWord.text.endsWith('で')) &&
        nextWord.dictionaryForm in setOf("いただく", "頂く", "貰う"))
        return true

    // A verb after an adjective くて starts a new clause (頭が良くて + やりたい).
    if (currentPOS == PartOfSpeech.IAdjective && currentWord.text.endsWith('て') &&
        nextWord.partOfSpeech == PartOfSpeech.Verb)
        return true

    if (currentWord.text.endsWith('ん') && (nextWord.text == "だ" || nextWord.text == "です")) return true
    if (nextWord.text == "じゃ" && nextWord.dictionaryForm == "だ") return true
    if (currentPOS == PartOfSpeech.NaAdjective && nextWord.text == "で" && nextWord.dictionaryForm == "だ") return true

    return false
}

private val PrefixCombineExclusions: Set<String> = hashSetOf("おつもり", "おいま", "おにく")

private fun isKanjiPrefix(text: String): Boolean = text.isNotEmpty() && JapaneseTextHelper.isKanji(text[0])

// Prefix combine runs before noun compounding: お|母|上 must yield お + 母上, not お母; お+手+紙 → お手紙 is a word.
private fun MorphologicalAnalyser.completesCompoundWithFollowing(wordInfos: List<WordInfo>, prefixIndex: Int): Boolean {
    val nonName = hasNonNameCompoundLookup
    if (nonName == null || prefixIndex + 2 >= wordInfos.size) return false

    val head = wordInfos[prefixIndex + 1]
    val following = wordInfos[prefixIndex + 2]
    if (!PosMapper.isNounForCompounding(following.partOfSpeech) || following.text.isEmpty()) return false

    return nonName(head.text + following.text) &&
        !hasCompoundLookup!!(wordInfos[prefixIndex].text + head.text + following.text)
}

internal fun MorphologicalAnalyser.combinePrefixes(words: MutableList<WordInfo>): MutableList<WordInfo> {
    val wordInfos = words
    val lookup = hasCompoundLookup
    if (wordInfos.size < 2 || lookup == null) return wordInfos

    var newList: MutableList<WordInfo>? = null
    fun out(i: Int): MutableList<WordInfo> = newList ?: copyAccumulatorUpTo(wordInfos, i).also { newList = it }

    var i = 0
    while (i < wordInfos.size) {
        var currentWord = wordInfos[i]

        // Sudachi tags emphatic ど as Adverb (truncated どう); before an i-adjective it is a prefix (ど偉い).
        val isEmphaticDo = currentWord.text == "ど" && currentWord.partOfSpeech == PartOfSpeech.Adverb &&
            i + 1 < wordInfos.size && wordInfos[i + 1].partOfSpeech == PartOfSpeech.IAdjective

        if ((currentWord.partOfSpeech == PartOfSpeech.Prefix || isEmphaticDo) && i + 1 < wordInfos.size) {
            val nextWord = wordInfos[i + 1]
            val isKanjiPrefix = isKanjiPrefix(currentWord.text)

            // Kanji prefixes (相, 再, 不) may take verbs/adjectives; kana prefixes (お, ご) only nominals.
            val np = nextWord.partOfSpeech
            val isContentWord = np == PartOfSpeech.Noun || np == PartOfSpeech.NaAdjective ||
                np == PartOfSpeech.Adverb || np == PartOfSpeech.NominalAdjective || np == PartOfSpeech.CommonNoun ||
                ((isKanjiPrefix || isEmphaticDo) && (np == PartOfSpeech.Verb || np == PartOfSpeech.IAdjective))

            if (isContentWord) {
                val combinedText = currentWord.text + nextWord.text

                if (combinedText !in PrefixCombineExclusions &&
                    lookup(combinedText) &&
                    !completesCompoundWithFollowing(wordInfos, i)) {
                    val prefixStart = currentWord.startOffset
                    currentWord = nextWord.copy()
                    currentWord.text = combinedText
                    currentWord.dictionaryForm = combinedText
                    currentWord.normalizedForm = combinedText
                    currentWord.startOffset = prefixStart
                    if (np == PartOfSpeech.Verb || np == PartOfSpeech.IAdjective)
                        currentWord.partOfSpeech = PartOfSpeech.Noun
                    out(i).add(currentWord)
                    i += 2
                    continue
                }

                // Inflected verb: 相 + 次い → 相次ぐ; POS kept so CombineInflections and deconjugation still apply.
                if (np == PartOfSpeech.Verb && nextWord.dictionaryForm.isNotEmpty() &&
                    nextWord.dictionaryForm != nextWord.text) {
                    val dictionaryCombined = currentWord.text + nextWord.dictionaryForm
                    if (dictionaryCombined !in PrefixCombineExclusions &&
                        hasVerbOrAdjectiveLookup?.invoke(dictionaryCombined) == true) {
                        val prefixStart = currentWord.startOffset
                        currentWord = nextWord.copy()
                        currentWord.text = combinedText
                        currentWord.dictionaryForm = dictionaryCombined
                        currentWord.normalizedForm = dictionaryCombined
                        currentWord.startOffset = prefixStart
                        out(i).add(currentWord)
                        i += 2
                        continue
                    }
                }

                // Classical i-adjective: 故 + 無き → 故無い (2112310); IAdjective kept so deconjugation reaches adj-i.
                if (np == PartOfSpeech.IAdjective && nextWord.normalizedForm.isNotEmpty() &&
                    nextWord.normalizedForm != nextWord.text) {
                    val normalizedCombined = currentWord.text + nextWord.normalizedForm
                    if (normalizedCombined !in PrefixCombineExclusions && lookup(normalizedCombined)) {
                        val prefixStart = currentWord.startOffset
                        currentWord = nextWord.copy()
                        currentWord.text = combinedText
                        currentWord.dictionaryForm = normalizedCombined
                        currentWord.normalizedForm = normalizedCombined
                        currentWord.startOffset = prefixStart
                        out(i).add(currentWord)
                        i += 2
                        continue
                    }
                }

                // Colloquial surfaces carry the standard form in Reading (古 + くせー reading クサイ → 古くさい).
                if (nextWord.reading.isNotEmpty()) {
                    val readingHira = KanaConverter.toHiragana(nextWord.reading)
                    if (readingHira != nextWord.text && readingHira != combinedText && !lookup(nextWord.text)) {
                        val readingCombined = currentWord.text + readingHira
                        if (readingCombined !in PrefixCombineExclusions && lookup(readingCombined)) {
                            val prefixStart = currentWord.startOffset
                            currentWord = nextWord.copy()
                            currentWord.text = combinedText
                            currentWord.dictionaryForm = readingCombined
                            currentWord.normalizedForm = readingCombined
                            currentWord.startOffset = prefixStart
                            out(i).add(currentWord)
                            i += 2
                            continue
                        }
                    }
                }

                // Re-cuts an unattested next token (相+当腹 → 相当+腹); remainder is a word or one stray kana (おに + ぃ).
                if (nextWord.text.length >= 2 && combinedText !in PrefixCombineExclusions && !lookup(nextWord.text)) {
                    var partialMatch = false
                    for (len in nextWord.text.length - 1 downTo 1) {
                        val partialText = currentWord.text + nextWord.text.substring(0, len)
                        if (partialText !in PrefixCombineExclusions &&
                            lookup(partialText) &&
                            (lookup(nextWord.text.substring(len)) ||
                                (nextWord.text.length - len == 1 && JapaneseTextHelper.isKana(nextWord.text[len])))) {
                            val combinedWord = nextWord.copy()
                            combinedWord.text = partialText
                            combinedWord.startOffset = currentWord.startOffset
                            combinedWord.endOffset = if (nextWord.startOffset >= 0) nextWord.startOffset + len else -1
                            val list = out(i)
                            list.add(combinedWord)

                            val remainder = nextWord.copy()
                            remainder.text = nextWord.text.substring(len)
                            remainder.startOffset = if (nextWord.startOffset >= 0) nextWord.startOffset + len else -1
                            list.add(remainder)

                            i += 2
                            partialMatch = true
                            break
                        }
                    }

                    if (partialMatch) continue
                }
            }
        }

        newList?.add(currentWord)
        i++
    }

    return newList ?: wordInfos
}

internal fun MorphologicalAnalyser.combineAmounts(words: MutableList<WordInfo>): MutableList<WordInfo> {
    val wordInfos = words
    if (wordInfos.size < 2) return wordInfos

    var newList: MutableList<WordInfo>? = null
    var currentWord = wordInfos[0]

    for (i in 1 until wordInfos.size) {
        val nextWord = wordInfos[i]

        if ((currentWord.hasPartOfSpeechSection(PartOfSpeechSection.Amount) ||
                currentWord.hasPartOfSpeechSection(PartOfSpeechSection.Numeral)) &&
            (currentWord.text to nextWord.text) in AmountCombinations.combinations) {
            if (newList == null) newList = copyAccumulatorUpTo(wordInfos, i - 1)
            val text = currentWord.text + nextWord.text
            val startOff = currentWord.startOffset
            currentWord = nextWord.copy()
            currentWord.text = text
            currentWord.dictionaryForm = text
            currentWord.startOffset = startOff
            currentWord.partOfSpeech = PartOfSpeech.Noun
        } else {
            newList?.add(currentWord)
            currentWord = nextWord
        }
    }

    if (newList == null) return wordInfos
    newList.add(currentWord)
    return newList
}

internal fun MorphologicalAnalyser.combineTte(words: MutableList<WordInfo>): MutableList<WordInfo> =
    mergeAdjacentWhere(words) { currentWord, nextWord ->
        currentWord.text.endsWith('っ') && nextWord.text.startsWith('て')
    }

// って + kana いう → っていう (2757880); kanji 言う is "to say" (だって|言う|人) and stays split.
internal fun MorphologicalAnalyser.combineQuotativeToIu(words: MutableList<WordInfo>): MutableList<WordInfo> {
    val wordInfos = words
    if (wordInfos.size < 2) return wordInfos

    var newList: MutableList<WordInfo>? = null
    fun out(i: Int): MutableList<WordInfo> = newList ?: copyAccumulatorUpTo(wordInfos, i).also { newList = it }

    var i = 0
    while (i < wordInfos.size) {
        val word = wordInfos[i]

        if (i + 1 < wordInfos.size &&
            word.text == "って" && word.dictionaryForm == "って" && word.partOfSpeech == PartOfSpeech.Particle &&
            wordInfos[i + 1].text == "いう" && wordInfos[i + 1].dictionaryForm == "いう") {
            val iu = wordInfos[i + 1]
            out(i).add(word.copy().apply {
                text = "っていう"
                dictionaryForm = "っていう"
                normalizedForm = "っていう"
                reading = "ッテイウ"
                partOfSpeech = PartOfSpeech.Conjunction
                endOffset = iu.endOffset
            })
            i += 2
            continue
        }

        // Splits って off a volitional (しようって) for っていう; te-form stems (黙って) never end in う-row; bare うって skipped.
        if (i + 1 < wordInfos.size &&
            word.text.length > 3 &&
            word.text.endsWith("って") &&
            wordInfos[i + 1].text == "いう" && wordInfos[i + 1].dictionaryForm == "いう" &&
            isQuotativeTteStem(word.text.dropLast(2))) {
            val list = out(i)
            val iu = wordInfos[i + 1]
            val stem = word.text.dropLast(2)
            val mid = if (word.endOffset >= 0) word.endOffset - 2 else -1
            list.add(word.copy().apply {
                text = stem; dictionaryForm = stem; normalizedForm = stem; endOffset = mid
            })
            list.add(iu.copy().apply {
                text = "っていう"
                dictionaryForm = "っていう"
                normalizedForm = "っていう"
                reading = "ッテイウ"
                partOfSpeech = PartOfSpeech.Conjunction
                startOffset = mid
                endOffset = iu.endOffset
            })
            i += 2
            continue
        }

        newList?.add(word)
        i++
    }

    return newList ?: wordInfos
}

// Quotative って follows a う-row kana; a te-form stem never ends in one.
private fun isQuotativeTteStem(s: String): Boolean = s.isNotEmpty() && s.last() in "うくぐすつぬぶむる"

internal fun MorphologicalAnalyser.combineVerbDependant(words: MutableList<WordInfo>): MutableList<WordInfo> {
    var wordInfos = words
    if (wordInfos.size < 2) return wordInfos

    wordInfos = combineVerbDependants(wordInfos)
    wordInfos = combineVerbPossibleDependants(wordInfos)
    wordInfos = combineVerbDependantsSuru(wordInfos)
    wordInfos = combineVerbDependantsTeiru(wordInfos)

    return wordInfos
}

internal fun MorphologicalAnalyser.combineAdverbialParticle(words: MutableList<WordInfo>): MutableList<WordInfo> =
    mergeAdjacentWhere(words) { currentWord, nextWord ->
        nextWord.hasPartOfSpeechSection(PartOfSpeechSection.AdverbialParticle) &&
            (nextWord.dictionaryForm == "だり" || nextWord.dictionaryForm == "たり") &&
            currentWord.partOfSpeech == PartOfSpeech.Verb
    }

internal fun MorphologicalAnalyser.combineConjunctiveParticle(words: MutableList<WordInfo>): MutableList<WordInfo> {
    val wordInfos = words
    if (wordInfos.size < 2) return wordInfos

    var newList: MutableList<WordInfo>? = null

    for (i in 1 until wordInfos.size) {
        val currentWord = wordInfos[i]
        // Mutated in place, as in C# (the accumulator's last token, or the input's previous token).
        val previousWord = newList?.last() ?: wordInfos[i - 1]
        var combined = false

        if (currentWord.hasPartOfSpeechSection(PartOfSpeechSection.ConjunctionParticle) &&
            currentWord.text in setOf("て", "で", "ちゃ", "ば") &&
            previousWord.partOfSpeech.let {
                it == PartOfSpeech.Verb || it == PartOfSpeech.IAdjective || it == PartOfSpeech.Auxiliary
            }) {
            if (newList == null) newList = copyAccumulatorUpTo(wordInfos, i)
            previousWord.text += currentWord.text
            previousWord.endOffset = currentWord.endOffset
            previousWord.reading += currentWord.reading
            combined = true
        }

        if (!combined) newList?.add(currentWord)
    }

    return newList ?: wordInfos
}

// Keep in sync with ShouldStopMerging's list; kana lemmas included since Sudachi tags 言う as いう freely.
private fun isQuoteTakingVerb(w: WordInfo): Boolean =
    w.dictionaryForm in setOf("思う", "おもう", "言う", "いう", "聞く", "きく", "考える", "感じる")

internal fun MorphologicalAnalyser.combineAuxiliary(words: MutableList<WordInfo>): MutableList<WordInfo> {
    val wordInfos = words
    if (wordInfos.size < 2) return wordInfos

    val deconjugator = Deconjugator.instance
    val deconj: (String) -> List<DeconjugationForm> = { deconjugator.deconjugate(it) }

    var newList: MutableList<WordInfo>? = null

    for (i in 1 until wordInfos.size) {
        val currentWord = wordInfos[i]
        // Mutated in place, as in C#.
        val previousWord = newList?.last() ?: wordInfos[i - 1]
        var combined = false

        if (currentWord.partOfSpeech != PartOfSpeech.Auxiliary) {
            // Copula で keeps DictionaryForm だ after reclassification to Particle; で + ある → である.
            if (previousWord.text == "で" && previousWord.dictionaryForm == "だ" &&
                (currentWord.dictionaryForm == "ある" || currentWord.dictionaryForm == "有る")) {
                previousWord.text = "で" + currentWord.text
                previousWord.endOffset = currentWord.endOffset
                previousWord.reading += currentWord.reading
                previousWord.partOfSpeech = currentWord.partOfSpeech
                previousWord.dictionaryForm = "である"
                if (newList == null) newList = copyAccumulatorUpTo(wordInfos, i)
            } else {
                newList?.add(currentWord)
            }

            continue
        }

        val lookup = hasCompoundLookup
        val pp = previousWord.partOfSpeech
        if ((pp == PartOfSpeech.Verb || pp == PartOfSpeech.IAdjective || pp == PartOfSpeech.NaAdjective ||
                pp == PartOfSpeech.Auxiliary ||
                previousWord.hasPartOfSpeechSection(PartOfSpeechSection.Adjectival)) &&
            (lookup == null ||
                pp != PartOfSpeech.Verb ||
                previousWord.hasPartOfSpeechSection(PartOfSpeechSection.PossibleSuru) ||
                verbDictFormExistsInLookup(previousWord.dictionaryForm, previousWord.normalizedForm, deconj)) &&
            // A pinned auxiliary (した+んだ) is a repair stage's decision; absorbing it erases the word.
            currentWord.preMatchedWordId == null &&
            currentWord.text != "な" &&
            currentWord.text != "に" &&
            (currentWord.dictionaryForm != "です" ||
                (pp == PartOfSpeech.Verb && currentWord.dictionaryForm == "です" &&
                    (currentWord.text == "でし" || currentWord.text == "でした"))) &&
            currentWord.dictionaryForm != "らしい" &&
            currentWord.text != "なら" &&
            currentWord.text != "なる" &&
            currentWord.dictionaryForm != "べし" &&
            currentWord.dictionaryForm != "む" &&
            currentWord.dictionaryForm != "ごとし" && currentWord.dictionaryForm != "如し" &&
            currentWord.dictionaryForm != "ようだ" &&
            currentWord.dictionaryForm != "やがる" &&
            currentWord.dictionaryForm != "たり" &&
            currentWord.dictionaryForm != "筈" &&
            currentWord.text != "だろう" &&
            currentWord.text != "で" &&
            currentWord.text != "や" &&
            currentWord.text != "やろ" &&
            currentWord.text != "やしない" &&
            currentWord.text != "し" &&
            !(currentWord.text == "って" && previousWord.isImperative) &&
            // Copula だって before a quote verb is quotative (大袈裟|だって|言いたい); ようって folds for CombineQuotativeToIu.
            !(currentWord.dictionaryForm == "だ" &&
                currentWord.text.endsWith("って") &&
                i + 1 < wordInfos.size && isQuoteTakingVerb(wordInfos[i + 1])) &&
            currentWord.text != "なのだ" &&
            !currentWord.text.startsWith("なん") &&
            currentWord.text != "だろ" &&
            currentWord.text != "ハズ" &&
            (currentWord.text != "だ" ||
                (previousWord.text.last() == 'ん' && isValidNdaPastTense(previousWord.text))) &&
            !(currentWord.text == "じゃ" && currentWord.dictionaryForm == "だ")) {
            val stemText = previousWord.text
            previousWord.text += currentWord.text
            previousWord.endOffset = currentWord.endOffset
            previousWord.reading += currentWord.reading
            if (currentWord.dictionaryForm in setOf("ちまう", "じまう", "しまう") && lookup != null) {
                val mergedDictForm = stemText + currentWord.dictionaryForm
                if (lookup(mergedDictForm)) previousWord.dictionaryForm = mergedDictForm
            }
            combined = true
        }

        if (!combined && previousWord.partOfSpeech == PartOfSpeech.Expression &&
            currentWord.dictionaryForm == "た" &&
            (previousWord.text.last() == 'て' || previousWord.text.last() == 'で')) {
            previousWord.text += currentWord.text
            previousWord.endOffset = currentWord.endOffset
            previousWord.reading += currentWord.reading
            combined = true
        }

        if (combined && newList == null) newList = copyAccumulatorUpTo(wordInfos, i)

        if (!combined) newList?.add(currentWord)
    }

    return newList ?: wordInfos
}

// Merged only when JMDict attests the compound (逃げ|切った → 逃げ切る), so 紙を切った is untouched.
private val CompletionAuxVerbs: Set<String> = hashSetOf("切る")

internal fun MorphologicalAnalyser.combineCompletionAuxVerb(words: MutableList<WordInfo>): MutableList<WordInfo> {
    val wordInfos = words
    val lookup = hasCompoundLookup
    if (wordInfos.size < 2 || lookup == null) return wordInfos

    var result: MutableList<WordInfo>? = null
    var i = 0
    while (i < wordInfos.size) {
        val word = wordInfos[i]
        val aux = wordInfos.getOrNull(i + 1)
        if (aux != null &&
            word.partOfSpeech == PartOfSpeech.Verb &&
            word.text.length >= 2 &&
            !word.text.endsWith('て') && !word.text.endsWith('で') &&
            aux.partOfSpeech == PartOfSpeech.Verb &&
            aux.dictionaryForm in CompletionAuxVerbs &&
            lookup(word.text + aux.dictionaryForm)) {
            val list = result ?: copyAccumulatorUpTo(wordInfos, i).also { result = it }
            list.add(word.copy().apply {
                text = word.text + aux.text
                dictionaryForm = word.text + aux.dictionaryForm
                normalizedForm = word.text + aux.dictionaryForm
                reading = word.reading + aux.reading
                partOfSpeech = PartOfSpeech.Verb
                isMergedInflection = true
                endOffset = aux.endOffset
            })
            i += 2
            continue
        }

        result?.add(word)
        i++
    }

    return result ?: wordInfos
}

internal fun MorphologicalAnalyser.combineAuxiliaryVerbStem(words: MutableList<WordInfo>): MutableList<WordInfo> {
    val wordInfos = words
    if (wordInfos.size < 2) return wordInfos

    var newList: MutableList<WordInfo>? = null
    var currentWord = wordInfos[0]
    var isCopy = false

    for (i in 1 until wordInfos.size) {
        val nextWord = wordInfos[i]
        val prev = wordInfos[i - 1]

        // Adjectival suffixes (やすい, にくい) arrive as their stems (やす, にく) and also host そう.
        val isAdjectivalSuffix = prev.partOfSpeech == PartOfSpeech.Suffix && prev.dictionaryForm.endsWith('い')
        if (nextWord.hasPartOfSpeechSection(PartOfSpeechSection.AuxiliaryVerbStem) &&
            nextWord.text != "ように" &&
            nextWord.text != "よう" &&
            nextWord.text != "ようです" &&
            nextWord.text != "みたい" &&
            (prev.partOfSpeech == PartOfSpeech.Verb ||
                prev.partOfSpeech == PartOfSpeech.IAdjective ||
                (prev.partOfSpeech == PartOfSpeech.Noun &&
                    (prev.hasPartOfSpeechSection(PartOfSpeechSection.PossibleNaAdjective) ||
                        prev.hasPartOfSpeechSection(PartOfSpeechSection.PossibleVerbSuruNoun))) ||
                isAdjectivalSuffix)) {
            if (newList == null) newList = copyAccumulatorUpTo(wordInfos, i - 1)
            if (!isCopy) { currentWord = currentWord.copy(); isCopy = true }
            currentWord.text += nextWord.text
            currentWord.endOffset = nextWord.endOffset
            currentWord.reading += nextWord.reading
        } else {
            newList?.add(currentWord)
            currentWord = nextWord
            isCopy = false
        }
    }

    if (newList == null) return wordInfos
    newList.add(currentWord)
    return newList
}

internal fun MorphologicalAnalyser.combineSuffix(words: MutableList<WordInfo>): MutableList<WordInfo> {
    val wordInfos = words
    if (wordInfos.size < 2) return wordInfos

    val lookup = hasCompoundLookup
    var newList: MutableList<WordInfo>? = null
    var currentWord = wordInfos[0]
    var isCopy = false

    for (i in 1 until wordInfos.size) {
        val nextWord = wordInfos[i]
        val isSuffix = nextWord.partOfSpeech == PartOfSpeech.Suffix ||
            nextWord.hasPartOfSpeechSection(PartOfSpeechSection.Suffix)
        val df = nextWord.dictionaryForm

        if (isSuffix &&
            (df == "っこ" ||
                df == "さ" ||
                // 何|がって is が + って, not 何がる.
                (df == "がる" && currentWord.partOfSpeech != PartOfSpeech.Pronoun) ||
                ((df == "ぶり" || df == "振り") &&
                    currentWord.partOfSpeech == PartOfSpeech.IAdjective &&
                    !currentWord.text.endsWith('い') && currentWord.dictionaryForm.endsWith('い')) ||
                (df == "ら" &&
                    wordInfos[i - 1].partOfSpeech == PartOfSpeech.Pronoun && wordInfos[i - 1].text != "貴様"))) {
            if (newList == null) newList = copyAccumulatorUpTo(wordInfos, i - 1)
            if (!isCopy) { currentWord = currentWord.copy(); isCopy = true }
            currentWord.text += nextWord.text
            currentWord.endOffset = nextWord.endOffset
            currentWord.reading += nextWord.reading
        } else if (df == "ぶる" && isSuffix && lookup != null && lookup(currentWord.dictionaryForm + "ぶる")) {
            if (newList == null) newList = copyAccumulatorUpTo(wordInfos, i - 1)
            if (!isCopy) { currentWord = currentWord.copy(); isCopy = true }
            currentWord.dictionaryForm += "ぶる"
            currentWord.partOfSpeech = PartOfSpeech.Verb
            currentWord.text += nextWord.text
            currentWord.endOffset = nextWord.endOffset
            currentWord.reading += nextWord.reading
        }
        // Sudachi misparses がったり after an adjective stem as an adverb (怖|がったり).
        else if (nextWord.partOfSpeech == PartOfSpeech.Adverb && nextWord.text == "がったり" &&
            currentWord.partOfSpeech == PartOfSpeech.IAdjective &&
            !currentWord.text.endsWith('い') &&
            currentWord.dictionaryForm.endsWith('い')) {
            if (newList == null) newList = copyAccumulatorUpTo(wordInfos, i - 1)
            if (!isCopy) { currentWord = currentWord.copy(); isCopy = true }
            currentWord.text += nextWord.text
            currentWord.endOffset = nextWord.endOffset
            currentWord.reading += nextWord.reading
        } else {
            newList?.add(currentWord)
            currentWord = nextWord
            isCopy = false
        }
    }

    if (newList == null) return wordInfos
    newList.add(currentWord)
    return newList
}

// The base must be a spelling of the surface, not merely a lemma the deconjugator reaches.
private fun kanjiPreserved(surface: String, baseForm: String): Boolean {
    for (c in surface) if (JapaneseTextHelper.isKanji(c) && baseForm.indexOf(c) < 0) return false
    return true
}

internal fun MorphologicalAnalyser.reclassifyOrphanedSuffixes(words: MutableList<WordInfo>): MutableList<WordInfo> {
    val wordInfos = words
    val nonName = hasNonNameCompoundLookup
    for (i in 1 until wordInfos.size) {
        val w = wordInfos[i]
        if (w.partOfSpeech != PartOfSpeech.Suffix) continue

        // じまい follows ず-forms (わからずじまい) and honorifics follow names; both are genuine suffixes.
        if (w.dictionaryForm in setOf("じまい", "仕舞い", "ちゃん", "さん", "くん", "様", "殿", "氏")) continue

        // An unattested kanji "suffix" spelling a verb (レッテル|貼り → 貼る) is a verb; kana ones stay (ぶっ+た ≠ 打つ).
        if (nonName != null && !nonName(w.text) && w.text.any { JapaneseTextHelper.isKanji(it) }) {
            for (form in Deconjugator.instance.deconjugate(w.text)) {
                if (form.text == w.text || form.text.length < 2) continue
                if (form.text.last() !in DictionaryVerbEndings) continue
                if (!kanjiPreserved(w.text, form.text)) continue
                if (!nonName(form.text)) continue

                w.partOfSpeech = PartOfSpeech.Verb
                w.partOfSpeechSection1 = PartOfSpeechSection.None
                w.dictionaryForm = form.text
                w.normalizedForm = form.text
                w.reading = ""
                // A reclassified suffix must not anchor compound/expression windows.
                w.wasReclassifiedFromSuffix = true
                break
            }

            if (w.partOfSpeech == PartOfSpeech.Verb) continue
        }

        // A predicate-tail suffix (チッチャ|い) can't host a suffix; 車 after it must reclassify to reach its noun.
        val prevInfo = wordInfos[i - 1]
        val prevIsPredicateTailSuffix = prevInfo.partOfSpeech == PartOfSpeech.Suffix &&
            (prevInfo.text == "い" || prevInfo.text == "く" || prevInfo.text == "かっ")
        val prev = prevInfo.partOfSpeech
        if (prev == PartOfSpeech.Noun || prev == PartOfSpeech.CommonNoun || prev == PartOfSpeech.Numeral ||
            prev == PartOfSpeech.Prefix || prev == PartOfSpeech.Pronoun ||
            (prev == PartOfSpeech.Suffix && !prevIsPredicateTailSuffix))
            continue

        // 形容詞的 (っぽい) need their POS for the adj branch; 形状詞的 (気) may start expressions (気を引き締める).
        if (w.partOfSpeechSection1 == PartOfSpeechSection.Adjectival ||
            w.partOfSpeechSection1 == PartOfSpeechSection.NaAdjectiveLike)
            continue

        w.partOfSpeech = PartOfSpeech.CommonNoun
        w.partOfSpeechSection1 = PartOfSpeechSection.None
        w.reading = ""
        w.wasReclassifiedFromSuffix = true
    }

    return wordInfos
}

private fun isShireLike(t: String) = t.startsWith("しれ") || t.startsWith("しんな") || t.startsWith("しんね")

internal fun MorphologicalAnalyser.combineParticles(words: MutableList<WordInfo>): MutableList<WordInfo> {
    val wordInfos = words
    if (wordInfos.size < 2) return wordInfos

    var newList: MutableList<WordInfo>? = null
    fun out(i: Int): MutableList<WordInfo> = newList ?: copyAccumulatorUpTo(wordInfos, i).also { newList = it }

    var i = 0
    while (i < wordInfos.size) {
        val currentWord = wordInfos[i]

        // か+も+しれ* is one expression; the deconjugator recovers かもしれない from contracted しんない.
        if (i + 2 < wordInfos.size &&
            currentWord.text == "か" &&
            wordInfos[i + 1].text == "も" &&
            isShireLike(wordInfos[i + 2].text)) {
            val combinedWord = currentWord.copy()
            combinedWord.text = currentWord.text + wordInfos[i + 1].text + wordInfos[i + 2].text
            combinedWord.endOffset = wordInfos[i + 2].endOffset
            combinedWord.reading = currentWord.reading + wordInfos[i + 1].reading + wordInfos[i + 2].reading
            combinedWord.partOfSpeech = PartOfSpeech.Expression
            out(i).add(combinedWord)
            i += 3
            continue
        }

        // かも already fused upstream (after the ん-repair) arrives as [かも][しれ*].
        if (i + 1 < wordInfos.size &&
            currentWord.text == "かも" &&
            isShireLike(wordInfos[i + 1].text)) {
            val combinedWord = currentWord.copy()
            combinedWord.text = currentWord.text + wordInfos[i + 1].text
            combinedWord.endOffset = wordInfos[i + 1].endOffset
            combinedWord.reading = currentWord.reading + wordInfos[i + 1].reading
            combinedWord.partOfSpeech = PartOfSpeech.Expression
            out(i).add(combinedWord)
            i += 2
            continue
        }

        // A fused じゃない expression + か → じゃないか; a verb negative (しない か) keeps か separate.
        if (i + 1 < wordInfos.size &&
            currentWord.partOfSpeech == PartOfSpeech.Expression &&
            (currentWord.dictionaryForm == "じゃない" || currentWord.dictionaryForm == "ではない") &&
            wordInfos[i + 1].text == "か") {
            val combinedWord = currentWord.copy()
            combinedWord.text += "か"
            combinedWord.endOffset = wordInfos[i + 1].endOffset
            combinedWord.reading += wordInfos[i + 1].reading
            combinedWord.dictionaryForm += "か"
            out(i).add(combinedWord)
            i += 2
            continue
        }

        if (i + 1 < wordInfos.size) {
            val nextWord = wordInfos[i + 1]
            var combinedText = ""

            if (currentWord.text == "に" && nextWord.text == "は") combinedText = "には"
            else if (currentWord.text == "と" && nextWord.text == "は") combinedText = "とは"
            else if (currentWord.text == "で" && nextWord.text == "は") combinedText = "では"
            else if (currentWord.text == "の" && nextWord.text == "に") combinedText = "のに"

            if (combinedText.isNotEmpty()) {
                val combinedWord = currentWord.copy()
                combinedWord.text = combinedText
                combinedWord.endOffset = nextWord.endOffset
                combinedWord.reading = currentWord.reading + nextWord.reading

                if (combinedText == "では" && i + 2 < wordInfos.size) {
                    val lookAhead = wordInfos[i + 2]
                    if (lookAhead.dictionaryForm == "ない" || lookAhead.dictionaryForm == "無い") {
                        combinedWord.text += lookAhead.text
                        combinedWord.endOffset = lookAhead.endOffset
                        combinedWord.reading += lookAhead.reading
                        combinedWord.dictionaryForm = "ではない"
                        combinedWord.partOfSpeech = PartOfSpeech.Expression
                        var consumed = 3

                        if (i + 3 < wordInfos.size && wordInfos[i + 3].text == "か") {
                            combinedWord.text += "か"
                            combinedWord.endOffset = wordInfos[i + 3].endOffset
                            combinedWord.reading += wordInfos[i + 3].reading
                            combinedWord.dictionaryForm = "ではないか"
                            consumed = 4
                        }

                        out(i).add(combinedWord)
                        i += consumed
                        continue
                    }
                }

                out(i).add(combinedWord)
                i += 2
                continue
            }
        }

        newList?.add(currentWord)
        i++
    }

    return newList ?: wordInfos
}

internal fun MorphologicalAnalyser.combineFinal(words: MutableList<WordInfo>): MutableList<WordInfo> {
    val wordInfos = words
    if (wordInfos.size < 2) return wordInfos

    var newList: MutableList<WordInfo>? = null
    fun out(i: Int): MutableList<WordInfo> = newList ?: copyAccumulatorUpTo(wordInfos, i).also { newList = it }

    var i = 0
    while (i < wordInfos.size) {
        if (i + 1 >= wordInfos.size) {
            newList?.add(wordInfos[i])
            i++
            continue
        }

        val currentWord = wordInfos[i]
        val nextWord = wordInfos[i + 1]

        if (nextWord.text == "ば" && currentWord.partOfSpeech == PartOfSpeech.Verb) {
            val merged = currentWord.copy()
            merged.text += nextWord.text
            merged.endOffset = nextWord.endOffset
            merged.reading += nextWord.reading
            out(i).add(merged)
            i += 2
            continue
        }

        val sc2List = SpecialCases2Dict[currentWord.text]
        if (sc2List != null) {
            // ところで (1343110) only sentence-initially or after た/だ; 静かなところで is locative ところ + で.
            val tokoroDeBlocked = currentWord.text == "ところ" && i > 0 &&
                !(wordInfos[i - 1].text.endsWith("た") || wordInfos[i - 1].text.endsWith("だ"))

            val sc = sc2List.firstOrNull { !(it.second == "で" && tokoroDeBlocked) && nextWord.text == it.second }
            if (sc != null) {
                val merged = currentWord.copy().apply {
                    text = currentWord.text + nextWord.text
                    endOffset = nextWord.endOffset
                    reading = currentWord.reading + nextWord.reading
                    dictionaryForm = currentWord.text + nextWord.text
                }
                if (sc.pos != null) merged.partOfSpeech = sc.pos
                out(i).add(merged)
                i += 2
                continue
            }
        }

        val sc3List = if (i + 2 < wordInfos.size) SpecialCases3Dict[currentWord.text] else null
        if (sc3List != null) {
            val thirdWord = wordInfos[i + 2]
            val sc = sc3List.firstOrNull {
                val thirdMatch = thirdWord.text == it.third ||
                    (it.third.length > 1 && it.third.last() == 'ー' && thirdWord.text == it.third.dropLast(1))
                nextWord.text == it.second && thirdMatch
            }
            if (sc != null) {
                val merged = currentWord.copy().apply {
                    text = currentWord.text + nextWord.text + sc.third
                    endOffset = thirdWord.endOffset
                    reading = currentWord.reading + nextWord.reading + thirdWord.reading
                    dictionaryForm = currentWord.text + nextWord.text + sc.third
                }
                if (sc.pos != null) merged.partOfSpeech = sc.pos
                out(i).add(merged)
                i += 3
                continue
            }
        }

        newList?.add(currentWord)
        i++
    }

    return newList ?: wordInfos
}

/** Re-merges と + なる that Sudachi splits before punctuation (トラウマとなり、). */
internal fun MorphologicalAnalyser.combineToNaru(words: MutableList<WordInfo>): MutableList<WordInfo> {
    val wordInfos = words
    if (wordInfos.size < 2) return wordInfos

    val newList = ArrayList<WordInfo>(wordInfos.size)
    var changed = false

    var i = 0
    while (i < wordInfos.size) {
        val word = wordInfos[i]

        if (word.text == "と" && word.partOfSpeech == PartOfSpeech.Particle && i + 1 < wordInfos.size) {
            val next = wordInfos[i + 1]

            val nextIsNaruForm = (next.partOfSpeech == PartOfSpeech.Verb || next.partOfSpeech == PartOfSpeech.Auxiliary) &&
                (next.dictionaryForm == "なる" || next.dictionaryForm == "成る" ||
                    next.normalizedForm == "なる" || next.normalizedForm == "成る")

            if (nextIsNaruForm && next.text.length <= 3) {
                val prevIsNounLike = newList.isNotEmpty() && newList.last().partOfSpeech.let {
                    it == PartOfSpeech.Noun || it == PartOfSpeech.Pronoun || it == PartOfSpeech.Counter ||
                        it == PartOfSpeech.Numeral || it == PartOfSpeech.NaAdjective
                }

                if (prevIsNounLike) {
                    newList.add(next.copy().apply {
                        text = word.text + next.text
                        startOffset = word.startOffset
                        endOffset = next.endOffset
                        reading = word.reading + next.reading
                        dictionaryForm = "となる"
                        normalizedForm = "なる"
                    })
                    i += 2
                    changed = true
                    continue
                }
            }
        }

        newList.add(word)
        i++
    }

    return if (changed) newList else wordInfos
}

// Shared (RepairStages uses them): ported with the stubs.

internal fun containsText(forms: List<DeconjugationForm>, target: String): Boolean {
    for (i in forms.indices) if (forms[i].text == target) return true
    return false
}

/** SpecialCases2 grouped by first token text, in RuleData order (C#'s List<(string Second, PartOfSpeech? Pos)>). */
internal val SpecialCases2Dict: Map<String, List<SpecialCase2>> = SpecialCases2.groupBy { it.first }

/** SpecialCases3 grouped by first token text, in RuleData order (C#'s List<(string Second, string Third, PartOfSpeech? Pos)>). */
internal val SpecialCases3Dict: Map<String, List<SpecialCase3>> = SpecialCases3.groupBy { it.first }
