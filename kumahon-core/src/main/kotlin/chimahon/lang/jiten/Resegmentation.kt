// Ported from Jiten (github.com/Sirush/Jiten, Apache-2.0, commit 44b48a4): Jiten.Parser/Resegmentation/ResegmentationEngine.cs,
// ResegmentationModels.cs, ResegmentationScorer.cs, UncertaintyDetector.cs
//
// C#'s lookups / frequencyRanks / wordMeta parameters are all [JitenLexicon] (lexicon.lookup, wordFrequencyRank,
// wordMeta/hasPos/primaryPos/isTrueName); where C# lets frequencyRanks or wordMeta be null (FindBestPath, FindSpans,
// ScorePosTransitions) they stay separate nullable JitenLexicon parameters. marginMap keys are Pair(sentenceIndex,
// wordIndex); its margins are ProcessWord's form-candidate margins (not Sudachi boundary margins). Diagnostics and the
// JITEN_RESEG_DEBUG trace are left out. Only Parser.cs calls in (the two entry points below).
package chimahon.lang.jiten

// ResegmentationModels.cs

internal data class SpanTokenCandidate(val startChar: Int, val length: Int, val wordIds: List<Int>)

internal data class SpanPath(val segments: List<SpanTokenCandidate>) {
    fun isComplete(spanLength: Int): Boolean =
        segments.isNotEmpty() && segments.last().startChar + segments.last().length == spanLength
}

internal class UncertainSpan(
    val wordIndex: Int = 0,
    val text: String = "",
    val position: Int = 0,
    val length: Int = 0,
    /** Span resolves only to JMnedict names (ひとつだけ → 一ッ岳); only a multi-segment path (一つ+だけ) counts. */
    val nameOnly: Boolean = false,
)

// ResegmentationEngine.cs

internal object ResegmentationEngine {
    private const val MinAcceptScore = 25
    private const val MinAcceptScoreConfidence = 50

    private class Pending(
        val sentence: SentenceInfo,
        val span: UncertainSpan,
        val path: SpanPath,
        val prevPos: PartOfSpeech?,
        val nextPos: PartOfSpeech?,
    )

    fun tryImproveUncertainSpans(sentences: List<SentenceInfo>, lexicon: JitenLexicon, protectedSurfaces: Set<String>? = null) {
        val pending = ArrayList<Pending>()

        for (sentence in sentences) {
            val spans = UncertaintyDetector.findSpans(sentence, lexicon, lexicon, protectedSurfaces)
            if (spans.isEmpty()) continue

            for (span in spans.sortedByDescending { it.wordIndex }) {
                val word = sentence.words[span.wordIndex].word
                val isCompoundNumeral = word.partOfSpeechSection1 == PartOfSpeechSection.Numeral && word.text.length > 1

                val path: SpanPath
                if (isCompoundNumeral) {
                    path = trySplitCompoundNumeral(span.text, lexicon) ?: continue
                } else {
                    val p = ResegmentationScorer.findBestPath(span.text, lexicon, lexicon,
                        forbidFullSpanEdge = span.nameOnly, wordMeta = lexicon) ?: continue // no path found
                    val rejected = when {
                        !p.isComplete(span.text.length) -> true // path incomplete
                        p.segments.size <= 1 -> true // single segment
                        p.segments.size > (span.text.length + 1) / 2 -> true // too fragmented
                        hasBadSingleKana(p, span.text) -> true
                        isReduplicatedKanaSplit(p, span.text, lexicon) -> true // reduplicated mimetic
                        isKatakanaConjugatedWordSpelling(span.text, lexicon) -> true
                        hasSuruConjugationTail(p, span.text) -> true
                        hasShortPureNameSegment(p, lexicon) -> true
                        hasNameSegmentOutsidePersonContext(p, span, sentence, lexicon) -> true
                        ResegmentationScorer.scorePath(p, lexicon, span.text) < 0 -> true // negative path score
                        else -> false
                    }
                    if (rejected) continue
                    path = p
                }

                val prevPos = if (span.wordIndex > 0) sentence.words[span.wordIndex - 1].word.partOfSpeech else null
                val nextPos = if (span.wordIndex < sentence.words.size - 1) sentence.words[span.wordIndex + 1].word.partOfSpeech else null

                pending.add(Pending(sentence, span, path, prevPos, nextPos))
            }
        }

        if (pending.isEmpty()) return

        val wordPosByWordId = wordPosByWordId(pending, lexicon)

        for (p in pending) {
            val freqScore = ResegmentationScorer.scorePath(p.path, lexicon, p.span.text)
            val posScore = ResegmentationScorer.scorePosTransitions(p.path, wordPosByWordId, p.prevPos, p.nextPos, lexicon)
            if (freqScore + posScore < MinAcceptScore) continue
            replaceSpan(p.sentence, p.span, p.path, lexicon)
        }
    }

