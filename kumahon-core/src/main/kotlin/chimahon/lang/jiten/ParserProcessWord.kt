// Ported from Jiten (github.com/Sirush/Jiten, Apache-2.0, commit 44b48a4): Jiten.Parser/Parser.cs lines 1239-2796
// (ProcessWord: resolving one token to a JMdict word, deconjugation, direct lookups, reading indices)
//
// Conventions for this file (see JitenParser.kt): members are `internal fun JitenParser.x(...)`; C#'s
// `ConcurrentDictionary<int, JmDictWord>? batchWordCache` is `MutableMap<Int, JmDictWord>?`, JmDictCache.GetWordsAsync /
// GetWordsWithCache is getWordsWithCache(ids, batchWordCache); diagnostics, the DeckWordCache (prefetchedCache) and
// the semaphore are left out; C# byte reading indices are Int (255 = none). Where C# iterates a Dictionary returned by
// GetWordsWithCache, this iterates the requested ids in order (C#'s insertion order; the Kotlin map is a HashMap).
package chimahon.lang.jiten

/** C#'s (bool success, DeckWord? word, int? margin, List<FormCandidate>? candidates) tuple. */
internal data class ResolveResult(
    val success: Boolean,
    val word: DeckWord?,
    val margin: Int?,
    val candidates: MutableList<FormCandidate>?,
)

/** CollectVerbAdjectiveCandidates' (deconjugated, candidates, fromReadingChannel) tuple. */
internal data class VerbAdjectiveCandidates(
    val deconjugated: List<DeconjugationForm>,
    val candidates: MutableList<Pair<DeconjugationForm, List<Int>>>,
    val fromReadingChannel: Boolean,
)

private val FailedResolve = ResolveResult(false, null, null, null)

private fun isAllDigits(s: String) = s.isNotEmpty() && s.all(Char::isDigit)

private fun nonNamePos(p: PartOfSpeech) = p != PartOfSpeech.Name && p != PartOfSpeech.Unknown

internal fun JitenParser.processWord(
    wordInfo: WordInfo,
    deconjugator: Deconjugator,
    batchWordCache: MutableMap<Int, JmDictWord>? = null,
): ProcessWordResult {
    val textWithoutBar = wordInfo.text.trimEnd('ー')
    if (isAllDigits(textWithoutBar) || (textWithoutBar.length == 1 && textWithoutBar.isAsciiOrFullWidthLetter()))
        return ProcessWordResult.FilteredOut

    val isNameLikeSudachiNoun = PosMapper.isNameLikeSudachiNoun(wordInfo.partOfSpeech, wordInfo.partOfSpeechSection1,
        wordInfo.partOfSpeechSection2, wordInfo.partOfSpeechSection3)

    // (C#'s DeckWordCache lookup is left out: no cache here.)

    var processedWord: DeckWord? = null
    var resolvedMargin: Int? = null
    var firstPassCandidates: MutableList<FormCandidate>? = null

    // Captured before the escalation chain rewrites the POS; gates POS-relaxed lookups for exclamations.
    wordInfo.isKanaExclamation = (wordInfo.partOfSpeech == PartOfSpeech.Interjection || wordInfo.partOfSpeech == PartOfSpeech.Filler) &&
        JapaneseTextHelper.isAllKana(wordInfo.text)
    wordInfo.isKatakanaNounSurface = (wordInfo.partOfSpeech == PartOfSpeech.Noun || wordInfo.partOfSpeech == PartOfSpeech.CommonNoun) &&
        JapaneseTextHelper.isAllKatakana(wordInfo.text)
    var isProcessed = false
    var attemptCount = 0
    val maxAttempts = 3

    val baseWord = wordInfo.text
    do {
        attemptCount++
        try {
            // A resegmentation pin also carries PreMatchedCandidateWordIds and goes through the full scorer.
            if (wordInfo.preMatchedWordId != null && wordInfo.preMatchedCandidateWordIds == null) {
                val preMatched = tryResolvePreMatched(wordInfo, batchWordCache)
                if (preMatched != null) {
                    processedWord = preMatched
                    resolvedMargin = ScoringPolicy.HighConfidenceThreshold
                    break
                }
            }

            val pos = wordInfo.partOfSpeech
            if (pos == PartOfSpeech.Verb || pos == PartOfSpeech.IAdjective || pos == PartOfSpeech.Auxiliary ||
                pos == PartOfSpeech.NaAdjective || pos == PartOfSpeech.Expression ||
                wordInfo.partOfSpeechSection1 == PartOfSpeechSection.Adjectival) {
                val verbResult = deconjugateVerbOrAdjective(wordInfo, deconjugator, batchWordCache)
                if (!verbResult.success || verbResult.word == null) {
                    // A noun misparsed as a verb/adjective (お祭り).
                    val nounResult = deconjugateWord(wordInfo, batchWordCache)
                    processedWord = nounResult.word
                    resolvedMargin = nounResult.margin
                    firstPassCandidates = nounResult.candidates
                } else {
                    processedWord = verbResult.word
                    resolvedMargin = verbResult.margin
                    firstPassCandidates = verbResult.candidates
                }
            } else {
                val nounResult = deconjugateWord(wordInfo, batchWordCache)
                if (!nounResult.success || nounResult.word == null) {
                    val verbResult = resolveByPosEscalation(wordInfo, deconjugator, batchWordCache)
                    processedWord = verbResult.word
                    resolvedMargin = verbResult.margin
                    firstPassCandidates = verbResult.candidates
                } else if (pos == PartOfSpeech.Pronoun || pos == PartOfSpeech.Conjunction || pos == PartOfSpeech.Interjection ||
                    pos == PartOfSpeech.Particle || pos == PartOfSpeech.Adverb || pos == PartOfSpeech.NaAdjective ||
                    pos == PartOfSpeech.Suffix || pos == PartOfSpeech.NounSuffix || pos == PartOfSpeech.PrenounAdjectival ||
                    pos == PartOfSpeech.Prefix) {
                    processedWord = nounResult.word
                    resolvedMargin = nounResult.margin
                    firstPassCandidates = nounResult.candidates
                } else {
                    val arbitrated = arbitrateNounVsVerbStem(wordInfo, nounResult, isNameLikeSudachiNoun, deconjugator, batchWordCache)
                    processedWord = arbitrated.word
                    resolvedMargin = arbitrated.margin
                    if (firstPassCandidates == null && processedWord != null)
                        firstPassCandidates = arbitrated.candidates
                }
            }

            if (processedWord != null && isKanaCollisionResolution(processedWord, wordInfo, baseWord)) {
                processedWord = null
                resolvedMargin = null
                firstPassCandidates = null
            }

            if (processedWord != null) {
                processedWord.originalText = baseWord
                break
            }

            val mutatedText = nextSurfaceMutation(wordInfo, baseWord)
            if (mutatedText != null) wordInfo.text = mutatedText else isProcessed = true

            if (attemptCount >= maxAttempts) isProcessed = true
        } catch (ex: Exception) {
            System.err.println("[Error] Failed to process word '${wordInfo.text}': ${ex.message}")
            isProcessed = true
        }
    } while (!isProcessed)

    // WordInfo is shared with example sentence extraction and diagnostics.
    wordInfo.text = baseWord

    if (processedWord != null) {
        val geminationRetry = tryGeminationCollapseRetry(wordInfo, processedWord, baseWord, deconjugator, batchWordCache)
        if (geminationRetry != null) {
            processedWord = geminationRetry.word
            resolvedMargin = geminationRetry.margin
            firstPassCandidates = geminationRetry.candidates
        }
    }

    if (processedWord == null) return ProcessWordResult.Unresolved

    processedWord.sudachiReading = wordInfo.reading
    processedWord.sudachiPartOfSpeech = wordInfo.partOfSpeech

    val candidatesToKeep = if (!ScoringPolicy.isHighConfidence(resolvedMargin)) firstPassCandidates else null

    return ProcessWordResult.fromResolved(processedWord, resolvedMargin, candidatesToKeep)
}

