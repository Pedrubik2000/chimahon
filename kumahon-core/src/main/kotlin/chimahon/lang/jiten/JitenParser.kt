// Ported from Jiten (github.com/Sirush/Jiten, Apache-2.0, commit 44b48a4): Jiten.Parser/Parser.cs (lines 1-1238: state,
// initialisation, preprocessing, ParseText, word batches), Jiten.Parser/Data/FilteredHashSet.cs
package chimahon.lang.jiten

/** C#'s ParsedOccurrence: one resolved token in text order, before per-deck deduplication into DeckWords. */
data class ParsedOccurrence(val wordId: Int, val readingIndex: Int, val surface: String)

/** C#'s (DeckWord? word, int? margin, List<FormCandidate>? candidates) tuple. */
internal data class ProcessedWord(val word: DeckWord?, val margin: Int?, val candidates: MutableList<FormCandidate>?)

/** C#'s (WordInfo word, DeckWord? result, int? margin) tuple of the adjacent-scoring passes. */
internal data class SentencePair(val word: WordInfo, val result: DeckWord?, val margin: Int?)

/** C#'s 7-tuple dedup key (GetDedupKey). */
internal data class DedupKey(
    val text: String,
    val pos: PartOfSpeech,
    val dictionaryForm: String,
    val reading: String,
    val isPersonNameContext: Boolean,
    val isNameLike: Boolean,
    val preMatchedWordId: Int?,
)

internal enum class ProcessWordStatus { Resolved, FilteredOut, Unresolved }

/** C#'s ProcessWordResult; the DeckWordCache fields (CacheKey, CacheWord) are left out with the cache. */
internal class ProcessWordResult private constructor(
    val status: ProcessWordStatus,
    val word: DeckWord? = null,
    val margin: Int? = null,
    val firstPassCandidates: MutableList<FormCandidate>? = null,
) {
    companion object {
        fun fromResolved(word: DeckWord, margin: Int? = null, firstPassCandidates: MutableList<FormCandidate>? = null) =
            ProcessWordResult(ProcessWordStatus.Resolved, word, margin, firstPassCandidates)

        val FilteredOut = ProcessWordResult(ProcessWordStatus.FilteredOut)
        val Unresolved = ProcessWordResult(ProcessWordStatus.Unresolved)
    }
}

internal const val HASH_BASE = 131L

/** Rolling hash with katakana folded to hiragana; C#'s unchecked long arithmetic wraps the same way. */
internal fun hiraRollingHash(s: String): Long {
    var h = 0L
    for (ch in s) {
        val c = if (ch in 'ァ'..'ヶ') ch - 0x60 else ch
        h = h * HASH_BASE + c.code
    }
    return h
}

/**
 * Exact set of 64-bit hashes behind a 4 MB bit filter; most probes miss. C#'s HashSet<long> is a sorted LongArray here
 * (built once, ~8 bytes a key instead of a boxed entry).
 */
internal class FilteredHashSet(hashes: LongArray) {
    private val sorted: LongArray = hashes.copyOf().also { it.sort() }
    private val filter = LongArray((1 shl FilterBits) / 64)

    init {
        for (h in sorted) {
            val slot = slot(h)
            filter[(slot ushr 6).toInt()] = filter[(slot ushr 6).toInt()] or (1L shl (slot and 63).toInt())
        }
    }

    fun contains(hash: Long): Boolean {
        val slot = slot(hash)
        return (filter[(slot ushr 6).toInt()] and (1L shl (slot and 63).toInt())) != 0L &&
            java.util.Arrays.binarySearch(sorted, hash) >= 0
    }

    private companion object {
        const val FilterBits = 25
        fun slot(hash: Long): Long = (hash * -0x61c8864680b583ebL) ushr (64 - FilterBits) // 0x9E3779B97F4A7C15
    }
}

/**
 * Jiten's Parser (a C# static class) as an instance: its static state lives here as internal fields, its functions are
 * members here (Parser.cs lines 1-1238) or extension functions in ParserProcessWord.kt, ParserCompounds.kt,
 * ParserExpressions.kt and ParserScoring.kt, by C# line range.
 *
 * Parser.cs's maps are [lexicon]: `_lookups` = lexicon.lookup(key) (C#'s span-keyed `_lookupsAlt` too: pass a
 * substring), `WordMeta` = lexicon.wordMeta/priorityScore/hasPos/primaryPos, `_wordFrequencyRanks` =
 * lexicon.wordFrequencyRank, `_nameOnlyWordIds` = lexicon.isNameOnly, `_expressionWordIds` = lexicon.isExpression,
 * `_kanjiBackedWordIds` = lexicon.isKanjiBacked, `JmDictCache.GetWordsAsync` = [getWordsWithCache] / [getWords].
 * Parser.cs's lookup helpers HasLookupWhere, KanaFallback, LookupWithKanaFallback, AnyWithPos, AnyNonNameWithPos and
 * the ten analyser callbacks (HasLookupForCompound, HasNonNameLookup, HasPrioritizedNonNameLookup,
 * HasKanaAppropriateLookup, HasSuruVerbLookup, GetNonNameCompoundId, GetBestNonNameFrequencyRank,
 * HasVerbOrAdjectiveLookup, HasExpressionLookup, HasCounterSenseAvailable) plus IsKanaAppropriateId are lexicon
 * methods: call them there, don't redefine them.
 *
 * Left out: Postgres/Redis (the DeckWordCache: every word is resolved fresh), async and the 100-slot semaphore (all
 * synchronous), warmup, diagnostics and timings, decks (ParseTextToDeck/ParseTextsToDeck, statistics, example
 * sentences), ParseMorphenes/GetMorphemesBatch. Not thread-safe (one parse at a time, like the analyser).
 */