    /** True when any sentence changed. */
    fun tryResegmentLowConfidenceTokens(
        sentences: List<SentenceInfo>,
        lexicon: JitenLexicon,
        marginMap: Map<Pair<Int, Int>, Int?>,
    ): Boolean {
        val pending = ArrayList<Pending>()

        for (si in sentences.indices) {
            val sentence = sentences[si]
            for (wi in sentence.words.indices.reversed()) {
                val word = sentence.words[wi].word

                if (word.text.length < 3 || word.text.length > 14) continue
                if (word.preMatchedWordId != null) continue
                if (word.partOfSpeech in UncertaintyDetector.SkipPos) continue
                if (PosMapper.isNameLikeSudachiNoun(word.partOfSpeech, word.partOfSpeechSection1,
                        word.partOfSpeechSection2, word.partOfSpeechSection3)) continue
                val key = si to wi
                if (!marginMap.containsKey(key) || !ScoringPolicy.isLowConfidence(marginMap[key])) continue

                val path = ResegmentationScorer.findBestPath(word.text, lexicon, lexicon, wordMeta = lexicon)
                if (path == null || !path.isComplete(word.text.length) || path.segments.size <= 1) continue
                if (path.segments.size > (word.text.length + 1) / 2) continue
                if (hasBadSingleKana(path, word.text)) continue
                if (isReduplicatedKanaSplit(path, word.text, lexicon)) continue
                if (isKatakanaConjugatedWordSpelling(word.text, lexicon)) continue
                if (hasSuruConjugationTail(path, word.text)) continue
                if (hasShortPureNameSegment(path, lexicon)) continue
                if (ResegmentationScorer.scorePath(path, lexicon, word.text) < MinAcceptScoreConfidence) continue

                val prevPos = if (wi > 0) sentence.words[wi - 1].word.partOfSpeech else null
                val nextPos = if (wi < sentence.words.size - 1) sentence.words[wi + 1].word.partOfSpeech else null

                pending.add(Pending(sentence, UncertainSpan(
                    wordIndex = wi,
                    text = word.text,
                    position = sentence.words[wi].position,
                    length = sentence.words[wi].length,
                ), path, prevPos, nextPos))
            }
        }

        if (pending.isEmpty()) return false

        val wordPosByWordId = wordPosByWordId(pending, lexicon)

        var anyApplied = false
        for (p in pending) {
            val freqScore = ResegmentationScorer.scorePath(p.path, lexicon, p.span.text)
            val posScore = ResegmentationScorer.scorePosTransitions(p.path, wordPosByWordId, p.prevPos, p.nextPos, lexicon)
            if (freqScore + posScore < MinAcceptScoreConfidence) continue
            replaceSpan(p.sentence, p.span, p.path, lexicon)
            anyApplied = true
        }

        return anyApplied
    }

    private fun wordPosByWordId(pending: List<Pending>, lexicon: JitenLexicon): Map<Int, PartOfSpeech> {
        val result = HashMap<Int, PartOfSpeech>()
        for (p in pending) for (s in p.path.segments) for (id in s.wordIds)
            if (id !in result) lexicon.primaryPos(id)?.let { result[id] = it }
        return result
    }

    // Splits at the last place marker (五十七 → 五十+七); a piece JMnedict has only as a name (二十三) splits further.
    private fun trySplitCompoundNumeral(text: String, lexicon: JitenLexicon): SpanPath? {
        for (i in text.length - 1 downTo 1) {
            if (text[i - 1] !in "十百千万億兆") continue

            val left = text.substring(0, i)
            val right = text.substring(i)

            val leftIds = nonNameLookupIds(left, lexicon) ?: continue

            val rightIds = nonNameLookupIds(right, lexicon)
            if (rightIds != null)
                return SpanPath(listOf(SpanTokenCandidate(0, left.length, leftIds), SpanTokenCandidate(i, right.length, rightIds)))

            val sub = trySplitCompoundNumeral(right, lexicon)
            if (sub != null)
                return SpanPath(listOf(SpanTokenCandidate(0, left.length, leftIds)) +
                    sub.segments.map { SpanTokenCandidate(i + it.startChar, it.length, it.wordIds) })
        }

        return null
    }

    // Drops all-Name/Unknown entries, including JMnedict "unclass" (五十五 "Isoi"), unlike IsTrueName.
    private fun nonNameLookupIds(text: String, lexicon: JitenLexicon): List<Int>? {
        val ids = lexicon.lookup(text)
        if (ids == null || ids.isEmpty()) return null
        val filtered = ids.filter { id ->
            val meta = lexicon.wordMeta(id)
            meta == null || meta.pos.isEmpty() || !meta.pos.all { it == PartOfSpeech.Name || it == PartOfSpeech.Unknown }
        }
        return filtered.ifEmpty { null }
    }

    // Short pure-name segments shred OOV names (ファルマ → ファ+ルマ); long ones are real (ラムシャーリー → ラム+シャーリー).
    private fun hasShortPureNameSegment(path: SpanPath, lexicon: JitenLexicon): Boolean {
        for (s in path.segments) {
            if (s.length > 2) continue

            // IsTrueName, since JMnedict "unclass" entries cover real slang like ダサ.
            var anyName = false
            var allNameLike = true
            for (id in s.wordIds) {
                val meta = lexicon.wordMeta(id) ?: continue
                if (meta.isTrueName) anyName = true
                if (meta.pos.any { it != PartOfSpeech.Name && it != PartOfSpeech.Unknown }) {
                    allNameLike = false
                    break
                }
            }
            if (anyName && allNameLike) return true
        }
        return false
    }

