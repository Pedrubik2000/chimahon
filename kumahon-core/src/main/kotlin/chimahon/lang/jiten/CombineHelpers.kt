// Ported from Jiten (github.com/Sirush/Jiten, Apache-2.0, commit 44b48a4):
// Jiten.Parser/Helpers/MorphologicalAnalyser.CombineHelpers.cs
package chimahon.lang.jiten

internal fun MorphologicalAnalyser.compoundExistsInLookup(
    compoundForm: String, cachedDeconjugate: (String) -> List<DeconjugationForm>,
): Boolean {
    val lookup = hasCompoundLookup!!
    if (lookup(compoundForm)) return true
    for (form in cachedDeconjugate(compoundForm)) if (lookup(form.text)) return true
    return false
}

internal val DictionaryVerbEndings: Set<Char> = hashSetOf('う', 'く', 'ぐ', 'す', 'つ', 'ぬ', 'ぶ', 'む', 'る')

internal fun tryGodanDictForm(text: String): String? {
    if (text.length < 2) return null
    val dictEnding = when (text.last()) {
        'い' -> 'う'; 'き' -> 'く'; 'ぎ' -> 'ぐ'; 'し' -> 'す'
        'ち' -> 'つ'; 'に' -> 'ぬ'; 'び' -> 'ぶ'; 'み' -> 'む'; 'り' -> 'る'
        else -> '\u0000'
    }
    return if (dictEnding == '\u0000') null else text.dropLast(1) + dictEnding
}

internal fun MorphologicalAnalyser.verbDictFormExistsInLookup(
    dictForm: String, normalizedForm: String?, cachedDeconjugate: (String) -> List<DeconjugationForm>,
): Boolean {
    val lookup = hasCompoundLookup!!
    if (lookup(dictForm)) return true
    if (normalizedForm != null && normalizedForm != dictForm && lookup(normalizedForm)) return true
    for (form in cachedDeconjugate(dictForm)) {
        if (form.text.isNotEmpty() && form.text.last() in DictionaryVerbEndings && lookup(form.text)) return true
    }
    return false
}

internal fun MorphologicalAnalyser.combineVerbDependants(wordInfos: MutableList<WordInfo>): MutableList<WordInfo> =
    mergeAdjacentWhere(wordInfos) { currentWord, nextWord ->
        nextWord.hasPartOfSpeechSection(PartOfSpeechSection.Dependant) &&
            currentWord.partOfSpeech == PartOfSpeech.Verb &&
            nextWord.dictionaryForm != "おる" &&
            nextWord.text != currentWord.text &&
            // An interjection is never a verb dependant: 持って + あ (mis-split 当たれ) must not fuse.
            nextWord.partOfSpeech != PartOfSpeech.Interjection &&
            // くて is an i-adjective te-form; dependants attach to verb te-forms only (頭が良くて + やりたい stays split).
            !currentWord.text.endsWith("くて")
    }

internal fun MorphologicalAnalyser.combineVerbPossibleDependants(wordInfos: MutableList<WordInfo>): MutableList<WordInfo> =
    mergeAdjacentWhere(wordInfos) { currentWord, nextWord ->
        val isClassicalWaRowTeForm = nextWord.dictionaryForm.endsWith('う') && nextWord.text.endsWith("いて")
        val lookup = hasCompoundLookup
        nextWord.hasPartOfSpeechSection(PartOfSpeechSection.PossibleDependant) &&
            currentWord.partOfSpeech == PartOfSpeech.Verb && !currentWord.text.endsWith("たり") &&
            nextWord.text != currentWord.text &&
            !currentWord.text.endsWith("くて") &&
            !isClassicalWaRowTeForm &&
            (nextWord.dictionaryForm in setOf("しまう", "こなす", "いく", "貰う", "いる", "ない") ||
                // Aspectual だす attaches only to an attested compound (走り出す); 混じり+だした stays split, not unmatchable.
                (nextWord.dictionaryForm == "だす" && lookup != null &&
                    (lookup(currentWord.text + "だす") || lookup(currentWord.text + "出す"))) ||
                (nextWord.dictionaryForm == "得る" && lookup != null && lookup(currentWord.text + "得る")) ||
                (nextWord.dictionaryForm == "する" && (currentWord.text.endsWith('た') || currentWord.text.endsWith('だ'))) ||
                (nextWord.dictionaryForm == "付く" && lookup != null &&
                    currentWord.dictionaryForm.length >= 2 &&
                    lookup(currentWord.dictionaryForm.dropLast(1) + "り付く")))
    }