internal fun JitenParser.tryResolvePreMatched(wordInfo: WordInfo, batchWordCache: MutableMap<Int, JmDictWord>?): DeckWord? {
    val preMatchedWordId = wordInfo.preMatchedWordId!!
    val wordCache = getWordsWithCache(listOf(preMatchedWordId), batchWordCache)
    val preMatchedWord = wordCache[preMatchedWordId] ?: return null

    // Surface first: DictionaryForm may be a kanji homograph set only for cache-keying (いける→生ける).
    // DictionaryForm is the fallback when the surface isn't a form of the word (食べた→食べる).
    val surface = wordInfo.text
    var readingIndex: Int
    val pinnedIndex = wordInfo.preMatchedReadingIndex
    if (pinnedIndex != null) {
        readingIndex = pinnedIndex
    } else {
        readingIndex = getBestReadingIndex(preMatchedWord, surface, wordInfo.reading)
        if (readingIndex == 255 && wordInfo.dictionaryForm.isNotEmpty() && wordInfo.dictionaryForm != surface)
            readingIndex = getBestReadingIndex(preMatchedWord, wordInfo.dictionaryForm, wordInfo.reading)
        // Mixed-script pins (憎みあう) match no written form: try NormalizedForm (憎み合う), then reading 0, never 255.
        // Skipped when (WordId, 0) is an excluded misparse, which the filter would silently drop.
        if (readingIndex == 255 && wordInfo.normalizedForm.isNotEmpty() && wordInfo.normalizedForm != surface &&
            wordInfo.normalizedForm != wordInfo.dictionaryForm)
            readingIndex = getBestReadingIndex(preMatchedWord, wordInfo.normalizedForm, wordInfo.reading)
        if (readingIndex == 255 && WordForm(preMatchedWordId, 0) !in JitenParser.ExcludedMisparses)
            readingIndex = 0
    }

    return DeckWord(wordId = preMatchedWordId, readingIndex = readingIndex, originalText = wordInfo.text,
        conjugations = wordInfo.preMatchedConjugations ?: emptyList(),
        partsOfSpeech = ArrayList(preMatchedWord.cachedPOS), origin = preMatchedWord.origin)
}

// Retry POS order when Sudachi's POS resolved nothing; its mistags (らしく as noun, 朧気 as name) resolve under another.
// (pos, directLookupFirst, deconjugateAfter)
private val PosEscalationLadder = listOf(
    Triple(PartOfSpeech.Verb, false, true),
    Triple(PartOfSpeech.IAdjective, false, true),
    Triple(PartOfSpeech.NaAdjective, true, true),
    Triple(PartOfSpeech.Interjection, true, false),
)

internal fun JitenParser.resolveByPosEscalation(
    wordInfo: WordInfo,
    deconjugator: Deconjugator,
    batchWordCache: MutableMap<Int, JmDictWord>?,
): ResolveResult {
    var verbResult = deconjugateVerbOrAdjective(wordInfo, deconjugator, batchWordCache)

    val oldPos = wordInfo.partOfSpeech
    for ((pos, directLookupFirst, deconjugateAfter) in PosEscalationLadder) {
        if (verbResult.success && verbResult.word != null) break

        wordInfo.partOfSpeech = pos
        if (directLookupFirst) {
            val direct = deconjugateWord(wordInfo, batchWordCache)
            if (direct.success && direct.word != null) {
                verbResult = ResolveResult(true, direct.word, direct.margin, direct.candidates)
                continue
            }
        }

        if (deconjugateAfter) verbResult = deconjugateVerbOrAdjective(wordInfo, deconjugator, batchWordCache)
    }

    wordInfo.partOfSpeech = oldPos
    return verbResult
}