    // Suffixes that license a name segment (南アルプス市); without one it is a shred (ブリタニカ → ブリ+タニカ).
    private val NameContextSuffixes: Set<String> = TransitionRuleSets.HonorificSuffixes + setOf(
        "さま", "君", "どの", "先生", "先輩", "嬢", "卿",
        "市", "町", "村", "区", "郡", "県", "府", "都", "駅", "山", "川", "島", "湖", "港", "橋",
        "城", "寺", "神社", "線", "地方", "出身", "産", "語",
    )

    private fun hasNameSegmentOutsidePersonContext(
        path: SpanPath, span: UncertainSpan, sentence: SentenceInfo, lexicon: JitenLexicon,
    ): Boolean {
        // Re-splitting a name-only span into names is the intended outcome, whatever the context.
        if (span.nameOnly) return false

        val word = sentence.words[span.wordIndex].word
        if (word.isPersonNameContext) return false

        // A katakana particle marks a styled sentence (ボクノナマエハ) where name-like fragments are fine.
        for (s in path.segments)
            if (s.length == 1 && ResegmentationScorer.isKatakanaParticleChar(span.text[s.startChar])) return false

        for (j in path.segments.indices) {
            val s = path.segments[j]
            var anyName = false
            var allName = true
            for (id in s.wordIds) {
                if (!lexicon.hasWord(id)) continue
                if (lexicon.isTrueName(id)) anyName = true
                else { allName = false; break }
            }

            if (!anyName || !allName) continue

            // The following surface is the next segment, or the next sentence token for a span-final one.
            val following = if (j + 1 < path.segments.size) path.segments[j + 1].let { span.text.substring(it.startChar, it.startChar + it.length) }
            else if (span.wordIndex + 1 < sentence.words.size) sentence.words[span.wordIndex + 1].word.text
            else null
            if (following == null || following !in NameContextSuffixes) return true
        }

        return false
    }

    // A bare する-conjugation tail only matches noun homographs (キャッキャウフフした must not shed 舌).
    private fun hasSuruConjugationTail(path: SpanPath, text: String): Boolean {
        val last = path.segments.last()
        val tail = text.substring(last.startChar, last.startChar + last.length)
        return tail == "し" || tail == "した" || tail == "して"
    }

    // A katakana-spelled conjugated word is one word (オカシクナイ → おかしい), never fragments like オカ|シク.
    private fun isKatakanaConjugatedWordSpelling(text: String, lexicon: JitenLexicon): Boolean {
        if (text.length < 3 || !JapaneseTextHelper.isAllKatakana(text)) return false

        val hira = KanaConverter.toHiragana(text)
        for (f in Deconjugator.instance.deconjugate(hira)) {
            if (f.process.isEmpty() || f.text.length < 2 || f.text == hira) continue
            val ids = lexicon.lookup(f.text) ?: continue
            for (id in ids)
                if (lexicon.hasPos(id, PartOfSpeech.Verb) || lexicon.hasPos(id, PartOfSpeech.IAdjective)) return true
        }

        return false
    }

    // A repeated kana segment is one mimetic (ずりずり), except repeated interjections (はいはいはい) which stay split.
    private fun isReduplicatedKanaSplit(path: SpanPath, text: String, lexicon: JitenLexicon): Boolean {
        if (path.segments.size < 2) return false

        val firstSeg = path.segments[0]
        val first = text.substring(firstSeg.startChar, firstSeg.startChar + firstSeg.length)
        if (first.isEmpty() || !first.all(::isKana)) return false

        for (s in path.segments.drop(1))
            if (text.substring(s.startChar, s.startChar + s.length) != first) return false

        for (id in firstSeg.wordIds)
            if (lexicon.primaryPos(id) == PartOfSpeech.Interjection) return false

        return true
    }

    // Frequent single kana (リ/ン) shred OOV names (ゴブリン → ゴ+ブ+リ+ン); leading お/ご and katakana particles (オマエガ) pass.
    private fun hasBadSingleKana(path: SpanPath, text: String): Boolean {
        for (s in path.segments) {
            if (s.length != 1 || !isKana(text[s.startChar])) continue
            if (s.startChar == 0 && (text[s.startChar] == 'お' || text[s.startChar] == 'ご')) continue
            if (ResegmentationScorer.isKatakanaParticleChar(text[s.startChar])) continue
            return true
        }
        return false
    }

    private fun isKana(c: Char) = JapaneseTextHelper.isKana(c)

    private const val CompoundTailVerbRankAdvantage = 2

    // Segments born here skip ApplyContextPins; ナシ means 無し, but the pear 梨 would win the rank tiebreak.
    private val SegmentSurfacePins = mapOf("ナシ" to 1529560, "オラ" to 2080360)