internal fun MorphologicalAnalyser.combineVerbDependantsSuru(wordInfos: MutableList<WordInfo>): MutableList<WordInfo> {
    if (wordInfos.size < 2) return wordInfos

    var newList: MutableList<WordInfo>? = null
    var i = 0
    while (i < wordInfos.size) {
        val currentWord = wordInfos[i]

        if (i + 1 < wordInfos.size) {
            val nextWord = wordInfos[i + 1]
            val isModernSuru = nextWord.dictionaryForm == "する" && nextWord.text != "する" && nextWord.text != "しない" &&
                !nextWord.text.endsWith("すぎ") && !nextWord.text.endsWith("過ぎ")
            val isLiterarySuru = nextWord.dictionaryForm == "す" && nextWord.normalizedForm == "為る"
            val isSuruNoun = currentWord.hasPartOfSpeechSection(PartOfSpeechSection.PossibleSuru) ||
                currentWord.hasPartOfSpeechSection(PartOfSpeechSection.PossibleVerbSuruNoun)
            if (isSuruNoun && (isModernSuru || isLiterarySuru)) {
                val list = newList ?: copyAccumulatorUpTo(wordInfos, i).also { newList = it }
                val combinedWord = currentWord.copy()
                combinedWord.text += nextWord.text
                combinedWord.endOffset = nextWord.endOffset
                combinedWord.reading += nextWord.reading
                list.add(combinedWord)
                i += 2
                continue
            }
        }

        newList?.add(currentWord)
        i++
    }

    return newList ?: wordInfos
}

internal fun MorphologicalAnalyser.combineVerbDependantsTeiru(wordInfos: MutableList<WordInfo>): MutableList<WordInfo> {
    if (wordInfos.size < 2) return wordInfos

    var newList: MutableList<WordInfo>? = null
    var i = 0
    while (i < wordInfos.size) {
        val currentWord = wordInfos[i]

        if (i + 2 < wordInfos.size) {
            val nextWord1 = wordInfos[i + 1]
            val nextWord2 = wordInfos[i + 2]

            if (currentWord.partOfSpeech == PartOfSpeech.Verb &&
                nextWord1.dictionaryForm == "て" &&
                nextWord2.dictionaryForm in TeFormAuxChainVerbs) {
                val list = newList ?: copyAccumulatorUpTo(wordInfos, i).also { newList = it }
                val combinedWord = currentWord.copy()
                combinedWord.text += nextWord1.text + nextWord2.text
                combinedWord.endOffset = nextWord2.endOffset
                combinedWord.reading += nextWord1.reading + nextWord2.reading
                list.add(combinedWord)
                i += 3
                continue
            }
        }

        if (i + 1 < wordInfos.size) {
            val nextWord = wordInfos[i + 1]

            val isClassicalWaRowTeForm = nextWord.dictionaryForm.endsWith('う') && nextWord.text.endsWith("いて")
            // Demonstratives never attach (夢見て + これ); gated on dict form, not POS, since Pronoun こん (来ん) must (出てこん).
            // これ before an inflection (ない/た/ます) is 来る's ら抜き potential stem and attaches (戻ってこれなかった).
            val isDemonstrative = nextWord.dictionaryForm in setOf("これ", "それ", "あれ", "どれ")
            val koreIsPotentialStem = nextWord.dictionaryForm == "これ" && i + 2 < wordInfos.size &&
                wordInfos[i + 2].dictionaryForm in setOf("ない", "無い", "た", "ます", "ん", "ぬ", "ず", "る")
            if ((currentWord.text.endsWith('て') || currentWord.text.endsWith('で')) &&
                (currentWord.partOfSpeech == PartOfSpeech.Verb || currentWord.partOfSpeech == PartOfSpeech.IAdjective) &&
                // くて is an i-adjective te-form; subsidiary verbs attach to verb te-forms only (良くて + やりたい).
                !currentWord.text.endsWith("くて") &&
                !isClassicalWaRowTeForm &&
                nextWord.partOfSpeech != PartOfSpeech.IAdjective &&
                (!isDemonstrative || koreIsPotentialStem)) {
                var isKnownSubsidiary = false

                if (nextWord.partOfSpeech == PartOfSpeech.Verb && nextWord.dictionaryForm != "おる") {
                    isKnownSubsidiary =
                        (nextWord.hasPartOfSpeechSection(PartOfSpeechSection.PossibleDependant) &&
                            (nextWord.dictionaryForm == "いる" || nextWord.dictionaryForm == "ない")) ||
                            nextWord.dictionaryForm in TeFormSubsidiaryVerbs ||
                            nextWord.normalizedForm in TeFormSubsidiaryVerbs
                }

                if (!isKnownSubsidiary) {
                    val nextHiragana = KanaNormalizer.normalize(KanaConverter.toHiragana(nextWord.text))
                    for (f in pipelineCachedDeconjugate(nextHiragana)) {
                        if (f.text in TeFormSubsidiaryVerbs) { isKnownSubsidiary = true; break }
                        if (f.text in TeFormAuxChainVerbs && f.tags.any { it.startsWith('v') }) { isKnownSubsidiary = true; break }
                    }
                }

                if (isKnownSubsidiary) {
                    val list = newList ?: copyAccumulatorUpTo(wordInfos, i).also { newList = it }
                    val combinedWord = currentWord.copy()
                    combinedWord.text += nextWord.text
                    combinedWord.endOffset = nextWord.endOffset
                    combinedWord.reading += nextWord.reading
                    list.add(combinedWord)
                    i += 2
                    continue
                }
            }
        }

        // Dialectal とる (contracted ておる) after a っ stem: 待っとる.
        if (i + 1 < wordInfos.size) {
            val nextWord = wordInfos[i + 1]

            if (currentWord.partOfSpeech == PartOfSpeech.Verb && currentWord.text.endsWith('っ') &&
                nextWord.dictionaryForm == "とる") {
                val nextHiragana = KanaNormalizer.normalize(KanaConverter.toHiragana(nextWord.text))
                val isTeOruForm = pipelineCachedDeconjugate(nextHiragana).any { f ->
                    f.process.any { "toru (teoru)" in it } && f.tags.any { it.startsWith('v') || it.startsWith("stem-te") }
                }

                if (isTeOruForm) {
                    val list = newList ?: copyAccumulatorUpTo(wordInfos, i).also { newList = it }
                    val combinedWord = currentWord.copy()
                    combinedWord.text += nextWord.text
                    combinedWord.endOffset = nextWord.endOffset
                    combinedWord.reading += nextWord.reading
                    list.add(combinedWord)
                    i += 2
                    continue
                }
            }
        }

        newList?.add(currentWord)
        i++
    }

    return newList ?: wordInfos
}