// Noun-tagged tokens may be verb stems (抱え: rare noun "armful" or 連用形 of 抱える), so both readings compete.
internal fun JitenParser.arbitrateNounVsVerbStem(
    wordInfo: WordInfo,
    nounResult: ResolveResult,
    isNameLikeSudachiNoun: Boolean,
    deconjugator: Deconjugator,
    batchWordCache: MutableMap<Int, JmDictWord>?,
): ProcessedWord {
    val processedWord: DeckWord?
    val resolvedMargin: Int?

    val savedPos = wordInfo.partOfSpeech
    wordInfo.partOfSpeech = PartOfSpeech.Verb
    var verbFallback = deconjugateVerbOrAdjective(wordInfo, deconjugator, batchWordCache)
    wordInfo.partOfSpeech = savedPos

    val nounWord = nounResult.word

    // Bare ichidan stems have no ending to deconjugate, so look up surface+る directly (抱え → 抱える).
    if (nounWord != null && (!verbFallback.success || verbFallback.word == null || verbFallback.word.wordId == nounWord.wordId)) {
        val surfaceHira = KanaConverter.toHiragana(wordInfo.text, convertLongVowelMark = false)
        val ruText = surfaceHira + "る"
        val ruIds = lexicon.lookup(ruText)
        if (ruIds != null) {
            val ruWordCache = getWordsWithCache(ruIds.asList(), batchWordCache)
            val isKanaForStem = JapaneseTextHelper.isAllKana(wordInfo.text)
            var bestV1: JmDictWord? = null
            for (id in ruIds.distinct()) {
                val ruWord = ruWordCache[id] ?: continue
                if (ruWord.partsOfSpeech.any { it == "v1" || it == "v1-s" } &&
                    (bestV1 == null || ruWord.getPriorityScore(isKanaForStem) > bestV1.getPriorityScore(isKanaForStem)))
                    bestV1 = ruWord
            }

            if (bestV1 != null) {
                // The stem (抱え) isn't one of 抱える's forms, so match the kana form minus る.
                val stemHira = KanaConverter.toHiragana(wordInfo.text, convertLongVowelMark = false)
                val stemReadingIndex = bestV1.forms.firstOrNull {
                    it.formType == JmDictFormType.KanaForm &&
                        KanaConverter.toHiragana(it.text, convertLongVowelMark = false) == stemHira + "る"
                }?.let { it.readingIndex and 0xFF } ?: 0
                verbFallback = ResolveResult(true,
                    DeckWord(wordId = bestV1.wordId, originalText = wordInfo.text, readingIndex = stemReadingIndex,
                        conjugations = listOf("continuative"), partsOfSpeech = ArrayList(bestV1.cachedPOS), origin = bestV1.origin),
                    null, null)
            }
        }
    }

    val verbWord = verbFallback.word
    val nounIsPureNameEntry = nounWord != null &&
        nounWord.partsOfSpeech.all { it == PartOfSpeech.Name || it == PartOfSpeech.Unknown }
    if ((wordInfo.isPersonNameContext || isNameLikeSudachiNoun) && nounIsPureNameEntry) {
        processedWord = nounWord
        resolvedMargin = nounResult.margin
    } else if (verbFallback.success && verbWord != null && nounWord != null) {
        val bothCache = getWordsWithCache(listOf(nounWord.wordId, verbWord.wordId), batchWordCache)
        val nounEntry = bothCache[nounWord.wordId]
        val verbEntry = bothCache[verbWord.wordId]
        if (nounEntry != null && verbEntry != null) {
            // Same word-level scorers as the main scorer so archaic penalties and copula boosts agree.
            fun nounVerbScore(word: JmDictWord, readingIndex: Int): Int {
                val form = word.forms.firstOrNull { (it.readingIndex and 0xFF) == readingIndex } ?: word.forms.firstOrNull()
                    ?: return 0
                val candidate = FormCandidate(word, form, readingIndex, form.text, null)
                return WordPriorityScorer.score(candidate, isNameContext = false, isArchaicSentence = false,
                    archaicPosTypes = JitenParser.ArchaicPosTypes) + EntryPriorityScorer.score(candidate)
            }

            val reading = wordInfo.reading
            val nounReadingMatch = reading.isNotEmpty() && FormCandidateFactory.hasKanaReadingMatch(nounEntry, reading)
            val verbReadingMatch = reading.isNotEmpty() &&
                FormCandidateFactory.hasKanaReadingMatch(verbEntry, reading, allowStemMatch = true)
            val verbExactReadingMatch = reading.isNotEmpty() && FormCandidateFactory.hasKanaReadingMatch(verbEntry, reading)

            if (nounReadingMatch && !verbReadingMatch) {
                processedWord = nounWord
                resolvedMargin = nounResult.margin
            } else if (!nounReadingMatch && verbReadingMatch) {
                val nounIsAdji = "adj-i" in nounEntry.partsOfSpeech
                // A function-word-only verb side can't realize a noun token (バッカ stays 馬鹿, not particle ばかり).
                val verbIsFunctionWordOnly = verbEntry.cachedPOS.all {
                    it == PartOfSpeech.Particle || it == PartOfSpeech.Conjunction || it == PartOfSpeech.Auxiliary ||
                        it == PartOfSpeech.Unknown
                }
                if (verbIsFunctionWordOnly && wordInfo.partOfSpeech == PartOfSpeech.Noun) {
                    processedWord = nounWord
                    resolvedMargin = nounResult.margin
                } else if (nounIsAdji && ScoringPolicy.isHighConfidence(nounResult.margin)) {
                    processedWord = nounWord
                    resolvedMargin = nounResult.margin
                } else {
                    processedWord = verbWord
                    resolvedMargin = verbFallback.margin
                }
            } else if (nounReadingMatch && verbReadingMatch && !verbExactReadingMatch) {
                // A stem-only verb (でき → 出来) needs a margin, larger over a confident noun (うえ → 上, not 飢える).
                val stemThreshold = if (ScoringPolicy.isHighConfidence(nounResult.margin)) 30 else 15
                var nounEvidence = nounVerbScore(nounEntry, nounWord.readingIndex)
                val verbEvidence = nounVerbScore(verbEntry, verbWord.readingIndex)
                // A standalone suru-noun is complete (真似+できない); its verb homograph 真似る needs more evidence.
                if ("vs" in nounEntry.partsOfSpeech) nounEvidence += 40
                // Sudachi's own-entry noun (備え) holds when nf <= 20; rarer nouns (抱え, nf23) yield to the verb.
                if (wordInfo.dictionaryForm == wordInfo.text) {
                    val nounNf = (nounEntry.priorities ?: emptyList()).firstOrNull { it.startsWith("nf") }
                    val nfRank = if (nounNf != null && nounNf.length > 2) nounNf.substring(2).toIntOrNull() else null
                    if (nfRank != null && nfRank <= 20) nounEvidence += 20
                }
                if (verbEvidence - nounEvidence > stemThreshold) {
                    processedWord = verbWord
                    resolvedMargin = verbFallback.margin
                } else {
                    processedWord = nounWord
                    resolvedMargin = nounResult.margin
                }
            } else if (nounVerbScore(verbEntry, verbWord.readingIndex) > nounVerbScore(nounEntry, nounWord.readingIndex)) {
                processedWord = verbWord
                resolvedMargin = verbFallback.margin
            } else {
                processedWord = nounWord
                resolvedMargin = nounResult.margin
            }
        } else {
            processedWord = nounWord
            resolvedMargin = nounResult.margin
        }
    } else {
        processedWord = nounWord
        resolvedMargin = nounResult.margin
    }

    val candidates = when {
        processedWord == null -> null
        processedWord.wordId == nounWord?.wordId -> nounResult.candidates
        else -> verbFallback.candidates
    }
    return ProcessedWord(processedWord, resolvedMargin, candidates)
}

// Reading-key collisions the surface can't support: イエイ → 遺影, ひゅーん → 庇陰, てりゃあ → テリア.
internal fun JitenParser.isKanaCollisionResolution(processedWord: DeckWord, wordInfo: WordInfo, baseWord: String): Boolean {
    fun directHas(key: String) = lexicon.lookup(key)?.contains(processedWord.wordId) == true

    if (wordInfo.isKanaExclamation && !lexicon.isKanaAppropriateId(processedWord.wordId) && !directHas(wordInfo.text))
        return true

    if (!wordInfo.isKanaExclamation && wordInfo.text != baseWord && JapaneseTextHelper.isAllKana(baseWord) &&
        !lexicon.isKanaAppropriateId(processedWord.wordId) && !directHas(baseWord))
        return true

    if (wordInfo.text != baseWord && isHiraganaSurface(baseWord) &&
        lexicon.wordMeta(processedWord.wordId)?.origin == WordOrigin.Gairaigo && !directHas(baseWord))
        return true

    return false
}

// One mutation per attempt, in priority order; null when none applies.
internal fun JitenParser.nextSurfaceMutation(wordInfo: WordInfo, baseWord: String): String? {
    val text = wordInfo.text
    // A trailing small vowel is stretching in hiragana (なんちゃってぇ) but identity in katakana (ソフィ, not ソフ).
    if (text.length > 2) {
        val last = text[text.length - 1]
        if (last in "っーぁぃぅぇぉ" || (last in "ァィゥェォ" && !JapaneseTextHelper.isAllKatakana(text)) ||
            text[text.length - 2] == last)
            return text.substring(0, text.length - 1)
    }

    if (text.startsWith('お') || text.startsWith('御')) return text.substring(1)

    // Before the blanket strips, which eat geminates: drop only echoing small vowels (たぁっぷり), not ふぁ or katakana ソフィ.
    if (!JapaneseTextHelper.isAllKatakana(text)) {
        val echoStripped = tryRemoveEchoedSmallVowels(text)
        if (echoStripped != null) return echoStripped
    }

    if ('ー' in text) return text.replace("ー", "")

    if ('っ' in text || 'ッ' in text) return baseWord.replace("っ", "").replace("ッ", "")

    if (text.any { it in "ゃゅょぁぃぅぇぉ" }) {
        return baseWord.replace("ゃ", "").replace("ゅ", "").replace("ょ", "").replace("ぁ", "").replace("ぃ", "")
            .replace("ぅ", "").replace("ぇ", "").replace("ぉ", "").replace("っ", "").replace("ッ", "")
    }

    // Sudachi fuses adv-to words with と (凛と, 毅然と); JMDict has only the base form.
    if (wordInfo.partOfSpeech == PartOfSpeech.Adverb && text.length > 1 && text[text.length - 1] == 'と')
        return text.substring(0, text.length - 1)

    return null
}

// A junk collision via emphatic sokuon (ばっかな → 幕下) retries without it; kana-appropriate hits (ばっか = ばかり) stay.
internal fun JitenParser.tryGeminationCollapseRetry(
    wordInfo: WordInfo,
    processedWord: DeckWord,
    baseWord: String,
    deconjugator: Deconjugator,
    batchWordCache: MutableMap<Int, JmDictWord>?,
): ProcessedWord? {
    if (baseWord.length < 3 || !JapaneseTextHelper.isAllKana(baseWord) ||
        lexicon.isKanaAppropriateId(processedWord.wordId) || hasPrioritizedMeta(processedWord.wordId))
        return null

    val desokuon = removeInternalSokuon(baseWord)
    if (desokuon == baseWord || desokuon.length < 2) return null

    val retryInfo = wordInfo.copy().apply { text = desokuon }
    var retry = deconjugateVerbOrAdjective(retryInfo, deconjugator, batchWordCache)
    if (retry.word == null) retry = deconjugateWord(retryInfo, batchWordCache)
    val retryWord = retry.word
    if (retryWord != null && (lexicon.isKanaAppropriateId(retryWord.wordId) || hasPrioritizedMeta(retryWord.wordId))) {
        retryWord.originalText = baseWord
        return ProcessedWord(retryWord, retry.margin, retry.candidates)
    }

    return null
}