    private fun replaceSpan(sentence: SentenceInfo, span: UncertainSpan, path: SpanPath, lexicon: JitenLexicon) {
        if (path.segments.isEmpty() || path.segments[0].startChar != 0 || path.segments.any { it.wordIds.isEmpty() })
            return

        fun rankOf(id: Int) = lexicon.wordFrequencyRank(id) ?: Int.MAX_VALUE

        val replacements = path.segments.mapIndexed { segIdx, seg ->
            val text = span.text.substring(seg.startChar, seg.startChar + seg.length)
            val pinnedId = SegmentSurfacePins[text]
            val surfacePinned = pinnedId != null
            val bestWordId: Int? = pinnedId ?: seg.wordIds.minByOrNull { rankOf(it) }

            var pos = PartOfSpeech.Noun
            if (bestWordId != null) lexicon.primaryPos(bestWordId)?.let { pos = it }

            val replacement = WordInfo().apply {
                this.text = text
                dictionaryForm = text
                normalizedForm = text
                partOfSpeech = pos
                reading = if (text.all(::isKana)) KanaConverter.toHiragana(text) else ""
                preMatchedWordId = bestWordId
                preMatchedCandidateWordIds = if (surfacePinned && bestWordId != null) mutableListOf(bestWordId)
                else seg.wordIds.toMutableList()
            }

            // An unattested compound's tail is a nominalised verb (逆回し → 回す) unless the noun is nearly as frequent (越し).
            if (segIdx > 0 && !surfacePinned) {
                val verb = VerbStemLookup.find(text, replacement.reading, lexicon)
                if (verb != null && rankOf(verb.wordId).toLong() * CompoundTailVerbRankAdvantage < seg.wordIds.minOf { rankOf(it) })
                    VerbStemLookup.pin(replacement, verb)
            }

            SentenceWord(replacement, span.position + seg.startChar, seg.length)
        }

        sentence.words.removeAt(span.wordIndex)
        sentence.words.addAll(span.wordIndex, replacements)
    }
}

// ResegmentationScorer.cs

internal object ResegmentationScorer {
    private const val MaxSpanLength = 14
    private const val MaxEdgeLength = 10
    private const val MaxEdgesPerStart = 10
    private const val BeamWidth = 16
    private const val KanaReadingFragmentMaxRank = 10000

    /** Single-kana particles valid in katakana-styled text (オマエガ来イ); other single kana are shred noise. */
    fun isKatakanaParticleChar(c: Char): Boolean = c in "ガヲニハヘデトモカノ"

    private fun isKatakanaRunChar(c: Char) = JapaneseTextHelper.isKatakanaWordChar(c)

    private fun nonEmptyLookup(lookups: JitenLexicon, key: String): IntArray? = lookups.lookup(key)?.takeIf { it.isNotEmpty() }

    fun buildEdges(spanText: String, startPos: Int, lookups: JitenLexicon): MutableList<SpanTokenCandidate> {
        val result = ArrayList<SpanTokenCandidate>(MaxEdgesPerStart)
        val maxLen = minOf(MaxEdgeLength, spanText.length - startPos)

        var len = maxLen
        while (len >= 1 && result.size < MaxEdgesPerStart) {
            val l = len--
            // Single-kana noise (リ/ン) crowds correct paths out of the beam; mirrors the engine's HasBadSingleKana gate.
            if (l == 1 && JapaneseTextHelper.isKana(spanText[startPos])
                && !(startPos == 0 && (spanText[startPos] == 'お' || spanText[startPos] == 'ご'))
                && !isKatakanaParticleChar(spanText[startPos])
            ) continue

            val slice = spanText.substring(startPos, startPos + l)
            var wordIds: IntArray? = nonEmptyLookup(lookups, slice)
            if (wordIds == null) {
                try {
                    val hira = KanaConverter.toNormalizedHiragana(slice)
                    if (hira != slice) wordIds = nonEmptyLookup(lookups, hira)
                } catch (_: Exception) {
                }
            }

            if (wordIds != null) result.add(SpanTokenCandidate(startPos, l, wordIds.toList()))
        }

        return result
    }

    // Verb-only matches (スク→空く) are never components; suffix-only (ディ→デー) only close a span, prefix-only only open one.
    private fun isPlausibleSegment(edge: SpanTokenCandidate, wordMeta: JitenLexicon, isFirst: Boolean, isFinal: Boolean): Boolean {
        for (id in edge.wordIds) {
            val meta = wordMeta.wordMeta(id) ?: return true
            for (p in meta.pos) {
                when (p) {
                    PartOfSpeech.Noun, PartOfSpeech.CommonNoun, PartOfSpeech.NaAdjective,
                    PartOfSpeech.Adverb, PartOfSpeech.Pronoun, PartOfSpeech.Name,
                    PartOfSpeech.Counter, PartOfSpeech.Numeral, PartOfSpeech.IAdjective,
                    PartOfSpeech.Expression, PartOfSpeech.Interjection -> return true
                    PartOfSpeech.Suffix, PartOfSpeech.NounSuffix, PartOfSpeech.Particle,
                    PartOfSpeech.Auxiliary -> if (isFinal) return true
                    PartOfSpeech.Prefix -> if (isFirst) return true
                    else -> {}
                }
            }
        }

        return false
    }

    private class BeamState(val segCount: Int, val lastLen: Int, val partialScore: Int, val segs: List<SpanTokenCandidate>)