class JitenParser(val lexicon: JitenLexicon, val analyser: MorphologicalAnalyser) {
    internal val deconjugator: Deconjugator get() = Deconjugator.instance

    // _compoundHashSet, _maxLookupKeyLength, _hashBasePowers (BuildCompoundHashSet)
    internal val compoundHashSet: FilteredHashSet
    internal val maxLookupKeyLength: Int
    internal val hashBasePowers: LongArray

    /** C#'s [ThreadStatic] _compoundScratchSet (CombineCompounds); insertion-ordered, the length sort relies on it. */
    internal val compoundScratchSet = LinkedHashSet<String>()

    /** JmDictCache stand-in: words built from the lexicon once, PriorityOverrides applied at insert. */
    private val jmDictCache = HashMap<Int, JmDictWord>()

    init {
        // EnsureInitializedAsync: Parser.cs gives each MorphologicalAnalyser its lookup callbacks
        analyser.hasCompoundLookup = lexicon::hasLookupForCompound
        analyser.hasNonNameCompoundLookup = lexicon::hasNonNameLookup
        analyser.hasPrioritizedNonNameCompoundLookup = lexicon::hasPrioritizedNonNameLookup
        analyser.hasKanaAppropriateCompoundLookup = lexicon::hasKanaAppropriateLookup
        analyser.hasSuruVerbCompoundLookup = lexicon::hasSuruVerbLookup
        analyser.getNonNameCompoundWordId = lexicon::getNonNameCompoundId
        analyser.getNonNameCompoundFrequencyRank = lexicon::getBestNonNameFrequencyRank
        analyser.hasVerbOrAdjectiveLookup = lexicon::hasVerbOrAdjectiveLookup
        analyser.hasExpressionLookup = lexicon::hasExpressionLookup
        analyser.hasCounterSenseLookup = lexicon::hasCounterSenseAvailable

        // BuildCompoundHashSet
        val hashes = LongArray(lexicon.keyCount)
        var maxLen = 0
        for (i in 0 until lexicon.keyCount) {
            val key = lexicon.keyAt(i)
            hashes[i] = hiraRollingHash(key)
            if (key.length > maxLen) maxLen = key.length
        }
        compoundHashSet = FilteredHashSet(hashes)
        maxLookupKeyLength = maxLen
        hashBasePowers = LongArray(maxLen + 1)
        hashBasePowers[0] = 1
        for (i in 1..maxLen) hashBasePowers[i] = hashBasePowers[i - 1] * HASH_BASE
    }

    // ------------------------------------------------------------------ preprocessing

    internal fun preprocessSentences(sentences: List<SentenceInfo>, protectedSurfaces: Set<String>? = null) {
        fun dbgDump(label: String) {
            if (StageDebug) println("[prep] $label: " + sentences.flatMap { it.words }.joinToString("|") { it.word.text })
        }

        cleanSentenceTokens(sentences)
        dbgDump("CleanSentenceTokens")
        splitSuruInflectionsForNounCompounding(sentences)
        splitUnknownNounTokens(sentences, protectedSurfaces)
        dbgDump("SplitUnknownNounTokens")

        combineNounCompounds(sentences)
        dbgDump("CombineNounCompounds")

        reclassifyLeftoverPrefixChars(sentences)
        dbgDump("ReclassifyLeftoverPrefixChars")

        combineCompounds(sentences)
        dbgDump("CombineCompounds")

        combineExpressions(sentences)
        dbgDump("CombineExpressions")

        combineLexicalAdverbs(sentences)
        dbgDump("CombineLexicalAdverbs")

        repairLongVowels(sentences)
        dbgDump("RepairLongVowels")

        filterOrphanedMisparses(sentences)
        validateGrammaticalSequences(sentences)
        stripTrailingParticles(sentences)

        pinCompoundVerbTails(sentences)

        ResegmentationEngine.tryImproveUncertainSpans(sentences, lexicon, protectedSurfaces)

        markPersonNameHonorificContexts(sentences)
    }