// Mirrors DeconjugateVerbOrAdjective's form fallback; when it can resolve, the reading channel must stay out.
internal fun JitenParser.dictOrNormalizedFormHasLookup(wordInfo: WordInfo): Boolean {
    if (wordInfo.dictionaryForm.isNotEmpty()) {
        val dictHiragana = KanaConverter.toHiragana(wordInfo.dictionaryForm.replace("ゎ", "わ").replace("ヮ", "わ"),
            convertLongVowelMark = false)
        if (lexicon.containsKey(dictHiragana) || lexicon.containsKey(wordInfo.dictionaryForm)) return true
    }

    if (wordInfo.normalizedForm.isNotEmpty() && !normalizedFormIntroducesKanji(wordInfo.text, wordInfo.normalizedForm)) {
        val normalizedHiragana = KanaConverter.toHiragana(wordInfo.normalizedForm, convertLongVowelMark = false)
        if (lexicon.containsKey(normalizedHiragana) || lexicon.containsKey(wordInfo.normalizedForm)) return true
    }

    return false
}

// Okurigana rewritten into extra kanji is another lexeme (屈し ≠ 屈指); 敲き → 叩く and チックショー → 畜生 are fine.
@Suppress("UnusedReceiverParameter")
internal fun JitenParser.normalizedFormIntroducesKanji(text: String, normalizedForm: String): Boolean {
    val surfaceKanji = text.count(JapaneseTextHelper::isKanji)
    if (surfaceKanji == 0) return false
    val normalizedKanji = normalizedForm.count(JapaneseTextHelper::isKanji)
    return normalizedKanji > surfaceKanji
}

internal fun JitenParser.deconjugateWord(
    wordInfo: WordInfo,
    batchWordCache: MutableMap<Int, JmDictWord>? = null,
): ResolveResult {
    val text = wordInfo.text

    val textWithoutBar = text.trimEnd('ー')
    if (isAllDigits(textWithoutBar) || (text.length == 1 && text.isAsciiOrFullWidthLetter())) return FailedResolve

    val textInHiragana = KanaConverter.toHiragana(wordInfo.text, convertLongVowelMark = false)
    var candidates: MutableList<Int>?
    var isStripped = false
    var textStripped = ""
    var desokuonText: String? = null

    val constrainedIds = wordInfo.preMatchedCandidateWordIds
    if (constrainedIds != null && constrainedIds.size > 0) {
        candidates = ArrayList(constrainedIds)
    } else {
        // Kana surfaces reaching a word only via long-vowel rewrites need a kana-appropriate word (イエーイ ≠ 遺影).
        val normalizedTierGate: ((Int) -> Boolean)? = if (JapaneseTextHelper.isAllKana(text)) lexicon::isKanaAppropriateId else null

        var collected = LookupCandidateCollector.collectIds(lexicon, text, includeKanaNormalized = true,
            includeLongVowelStripped = true, normalizedTierGate = normalizedTierGate)

        // NormalizedForm catches variants: チックショー → チクショー (畜生).
        if (wordInfo.normalizedForm.isNotEmpty() && wordInfo.normalizedForm != text &&
            !normalizedFormIntroducesKanji(text, wordInfo.normalizedForm)) {
            val normalizedCollected = LookupCandidateCollector.collectIds(lexicon, wordInfo.normalizedForm,
                includeKanaNormalized = true, includeLongVowelStripped = true, normalizedTierGate = normalizedTierGate)
            if (normalizedCollected.size > 0) collected = appendDistinct(collected, normalizedCollected)
        }

        // Emphatic-sokuon kana (バッカ = 馬鹿) also tries the de-sokuon key; priority-less kanji collisions (麦価) then drop.
        if (text.length >= 3 && JapaneseTextHelper.isAllKana(text)) {
            val desokuon = removeInternalSokuon(text)
            if (desokuon != text && desokuon.length >= 2) {
                val desokuonCollected = LookupCandidateCollector.collectIds(lexicon, desokuon, includeKanaNormalized = true,
                    includeLongVowelStripped = false, normalizedTierGate = lexicon::isKanaAppropriateId)
                if (desokuonCollected.size > 0) {
                    desokuonText = desokuon
                    collected = appendDistinct(
                        collected.filter { lexicon.isKanaAppropriateId(it) || hasPrioritizedMeta(it) }, desokuonCollected)
                }
            }
        }

        candidates = if (collected.size > 0) collected else null

        if ('ー' in text) {
            isStripped = true
            textStripped = text.replace("ー", "")
        }
    }

    if (candidates.isNullOrEmpty()) return FailedResolve

    candidates.sort()

    val wordCache = try {
        getWordsWithCache(candidates, batchWordCache)
    } catch (ex: Exception) {
        System.err.println("Error retrieving word cache: ${ex.message}")
        return FailedResolve
    }

    if (wordCache.isEmpty()) return FailedResolve

    // A katakana hit bars words with no katakana form (カモン ≠ 家紋, フル ≠ 降る); 懐炉/カイロ still competes.
    if (wordInfo.preMatchedCandidateWordIds.isNullOrEmpty() && KanaScoringHelpers.isPureKatakanaToken(text)) {
        val directKatakanaIds = lexicon.lookup(text)
        if (directKatakanaIds != null) {
            // Script is evidence for content words only: katakana function words are colloquial spelling (部屋ン中 = 部屋の中).
            val isFunctionWordToken = wordInfo.partOfSpeech == PartOfSpeech.Particle || wordInfo.partOfSpeech == PartOfSpeech.Auxiliary

            val hasUsableDirectCandidate = !isFunctionWordToken && directKatakanaIds.any { id ->
                wordCache[id]?.cachedPOS?.any(::nonNamePos) == true
            }

            if (hasUsableDirectCandidate) {
                val directIdSet = directKatakanaIds.toHashSet()
                candidates = candidates.filterTo(ArrayList()) { id ->
                    id in directIdSet || wordCache[id]?.forms?.any { KanaScoringHelpers.containsKatakana(it.text) } == true
                }
            }
        }
    }

    val isNameLikeSudachiNoun = PosMapper.isNameLikeSudachiNoun(wordInfo.partOfSpeech, wordInfo.partOfSpeechSection1,
        wordInfo.partOfSpeechSection2, wordInfo.partOfSpeechSection3)

    val isKanaSurfaceToken = JapaneseTextHelper.isAllKana(text)

    var hasAnyNonNameCandidate = false
    val compatibleNonNameMatches = ArrayList<JmDictWord>()
    val nameCandidates = ArrayList<JmDictWord>()

    for (id in candidates) {
        val word = wordCache[id] ?: continue

        val posList = word.cachedPOS
        val hasNonNamePos = posList.any(::nonNamePos)
        if (hasNonNamePos) hasAnyNonNameCandidate = true

        // Entries with both name and non-name tags count as non-name.
        val isPureNameEntry = !hasNonNamePos && PartOfSpeech.Name in posList

        // Stripped surfaces allow the interjection fallback (よー, こーら).
        val compatible = PosMapper.isJmDictCompatibleWithSudachi(word.cachedPOS, wordInfo.partOfSpeech,
            allowInterjectionFallback = isStripped, allowNounExpressionFallback = isKanaSurfaceToken && isNameLikeSudachiNoun)

        if (compatible && hasNonNamePos) {
            compatibleNonNameMatches.add(word)
            continue
        }

        if (isPureNameEntry && (wordInfo.isPersonNameContext || isNameLikeSudachiNoun)) nameCandidates.add(word)
    }

    var candidatePool: MutableList<JmDictWord>
    val isNameContext: Boolean

    if (wordInfo.isPersonNameContext && nameCandidates.size > 0) {
        candidatePool = nameCandidates
        isNameContext = true
    } else if (isNameLikeSudachiNoun && nameCandidates.size > 0) {
        candidatePool = ArrayList(compatibleNonNameMatches)
        candidatePool.addAll(nameCandidates)
        isNameContext = true
    } else if (compatibleNonNameMatches.size > 0) {
        candidatePool = compatibleNonNameMatches
        isNameContext = false
    } else if (!hasAnyNonNameCandidate && nameCandidates.size > 0) {
        candidatePool = nameCandidates
        isNameContext = true
    } else if (hasAnyNonNameCandidate) {
        // POS-relaxed fallback admits any non-name entry, except kana exclamations into kanji words (イエイ → 遺影).
        val requireKanaAppropriate = wordInfo.isKanaExclamation
        for (id in candidates) {
            val word = wordCache[id] ?: continue
            // Exact-surface forms are real spellings, not collisions (あなた → 貴方, ファルマ → name).
            if (requireKanaAppropriate && !lexicon.isKanaAppropriateId(id) && word.forms.none { it.text == text }) continue
            if (word.cachedPOS.any(::nonNamePos)) compatibleNonNameMatches.add(word)
        }

        candidatePool = compatibleNonNameMatches
        isNameContext = false
    } else {
        val lastResortCandidates = ArrayList<JmDictWord>()
        val surfaceIsHiragana = WanaKana.isHiragana(text)
        for (id in candidates) {
            val word = wordCache[id] ?: continue

            // Hiragana reaching a name only via the katakana fold is onomatopoeia (ひゅーん ≠ ヒューン).
            if (surfaceIsHiragana && word.cachedPOS.none(::nonNamePos) && word.forms.none { it.text == text }) continue

            lastResortCandidates.add(word)
        }

        if (lastResortCandidates.size > 0) {
            candidatePool = lastResortCandidates
            isNameContext = true
        } else {
            return FailedResolve
        }
    }

    // Surviving Suffix POS means attached to a noun: pure suffixes (め=奴, ら, ども) beat noun/suffix hybrids like 目.
    if (wordInfo.partOfSpeech == PartOfSpeech.Suffix && !isNameContext) {
        val pureSuffixes = candidatePool.filterTo(ArrayList()) { w ->
            (w.cachedPOSMask and (PosMask.bit(PartOfSpeech.Suffix) or PosMask.bit(PartOfSpeech.NounSuffix))) != 0 &&
                (w.cachedPOSMask and PosMask.NounLike) == 0
        }
        if (pureSuffixes.size > 0) candidatePool = pureSuffixes
    }

    val allFormCandidates = ArrayList<FormCandidate>()
    for (word in candidatePool) {
        val forms = FormCandidateFactory.enumerateCandidateForms(word, textInHiragana, allowLooseLvmMatch = true, surface = text)
        allFormCandidates.addAll(forms)

        // Gemination fallback words match only via the de-sokuon text (バッカ → バカ → 馬鹿).
        if (desokuonText != null) {
            val desokuonHira = KanaConverter.toHiragana(desokuonText, convertLongVowelMark = false)
            if (desokuonHira != textInHiragana)
                allFormCandidates.addAll(FormCandidateFactory.enumerateCandidateForms(word, desokuonHira,
                    allowLooseLvmMatch = true, surface = desokuonText))
        }

        if (!isStripped) continue

        val strippedHira = KanaConverter.toHiragana(textStripped, convertLongVowelMark = false)
        if (strippedHira == textInHiragana) continue

        val strippedForms = FormCandidateFactory.enumerateCandidateForms(word, strippedHira, allowLooseLvmMatch = true,
            surface = textStripped)
        allFormCandidates.addAll(strippedForms)
    }

    var (bestPair, margin) = pickBestFormCandidate(allFormCandidates, text, wordInfo.dictionaryForm, wordInfo.normalizedForm,
        isNameContext, sudachiReading = wordInfo.reading, sudachiPOS = wordInfo.partOfSpeech,
        isSudachiPossibleDependant = wordInfo.hasPartOfSpeechSection(PartOfSpeechSection.PossibleDependant),
        isSudachiNameGuess = isNameLikeSudachiNoun && !wordInfo.isPersonNameContext)

    // Resegmented tokens break scorer ties with the frequency-best candidate chosen at resegmentation.
    val preferredId = wordInfo.preMatchedWordId
    if (margin == 0 && wordInfo.preMatchedCandidateWordIds != null && preferredId != null) {
        val preferred = allFormCandidates.filter { it.word.wordId == preferredId }.maxByOrNull { it.totalScore }
        if (preferred != null && preferred.totalScore >= bestPair!!.totalScore) bestPair = preferred
    }

    if (bestPair == null) return FailedResolve

    val deckWord = DeckWord(wordId = bestPair.word.wordId, originalText = wordInfo.text, readingIndex = bestPair.readingIndex,
        partsOfSpeech = ArrayList(bestPair.word.cachedPOS), origin = bestPair.word.origin)
    return ResolveResult(true, deckWord, margin, allFormCandidates)
}