    private val structuralOrder = Comparator<BeamState> { a, b ->
        val c = a.segCount.compareTo(b.segCount)
        if (c != 0) c else b.lastLen.compareTo(a.lastLen)
    }

    private val partialScoreOrder = Comparator<BeamState> { a, b -> b.partialScore.compareTo(a.partialScore) }

    private fun rankBonus(bestRank: Int) = when {
        bestRank <= 5000 -> 30
        bestRank <= 15000 -> 15
        bestRank <= 30000 -> 5
        else -> 0
    }

    private fun lengthBonus(length: Int) = when {
        length >= 5 -> 40
        length >= 4 -> 25
        length >= 3 -> 10
        else -> 0
    }

    private fun bestRank(wordIds: List<Int>, frequencyRanks: JitenLexicon): Int {
        var best = Int.MAX_VALUE
        for (id in wordIds) {
            val rank = frequencyRanks.wordFrequencyRank(id)
            if (rank != null && rank < best) best = rank
        }
        return best
    }

    fun findBestPath(
        spanText: String,
        lookups: JitenLexicon,
        frequencyRanks: JitenLexicon? = null,
        beamWidth: Int = BeamWidth,
        forbidFullSpanEdge: Boolean = false,
        wordMeta: JitenLexicon? = null,
    ): SpanPath? {
        if (spanText.isEmpty() || spanText.length > MaxSpanLength) return null

        // partialScore must track ScorePath's per-segment additive terms.
        val beamByPos = HashMap<Int, MutableList<BeamState>>()
        beamByPos[0] = mutableListOf(BeamState(0, 0, 0, emptyList()))

        val filterKatakanaFragments = wordMeta != null && JapaneseTextHelper.isAllKatakana(spanText)

        for (pos in spanText.indices) {
            val states = beamByPos[pos]
            if (states.isNullOrEmpty()) continue

            val edges = buildEdges(spanText, pos, lookups)
            // For a name-only span the whole-span edge just re-finds the bad name.
            if (forbidFullSpanEdge && pos == 0)
                edges.removeAll { it.length == spanText.length }
            // A katakana fragment attested only via a rare kanji word's reading (アンブ → 安否) is coincidence.
            if (filterKatakanaFragments && frequencyRanks != null)
                edges.removeAll { e ->
                    e.length < spanText.length &&
                        !lookups.containsKey(spanText.substring(e.startChar, e.startChar + e.length)) &&
                        e.wordIds.none { id -> frequencyRanks.wordFrequencyRank(id)?.let { it <= KanaReadingFragmentMaxRank } == true }
                }
            // Pure-katakana only: kana-normalised lookup invents verb/suffix readings (テラバイトディスク → ディ|スク).
            if (filterKatakanaFragments)
                edges.removeAll { e ->
                    e.length <= 2 && e.length < spanText.length &&
                        !(e.length == 1 && isKatakanaParticleChar(spanText[e.startChar])) &&
                        !(e.length == 1 && pos == 0 && spanText[0] == 'ド') &&
                        !isPlausibleSegment(e, wordMeta!!, pos == 0, pos + e.length == spanText.length)
                }
            // Mixed spans never cut inside a katakana run (ポンコツ車 → ポンコツ|車); pure katakana may (テラバイト|ディスク).
            if (!JapaneseTextHelper.isAllKatakana(spanText))
                edges.removeAll { e ->
                    pos + e.length < spanText.length &&
                        isKatakanaRunChar(spanText[pos + e.length - 1]) &&
                        isKatakanaRunChar(spanText[pos + e.length])
                }
            if (edges.isEmpty()) continue

            for (state in states) {
                for (edge in edges) {
                    val nextPos = pos + edge.length
                    val nextCount = state.segCount + 1

                    val edgeFreqBonus = if (frequencyRanks != null) rankBonus(bestRank(edge.wordIds, frequencyRanks)) else 0
                    val nextPartial = state.partialScore + edgeFreqBonus + lengthBonus(edge.length) - 15

                    val nextStates = beamByPos.getOrPut(nextPos) { ArrayList(beamWidth) }
                    nextStates.add(BeamState(nextCount, edge.length, nextPartial, state.segs + edge))
                }
            }

            for (bucket in beamByPos.values) {
                if (bucket.size > beamWidth) {
                    // C#'s List.Sort is an unstable introsort; the tie order decides which states survive the cut.
                    DotNetSort.sort(bucket, if (frequencyRanks != null) partialScoreOrder else structuralOrder)
                    bucket.subList(beamWidth, bucket.size).clear()
                }
            }
        }

        val completeStates = beamByPos[spanText.length]
        if (completeStates.isNullOrEmpty()) return null

        // A final single kana is allowed since it is usually a particle (ガ/ニ/ヲ).
        val maxSegments = (spanText.length + 1) / 2
        var validStates = completeStates.filter { it.segCount <= maxSegments && !hasNonTerminalSingleCharKana(it.segs, spanText) }.toMutableList()
        if (validStates.isEmpty()) validStates = completeStates.filter { it.segCount <= maxSegments }.toMutableList()
        if (validStates.isEmpty()) validStates = completeStates

        // 3+ short katakana fragments (コーポ|レイ|テッド) are an OOV loanword; two-piece splits (テラバイト|ディスク) stay.
        if (filterKatakanaFragments && spanText.length >= 5) {
            validStates.removeAll { s ->
                s.segs.size >= 3 && s.segs.all { it.length < 4 } &&
                    !s.segs.any { it.length == 1 && isKatakanaParticleChar(spanText[it.startChar]) }
            }
            if (validStates.isEmpty()) return null
        }

        if (frequencyRanks != null) {
            // ScorePath adds structural bonuses the partial score omits.
            return validStates.map { SpanPath(it.segs) }.maxByOrNull { scorePath(it, frequencyRanks, spanText) }
        }

        DotNetSort.sort(validStates, structuralOrder)
        return SpanPath(validStates[0].segs)
    }