internal fun copyAccumulatorUpTo(source: List<WordInfo>, upToExclusive: Int): MutableList<WordInfo> {
    // Slack for split stages, which append more tokens than they consume; a regrow copies the whole document.
    val list = ArrayList<WordInfo>(source.size + source.size / 32 + 8)
    for (i in 0 until upToExclusive) list.add(source[i])
    return list
}

/** Copy-on-write; shouldMerge sees the accumulator with earlier merges applied. Returns the input list when nothing merges. */
internal fun mergeAdjacentWhere(
    wordInfos: MutableList<WordInfo>, shouldMerge: (WordInfo, WordInfo) -> Boolean,
): MutableList<WordInfo> {
    if (wordInfos.size < 2) return wordInfos

    var newList: MutableList<WordInfo>? = null
    var currentWord = wordInfos[0]
    var isCopy = false

    for (i in 1 until wordInfos.size) {
        val nextWord = wordInfos[i]
        if (shouldMerge(currentWord, nextWord)) {
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

/**
 * Returns tokens consumed at i (0 = no match), emitting via output(); result is read-only, null until a rewrite fires.
 * (C#'s TryRewriteAt delegate.)
 */
internal typealias TryRewriteAt =
    (tokens: MutableList<WordInfo>, i: Int, result: MutableList<WordInfo>?, output: () -> MutableList<WordInfo>) -> Int

/** Returns the input list when no rewrite fires. */
internal fun scanRewrite(wordInfos: MutableList<WordInfo>, tryRewrite: TryRewriteAt): MutableList<WordInfo> {
    var result: MutableList<WordInfo>? = null
    var i = 0
    val output: () -> MutableList<WordInfo> = { result ?: copyAccumulatorUpTo(wordInfos, i).also { result = it } }
    while (i < wordInfos.size) {
        val consumed = tryRewrite(wordInfos, i, result, output)
        if (consumed > 0) i += consumed - 1
        else result?.add(wordInfos[i])
        i++
    }
    return result ?: wordInfos
}