internal fun JitenParser.deconjugateVerbOrAdjective(
    wordInfo: WordInfo,
    deconjugator: Deconjugator,
    batchWordCache: MutableMap<Int, JmDictWord>? = null,
): ResolveResult {
    // Before WanaKana, which can't convert full-width digits.
    val textWithoutBar = wordInfo.text.trimEnd('ー')
    if (isAllDigits(textWithoutBar) || (textWithoutBar.length == 1 && textWithoutBar.isAsciiOrFullWidthLetter()))
        return FailedResolve

    val normalizedText = KanaNormalizer.normalize(KanaConverter.toHiragana(wordInfo.text))

    if (normalizedText.length == 1 && normalizedText.isAsciiOrFullWidthLetter()) return FailedResolve

    val (deconjugated, candidates, fromReadingChannel) = collectVerbAdjectiveCandidates(wordInfo, deconjugator, normalizedText)

    val baseDictionaryWord = KanaConverter.toHiragana(wordInfo.dictionaryForm.replace("ゎ", "わ").replace("ヮ", "わ"),
        convertLongVowelMark = false)
    val (allCandidateIds, directSurfaceIds) = prioritiseAndCollectCandidateIds(wordInfo, candidates, baseDictionaryWord)

    if (allCandidateIds.size == 0) return FailedResolve

    val wordCache = try {
        getWordsWithCache(allCandidateIds, batchWordCache)
    } catch (ex: Exception) {
        System.err.println("Error retrieving verb/adjective word cache: ${ex.message}")
        return FailedResolve
    }
    if (wordCache.isEmpty()) return FailedResolve

    val (matches, filteredSuruNounIds) = filterAndMatchCandidates(wordInfo, deconjugated, candidates, wordCache,
        fromReadingChannel, normalizedText, baseDictionaryWord, batchWordCache)

    if (matches.size == 0) return FailedResolve

    val allFormCandidates = enumerateMatchAndSurfaceCandidates(wordInfo, matches, directSurfaceIds, filteredSuruNounIds,
        wordCache, normalizedText, baseDictionaryWord)

    val picked = pickBestFormCandidate(allFormCandidates, wordInfo.text, wordInfo.dictionaryForm,
        wordInfo.normalizedForm, isNameContext = wordInfo.isPersonNameContext, sudachiReading = wordInfo.reading,
        sudachiPOS = wordInfo.partOfSpeech,
        isSudachiPossibleDependant = wordInfo.hasPartOfSpeechSection(PartOfSpeechSection.PossibleDependant))

    val (bestPair, margin) = applyConjugationChainOverrides(picked.first, picked.second, allFormCandidates, wordInfo)

    if (bestPair == null) return FailedResolve

    val process = bestPair.deconjForm?.process
    val deckWord = DeckWord(wordId = bestPair.word.wordId, originalText = wordInfo.text, readingIndex = bestPair.readingIndex,
        conjugations = if (process != null && process.size == 1 && process[0] == "casual kind request" &&
            "adj-na" in bestPair.word.partsOfSpeech) emptyList() else process?.toList() ?: emptyList(),
        partsOfSpeech = ArrayList(bestPair.word.cachedPOS), origin = bestPair.word.origin)

    return ResolveResult(true, deckWord, margin, allFormCandidates)
}