    fun scorePath(path: SpanPath, frequencyRanks: JitenLexicon, spanText: String? = null): Int {
        var score = 0
        var totalLength = 0

        score -= 15 * path.segments.size

        var noWeakSingleCharSegments = true
        // A single Latin char is noise only in an all-Latin span (ＤＡＴＡ → Ｄ+ＡＴＡ), not before a word (Ｂ+メロ).
        val spanAllLatin = spanText != null && JapaneseTextHelper.isAllLatin(spanText)
        for (seg in path.segments) {
            totalLength += seg.length
            if (seg.length < 2) {
                if (spanText == null || JapaneseTextHelper.isKana(spanText[seg.startChar]) ||
                    (spanAllLatin && JapaneseTextHelper.isLatinLetter(spanText[seg.startChar]))
                ) {
                    if (!(seg.startChar == 0 && spanText != null && (spanText[seg.startChar] == 'お' || spanText[seg.startChar] == 'ご')))
                        noWeakSingleCharSegments = false
                }
            }

            score += rankBonus(bestRank(seg.wordIds, frequencyRanks))
            score += lengthBonus(seg.length)
        }

        if (noWeakSingleCharSegments) score += 50

        if (path.segments.isNotEmpty() && totalLength / path.segments.size >= 3) score += 20

        if (spanText != null && path.segments.size >= 2 &&
            path.segments[0].startChar == 0 && path.segments[0].length == 1 &&
            (spanText[0] == 'お' || spanText[0] == 'ご')
        ) score += 35

        return score
    }

    fun scorePosTransitions(
        path: SpanPath,
        wordPosByWordId: Map<Int, PartOfSpeech>,
        prevNeighborPos: PartOfSpeech?,
        nextNeighborPos: PartOfSpeech?,
        frequencyRanks: JitenLexicon? = null,
    ): Int {
        var score = 0

        val firstPos = resolveSegmentPos(path.segments[0], wordPosByWordId, frequencyRanks)
        val lastPos = resolveSegmentPos(path.segments.last(), wordPosByWordId, frequencyRanks)

        if (lastPos != null && isNounLike(lastPos) && nextNeighborPos == PartOfSpeech.Particle) score += 20

        if (lastPos != null && isPredicateHost(lastPos) && nextNeighborPos == PartOfSpeech.Auxiliary) score += 15

        if (prevNeighborPos == PartOfSpeech.Numeral && firstPos == PartOfSpeech.Counter) score += 30

        if (firstPos == PartOfSpeech.Counter && prevNeighborPos != null && !isNumericLike(prevNeighborPos)) score -= 15

        if (prevNeighborPos == PartOfSpeech.Particle && firstPos != null && isNounLike(firstPos)) score += 10

        if (prevNeighborPos == PartOfSpeech.Particle && firstPos != null && !isNounLike(firstPos)) score -= 15

        val allNounLike = path.segments.all { s -> resolveSegmentPos(s, wordPosByWordId, frequencyRanks)?.let(::isNounLike) == true }
        if (allNounLike && path.segments.size > 1) score += 10

        for (i in 0 until path.segments.size - 1) {
            val a = resolveSegmentPos(path.segments[i], wordPosByWordId, frequencyRanks)
            val b = resolveSegmentPos(path.segments[i + 1], wordPosByWordId, frequencyRanks)
            if (a == PartOfSpeech.Particle && b == PartOfSpeech.Particle) score -= 20
        }

        return score
    }

    private fun resolveSegmentPos(
        seg: SpanTokenCandidate,
        wordPosByWordId: Map<Int, PartOfSpeech>,
        frequencyRanks: JitenLexicon?,
    ): PartOfSpeech? {
        var bestRank = Int.MAX_VALUE
        var bestPos: PartOfSpeech? = null

        for (id in seg.wordIds) {
            val pos = wordPosByWordId[id] ?: continue
            val rank = frequencyRanks?.wordFrequencyRank(id) ?: (Int.MAX_VALUE - 1)
            if (rank < bestRank) {
                bestRank = rank
                bestPos = pos
            }
        }

        return bestPos
    }

    private fun isNounLike(pos: PartOfSpeech) = pos == PartOfSpeech.Noun || pos == PartOfSpeech.CommonNoun ||
        pos == PartOfSpeech.NaAdjective || pos == PartOfSpeech.NominalAdjective || pos == PartOfSpeech.Pronoun ||
        pos == PartOfSpeech.Expression