    internal fun isClassicalSentence(sentenceWords: List<SentencePair>): Boolean =
        sentenceWords.any { it.word.text in ClassicalMarkerSurfaces }

    private fun isPersonNameNoun(w: WordInfo): Boolean =
        PosMapper.isNameLikeSudachiNoun(w.partOfSpeech, w.partOfSpeechSection1, w.partOfSpeechSection2, w.partOfSpeechSection3) &&
            (w.hasPartOfSpeechSection(PartOfSpeechSection.PersonName) ||
                w.hasPartOfSpeechSection(PartOfSpeechSection.FamilyName) ||
                w.hasPartOfSpeechSection(PartOfSpeechSection.Name))

    internal fun markPersonNameHonorificContexts(sentences: List<SentenceInfo>) {
        for (sentence in sentences) {
            if (sentence.words.size < 2) continue
            for (i in 0 until sentence.words.size - 1) {
                val current = sentence.words[i].word
                val next = sentence.words[i + 1].word

                if (next.text !in PersonHonorifics) continue
                if (current.text in HonorificExclusions) continue

                // Katakana noun + person honorific is a foreign name, which Sudachi doesn't tag as a proper noun.
                if (current.partOfSpeech == PartOfSpeech.Noun && WanaKana.isKatakana(current.text)) {
                    current.isPersonNameContext = true
                    continue
                }

                if (!isPersonNameNoun(current)) continue
                current.isPersonNameContext = true
            }
        }
    }

    /** C#'s PropagatePersonNameContexts(List<List<SentenceInfo>>) overload (a JVM signature clash otherwise). */
    internal fun propagatePersonNameContextsAll(allTexts: List<List<SentenceInfo>>) {
        val confirmedNames = HashSet<String>()
        for (sentences in allTexts) for (sentence in sentences) for (sw in sentence.words)
            if (sw.word.isPersonNameContext) confirmedNames.add(sw.word.text)

        if (confirmedNames.isEmpty()) return

        for (sentences in allTexts) for (sentence in sentences) for (sw in sentence.words) {
            val word = sw.word
            if (word.isPersonNameContext) continue
            if (word.text !in confirmedNames) continue
            if (!isPersonNameNoun(word)) continue
            word.isPersonNameContext = true
        }
    }

    internal fun propagatePersonNameContexts(sentences: List<SentenceInfo>) = propagatePersonNameContextsAll(listOf(sentences))

    internal fun isNameSplitCandidate(w: WordInfo): Boolean =
        (PosMapper.isNounForCompounding(w.partOfSpeech) || w.partOfSpeech == PartOfSpeech.Counter ||
            // 乃/之 in names (希里乃, 竜之介) come back as the archaic genitive particle
            (w.partOfSpeech == PartOfSpeech.Particle && (w.text == "乃" || w.text == "之"))) &&
            w.text.isNotEmpty() &&
            (w.text.all(JapaneseTextHelper::isKanji) || WanaKana.isKatakana(w.text))

    /** An honorific-confirmed name (七海さん) re-joins its splits (七|海) document-wide; else counters win (二|条). */
    internal fun mergeConfirmedNameSplits(allTexts: List<List<SentenceInfo>>) {
        val confirmed = HashSet<String>()

        for (sentences in allTexts) for (sentence in sentences) {
            val words = sentence.words
            for (i in words.indices) {
                if (words[i].word.isPersonNameContext && words[i].word.text.length >= 2) confirmed.add(words[i].word.text)

                if (i + 2 >= words.size || words[i + 2].word.text !in PersonHonorifics) continue

                val a = words[i].word
                val b = words[i + 1].word
                if (!isNameSplitCandidate(a) || !isNameSplitCandidate(b)) continue

                val joined = a.text + b.text
                if (joined.length > 6) continue

                if (lexicon.lookup(joined)?.any(lexicon::isNameOnly) == true) confirmed.add(joined)
            }
        }

        if (confirmed.isEmpty()) return

        for (sentences in allTexts) for (sentence in sentences) {
            val words = sentence.words
            var i = 0
            while (i < words.size - 1) {
                val (a, posA, lenA) = words[i]
                val (b, _, lenB) = words[i + 1]
                if (isNameSplitCandidate(a) && isNameSplitCandidate(b)) {
                    val joined = a.text + b.text
                    if (joined in confirmed) {
                        val mergedWord = a.copy().apply {
                            text = joined
                            dictionaryForm = joined
                            normalizedForm = joined
                            partOfSpeech = PartOfSpeech.Noun
                            reading = a.reading + b.reading
                            endOffset = b.endOffset
                            isPersonNameContext = true
                        }
                        words[i] = SentenceWord(mergedWord, posA, lenA + lenB)
                        words.removeAt(i + 1)
                    }
                }
                i++
            }
        }
    }