internal fun JitenParser.collectVerbAdjectiveCandidates(
    wordInfo: WordInfo,
    deconjugator: Deconjugator,
    normalizedText: String,
): VerbAdjectiveCandidates {
    val deconjugated = deconjugator.deconjugate(normalizedText).toList()

    // Deconjugating a katakana noun into a kanji word fabricates vocabulary (ハガナ → 剥ぐ); ヤバイ → やばい stays.
    val katakanaNounSurface = wordInfo.isKatakanaNounSurface

    val candidates = ArrayList<Pair<DeconjugationForm, List<Int>>>()
    for (form in deconjugated) {
        val lookup = lexicon.lookup(form.text) ?: continue
        // A suru-noun stem (tagged n: アクシュシヨウ → あくしゅ+しよう) is the noun itself, not fabricated.
        if (katakanaNounSurface && form.process.isNotEmpty() && "n" !in form.tags) {
            val kanaIds = lookup.filter(lexicon::isKanaAppropriateId)
            if (kanaIds.isEmpty()) continue
            candidates.add(form to kanaIds)
            continue
        }

        candidates.add(form to lookup.asList())
    }

    // Kana-for-kanji spellings (帰りつけた) deconjugate to non-keys, so deconjugate the reading when nothing else can.
    // Excludes single kanji and digit tokens, whose readings hit homophones (髀 → 蒲桃, ３万 → 散漫).
    var fromReadingChannel = false
    if (candidates.isEmpty() && wordInfo.text.length >= 2 && wordInfo.text.any(JapaneseTextHelper::isKanji) &&
        !wordInfo.text.any { it.isDigit() || it in '０'..'９' } && wordInfo.reading.isNotEmpty() &&
        !dictOrNormalizedFormHasLookup(wordInfo)) {
        val readingHiragana = KanaNormalizer.normalize(KanaConverter.toHiragana(wordInfo.reading))
        if (readingHiragana != normalizedText && WanaKana.isHiragana(readingHiragana)) {
            for (form in deconjugator.deconjugate(readingHiragana)) {
                val lookup = lexicon.lookup(form.text) ?: continue
                candidates.add(form to lookup.asList())
                fromReadingChannel = true
            }
        }
    }

    return VerbAdjectiveCandidates(deconjugated, candidates, fromReadingChannel)
}

/** Reorders [candidates] in place, as C# does; returns (allCandidateIds, directSurfaceIds). */
internal fun JitenParser.prioritiseAndCollectCandidateIds(
    wordInfo: WordInfo,
    candidates: MutableList<Pair<DeconjugationForm, List<Int>>>,
    baseDictionaryWord: String,
): Pair<MutableList<Int>, MutableList<Int>> {
    val tryDictionaryFormFallback = candidates.isEmpty() && wordInfo.dictionaryForm.isNotEmpty()

    val baseDictionaryWordIndex = candidates.indexOfFirst { it.first.text == baseDictionaryWord }
    if (baseDictionaryWordIndex != -1) {
        val baseDictionaryWordCandidate = candidates.removeAt(baseDictionaryWordIndex)
        candidates.add(0, baseDictionaryWordCandidate)
    }

    val baseWord = KanaConverter.toHiragana(wordInfo.text)
    val baseWordIndex = candidates.indexOfFirst { it.first.text == baseWord }
    if (baseWordIndex != -1 && candidates[0].first.text != baseDictionaryWord) {
        val baseWordCandidate = candidates.removeAt(baseWordIndex)
        candidates.add(0, baseWordCandidate)
    }

    val seenIds = HashSet<Int>()
    val allCandidateIds = ArrayList<Int>()
    for (c in candidates) for (id in c.second) if (seenIds.add(id)) allCandidateIds.add(id)

    if (tryDictionaryFormFallback) {
        var dictFormLookupIds = lexicon.lookup(baseDictionaryWord) ?: lexicon.lookup(wordInfo.dictionaryForm)
        if (dictFormLookupIds != null && dictFormLookupIds.isNotEmpty()) {
            for (id in dictFormLookupIds) if (seenIds.add(id)) allCandidateIds.add(id)
        }

        // 多き: DictionaryForm 多し, NormalizedForm 多い.
        if ((dictFormLookupIds == null || dictFormLookupIds.isEmpty()) && wordInfo.normalizedForm.isNotEmpty() &&
            !normalizedFormIntroducesKanji(wordInfo.text, wordInfo.normalizedForm)) {
            val normalizedHiragana = KanaConverter.toHiragana(wordInfo.normalizedForm, convertLongVowelMark = false)
            dictFormLookupIds = lexicon.lookup(normalizedHiragana) ?: lexicon.lookup(wordInfo.normalizedForm)
            if (dictFormLookupIds != null && dictFormLookupIds.isNotEmpty()) {
                for (id in dictFormLookupIds) if (seenIds.add(id)) allCandidateIds.add(id)
            }
        }
    }

    // Exact-surface entries (adverb 悪しからず, not deconjugated 悪しい); keep the full set, matches were POS-filtered.
    val directSurfaceIds = LookupCandidateCollector.collectIds(lexicon, wordInfo.text, includeKanaNormalized = false)
    for (id in directSurfaceIds) if (seenIds.add(id)) allCandidateIds.add(id)

    return allCandidateIds to directSurfaceIds
}