    private fun isPredicateHost(pos: PartOfSpeech) = pos == PartOfSpeech.Verb || pos == PartOfSpeech.IAdjective ||
        pos == PartOfSpeech.NaAdjective || pos == PartOfSpeech.Auxiliary

    private fun isNumericLike(pos: PartOfSpeech) = pos == PartOfSpeech.Numeral || pos == PartOfSpeech.Prefix ||
        pos == PartOfSpeech.Noun || pos == PartOfSpeech.CommonNoun || pos == PartOfSpeech.Pronoun || pos == PartOfSpeech.Name

    private fun hasNonTerminalSingleCharKana(segs: List<SpanTokenCandidate>, spanText: String): Boolean {
        for (i in 0 until segs.size - 1) {
            val seg = segs[i]
            if (seg.length != 1) continue
            val c = spanText[seg.startChar]
            if (seg.startChar == 0 && (c == 'お' || c == 'ご')) continue
            if (c in 'ぁ'..'ゖ' || c in 'ァ'..'ヶ') return true
        }
        return false
    }
}

/**
 * .NET's List<T>.Sort (ArraySortHelper introsort: insertion sort up to 16 items, median-of-three quicksort, heapsort
 * past the depth limit), so tied beam states keep Jiten's order; Kotlin's stable sortWith would keep different ones.
 */
internal object DotNetSort {
    fun <T> sort(list: MutableList<T>, cmp: Comparator<in T>) {
        if (list.size > 1) introSort(list, 0, list.size, 2 * (31 - Integer.numberOfLeadingZeros(list.size) + 1), cmp)
    }

    private fun <T> swap(a: MutableList<T>, i: Int, j: Int) { val t = a[i]; a[i] = a[j]; a[j] = t }

    private fun <T> swapIfGreater(a: MutableList<T>, cmp: Comparator<in T>, i: Int, j: Int) {
        if (cmp.compare(a[i], a[j]) > 0) swap(a, i, j)
    }

    // Sorts a[lo, lo + size)
    private fun <T> introSort(a: MutableList<T>, lo: Int, size: Int, depth: Int, cmp: Comparator<in T>) {
        var partitionSize = size
        var depthLimit = depth
        while (partitionSize > 1) {
            if (partitionSize <= 16) {
                if (partitionSize == 2) { swapIfGreater(a, cmp, lo, lo + 1); return }
                if (partitionSize == 3) {
                    swapIfGreater(a, cmp, lo, lo + 1); swapIfGreater(a, cmp, lo, lo + 2); swapIfGreater(a, cmp, lo + 1, lo + 2)
                    return
                }
                insertionSort(a, lo, partitionSize, cmp)
                return
            }
            if (depthLimit == 0) { heapSort(a, lo, partitionSize, cmp); return }
            depthLimit--
            val p = pickPivotAndPartition(a, lo, partitionSize, cmp)
            introSort(a, lo + p + 1, partitionSize - (p + 1), depthLimit, cmp)
            partitionSize = p
        }
    }

    private fun <T> pickPivotAndPartition(a: MutableList<T>, lo: Int, size: Int, cmp: Comparator<in T>): Int {
        val hi = size - 1
        val middle = hi shr 1
        swapIfGreater(a, cmp, lo, lo + middle)
        swapIfGreater(a, cmp, lo, lo + hi)
        swapIfGreater(a, cmp, lo + middle, lo + hi)
        val pivot = a[lo + middle]
        swap(a, lo + middle, lo + hi - 1)
        var left = 0
        var right = hi - 1
        while (left < right) {
            while (cmp.compare(a[lo + ++left], pivot) < 0) { }
            while (cmp.compare(pivot, a[lo + --right]) < 0) { }
            if (left >= right) break
            swap(a, lo + left, lo + right)
        }
        if (left != hi - 1) swap(a, lo + left, lo + hi - 1)
        return left
    }

    private fun <T> insertionSort(a: MutableList<T>, lo: Int, size: Int, cmp: Comparator<in T>) {
        for (i in 0 until size - 1) {
            val t = a[lo + i + 1]
            var j = i
            while (j >= 0 && cmp.compare(t, a[lo + j]) < 0) { a[lo + j + 1] = a[lo + j]; j-- }
            a[lo + j + 1] = t
        }
    }

    private fun <T> heapSort(a: MutableList<T>, lo: Int, n: Int, cmp: Comparator<in T>) {
        for (i in n shr 1 downTo 1) downHeap(a, lo, i, n, cmp)
        for (i in n downTo 2) { swap(a, lo, lo + i - 1); downHeap(a, lo, 1, i - 1, cmp) }
    }

    private fun <T> downHeap(a: MutableList<T>, lo: Int, start: Int, n: Int, cmp: Comparator<in T>) {
        var i = start
        val d = a[lo + i - 1]
        while (i <= n shr 1) {
            var child = 2 * i
            if (child < n && cmp.compare(a[lo + child - 1], a[lo + child]) < 0) child++
            if (cmp.compare(d, a[lo + child - 1]) >= 0) break
            a[lo + i - 1] = a[lo + child - 1]
            i = child
        }
        a[lo + i - 1] = d
    }
}

// UncertaintyDetector.cs