    /** Splits Xして only when X completes a noun compound with preceding nouns (物々 + 交換して → 物々交換 + して). */
    internal fun splitSuruInflectionsForNounCompounding(sentences: List<SentenceInfo>) {
        fun getSuruSplitSuffixPrefix(surface: String): String? = when {
            // Kept small to avoid standalone auxiliary chains; prefix match also covers trailing auxiliaries (交換しておかない).
            surface.startsWith("して") -> "して"
            surface.startsWith("した") -> "した"
            surface.startsWith("し") -> "し"
            // Passive/causative chains (指名手配されている, 強制させる) recover their compound the same way.
            surface.startsWith("され") -> "され"
            surface.startsWith("させ") -> "させ"
            else -> null
        }

        fun hasLookup(key: String): Boolean {
            if (lexicon.lookup(key)?.isNotEmpty() == true) return true
            val hiraganaKey = KanaConverter.toHiragana(key, convertLongVowelMark = false)
            return hiraganaKey != key && lexicon.lookup(hiraganaKey)?.isNotEmpty() == true
        }

        for (sentence in sentences) {
            if (sentence.words.size < 2) continue

            var i = 1
            while (i < sentence.words.size) {
                val (word, position, _) = sentence.words[i]
                val dict = word.dictionaryForm
                val baseNoun = when {
                    dict.isEmpty() -> null
                    dict.endsWith("する") && dict.length > 2 -> dict.dropLast(2)
                    // CombineVerbDependantsSuru keeps the noun stem as DictionaryForm (交換してる → 交換).
                    word.hasPartOfSpeechSection(PartOfSpeechSection.PossibleSuru) -> dict
                    else -> null
                }
                if (baseNoun.isNullOrEmpty() || !word.text.startsWith(baseNoun)) { i++; continue }

                val suffixSurface = word.text.substring(baseNoun.length)
                if (getSuruSplitSuffixPrefix(suffixSurface) == null) { i++; continue }

                // Longest window first: up to 4 preceding nouns + baseNoun.
                var bestStart = -1
                for (windowSize in minOf(5, i + 1) downTo 2) {
                    val nounCount = windowSize - 1
                    val start = i - nounCount
                    if (start < 0) continue
                    if ((start until i).any { !PosMapper.isNounForCompounding(sentence.words[it].word.partOfSpeech) }) continue
                    val combined = concatTokenTexts(sentence.words, start, nounCount) + baseNoun
                    if (hasLookup(combined)) {
                        bestStart = start
                        break
                    }
                }
                if (bestStart == -1) { i++; continue }

                // Sudachi readings aren't preserved: merged tokens carry only the accumulator reading.
                val nounWord = word.copy().apply {
                    text = baseNoun
                    partOfSpeech = PartOfSpeech.Noun
                    dictionaryForm = baseNoun
                    normalizedForm = if (word.normalizedForm.isEmpty()) baseNoun else word.normalizedForm
                    reading = KanaConverter.toHiragana(baseNoun, convertLongVowelMark = false)
                }
                val suruWord = WordInfo().apply {
                    // The auxiliary chain (しておかない) stays one token.
                    text = suffixSurface
                    partOfSpeech = PartOfSpeech.Verb
                    dictionaryForm = "する"
                    normalizedForm = "する"
                    reading = KanaConverter.toHiragana(suffixSurface, convertLongVowelMark = false)
                }

                val baseLen = nounWord.text.length
                sentence.words[i] = SentenceWord(nounWord, position, baseLen)
                sentence.words.add(i + 1, SentenceWord(suruWord, position + baseLen, suruWord.text.length))
                i += 2
            }
        }
    }

    internal fun extractWordInfos(sentences: List<SentenceInfo>): MutableList<WordInfo> =
        sentences.flatMap { it.words }.filter { it.word.partOfSpeech != PartOfSpeech.SupplementarySymbol }
            .mapTo(ArrayList()) { it.word }

    /** Occurrences aren't counted here: ProcessSentencesToDeck recounts them and other callers discard them. */
    internal fun collectUniqueWordInfos(wordInfos: List<WordInfo>): MutableList<WordInfo> {
        val seen = HashSet<DedupKey>()
        return wordInfos.filterTo(ArrayList()) { seen.add(getDedupKey(it)) }
    }

    // ------------------------------------------------------------------ word resolution