/** Narrows [candidates] in place for the reading channel, as C# does; returns (matches, filteredSuruNounIds). */
internal fun JitenParser.filterAndMatchCandidates(
    wordInfo: WordInfo,
    deconjugated: List<DeconjugationForm>,
    candidates: MutableList<Pair<DeconjugationForm, List<Int>>>,
    wordCache: Map<Int, JmDictWord>,
    fromReadingChannel: Boolean,
    normalizedText: String,
    baseDictionaryWord: String,
    batchWordCache: MutableMap<Int, JmDictWord>?,
): Pair<MutableList<Pair<JmDictWord, DeconjugationForm>>, MutableSet<Int>?> {
    // Reading-derived candidates must share a kanji with the token (帰りつけた/帰り着く), or any homophone wins.
    if (fromReadingChannel) {
        val surfaceKanji = wordInfo.text.filter(JapaneseTextHelper::isKanji).toCharArray()
        for (ci in candidates.size - 1 downTo 0) {
            val kept = candidates[ci].second.filter { id ->
                wordCache[id]?.forms?.any { it.text.indexOfAny(surfaceKanji) >= 0 } == true
            }
            if (kept.isNotEmpty()) candidates[ci] = candidates[ci].first to kept else candidates.removeAt(ci)
        }
    }

    val matchResults = DeconjugationMatcher.filterMatches(candidates, wordCache, wordInfo.partOfSpeech)
    val matches = matchResults.mapTo(ArrayList()) { it.word to it.form }

    // When DictionaryForm differs (いかん → いく), drop vs-only identity matches so 移管 can't beat 行かん/行く.
    var filteredSuruNounIds: MutableSet<Int>? = null
    if (matches.size > 1 && normalizedText != baseDictionaryWord && baseDictionaryWord.isNotEmpty()) {
        val hasDictFormMatch = matches.any { it.second.text == baseDictionaryWord && it.second.process.isNotEmpty() }
        if (hasDictFormMatch) {
            val filtered = matches.filter {
                it.second.process.isEmpty() && FormCandidateFactory.isSuruNounWithoutExpression(it.first.partsOfSpeech)
            }.mapTo(HashSet()) { it.first.wordId }
            if (filtered.isNotEmpty()) {
                filteredSuruNounIds = filtered
                matches.removeAll { it.first.wordId in filtered }
            }
        }
    }

    if (matches.isEmpty()) {
        if (wordInfo.dictionaryForm.isNotEmpty()) {
            tryFallbackLookup(wordInfo.dictionaryForm.replace("ゎ", "わ").replace("ヮ", "わ"), wordInfo, deconjugated, matches,
                batchWordCache)
        }

        if (matches.isEmpty() && wordInfo.normalizedForm.isNotEmpty() && wordInfo.normalizedForm != wordInfo.dictionaryForm &&
            !normalizedFormIntroducesKanji(wordInfo.text, wordInfo.normalizedForm)) {
            tryFallbackLookup(wordInfo.normalizedForm, wordInfo, deconjugated, matches, batchWordCache, baseDictionaryWord)
        }

        // POS-relaxed fallback: any non-name entry, still validating deconjugation tags.
        if (matches.isEmpty()) {
            for (m in DeconjugationMatcher.filterMatches(candidates, wordCache, wordInfo.partOfSpeech, strictPosCheck = false))
                matches.add(m.word to m.form)
        }
    }

    return matches to filteredSuruNounIds
}

@Suppress("UnusedReceiverParameter")
internal fun JitenParser.enumerateMatchAndSurfaceCandidates(
    wordInfo: WordInfo,
    matches: List<Pair<JmDictWord, DeconjugationForm>>,
    directSurfaceIds: List<Int>,
    filteredSuruNounIds: Set<Int>?,
    wordCache: Map<Int, JmDictWord>,
    normalizedText: String,
    baseDictionaryWord: String,
): MutableList<FormCandidate> {
    // Each match's deconjForm.Text is the targetHiragana for phonetic gating.
    val allFormCandidates = ArrayList<FormCandidate>()
    for ((word, form) in matches) {
        allFormCandidates.addAll(FormCandidateFactory.enumerateCandidateForms(word, form.text, allowLooseLvmMatch = true,
            deconjForm = form, surface = wordInfo.text))
    }

    // Words already in matches are skipped (a duplicate fakes margin=0); POS-incompatible ones are marked for penalty.
    val matchedWordIds = matches.mapTo(HashSet()) { it.first.wordId }
    // A merged inflection whose deconjugation confirms DictionaryForm (いない → 居る) drops POS-incompatible 以内.
    // Merged tokens only: single-token ambiguity (いい as adj vs verb いう) still competes.
    val hasMergeConfirmedDeconj = wordInfo.isMergedInflection &&
        matches.any { it.second.text == baseDictionaryWord && it.second.process.isNotEmpty() }
    val pastFormProcess = matches.firstOrNull { "past" in it.second.process }?.second?.process
    for (id in directSurfaceIds) {
        if (id in matchedWordIds) continue
        if (filteredSuruNounIds != null && id in filteredSuruNounIds) continue
        val directWord = wordCache[id] ?: continue
        if (directWord.cachedPOS.none(::nonNamePos)) continue
        val isPosIncompat = matches.isNotEmpty() &&
            !PosMapper.isJmDictCompatibleWithSudachi(directWord.cachedPOS, wordInfo.partOfSpeech)
        if (isPosIncompat && hasMergeConfirmedDeconj) continue
        if (pastFormProcess != null &&
            FormCandidateSelector.isUnattestedInterjectionOverPastForm(directWord, pastFormProcess.asList()))
            continue
        val forms = FormCandidateFactory.enumerateCandidateForms(directWord, normalizedText, allowLooseLvmMatch = true,
            surface = wordInfo.text)
        if (isPosIncompat) for (f in forms) f.isPosIncompatibleDirectSurface = true
        allFormCandidates.addAll(forms)
    }

    return allFormCandidates
}

/** Returns (bestPair, margin). */
@Suppress("UnusedReceiverParameter")
internal fun JitenParser.applyConjugationChainOverrides(
    bestPair: FormCandidate?,
    margin: Int?,
    allFormCandidates: List<FormCandidate>,
    wordInfo: WordInfo,
): Pair<FormCandidate?, Int?> {
    var best = bestPair
    var m = margin
    // 行けよ is almost always 行く's imperative, rarely potential 行ける's.
    if (best != null && wordInfo.isImperative && wordInfo.normalizedForm.isNotEmpty() &&
        wordInfo.normalizedForm != wordInfo.dictionaryForm) {
        val normalizedHira = KanaConverter.toHiragana(wordInfo.normalizedForm, convertLongVowelMark = false)
        val normalizedFormHira = KanaNormalizer.normalize(normalizedHira)

        val baseVerbCandidate = allFormCandidates.filter {
            it.form.text == wordInfo.normalizedForm ||
                KanaNormalizer.normalize(KanaConverter.toHiragana(it.form.text, convertLongVowelMark = false)) == normalizedFormHira
        }.maxByOrNull { ScoringPolicy.effectiveScore(it) }

        if (baseVerbCandidate != null) {
            best = baseVerbCandidate
            m = ScoringPolicy.HighConfidenceThreshold
        }
    }

    // Sudachi's ん was negative ぬ, but the shorter slurred-る chain wins (認められん as non-negative), so swap chains.
    // margin stays: the word is unchanged, and lowering it would trigger a resegmentation re-cut.
    if (best != null && wordInfo.isSlurredNegative && best.deconjForm?.process?.any { "negative" in it } != true) {
        val bestId = best.word.wordId
        val negativeChain = allFormCandidates.filter {
            it.word.wordId == bestId && it.deconjForm?.process?.any { p -> "negative" in p } == true
        }.maxByOrNull { ScoringPolicy.effectiveScore(it) }
        if (negativeChain != null) best = negativeChain
    }

    return best to m
}

@Suppress("UnusedReceiverParameter")
internal fun JitenParser.isGodanClassTag(tag: String): Boolean = tag.startsWith("v5") || tag.startsWith("v4")

internal fun JitenParser.isIchidanOnlyVerb(partsOfSpeech: List<String>): Boolean =
    partsOfSpeech.any { it == "v1" || it == "v1-s" } &&
        partsOfSpeech.none { isGodanClassTag(it) || it.startsWith("v2") || it.startsWith("vs") || it == "vk" || it == "vz" }