internal object UncertaintyDetector {
    val SkipPos: Set<PartOfSpeech> = setOf(
        PartOfSpeech.Particle, PartOfSpeech.Auxiliary, PartOfSpeech.Verb, PartOfSpeech.IAdjective,
        PartOfSpeech.SupplementarySymbol, PartOfSpeech.Symbol, PartOfSpeech.Conjunction,
        PartOfSpeech.Adnominal, PartOfSpeech.Prefix, PartOfSpeech.BlankSpace,
        PartOfSpeech.Suffix, PartOfSpeech.NounSuffix,
        PartOfSpeech.Counter, PartOfSpeech.Numeral, PartOfSpeech.Filler,
        PartOfSpeech.Expression,
    )

    fun findSpans(
        sentence: SentenceInfo,
        lookups: JitenLexicon,
        wordMeta: JitenLexicon? = null,
        protectedSurfaces: Set<String>? = null,
    ): List<UncertainSpan> {
        val result = ArrayList<UncertainSpan>()

        for (i in sentence.words.indices) {
            val (word, position, length) = sentence.words[i]

            if (word.text.length < 3 || word.text.length > 14) continue
            if (word.preMatchedWordId != null) continue
            if (word.partOfSpeech in SkipPos) continue
            if (protectedSurfaces != null && word.text in protectedSurfaces) continue

            // Compound numerals (五十七) are Sudachi OOV and often match only JMDict names, so always flag them.
            var nameOnly = false
            val isCompoundNumeral = word.partOfSpeechSection1 == PartOfSpeechSection.Numeral && word.text.length > 1
            if (!isCompoundNumeral) {
                val textMatch = hasMatch(word.text, lookups)
                val dictMatch = word.dictionaryForm != word.text && word.dictionaryForm.isNotEmpty() &&
                    hasMatch(word.dictionaryForm, lookups)
                if (textMatch || dictMatch) {
                    // An all-hiragana token matching only JMnedict names (ひとつだけ → 一ッ岳) is a misparsed word run.
                    val pureNameOnly = wordMeta != null &&
                        JapaneseTextHelper.isAllHiragana(word.text) &&
                        isPureNameOnlyMatch(word.text, lookups, wordMeta) &&
                        (!dictMatch || isPureNameOnlyMatch(word.dictionaryForm, lookups, wordMeta))
                    if (!pureNameOnly) continue
                    nameOnly = true
                }
            }

            result.add(UncertainSpan(wordIndex = i, text = word.text, position = position, length = length, nameOnly = nameOnly))
        }

        return result
    }

    fun hasMatch(text: String, lookups: JitenLexicon): Boolean {
        if (LookupCandidateCollector.hasAnyMatch(lookups, text, includeLongVowelStripped = true)) return true

        try {
            val hira = KanaConverter.toNormalizedHiragana(text)

            if (hasGodanDictFormMatch(text, lookups) || (hira != text && hasGodanDictFormMatch(hira, lookups))) return true

            if (hasIchidanDictFormMatch(text, lookups) || (hira != text && hasIchidanDictFormMatch(hira, lookups))) return true

            if (hasAdjSaNominalizationMatch(text, lookups) || (hira != text && hasAdjSaNominalizationMatch(hira, lookups))) return true
        } catch (_: Exception) {
        }
        return false
    }

    // A verb/adjective-derived match means the token is not name-only, whatever the lookup entries say.
    private fun isPureNameOnlyMatch(text: String, lookups: JitenLexicon, wordMeta: JitenLexicon): Boolean {
        val hira = try { KanaConverter.toNormalizedHiragana(text) } catch (_: Exception) { text }

        if (hasGodanDictFormMatch(text, lookups) || (hira != text && hasGodanDictFormMatch(hira, lookups))) return false
        if (hasIchidanDictFormMatch(text, lookups) || (hira != text && hasIchidanDictFormMatch(hira, lookups))) return false
        if (hasAdjSaNominalizationMatch(text, lookups) || (hira != text && hasAdjSaNominalizationMatch(hira, lookups))) return false

        var any = false
        for (key in if (hira != text) listOf(text, hira) else listOf(text)) {
            val ids = lookups.lookup(key) ?: continue
            for (id in ids) {
                any = true
                val meta = wordMeta.wordMeta(id) ?: return false
                if (!meta.isTrueName) return false
                if (meta.pos.any { it != PartOfSpeech.Name && it != PartOfSpeech.Unknown }) return false
            }
        }

        return any
    }

    private fun hasGodanDictFormMatch(text: String, lookups: JitenLexicon): Boolean {
        val dictForm = tryGodanDictForm(text)
        return dictForm != null && lookups.lookup(dictForm)?.isNotEmpty() == true
    }

    private fun hasIchidanDictFormMatch(text: String, lookups: JitenLexicon): Boolean {
        if (text.length < 2) return false
        return lookups.lookup(text + "る")?.isNotEmpty() == true
    }

    private fun hasAdjSaNominalizationMatch(text: String, lookups: JitenLexicon): Boolean {
        if (text.length < 3 || text.last() != 'さ') return false
        return lookups.lookup(text.dropLast(1) + "い")?.isNotEmpty() == true
    }
}