    /**
     * ProcessWordsInBatches, synchronous. [nameEntrySurfaces] stands in for dictionaryEntriesBySurface (the deck's
     * dictionary entries, used only by ParseTextsToDeck): the surfaces of its Name entries; other entry types never
     * override.
     */
    internal fun processWordsInBatches(
        words: List<WordInfo>,
        deconjugator: Deconjugator = this.deconjugator,
        batchSize: Int = 1000,
        nameEntrySurfaces: Set<String>? = null,
    ): MutableList<ProcessedWord> {
        val allProcessedWords = ArrayList<ProcessedWord>(words.size)

        var i = 0
        while (i < words.size) {
            val batch = words.subList(i, minOf(i + batchSize, words.size))
            val batchWordCache = HashMap<Int, JmDictWord>()

            val batchResults = batch.map { processWord(it, deconjugator, batchWordCache) }

            for (j in batch.indices) {
                val result = batchResults[j]
                val surface = batch[j].text

                if (nameEntrySurfaces != null && surface in nameEntrySurfaces) {
                    val shouldOverride = !isRiskyNameEntry(surface) || batch[j].isPersonNameContext
                    val overrideWord = if (shouldOverride) resolveAsNameEntry(surface, batchWordCache) else null
                    if (overrideWord != null) {
                        allProcessedWords.add(ProcessedWord(overrideWord, Int.MAX_VALUE, null))
                        continue
                    }
                }

                // An all-kanji-numeral surface is a number, never a JMnedict name (二千, 四万); unlike NumeralKanji, 万 counts.
                val w = result.word
                if (w != null && surface.length >= 2 && surface.all { it in NumeralKanji || it == '万' || it == '億' || it == '兆' }) {
                    val meta = lexicon.wordMeta(w.wordId)
                    if (meta != null && meta.pos.isNotEmpty() &&
                        meta.pos.all { it == PartOfSpeech.Name || it == PartOfSpeech.Unknown }) {
                        allProcessedWords.add(ProcessedWord(null, null, null))
                        continue
                    }
                }

                allProcessedWords.add(ProcessedWord(result.word, result.margin, result.firstPassCandidates))
            }
            i += batchSize
        }
        return allProcessedWords
    }

    internal fun resolveAsNameEntry(surface: String, batchWordCache: MutableMap<Int, JmDictWord>?): DeckWord? {
        val candidates = lexicon.lookup(surface)
        if (candidates == null || candidates.isEmpty()) return null

        val wordCache = getWordsWithCache(candidates.asList(), batchWordCache)

        // Lookups fold hiragana and katakana (ひなた hits ヒナタ-only names), so an exact spelling beats lookup order.
        var fallback: DeckWord? = null
        for (id in candidates) {
            val word = wordCache[id] ?: continue
            if (!word.cachedPOS.all { it == PartOfSpeech.Name || it == PartOfSpeech.Unknown }) continue

            var readingIndex = getBestReadingIndex(word, surface)
            if (readingIndex == 255) readingIndex = 0

            val deckWord = DeckWord(wordId = word.wordId, originalText = surface, readingIndex = readingIndex,
                partsOfSpeech = word.cachedPOS.toMutableList(), origin = word.origin)
            if (word.forms.any { it.readingIndex == readingIndex && it.text == surface }) return deckWord

            if (fallback == null) fallback = deckWord
        }
        return fallback
    }

    /** JMdict entries with their forms, served from the parser's cache (C#'s public GetWordsAsync). */
    fun getWords(wordIds: Iterable<Int>): Map<Int, JmDictWord> = getWordsWithCache(wordIds, null)

    /** C#'s ParseText: the words of [text] in order (DeckWord.originalText is the surface). */
    fun parseText(text: String, preserveStopToken: Boolean = false): List<DeckWord> {
        val (cleanText, furiganaHints) = FuriganaHintExtractor.extract(text)

        analyser.interactive = true
        val (sentences, cleanedOriginal) = analyser.parseWithCleanedOriginal(cleanText, preserveStopToken = preserveStopToken)

        // ComputeTokenOffsets strips \r\n, so relocate in the same coordinate space.
        val cleanedOriginalFlat = cleanedOriginal.replace("\r", "").replace("\n", "")
        val relocatedHints = if (furiganaHints.isNotEmpty() && cleanedOriginalFlat.isNotEmpty())
            FuriganaHintExtractor.relocateToCleanedOriginal(cleanedOriginalFlat, furiganaHints, cleanText) else null

        preprocessSentences(sentences)
        mergeConfirmedNameSplits(listOf(sentences))
        propagatePersonNameContexts(sentences)
        var wordInfos = extractWordInfos(sentences)
        var processedWithMargins: List<ProcessedWord> = processWordsInBatches(wordInfos, deconjugator)

        val marginMap = buildMarginMap(sentences, processedWithMargins)
        val candidateLookup = HashMap<DedupKey, MutableList<FormCandidate>>()

        if (ResegmentationEngine.tryResegmentLowConfidenceTokens(sentences, lexicon, marginMap)) {
            val oldResultLookup = HashMap<DedupKey, Pair<DeckWord?, Int?>>()
            for (i in wordInfos.indices) {
                val key = getDedupKey(wordInfos[i])
                oldResultLookup.putIfAbsent(key, processedWithMargins[i].word to processedWithMargins[i].margin)
                processedWithMargins[i].candidates?.let { candidateLookup.putIfAbsent(key, it) }
            }

            wordInfos = extractWordInfos(sentences)

            val newTokens = wordInfos.filter { getDedupKey(it) !in oldResultLookup }
            if (newTokens.isNotEmpty()) {
                val newResults = processWordsInBatches(newTokens, deconjugator)
                for (i in newTokens.indices) {
                    val key = getDedupKey(newTokens[i])
                    oldResultLookup.putIfAbsent(key, newResults[i].word to newResults[i].margin)
                    newResults[i].candidates?.let { candidateLookup.putIfAbsent(key, it) }
                }
            }

            processedWithMargins = wordInfos.map { wi ->
                val key = getDedupKey(wi)
                val r = oldResultLookup[key]
                if (r != null) ProcessedWord(r.first, r.second, candidateLookup[key]) else ProcessedWord(null, null, null)
            }
        } else {
            for (i in wordInfos.indices)
                processedWithMargins[i].candidates?.let { candidateLookup.putIfAbsent(getDedupKey(wordInfos[i]), it) }
        }

        var corrected = applyAdjacentScoring(sentences, processedWithMargins, candidateLookup, relocatedHints)
        corrected = applyMisparseGates(sentences, corrected)
        return excludeFinalMisparses(corrected)
    }