/** Appends to [matches]. */
internal fun JitenParser.tryFallbackLookup(
    formText: String,
    wordInfo: WordInfo,
    deconjugated: List<DeconjugationForm>,
    matches: MutableList<Pair<JmDictWord, DeconjugationForm>>,
    batchWordCache: MutableMap<Int, JmDictWord>?,
    extraProcessPrefix: String? = null,
) {
    val formHiragana = KanaConverter.toHiragana(formText, convertLongVowelMark = false)
    val lookupIds = lexicon.lookup(formHiragana) ?: lexicon.lookup(formText) ?: return
    if (lookupIds.isEmpty()) return

    try {
        val wordCache = getWordsWithCache(lookupIds.asList(), batchWordCache)
        val recoveredProcess = deconjugated
            .filter { d ->
                d.process.isNotEmpty() &&
                    (d.text.startsWith(formHiragana) || (extraProcessPrefix != null && d.text.startsWith(extraProcessPrefix)))
            }
            .minByOrNull { it.text.length }?.process?.filter { it.isNotEmpty() } ?: emptyList()
        // Imperatives are left out: godan 食べれ/起きれ is also ichidan ら抜き, and the deconjugator only reads the godan side.
        val verbClassTags = deconjugated
            .filter { d -> d.process.isNotEmpty() && d.text == formHiragana && "imperative" !in d.process }
            .mapNotNull { d -> PosMapper.getValidatableDeconjTags(d.tags.asList()).lastOrNull() }
            .filter { it[0] == 'v' }
        // Truncated godan forms read as ichidan (揺 of 揺られる, 帰ろ) and stretched vowels as volitional (やめろー), so neither is godan evidence.
        val godanEvidence = verbClassTags.isNotEmpty() && verbClassTags.all { isGodanClassTag(it) } && 'ー' !in wordInfo.text
        for (id in lookupIds.distinct()) {
            val word = wordCache[id] ?: continue
            // Godan evidence rules out an ichidan-only entry: 癒り is 治る, not ichidan 癒る (いる).
            if (godanEvidence && isIchidanOnlyVerb(word.partsOfSpeech)) continue

            if (wordInfo.partOfSpeech in word.cachedPOS) {
                val form = DeconjugationForm(formHiragana, wordInfo.text, ArrayList<String>(), HashSet<String>(), recoveredProcess)
                matches.add(word to form)
            }
        }
    } catch (_: Exception) {
    }
}

/** Public API, not used by parseText. */
fun JitenParser.getWordsDirectLookup(words: List<String>): List<DeckWord> {
    val (candidates, formFrequencies) = resolveDirectLookupCandidates(words)

    val matchedWords = ArrayList<DeckWord>()
    for (word in words) {
        val matches = candidates[word] ?: continue
        val best = pickBestDirectLookupMatch(matches, formFrequencies, readingHint = null) ?: continue
        matchedWords.add(buildDirectLookupWord(word, best))
    }

    return excludeFinalMisparses(matchedWords)
}

/**
 * Readings may list alternatives split by ; , 、 or /; any dictionary hit adds to surfacesInDictionary. Public API, not
 * used by parseText. Key = (word, reading ?: "").
 */
fun JitenParser.getWordsDirectLookupByReading(
    pairs: List<Pair<String, String?>>,
    surfacesInDictionary: MutableSet<String>? = null,
): Map<Pair<String, String>, DeckWord> {
    val (candidates, formFrequencies) = resolveDirectLookupCandidates(pairs.map { it.first })

    val result = HashMap<Pair<String, String>, DeckWord>()
    for ((word, reading) in pairs) {
        val key = word to (reading ?: "")
        if (key in result) continue
        val matches = candidates[word] ?: continue
        surfacesInDictionary?.add(word)

        val best = pickBestDirectLookupMatch(matches, formFrequencies, reading, requireReadingMatch = true) ?: continue
        if (WordForm(best.first.wordId, best.second and 0xFF) in JitenParser.ExcludedMisparses) continue

        result[key] = buildDirectLookupWord(word, best)
    }

    return result
}

/**
 * Returns (candidates: surface -> (word, readingIndex) list, formFrequencies). Jiten's WordFormFrequencies table
 * (per-form frequency ranks) isn't in the lexicon: the second map is (wordId, readingIndex) -> FrequencyRank and stays
 * empty, so PickBestDirectLookupMatch keeps list order.
 */
internal fun JitenParser.resolveDirectLookupCandidates(
    surfaces: Iterable<String>,
): Pair<Map<String, List<Pair<JmDictWord, Int>>>, Map<WordForm, Int>> {
    val candidates = HashMap<String, List<Pair<JmDictWord, Int>>>()

    for (word in surfaces) {
        if (word in candidates) continue

        val wordInHiragana = KanaConverter.toHiragana(word, convertLongVowelMark = false)
        val wordNormalized = KanaNormalizer.normalize(wordInHiragana)

        val matchesIds = lexicon.lookup(wordNormalized)
        if (matchesIds == null || matchesIds.isEmpty()) continue

        val wordCache = getWordsWithCache(matchesIds.asList(), null)
        if (wordCache.isEmpty()) continue

        val matchesWithReading = ArrayList<Pair<JmDictWord, Int>>()
        for (id in matchesIds) {
            val match = wordCache[id] ?: continue
            val matchedForm = match.forms.firstOrNull { it.text == word }
            if (matchedForm != null) matchesWithReading.add(match to matchedForm.readingIndex)
        }

        if (matchesWithReading.isEmpty()) continue

        candidates[word] = matchesWithReading
    }

    // ponytail: no WordFormFrequencies table in the lexicon; add per-form ranks there if direct lookups need them.
    return candidates to emptyMap()
}

private val ReadingHintSeparators = charArrayOf(';', '；', ',', '，', '、', '/', '／', '|')

internal fun JitenParser.pickBestDirectLookupMatch(
    matches: List<Pair<JmDictWord, Int>>,
    formFrequencies: Map<WordForm, Int>,
    readingHint: String?,
    requireReadingMatch: Boolean = false,
): Pair<JmDictWord, Int>? {
    if (matches.isEmpty()) return null

    fun freqRank(m: Pair<JmDictWord, Int>) = formFrequencies[WordForm(m.first.wordId, m.second)] ?: Int.MAX_VALUE

    if (!readingHint.isNullOrBlank()) {
        val hints = readingHint.split(*ReadingHintSeparators).map { it.trim() }.filter { it.isNotEmpty() }
        for (hint in hints) {
            val hintHira = KanaNormalizer.normalize(KanaConverter.toHiragana(hint, convertLongVowelMark = false))
            val byReading = matches.filter { wordHasKanaReading(it.first, hintHira) }
            if (byReading.isNotEmpty()) return byReading.minBy(::freqRank)
        }

        if (requireReadingMatch) return null
    }

    return matches.minBy(::freqRank)
}

@Suppress("UnusedReceiverParameter")
internal fun JitenParser.wordHasKanaReading(word: JmDictWord, hintHira: String): Boolean =
    word.forms.any {
        it.formType == JmDictFormType.KanaForm &&
            KanaNormalizer.normalize(KanaConverter.toHiragana(it.text, convertLongVowelMark = false)) == hintHira
    }

internal fun JitenParser.buildDirectLookupWord(word: String, best: Pair<JmDictWord, Int>): DeckWord =
    DeckWord(wordId = best.first.wordId, readingIndex = best.second and 0xFF, originalText = word,
        sudachiReading = getKatakanaReading(best.first, best.second and 0xFF))

/** WanaKana.ToKatakana(ConvertLongVowelMark = false): the Kotlin toKatakana never rewrites ー, so it matches. */
@Suppress("UnusedReceiverParameter")
internal fun JitenParser.getKatakanaReading(word: JmDictWord, readingIndex: Int): String {
    val kanaForm = word.forms.firstOrNull { it.formType == JmDictFormType.KanaForm && it.readingIndex == readingIndex }
    return if (kanaForm != null) WanaKana.toKatakana(kanaForm.text) else ""
}

/** 255 = no candidate. */
internal fun JitenParser.getBestReadingIndex(word: JmDictWord, originalText: String, sudachiReading: String? = null): Int {
    if (word.forms.isEmpty()) return 0

    val targetHiragana = KanaConverter.toHiragana(originalText, convertLongVowelMark = false)
    val candidates = FormCandidateFactory.enumerateCandidateForms(word, targetHiragana, allowLooseLvmMatch = true,
        surface = originalText)

    if (candidates.isEmpty()) return 255

    val (best, _) = pickBestFormCandidate(candidates.toMutableList(), originalText, dictionaryForm = null,
        normalizedForm = null, isNameContext = false, sudachiReading = sudachiReading)

    return best?.readingIndex ?: 255
}