    /** Line break positions counted in the text with line breaks removed, which is what word offsets index. */
    internal fun flatLineBreakOffsets(text: String): List<Int> {
        val offsets = ArrayList<Int>()
        var flatPos = 0
        for (c in text) {
            if (c == '\n') {
                if (flatPos > 0 && (offsets.isEmpty() || offsets.last() != flatPos)) offsets.add(flatPos)
            } else if (c != '\r') {
                flatPos++
            }
        }
        return offsets
    }

    /** C#'s GetWordsWithCache; JmDictCache.GetWordsAsync = [lexicon].getWord behind [jmDictCache]. */
    internal fun getWordsWithCache(wordIds: Iterable<Int>, batchCache: MutableMap<Int, JmDictWord>?): Map<Int, JmDictWord> {
        if (batchCache == null) return jmDictCacheGetWords(wordIds)

        val distinct = wordIds.distinct()
        val result = HashMap<Int, JmDictWord>(distinct.size)
        val needed = ArrayList<Int>()
        for (id in distinct) {
            val cached = batchCache[id]
            if (cached != null) result[id] = cached else needed.add(id)
        }
        if (needed.isNotEmpty()) {
            for ((id, word) in jmDictCacheGetWords(needed)) {
                result[id] = word
                batchCache.putIfAbsent(id, word)
            }
        }
        return result
    }

    /** JmDictCache.GetWordsAsync: the words that exist; PriorityOverrides are applied once at insert. */
    private fun jmDictCacheGetWords(wordIds: Iterable<Int>): Map<Int, JmDictWord> {
        val result = HashMap<Int, JmDictWord>()
        for (id in wordIds) {
            val word = jmDictCache[id] ?: lexicon.getWord(id)?.also {
                PriorityOverrides.apply(it)
                jmDictCache[id] = it
            } ?: continue
            result[id] = word
        }
        return result
    }

    /** First-seen order; [first] must already be duplicate-free (CollectIds output is). */
    internal fun appendDistinct(first: List<Int>, second: List<Int>): MutableList<Int> {
        val seen = HashSet(first)
        val merged = ArrayList<Int>(first.size + second.size)
        merged.addAll(first)
        for (id in second) if (seen.add(id)) merged.add(id)
        return merged
    }

    // ------------------------------------------------------------------ hash pre-filter (C# lines 4293-4318)

    /** Hash fold (c - 0x60) matches ToHiragana here, so a hash miss proves a lookup miss; ヶ/ゎ go slow. */
    internal fun isHashFoldSafeChar(c: Char): Boolean =
        (c in 'ぁ'..'ゔ' && c != 'ゎ') || (c in 'ァ'..'ヴ' && c != 'ヮ') || c == 'ー' || c == '々' ||
            c in '一'..'鿿' // CJK unified ideographs

    internal fun isHashFoldSafe(s: String): Boolean = s.all(::isHashFoldSafeChar)

    /** False only when the hash proves no lookup key equals prefix + tail; unsafe chars always pass. */
    internal fun prefixTailMayMatch(prefixSafe: Boolean, prefixLenOk: Boolean, prefixHash: Long, prefixLen: Int, tail: String): Boolean {
        if (!prefixSafe || !isHashFoldSafe(tail)) return true
        return prefixLenOk && tail.length <= maxLookupKeyLength && prefixLen + tail.length <= maxLookupKeyLength &&
            compoundHashSet.contains(prefixHash * hashBasePowers[tail.length] + hiraRollingHash(tail))
    }

    internal companion object {
        private val StageDebug: Boolean = !System.getenv("JITEN_STAGE_DEBUG").isNullOrEmpty()

        val TokenCleanRegex = Regex("[^a-zA-Z0-9\\u3040-\\u309F\\u30A0-\\u30FF\\u4E00-\\u9FAF\\uFF21-\\uFF3A\\uFF41-\\uFF5A\\uFF10-\\uFF19\\u3005．]")
        val SmallTsuLongVowelRegex = Regex("ッー")
        val MultiLongVowelRegex = Regex("ー{2,}")
        val DialogueRegex = Regex("[「『].{0,200}?[」』]", RegexOption.DOT_MATCHES_ALL)

        // Opening quotes/brackets start a fresh utterance; the next content token counts as sentence-initial.
        val OpeningQuoteChars: Set<Char> = hashSetOf('「', '『', '（', '〈', '《', '【', '〔', '｢', '(', '“', '‘', '"', '\'')

        // Removed after repair stages, not in MorphologicalAnalyser: RepairLongVowelMisparses needs them for backward merges.
        val MisparsesRemove: Set<String> = hashSetOf(
            "そ", "る", "ま", "ふ", "ち", "ほ", "す", "じ", "なさ", "い", "ぴ", "ふあ", "ぷ", "ちゅ", "にっ", "じら", "タ", "け", "イ", "イッ", "ほっ", "そっ",
            "ウー", "うー", "ううう", "うう", "ウウウウ", "ウウ", "ううっ", "かー", "ぐわー", "違", "タ", "ッ", "ニヒヒ",
        )

        // Narrower than IsNumeralChar: runs containing 万/億/兆 must still shatter so those survive as standalone words.
        const val NumeralKanji = "一二三四五六七八九十百千〇零"

        val ExcludedMisparses: Set<WordForm> = listOf(
            1291070 to 1, 1587980 to 1, 1443970 to 5, 2029660 to 0, 1177490 to 5, 2029000 to 1,
            1244950 to 1, 1243940 to 1, 2747970 to 1, 2029680 to 0, 1193570 to 6, 1796500 to 2,
            1811220 to 1, 2654270 to 0, 2269410 to 1, 2439040 to 3, 2861095 to 0, 2836250 to 0,
            1595910 to 4, 2577750 to 0, 1365520 to 1, 1310720 to 1, 1528180 to 1, 2866457 to 1,
            2394370 to 4, 1203250 to 2, 1537250 to 2, 2783750 to 1, 2654250 to 0, 2609820 to 1,
            1333240 to 2, 2035220 to 2, 5616612 to 5, 2249020 to 1, 2783700 to 1,
            2411420 to 0, 1604890 to 2, 2602280 to 1, 1407450 to 1, 1595120 to 1, 2083370 to 1,
            2862482 to 0, 2849996 to 0, 1266970 to 2, 2574180 to 2, 2574180 to 1, 1550770 to 1,
            5626489 to 28, 5045509 to 3, 2029780 to 0, 5430309 to 1, 1496170 to 2, 2564800 to 1,
            2026870 to 1, 1585310 to 4, 1585310 to 5, 2252690 to 1, 2835861 to 0, 1223130 to 1,
            1246880 to 1, 1246880 to 2, 1461140 to 8, 1461140 to 6, 2029700 to 0, 2594040 to 2,
            1324950 to 1, 1949190 to 1, 1344210 to 1, 2029730 to 0, 5612068 to 1, 1370270 to 3, 1581200 to 2, 1332670 to 2,
            1150090 to 1, 1533340 to 3, 5050910 to 0, 2406530 to 0, 1243650 to 2, 1158500 to 1, 2759530 to 0,
            1689970 to 3, 5257597 to 0, 1446210 to 2, 2416380 to 1, 1244220 to 1,
            1578010 to 2, 2028930 to 1, 2028930 to 2, 1579350 to 6, 2821500 to 2, 2821500 to 3,
            1592150 to 2, 1467040 to 6, 1311350 to 1, 1175280 to 1, 1578150 to 4, 2029650 to 0,
            1154770 to 1, 1401940 to 2, 2264280 to 0, 2264280 to 1, 1323350 to 2, 1950890 to 2,
            1429010 to 1, 2871946 to 0, 2871946 to 1, 2872080 to 0, 2872080 to 1,
            1583240 to 5, 2619520 to 0, 1319210 to 8,
            // Rare kana forms that surface only as fragments: うほー (禹歩), はむっ (鱧), や (矢), お (尾), ハハッ, だもの (駄物), てし (手四)
            2545990 to 1, 1575020 to 2, 1537760 to 2, 1485770 to 1, 2029740 to 0, 1753980 to 1, 2847163 to 1,
        ).mapTo(HashSet()) { WordForm(it.first, it.second) }

        val ArchaicPosTypes: Set<String> = hashSetOf(
            "v2a-s", "v2b-k", "v2b-s", "v2d-k", "v2d-s", "v2g-k", "v2g-s",
            "v2h-k", "v2h-s", "v2k-k", "v2k-s", "v2m-k", "v2m-s", "v2n-s",
            "v2r-k", "v2r-s", "v2s-s", "v2t-k", "v2t-s", "v2w-s", "v2y-k",
            "v2y-s", "v2z-s",
            "v4b", "v4g", "v4h", "v4k", "v4m", "v4n", "v4r", "v4s", "v4t",
            "adj-kari", "adj-ku", "adj-shiku",
        )

        // Sentence-level archaic context markers; ぬ and べき are excluded as too common in modern formal prose.
        val ClassicalMarkerSurfaces: Set<String> = hashSetOf(
            "けり", "けれ", // classical past/retrospective auxiliary
            "べし", "べく", // classical necessity/conjecture
            "ごとし", "ごとく", "ごとき", // classical similarity auxiliary
            "まほし", "まほしく", // classical desiderative
            "たまふ", "たまへ", "たまひ", // classical honorific verb (v4h)
            "汝", "なんじ", // archaic pronoun (literary/fantasy)
        )

        val PersonHonorifics: Set<String> =
            TransitionRuleSets.HonorificSuffixes + setOf("たん", "先生", "課長", "部長", "姉さん", "兄さん")

        // Curated: most particles over-merge (とは, には); しか only ever heads しかない/しかねぇ.
        val ParticleExpressionOpeners: Set<String> = hashSetOf("しか")

        // Curated, not entry-derived: many X+に pairs are genuine case particles (それに気づいた, 外に出る).
        val LexicalizedAdverbPairs: Set<String> = hashSetOf("フルに", "無性に", "意地でも", "絶対に", "徐々に")

        // 雪の下: the botanical ユキノシタ must not swallow prose 雪+の+下 "under the snow".
        val NounCompoundExclusions: Set<String> = hashSetOf("おつもり", "ものたち", "雪の下")

        val ExpressionExclusions: Set<String> = hashSetOf(
            "このように", "そのように", "あのように", "どのように", "のように",
            "この様に", "その様に", "あの様に", "どの様に", "の様に",
            "何だと", "なんだと",
            // Their JMDict interjection/discourse senses rarely apply.
            "それが", "それは", "これが", "これは", "あれが", "あれは",
            // The temper idiom is rare; fiction means literal insect-killing (小の虫を殺す).
            "虫を殺す", "むしをころす", "虫を殺し", "むしをころし",
            // こと is almost always the nominalizer (関与することを好まない).
            "ことを好む", "事を好む", "ことをこのむ",
            // The idiom is rare; prose means "know that man" (この男を知っているか).
            "男を知る", "男を知って", "男を知った", "男を知ってる", "男を知っている",
            "男を知らない", "男を知り",
        )

        /** Curated: JMDict also lists compositional pairs (のです, それを, 外に) that must stay split. */
        val TwoTokenExpressionWhitelist: Set<String> = hashSetOf(
            "どちらも", "どっちも", "いつでも", "何時でも", "一人で", "ひとりで", "独りで",
            "ものか", "もんか", "たまるか", "堪るか", "それとも", "至るまで", "いたるまで",
            "史上初", "綺麗さっぱり", "きれいさっぱり", "とはいえ", "とは言え", "あいよ", "いいや",
        )

        /** Idioms with elided を; curated because compositional 〜を〜 entries (服を着る) must stay split. */
        val TwoTokenNounVerbIdioms: Map<Pair<String, String>, String> = mapOf(
            ("カマ" to "かける") to "カマをかける",
            ("かま" to "かける") to "かまをかける",
            ("鎌" to "かける") to "鎌をかける",
            ("鎌" to "掛ける") to "鎌を掛ける",
        )

        // BuildExpressionExclusionHashes, BuildTwoTokenWhitelistHashes
        val ExpressionExclusionHashes: Set<Long> = ExpressionExclusions.mapTo(HashSet(), ::hiraRollingHash)
        val TwoTokenExpressionWhitelistHashes: Set<Long> = TwoTokenExpressionWhitelist.mapTo(HashSet(), ::hiraRollingHash)

        val HonorificExclusions: Set<String> = hashSetOf(
            "うさぎ", "ウサギ", "兎",
            "くま", "クマ", "熊",
            "たぬき", "タヌキ", "狸",
            "きつね", "キツネ", "狐",
            "さる", "サル", "猿",
            "つる", "ツル", "鶴",
            "かめ", "カメ", "亀",
            "ねずみ", "ネズミ", "鼠",
        )
    }
}
