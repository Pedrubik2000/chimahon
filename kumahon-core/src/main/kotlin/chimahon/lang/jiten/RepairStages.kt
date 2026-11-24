// Ported from Jiten (github.com/Sirush/Jiten, Apache-2.0, commit 44b48a4): Jiten.Parser/Stages/MorphologicalAnalyser.RepairStages.cs
package chimahon.lang.jiten

// C# lines 1-2021 (lines 2022-end are in RepairStages2.kt). Private helpers a stage needs go
// in this file, file-private; helpers other files use are marked "shared" below.


// Shared (C# internal; RepairStages' other half uses it). Verb heads a quotative って attaches to (言う/思う/聞く/考える/感じる);
// the datte-quote rewrite rule shares this set.
internal val QuoteVerbHeads: CharArray = charArrayOf('言', '思', '聞', '考', '感')

/** C#'s `HasNonNameCompoundLookup?.Invoke(s) == true`. */
private fun MorphologicalAnalyser.nonName(s: String): Boolean = hasNonNameCompoundLookup?.invoke(s) == true

private fun isKanji(c: Char) = JapaneseTextHelper.isKanji(c)

private fun isHiraganaLetter(c: Char) = c in 'ぁ'..'ゖ'

private fun isNounOrCommonNoun(p: PartOfSpeech) = p == PartOfSpeech.Noun || p == PartOfSpeech.CommonNoun

// Sudachi bleeds the っ of 〜っぱなし/っぷり/っぽい into the stem (流|しっ|ぱなし, しっ→知る); っ must head its suffix.
private val GeminateSuffixes: Map<String, Pair<String, Int>> = hashMapOf(
    "ぱなし" to ("っぱなし" to 1008020),
    "ぷり" to ("っぷり" to 2202980),
    "ぽい" to ("っぽい" to 2083720),
    "ぽく" to ("っぽい" to 2083720),
    "ぽさ" to ("っぽい" to 2083720),
)

// Host must end in kanji or i/e-row kana; an a-row final (やっ|ぱなし = やっぱ+なし) never hosts the suffix.
private fun canHostGeminateSuffix(stem: String): Boolean =
    stem.isNotEmpty() && (isKanji(stem.last()) || stem.last() in "いきしちにひみりぎじぢびぴえけせてねへめれげぜでべぺ")

internal fun MorphologicalAnalyser.repairGeminateSuffixTheft(words: MutableList<WordInfo>, candidates: List<Int>): MutableList<WordInfo> {
    val wordInfos = words
    if (wordInfos.size < 2) return wordInfos

    val deconj = Deconjugator.instance

    // Extends only onto an attested renyoukei (上がり) or orphan kanji (失+い); ワクワク+し stays split so し resolves as する.
    fun stemExtends(prev: WordInfo, candidate: String): Boolean {
        if (candidate.isEmpty()) return false
        val forms = deconj.deconjugate(normalizeToHiragana(candidate))
        val isVerbStem = forms.any { f ->
            f.tags.any { it.startsWith("v") } && hasVerbOrAdjectiveLookup?.invoke(f.text) == true
        }
        if (!isVerbStem) return false
        if (nonName(candidate)) return true
        return prev.text.any(::isKanji)
    }

    var list: MutableList<WordInfo>? = null
    var indexDelta = 0
    var skipOriginalThrough = -1
    for (originalIndex in candidates) {
        if (originalIndex <= skipOriginalThrough) continue
        val toks = list ?: wordInfos
        val i = originalIndex + indexDelta
        if (i < 0 || i >= toks.size) continue
        val t = toks[i]
        var stemText: String? = null
        var suffixText: String? = null
        var consumed = 0

        if (t.text.length > 1 && t.text.endsWith('っ') && i + 1 < toks.size &&
            toks[i + 1].text in GeminateSuffixes && canHostGeminateSuffix(t.text.dropLast(1))) {
            stemText = t.text.dropLast(1)
            suffixText = "っ" + toks[i + 1].text
            consumed = 2
        } else if (t.text.length > 2 && t.text.endsWith("っぱ") && i + 1 < toks.size &&
            (toks[i + 1].text == "なし" || (i + 2 < toks.size && toks[i + 1].text == "な" && toks[i + 2].text == "し")) &&
            canHostGeminateSuffix(t.text.dropLast(2))) {
            stemText = t.text.dropLast(2)
            suffixText = "っぱなし"
            consumed = if (toks[i + 1].text == "なし") 2 else 3
        } else if (t.text.length > 2 && t.text.endsWith("っぷ") && i + 1 < toks.size && toks[i + 1].text == "り" &&
            canHostGeminateSuffix(t.text.dropLast(2))) {
            stemText = t.text.dropLast(2)
            suffixText = "っぷり"
            consumed = 2
        }

        // Lexicalised 女っぷり stranding a compound's first kanji (巫|女っぷり); いい女っぷり stays whole.
        var rejoinsPrevKanji = false
        if (stemText == null && t.text.length > 3 && t.text.endsWith("っぷり") && i > 0 &&
            toks[i - 1].text.length == 1 && isKanji(toks[i - 1].text[0]) &&
            nonName(toks[i - 1].text + t.text.dropLast(3))) {
            stemText = t.text.dropLast(3)
            suffixText = "っぷり"
            consumed = 1
            rejoinsPrevKanji = true
        }

        val stemT = stemText ?: continue
        val suffixT = suffixText!!

        val l = list ?: ArrayList(wordInfos).also { list = it }
        skipOriginalThrough = originalIndex + consumed - 1
        val (suffixDict, suffixPin) = GeminateSuffixes.getValue(suffixT.trimStart('っ'))
        val last = l[i + consumed - 1]

        val cut = t.text.length - stemT.length
        val stem = t.copy().apply {
            text = stemT
            dictionaryForm = stemT
            normalizedForm = stemT
            reading = if (t.reading.length > cut) t.reading.dropLast(cut) else ""
            endOffset = if (t.startOffset >= 0) t.startOffset + stemT.length else -1
            partOfSpeech = PartOfSpeech.Noun
        }
        val suffix = WordInfo().apply {
            text = suffixT
            dictionaryForm = suffixDict
            normalizedForm = suffixDict
            reading = WanaKana.toKatakana(suffixT)
            partOfSpeech = PartOfSpeech.Suffix
            startOffset = stem.endOffset
            endOffset = last.endOffset
            preMatchedWordId = suffixPin
            hardPinned = true
        }

        l.subList(i, i + consumed).clear()
        l.add(i, suffix)
        l.add(i, stem)

        // Rejoins leftward while it deconjugates to an attested verb (上+が+り→上がる); を+飛ばし fails.
        var merges = 0
        var stemIdx = i
        while (stemIdx > 0 && merges < 3) {
            val prev = l[stemIdx - 1]
            val cur = l[stemIdx]
            val cand = prev.text + cur.text
            if (!(rejoinsPrevKanji && merges == 0) && !stemExtends(prev, cand)) break
            val merged = cur.copy().apply {
                text = cand
                dictionaryForm = cand
                normalizedForm = cand
                reading = prev.reading + cur.reading
                startOffset = prev.startOffset
            }
            l[stemIdx] = merged
            l.removeAt(stemIdx - 1)
            stemIdx--
            merges++
        }

        indexDelta = l.size - wordInfos.size
    }

    return list ?: wordInfos
}

// A blob that cuts into two attested words (ハロー|ワーク) belongs to the resegmentation lattice, which scores the cut.
private fun MorphologicalAnalyser.hasAttestedBipartition(text: String): Boolean {
    for (cutAt in 2..text.length - 2) {
        if (nonName(text.substring(0, cutAt)) && nonName(text.substring(cutAt))) return true
    }
    return false
}

private fun isKatakanaRunToken(w: WordInfo): Boolean =
    w.text.isNotEmpty() && w.preMatchedWordId == null &&
        !w.isPersonNameContext &&
        !PosMapper.isNameLikeSudachiNoun(w.partOfSpeech, w.partOfSpeechSection1, w.partOfSpeechSection2, w.partOfSpeechSection3) &&
        w.text.all(JapaneseTextHelper::isKatakanaWordChar)

// Stripped markup leaves unrelated tokens adjacent ([name]アリサ[line]“リア充); merging across the gap makes アリサリア.
private fun contiguous(a: WordInfo, b: WordInfo): Boolean =
    a.endOffset >= 0 && b.startOffset >= 0 && a.endOffset == b.startOffset

// Loanword head tagged a proper noun with its last mora fused into the particle (ドラ|マで[まで]).
private fun MorphologicalAnalyser.isNameShredOfMixedToken(toks: List<WordInfo>, i: Int): Boolean {
    if (i + 1 >= toks.size) return false
    val head = toks[i]
    val next = toks[i + 1]
    if (head.preMatchedWordId != null || head.isPersonNameContext || next.preMatchedWordId != null ||
        head.text.isEmpty() || !head.text.all(JapaneseTextHelper::isKatakanaWordChar) ||
        !contiguous(head, next))
        return false
    var kl = 0
    while (kl < next.text.length && JapaneseTextHelper.isKatakanaWordChar(next.text[kl])) kl++
    return kl >= 1 && kl < next.text.length &&
        next.text.substring(kl).all(::isHiraganaLetter) &&
        nonName(head.text + next.text.substring(0, kl))
}

// Sudachi shreds OOV katakana into junk-attested fragments (ホロ|グラフ); merge if any piece is fragment-shaped (システム|エラー stays).
internal fun MorphologicalAnalyser.repairKatakanaShreds(words: MutableList<WordInfo>, candidates: List<Int>): MutableList<WordInfo> {
    val wordInfos = words
    if (wordInfos.isEmpty()) return wordInfos

    var list: MutableList<WordInfo>? = null
    var indexDelta = 0
    var skipOriginalThrough = -1
    for (originalIndex in candidates) {
        if (originalIndex <= skipOriginalThrough) continue
        val toks = list ?: wordInfos
        val i = originalIndex + indexDelta
        // A sentence-final token has no run to extend but can still take the tail-word split.
        if (i < 0 || i >= toks.size) continue

        if (!isKatakanaRunToken(toks[i]) && !isNameShredOfMixedToken(toks, i)) continue

        var end = i
        while (end + 1 < toks.size && end - i < 4 && isKatakanaRunToken(toks[end + 1]) && contiguous(toks[end], toks[end + 1]))
            end++

        // A katakana-headed mixed token continues the run (ブロー|アップされる); its hiragana tail splits back off.
        var mixedTail = ""
        var mixedIdx = -1
        if (end + 1 < toks.size && end - i < 4 && toks[end + 1].preMatchedWordId == null && contiguous(toks[end], toks[end + 1])) {
            val cand = toks[end + 1].text
            var kl = 0
            while (kl < cand.length && JapaneseTextHelper.isKatakanaWordChar(cand[kl])) kl++
            // A kanji tail means the token is its own compound (リア充), not a shredded head plus grammar.
            if (kl >= 1 && kl < cand.length && cand.substring(kl).all(::isHiraganaLetter)) {
                mixedIdx = end + 1
                mixedTail = cand.substring(kl)
            }
        }

        // Splits a real loanword tail off an unattested blob (OOV name+レベル); heads under 4 chars defer to the lattice.
        if (end == i && mixedIdx < 0) {
            val text = toks[i].text
            if (text.length >= 7 && hasNonNameCompoundLookup?.invoke(text) != true && !hasAttestedBipartition(text)) {
                for (headLen in 4..text.length - 3) {
                    val head = text.substring(0, headLen)
                    val tail = text.substring(headLen)
                    if (hasNonNameCompoundLookup?.invoke(tail) != true || nonName(head)) continue

                    val l = list ?: ArrayList(wordInfos).also { list = it }
                    val source = l[i]
                    val cut = if (source.startOffset >= 0) source.startOffset + headLen else -1
                    val r = source.reading
                    val headToken = source.copy().apply {
                        this.text = head; dictionaryForm = head; normalizedForm = head
                        reading = if (r.isNotEmpty() && r.length >= headLen) r.substring(0, headLen) else head
                        endOffset = cut
                    }
                    val tailToken = source.copy().apply {
                        this.text = tail; dictionaryForm = tail; normalizedForm = tail
                        reading = if (r.isNotEmpty() && r.length >= headLen) r.substring(headLen) else tail
                        startOffset = cut
                    }
                    l[i] = headToken
                    l.add(i + 1, tailToken)
                    skipOriginalThrough = originalIndex
                    indexDelta = l.size - wordInfos.size
                    break
                }
            }

            continue
        }

        var full = (i..end).joinToString("") { toks[it].text }
        if (mixedIdx >= 0) full += toks[mixedIdx].text.dropLast(mixedTail.length)
        var wholeAttested = nonName(full)
        var anyFragment = mixedIdx >= 0
        var j = i
        while (j <= end && !anyFragment) {
            anyFragment = toks[j].text.length <= 2 || hasNonNameCompoundLookup?.invoke(toks[j].text) != true
            j++
        }

        if (!wholeAttested && !anyFragment) {
            skipOriginalThrough = originalIndex + end - i
            continue
        }

        // An attested ≥3-char tail (レベル) stays out of the merge, but a two-piece run still merges whole as name material.
        if (!wholeAttested && mixedIdx < 0) {
            while (end > i + 1 && toks[end].text.length >= 3 && nonName(toks[end].text)) end--

            full = (i..end).joinToString("") { toks[it].text }
            wholeAttested = nonName(full)
        }

        val l = list ?: ArrayList(wordInfos).also { list = it }
        val last = if (mixedIdx >= 0) mixedIdx else end
        val mergedEnd = if (mixedIdx >= 0) {
            if (l[mixedIdx].endOffset >= 0) l[mixedIdx].endOffset - mixedTail.length else -1
        } else {
            l[end].endOffset
        }
        val mergedReading = (i..end).joinToString("") { l[it].reading } +
            (if (mixedIdx >= 0) full.takeLast(l[mixedIdx].text.length - mixedTail.length) else "")
        val fullText = full
        val merged = l[i].copy().apply {
            text = fullText
            dictionaryForm = fullText
            normalizedForm = fullText
            reading = mergedReading
            partOfSpeech = PartOfSpeech.Noun
            endOffset = mergedEnd
        }
        l.subList(i, last + 1).clear()
        l.add(i, merged)
        if (mixedIdx >= 0) l.addAll(i + 1, tokenizeGrammarRemainder(mixedTail, mergedEnd))
        skipOriginalThrough = originalIndex + last - i
        indexDelta = l.size - wordInfos.size
    }

    return list ?: wordInfos
}

// These attach to the FULL preceding compound (滑走路+上, 消去法+的), so 滑走|路上 re-cuts to 滑走路|上.
private val LocationSuffixChars: Set<Char> = hashSetOf('上', '中', '内', '外', '前', '後', '間', '下', '先', '際', '的')

// Tuned cutoff: 日本|国内 stays, 滑走|路上 re-cuts.
private const val BoundaryTheftRankDominanceFactor = 4

private val ExpressionSplitParticles = charArrayOf('を', 'が', 'に')

// 当薬 (267598) vs 薬局 (21272) re-cuts; common heads (大学|生, 日本|国) never qualify.
private const val PrefixTheftMinHeadRank = 50000
private const val PrefixTheftRankDominanceFactor = 8

// C#'s RareVerbShredMinRank (5000) and RareVerbShredRankRatio (4) are declared here but never read: left out.

private val DemonstrativeHeads = arrayOf("その", "この", "あの", "どの")

private val CopulaOrSuruNegativeTails = setOf(
    "だ", "だっ", "だった", "だったら", "で", "です", "でし",
    "でした", "だろ", "だろう", "じゃ", "じゃない", "じゃなく",
    // Noun+する-negative (話|しないで), never a shred.
    "しない", "しないで", "しなく", "せず",
)

// Sudachi splits a conjugated verb's kanji head from its kana tail as unrelated words (会|おう → 会+王).
internal fun MorphologicalAnalyser.repairKanjiVerbShred(words: MutableList<WordInfo>, candidates: List<Int>): MutableList<WordInfo> {
    val wordInfos = words
    if (wordInfos.size < 2) return wordInfos

    val deconj = Deconjugator.instance

    var list: MutableList<WordInfo>? = null
    var indexDelta = 0

    // A sokuon contraction after a kanji verb stem shifts every cut one char early (探|しっス → 探し|っス); run[1] っ marks it.
    // (C#'s TryShiftStrandedOkurigana with ref list/indexDelta: a local function over the captured vars.)
    fun tryShiftStrandedOkurigana(i: Int): Boolean {
        val toks = list ?: wordInfos
        val k = toks[i]

        var end = i
        var runLength = 0
        while (end + 1 < toks.size && end - i < 3) {
            val t = toks[end + 1]
            if (t.preMatchedWordId != null || t.text.isEmpty()) break
            if (!t.text.all { it in 'ぁ'..'ゖ' || it in 'ァ'..'ヺ' }) break
            if (runLength + t.text.length > 4) break
            end++
            runLength += t.text.length
            if (t.text.last() != 'っ' && t.text.last() != 'ッ') break
        }

        if (end == i || runLength < 2) return false

        val run = (i + 1..end).joinToString("") { toks[it].text }
        // A small kana leading the run means the sokuon is the stem's own (真|っ赤), not a stolen boundary.
        if (run[0] in "ぁぃぅぇぉっゃゅょゎ" || run[0] !in 'ぁ'..'ゖ') return false
        if (run[1] != 'っ' && run[1] != 'ッ') return false

        val stem = k.text + run[0]
        val dictForm = resolveVerbStem(stem, k.text) ?: return false

        val l = list ?: ArrayList(wordInfos).also { list = it }
        var offset = if (k.endOffset >= 0) k.endOffset + 1 else -1
        val stemEnd = offset
        l[i] = k.copy().apply {
            text = stem
            dictionaryForm = dictForm
            normalizedForm = dictForm
            reading = ""
            partOfSpeech = PartOfSpeech.Verb
            endOffset = stemEnd
        }

        val shifted = ArrayList<WordInfo>(end - i)
        var taken = 1
        var j = i + 1
        while (j <= end && taken < run.length) {
            val src = toks[j]
            val len = minOf(src.text.length, run.length - taken)
            val piece = run.substring(taken, taken + len)
            val start = offset
            shifted.add(src.copy().apply {
                text = piece
                dictionaryForm = piece
                normalizedForm = piece
                reading = ""
                // A shifted って can only be the quotative.
                partOfSpeech = if (piece == "って") PartOfSpeech.Particle else src.partOfSpeech
                partOfSpeechSection1 = if (piece == "って") PartOfSpeechSection.AdverbialParticle else src.partOfSpeechSection1
                startOffset = start
                endOffset = if (start >= 0) start + len else -1
            })
            offset = if (offset >= 0) offset + len else -1
            taken += len
            j++
        }

        l.subList(i + 1, end + 1).clear()
        l.addAll(i + 1, shifted)
        indexDelta += shifted.size - (end - i)
        return true
    }

    for (originalIndex in candidates) {
        var toks = list ?: wordInfos
        val i = originalIndex + indexDelta
        if (i < 0 || i + 1 >= toks.size) continue
        var k = toks[i]
        if (k.preMatchedWordId != null) continue
        if (k.text.length != 1 || !isKanji(k.text[0])) continue
        // 何 heads no verb; its kana continuations are their own words (何|かって).
        if (k.text == "何") continue

        if (tryShiftStrandedOkurigana(i)) continue

        toks = list ?: wordInfos
        k = toks[i]
        val tail = toks[i + 1]
        if (tail.preMatchedWordId != null) continue
        if (!isNounOrCommonNoun(k.partOfSpeech)) continue
        // A clause-initial single kanji is a speaker name in script dumps ([name]至[line]“そうなん?” is not 至る).
        if (i == 0 || toks[i - 1].partOfSpeech == PartOfSpeech.Symbol ||
            toks[i - 1].partOfSpeech == PartOfSpeech.SupplementarySymbol ||
            toks[i - 1].partOfSpeech == PartOfSpeech.BlankSpace) continue
        if (tail.text.length < 1 || tail.text.length > 4 || !tail.text.all(::isHiraganaLetter)) continue
        if (tail.partOfSpeech == PartOfSpeech.Particle || tail.partOfSpeech == PartOfSpeech.Auxiliary ||
            tail.partOfSpeech == PartOfSpeech.Symbol || tail.partOfSpeech == PartOfSpeech.SupplementarySymbol) continue
        // A copula tail belongs to the sentence (目|だった is not 目立った); a complete dictionary-form verb stays (今|いる).
        if (tail.dictionaryForm == "だ" || tail.dictionaryForm == "です" || tail.text in CopulaOrSuruNegativeTails) continue
        // Same する-negative split one token earlier (話|し|ないで).
        if (tail.text == "し" && i + 2 < toks.size && toks[i + 2].text.startsWith("な")) continue
        if (tail.partOfSpeech == PartOfSpeech.Verb && tail.text == tail.dictionaryForm) continue

        val cand = k.text + tail.text
        val form = deconj.deconjugate(cand).firstOrNull { f ->
            f.text.length > 1 && f.text != cand &&
                f.text.startsWith(k.text) &&
                f.tags.any { it.startsWith("v") } &&
                f.process.isNotEmpty() &&
                nonName(f.text)
        } ?: continue

        val l = list ?: ArrayList(wordInfos).also { list = it }
        l[i] = k.copy().apply {
            text = cand
            dictionaryForm = form.text
            normalizedForm = form.text
            reading = ""
            partOfSpeech = PartOfSpeech.Verb
            endOffset = tail.endOffset
        }
        l.removeAt(i + 1)
        indexDelta--
    }

    return list ?: wordInfos
}

// Accepts a dictionary-form verb (探す) or one deconjugation step off one (探し → 探す).
private fun MorphologicalAnalyser.resolveVerbStem(stem: String, kanji: String): String? {
    if (stem.last() in "うくぐすつぬぶむる" && nonName(stem) && deconjugatesToVerb(stem)) return stem

    for (f in Deconjugator.instance.deconjugate(stem)) {
        if (f.process.size != 1 || f.text.length <= 1 || f.text == stem) continue
        if (!f.text.startsWith(kanji)) continue
        if (f.tags.none { it.startsWith("v") }) continue
        if (nonName(f.text)) return f.text
    }

    return null
}

internal fun MorphologicalAnalyser.repairCompoundBoundaryTheft(words: MutableList<WordInfo>, candidates: List<Int>): MutableList<WordInfo> {
    val wordInfos = words
    if (wordInfos.size < 2) return wordInfos

    var list: MutableList<WordInfo>? = null
    var shift = 0
    for (candidate in candidates) {
        val toks = list ?: wordInfos
        val i = candidate + shift
        if (i < 0 || i + 1 >= toks.size) continue
        val a = toks[i]
        val b = toks[i + 1]
        if (a.preMatchedWordId != null || b.preMatchedWordId != null) continue

        // An N+particle+V expression swallowed a compound's tail: [素][振りをする].
        val particleAt = if (b.partOfSpeech == PartOfSpeech.Expression && b.text.length > 2)
            b.text.indexOfAny(ExpressionSplitParticles) else -1
        if (a.text.length == 1 && isKanji(a.text[0]) &&
            (a.partOfSpeech == PartOfSpeech.Suffix || a.partOfSpeech == PartOfSpeech.Prefix || isNounOrCommonNoun(a.partOfSpeech)) &&
            particleAt >= 1 && particleAt < b.text.length - 1 &&
            isKanji(b.text[0]) &&
            nonName(a.text + b.text.substring(0, particleAt))) {
            val noun = a.text + b.text.substring(0, particleAt)
            val particle = b.text.substring(particleAt, particleAt + 1)
            val verbText = b.text.substring(particleAt + 1)
            val verbDict = if (b.dictionaryForm.startsWith(b.text.substring(0, particleAt + 1)))
                b.dictionaryForm.substring(particleAt + 1) else verbText
            val nounEnd = if (a.startOffset >= 0) a.startOffset + noun.length else -1

            val l = list ?: ArrayList(wordInfos).also { list = it }
            l[i] = a.copy().apply {
                text = noun; dictionaryForm = noun; normalizedForm = noun; reading = ""
                partOfSpeech = PartOfSpeech.Noun; partOfSpeechSection1 = PartOfSpeechSection.None
                endOffset = nounEnd
            }
            l[i + 1] = b.copy().apply {
                text = particle; dictionaryForm = particle; normalizedForm = particle
                reading = WanaKana.toKatakana(particle)
                partOfSpeech = PartOfSpeech.Particle; partOfSpeechSection1 = PartOfSpeechSection.CaseMarkingParticle
                startOffset = nounEnd; endOffset = if (nounEnd >= 0) nounEnd + 1 else -1
            }
            l.add(i + 2, b.copy().apply {
                text = verbText; dictionaryForm = verbDict; normalizedForm = if (verbDict == "する") "為る" else verbDict
                reading = if (verbText == verbDict && verbText.all(JapaneseTextHelper::isHiragana)) WanaKana.toKatakana(verbText) else ""
                partOfSpeech = PartOfSpeech.Verb; partOfSpeechSection1 = PartOfSpeechSection.None
                startOffset = if (nounEnd >= 0) nounEnd + 1 else -1
            })
            shift++
            continue
        }

        // [不時][着し] → [不時着][し]: a suru-noun's last kanji misread as a verb stem (着す).
        if (a.text.all(::isKanji) &&
            isNounOrCommonNoun(a.partOfSpeech) &&
            b.partOfSpeech == PartOfSpeech.Verb && b.text.length >= 2 && b.text[1] == 'し' &&
            isKanji(b.text[0]) &&
            hasSuruVerbCompoundLookup?.invoke(a.text + b.text[0]) == true &&
            nonName(a.text + b.text[0])) {
            val noun = a.text + b.text[0]
            val rest = b.text.substring(1)
            val cut = if (a.endOffset >= 0) a.endOffset + 1 else -1
            val l = list ?: ArrayList(wordInfos).also { list = it }
            l[i] = a.copy().apply {
                text = noun; dictionaryForm = noun; normalizedForm = noun; reading = ""
                partOfSpeech = PartOfSpeech.Noun; partOfSpeechSection1 = PartOfSpeechSection.None
                endOffset = cut
            }
            l[i + 1] = b.copy().apply {
                text = rest; dictionaryForm = "する"; normalizedForm = "為る"
                reading = WanaKana.toKatakana(rest)
                partOfSpeech = PartOfSpeech.Verb; partOfSpeechSection1 = PartOfSpeechSection.None
                startOffset = cut
            }
            continue
        }

        // [ちびっ][こども] → [ちびっこ][ども]: the っ-final word ends one mora into the next token.
        if (a.text.length >= 2 && a.text.last() == 'っ' && b.text.length >= 3 &&
            a.partOfSpeech != PartOfSpeech.Verb && a.partOfSpeech != PartOfSpeech.Auxiliary && a.partOfSpeech != PartOfSpeech.IAdjective &&
            JapaneseTextHelper.isKana(b.text[0]) &&
            nonName(a.text + b.text[0]) &&
            nonName(b.text.substring(1)) &&
            hasNonNameCompoundLookup?.invoke(a.text) != true) {
            val head = a.text + b.text[0]
            val rest = b.text.substring(1)
            val cut = if (a.endOffset >= 0) a.endOffset + 1 else -1
            val l = list ?: ArrayList(wordInfos).also { list = it }
            l[i] = a.copy().apply {
                text = head; dictionaryForm = head; normalizedForm = head; reading = WanaKana.toKatakana(head)
                partOfSpeech = PartOfSpeech.Noun; partOfSpeechSection1 = PartOfSpeechSection.None
                endOffset = cut
            }
            l[i + 1] = b.copy().apply {
                text = rest; dictionaryForm = rest; normalizedForm = rest
                reading = if (b.reading.length > 1) b.reading.substring(1) else ""
                startOffset = cut
            }
            continue
        }

        // [当薬][局] → [当][薬局]: a prefix kanji paired into a rare word stole a frequent compound's head.
        if (a.text.length == 2 && a.text.all(::isKanji) &&
            b.text.length == 1 && isKanji(b.text[0]) &&
            isNounOrCommonNoun(a.partOfSpeech) &&
            (isNounOrCommonNoun(b.partOfSpeech) || b.partOfSpeech == PartOfSpeech.Suffix) &&
            nonName(a.text.substring(0, 1)) &&
            run {
                val tailRank = getNonNameCompoundFrequencyRank?.invoke(a.text.substring(1) + b.text) ?: return@run false
                val headRank = getNonNameCompoundFrequencyRank!!(a.text) ?: Int.MAX_VALUE
                headRank >= PrefixTheftMinHeadRank && headRank / PrefixTheftRankDominanceFactor > tailRank
            }) {
            val tail = a.text.substring(1) + b.text
            val head = a.text.substring(0, 1)
            val cut = if (a.startOffset >= 0) a.startOffset + 1 else -1
            val l = list ?: ArrayList(wordInfos).also { list = it }
            l[i] = a.copy().apply {
                text = head; dictionaryForm = head; normalizedForm = head; reading = ""
                partOfSpeech = PartOfSpeech.Prefix; partOfSpeechSection1 = PartOfSpeechSection.None
                endOffset = cut
            }
            l[i + 1] = b.copy().apply {
                text = tail; dictionaryForm = tail; normalizedForm = tail; reading = ""
                partOfSpeech = PartOfSpeech.Noun; partOfSpeechSection1 = PartOfSpeechSection.None
                startOffset = cut
            }
            continue
        }

        // [滑走][路上] → [滑走路][上], [小][動物的] → [小動物][的].
        if ((b.text.length == 2 || b.text.length == 3) && b.text != "時間" && b.text.last() in LocationSuffixChars &&
            b.text.dropLast(1).all(::isKanji) &&
            (isNounOrCommonNoun(a.partOfSpeech) || a.partOfSpeech == PartOfSpeech.Prefix) &&
            (isNounOrCommonNoun(b.partOfSpeech) || b.partOfSpeech == PartOfSpeech.NaAdjective) &&
            a.text.isNotEmpty() && a.text.all(::isKanji) &&
            (a.text.length >= 2 || a.partOfSpeech == PartOfSpeech.Prefix) &&
            // A numeral head means B is a duration/counter word (二十|時間), never a theft.
            !a.text.all(JapaneseTextHelper::isNumeralChar) &&
            nonName(a.text + b.text.dropLast(1)) &&
            // B heading its own compound with what follows is genuine (人類|史上|初); 滑走|路上|で is not.
            !(i + 2 < toks.size && nonName(b.text + toks[i + 2].text)) &&
            // A much more frequent B is its own word (国内 far outranks 日本国, so 日本|国内 stays).
            !run {
                val bRank = getNonNameCompoundFrequencyRank?.invoke(b.text) ?: return@run false
                val extRank = getNonNameCompoundFrequencyRank?.invoke(a.text + b.text.dropLast(1))
                extRank == null || bRank * BoundaryTheftRankDominanceFactor < extRank
            }) {
            val extended = a.text + b.text.dropLast(1)
            val suffix = b.text.takeLast(1)
            val extendedEnd = if (a.endOffset >= 0) a.endOffset + b.text.length - 1 else -1

            val l = list ?: ArrayList(wordInfos).also { list = it }
            l[i] = a.copy().apply {
                text = extended
                dictionaryForm = extended
                normalizedForm = extended
                reading = ""
                partOfSpeech = PartOfSpeech.Noun
                endOffset = extendedEnd
            }
            l[i + 1] = b.copy().apply {
                text = suffix
                dictionaryForm = suffix
                normalizedForm = suffix
                reading = ""
                partOfSpeech = PartOfSpeech.Suffix
                startOffset = extendedEnd
            }
            continue
        }

        // [その時][系列] → [その][時系列].
        if (a.partOfSpeech == PartOfSpeech.Expression && a.text.length == 3 &&
            a.text.substring(0, 2) in DemonstrativeHeads &&
            isKanji(a.text[2]) &&
            b.text.isNotEmpty() && b.text.all(::isKanji) &&
            isNounOrCommonNoun(b.partOfSpeech) &&
            nonName(a.text[2].toString() + b.text)) {
            val stolen = a.text[2]
            val demonstrative = a.text.substring(0, 2)
            val extendedNoun = stolen.toString() + b.text

            val l = list ?: ArrayList(wordInfos).also { list = it }
            l[i] = a.copy().apply {
                text = demonstrative
                dictionaryForm = demonstrative
                normalizedForm = demonstrative
                // Keep the first two kana; the stolen kanji's reading length varies (時=トキ, 日=ヒ).
                reading = if (a.reading.length >= 2) a.reading.substring(0, 2) else ""
                partOfSpeech = PartOfSpeech.PrenounAdjectival
                endOffset = if (a.endOffset >= 0) a.endOffset - 1 else -1
            }
            l[i + 1] = b.copy().apply {
                text = extendedNoun
                dictionaryForm = extendedNoun
                normalizedForm = extendedNoun
                reading = ""
                startOffset = if (b.startOffset >= 0) b.startOffset - 1 else -1
            }
        }
    }

    return list ?: wordInfos
}

// An OOV intensifier X返る (のさばり返る) drops whole; split to head verb + 返る. Attested 静まり返る fails the lookup gate.
internal fun MorphologicalAnalyser.repairIntensifierKaeru(words: MutableList<WordInfo>): MutableList<WordInfo> =
    if (hasNonNameCompoundLookup == null) words
    else scanRewrite(words) { tokens, i, _, output -> tryRepairIntensifierKaeru(tokens, i, output) }

private fun MorphologicalAnalyser.tryRepairIntensifierKaeru(
    tokens: MutableList<WordInfo>, i: Int, output: () -> MutableList<WordInfo>,
): Int {
    val lookup = hasNonNameCompoundLookup!!
    val w = tokens[i]
    if (!(w.partOfSpeech == PartOfSpeech.Verb && w.preMatchedWordId == null &&
            w.normalizedForm.length >= 4 && w.text.length >= 3 &&
            w.normalizedForm.endsWith("返る") &&
            !lookup(w.normalizedForm)))
        return 0

    // The head is the normalised form minus 返る, a kana-stable renyoukei (のさばり → のさばる).
    val headStem = w.normalizedForm.dropLast(2)
    var found: String? = null
    for (f in Deconjugator.instance.deconjugate(headStem))
        if (f.tags.any { it.startsWith("v") } && lookup(f.text)) { found = f.text; break }

    // Surface must open with the same kana head (のさばりかえっ).
    val headDict = found
    if (headDict == null || !w.text.startsWith(headStem) || w.text.length <= headStem.length)
        return 0

    val result = output()
    val suffixSurface = w.text.substring(headStem.length)
    val cut = if (w.startOffset >= 0) w.startOffset + headStem.length else -1
    result.add(w.copy().apply {
        text = headStem; dictionaryForm = headDict; normalizedForm = headDict
        reading = ""; partOfSpeech = PartOfSpeech.Verb; endOffset = cut
    })
    // Folds て/た into 返って so it is read neither as quotative って nor as 却って.
    if (suffixSurface.endsWith("っ") && i + 1 < tokens.size && (tokens[i + 1].text == "て" || tokens[i + 1].text == "た")) {
        val next = tokens[i + 1]
        result.add(w.copy().apply {
            text = suffixSurface + next.text; dictionaryForm = "返る"; normalizedForm = "返る"
            reading = ""; partOfSpeech = PartOfSpeech.Verb; preMatchedWordId = 1512150
            // Deconjugation is kana-side, so the chain is recovered against かえる, not 返る.
            preMatchedConjugations = pinnedConjugationProcess(suffixSurface + next.text, "かえる")
            startOffset = cut; endOffset = next.endOffset
        })
        return 2
    }

    result.add(w.copy().apply {
        text = suffixSurface; dictionaryForm = "返る"; normalizedForm = "返る"
        reading = ""; partOfSpeech = PartOfSpeech.Verb; preMatchedWordId = 1512150
        preMatchedConjugations = pinnedConjugationProcess(suffixSurface, "かえる")
        startOffset = cut; endOffset = w.endOffset
    })
    return 1
}

// A numeral claims 段 (一段|飛ばし), stranding 飛ばし on the fraud noun; reform 段飛ばし. A blanket 段+X rule would split 三段跳び.
internal fun MorphologicalAnalyser.repairDanTobashi(words: MutableList<WordInfo>): MutableList<WordInfo> =
    scanRewrite(words) { tokens, i, _, output ->
        val w = tokens[i]
        val b = if (i + 1 < tokens.size) tokens[i + 1] else null
        if (!(b != null && b.text == "飛ばし" &&
                w.preMatchedWordId == null && b.preMatchedWordId == null &&
                w.text.endsWith("段")))
            return@scanRewrite 0

        val head = w.text.dropLast(1)
        if (head.isEmpty()) {
            output().add(b.copy().apply {
                text = "段飛ばし"; dictionaryForm = "段飛ばし"; normalizedForm = "段飛ばし"
                reading = "ダントバシ"; partOfSpeech = PartOfSpeech.Noun
                startOffset = w.startOffset; endOffset = b.endOffset; preMatchedWordId = 2746000
            })
            return@scanRewrite 2
        }
        // 何段/数段 ("how many / several steps") count as numeral heads.
        if (head.all { JapaneseTextHelper.isNumeralChar(it) || it == '何' || it == '数' }) {
            val result = output()
            val cut = if (w.startOffset >= 0) w.startOffset + head.length else -1
            result.add(w.copy().apply {
                text = head; dictionaryForm = head; normalizedForm = head; reading = ""; endOffset = cut
            })
            result.add(b.copy().apply {
                text = "段飛ばし"; dictionaryForm = "段飛ばし"; normalizedForm = "段飛ばし"
                reading = "ダントバシ"; partOfSpeech = PartOfSpeech.Noun
                startOffset = cut; endOffset = b.endOffset; preMatchedWordId = 2746000
            })
            return@scanRewrite 2
        }
        0
    }

internal fun MorphologicalAnalyser.repairTankaToTaNKa(words: MutableList<WordInfo>): MutableList<WordInfo> {
    val wordInfos = words
    val result = ArrayList<WordInfo>(wordInfos.size + 4)
    val deconj = Deconjugator.instance

    fun kaToken(start: Int, end: Int) = WordInfo().apply {
        text = "か"; dictionaryForm = "か"; partOfSpeech = PartOfSpeech.Particle; reading = "か"
        startOffset = start; endOffset = end
    }

    fun getPrevTokenIndex(offset: Int): Int {
        var count = 0
        for (j in result.size - 1 downTo 0) {
            if (result[j].partOfSpeech == PartOfSpeech.SupplementarySymbol) continue
            count++
            if (count == offset) return j
        }
        return -1
    }

    fun getPrevToken(offset: Int): WordInfo? = getPrevTokenIndex(offset).let { if (it >= 0) result[it] else null }

    for (i in wordInfos.indices) {
        val word = wordInfos[i]

        // Noun たか after a verb stem is past た + か (言い過ぎ|たか); 鷹/高 after を/の never forms a past tense.
        if (word.partOfSpeech == PartOfSpeech.Noun && word.text == "たか" && result.size > 0) {
            val prevTok = result[result.size - 1]
            val pastForms = deconj.deconjugate(normalizeToHiragana(prevTok.text + "た"))
            val validPast = pastForms.any { f ->
                f.process.any { it == "past" } && f.tags.any { it.startsWith("v") }
            }
            if (validPast) {
                result[result.size - 1] = prevTok.copy().apply {
                    text = prevTok.text + "た"
                    partOfSpeech = PartOfSpeech.Verb
                    reading = if (prevTok.reading.isEmpty()) prevTok.reading else prevTok.reading + "タ"
                    endOffset = if (word.startOffset >= 0) word.startOffset + 1 else prevTok.endOffset
                }
                result.add(WordInfo().apply {
                    text = "か"; dictionaryForm = "か"; normalizedForm = "か"
                    partOfSpeech = PartOfSpeech.Particle; reading = "カ"
                    startOffset = if (word.startOffset >= 0) word.startOffset + 1 else -1
                    endOffset = word.endOffset
                })
                continue
            }
        }

        if (word.partOfSpeech != PartOfSpeech.Noun || word.text != "たんか") {
            result.add(word)
            continue
        }

        // An adjacent を or a possessive の marks the real noun (たんかを吐く, お島の方のたんか).
        if (i + 1 < wordInfos.size && wordInfos[i + 1].text == "を") {
            result.add(word)
            continue
        }

        if (result.size > 0 && result[result.size - 1].text == "を") {
            result.add(word)
            continue
        }

        if (result.size > 0 && result[result.size - 1].text == "の") {
            result.add(word)
            continue
        }

        var shouldSplit = false
        val prev = getPrevToken(1)
        val nStart = if (word.startOffset >= 0) word.startOffset + 1 else -1
        val nEnd = if (word.startOffset >= 0) word.startOffset + 2 else -1

        if (prev != null) {
            // 云う|たんか → 云うた|ん|か.
            if (prev.partOfSpeech == PartOfSpeech.Verb) {
                val candidateText = prev.text + "た"
                val forms = deconj.deconjugate(normalizeToHiragana(candidateText))
                if (forms.any { f -> f.tags.any { it.startsWith('v') } }) shouldSplit = true
            }

            // Any て/で ending takes the た, even one Sudachi tags IAdjective (怖がって|たんか → 怖がってた).
            if (!shouldSplit && (prev.text.endsWith('て') || prev.text.endsWith('で'))) shouldSplit = true

            // Kansai てもうた (ハズレて|もう|たんか → ハズレてもうた|ん|か); matched by text since もう's POS varies.
            if (prev.text == "もう") {
                val verbBefore = getPrevToken(2)
                if (verbBefore != null && (verbBefore.text.endsWith('て') || verbBefore.text.endsWith('で'))) {
                    val combinedText = verbBefore.text + "もうた"
                    val prevIdx = getPrevTokenIndex(1)
                    val verbIdx = getPrevTokenIndex(2)
                    if (prevIdx >= 0 && verbIdx >= 0) {
                        if (prevIdx > verbIdx) {
                            result.removeAt(prevIdx)
                            result.removeAt(verbIdx)
                        } else {
                            result.removeAt(verbIdx)
                            result.removeAt(prevIdx)
                        }
                    }

                    result.add(verbBefore.copy().apply { text = combinedText; partOfSpeech = PartOfSpeech.Verb; endOffset = nStart })
                    result.add(createNToken().apply { startOffset = nStart; endOffset = nEnd })
                    result.add(kaToken(nEnd, word.endOffset))
                    continue
                }
            }

            // Kansai てしもた (言うて|し|も|たんか → 言うてしもた|ん|か).
            if (prev.text == "も") {
                val shiToken = getPrevToken(2)
                if (shiToken != null && shiToken.text == "し") {
                    val verbBefore = getPrevToken(3)
                    if (verbBefore != null && (verbBefore.text.endsWith('て') || verbBefore.text.endsWith('で') ||
                            verbBefore.partOfSpeech == PartOfSpeech.Expression)) {
                        val combinedText = verbBefore.text + "しもた"
                        val moIdx = getPrevTokenIndex(1)
                        val shiIdx = getPrevTokenIndex(2)
                        val verbIdx = getPrevTokenIndex(3)
                        val indices = listOf(moIdx, shiIdx, verbIdx).filter { it >= 0 }.sortedDescending()
                        for (idx in indices) result.removeAt(idx)
                        result.add(verbBefore.copy().apply { text = combinedText; partOfSpeech = PartOfSpeech.Verb; endOffset = nStart })
                        result.add(createNToken().apply { startOffset = nStart; endOffset = nEnd })
                        result.add(kaToken(nEnd, word.endOffset))
                        continue
                    }
                }
            }
        }

        if (shouldSplit && prev != null) {
            val prevIdx = getPrevTokenIndex(1)
            if (prevIdx >= 0) {
                result[prevIdx] = prev.copy().apply { text = prev.text + "た"; partOfSpeech = PartOfSpeech.Verb; endOffset = nStart }
            }

            result.add(createNToken().apply { startOffset = nStart; endOffset = nEnd })
            result.add(kaToken(nEnd, word.endOffset))
        } else {
            result.add(word)
        }
    }

    return result
}

internal fun MorphologicalAnalyser.repairColloquialNegativeNee(words: MutableList<WordInfo>): MutableList<WordInfo> {
    val wordInfos = words
    if (wordInfos.size < 3) return wordInfos

    val result = ArrayList<WordInfo>(wordInfos.size)

    for (i in wordInfos.indices) {
        val current = wordInfos[i]

        // Sudachi splits negative ねえ into ね|え after a te-form (入って|ね|え|のに).
        if (current.text == "え" && current.partOfSpeech == PartOfSpeech.Interjection &&
            result.size >= 2 &&
            result[result.size - 1].let { it.text == "ね" && it.partOfSpeech == PartOfSpeech.Particle } &&
            result[result.size - 2].let {
                (it.partOfSpeech == PartOfSpeech.Particle && (it.text == "て" || it.text == "で")) ||
                    (it.partOfSpeech == PartOfSpeech.Verb && (it.text.endsWith('て') || it.text.endsWith('で')))
            }) {
            result[result.size - 1] = result[result.size - 1].copy().apply {
                text = "ねえ"; endOffset = current.endOffset
                partOfSpeech = PartOfSpeech.Auxiliary; dictionaryForm = "ない"
                normalizedForm = "ない"; reading = "ネエ"
            }
            continue
        }

        result.add(current)
    }

    return result
}

private val NeeNaiForms = setOf("ない", "ねえ", "ねぇ", "ねー")

/** Sudachi tags らん an adverb, blocking CombineInflections; らんない as an auxiliary reaches the deconjugator. */
internal fun MorphologicalAnalyser.repairColloquialRanNai(words: MutableList<WordInfo>): MutableList<WordInfo> {
    val wordInfos = words
    if (wordInfos.size < 3) return wordInfos

    val result = ArrayList<WordInfo>(wordInfos.size)

    var i = 0
    while (i < wordInfos.size) {
        val current = wordInfos[i]

        if (current.text == "らん" &&
            i + 1 < wordInfos.size &&
            wordInfos[i + 1].text in NeeNaiForms &&
            result.size >= 1 &&
            result[result.size - 1].let {
                (it.partOfSpeech == PartOfSpeech.Particle && (it.text == "て" || it.text == "で")) ||
                    (it.text.endsWith('て') || it.text.endsWith('で'))
            }) {
            val next = wordInfos[i + 1]
            result.add(WordInfo().apply {
                text = "らん" + next.text
                startOffset = current.startOffset
                endOffset = next.endOffset
                partOfSpeech = PartOfSpeech.Auxiliary
                dictionaryForm = "られない"
                normalizedForm = "られない"
                reading = "ラン" + next.reading
            })
            i += 2
            continue
        }

        result.add(current)
        i++
    }

    return result
}

private val KnownParticlesAndConjunctions: Set<String> =
    hashSetOf("けど", "けども", "けれど", "けれども", "ので", "のに", "から", "まで")

private const val SmallVowelKana = "ぁぃぅぇぉァィゥェォ"

/** Collapses a colloquial small-vowel stretch (ちょっとぉ, そんなぁ) back onto its dictionary surface; null = false. */
private fun MorphologicalAnalyser.tryStripTrailingSmallVowel(w: WordInfo): WordInfo? {
    val lookup = hasNonNameCompoundLookup
    if (lookup == null || w.text.length < 3 || SmallVowelKana.indexOf(w.text.last()) < 0) return null

    // A small vowel from a different row is a digraph (ファ, ティ), not a stretch.
    val row = vowelRowOf(w.text.last())
    if (row < 0 || vowelRowOf(w.text[w.text.length - 2]) != row) return null

    for (c in w.text) if (!JapaneseTextHelper.isKana(c) || c == 'ー') return null

    val stripped = w.text.dropLast(1)
    // An attested surface is lexical (ねぇ, もぉ, すげぇ), however same-vowel it looks.
    if (lookup(w.text) || !lookup(stripped)) return null

    return w.copy().apply {
        text = stripped
        dictionaryForm = if (w.dictionaryForm == w.text) stripped else w.dictionaryForm
        reading = if (w.reading.length > 1 && SmallVowelKana.indexOf(w.reading.last()) >= 0) w.reading.dropLast(1) else w.reading
    }
}

// IsTrailingSmallVowelRun, C# line 3266 (the other half of RepairStages.cs), file-private here. A trailing run of ≥2
// identical small vowels (ぇぇぇ) is expressive elongation, not part of a word. Returns coreLen, or null for false.
private fun isTrailingSmallVowelRun_p1(text: String): Int? {
    val smallVowels = "ぁぃぅぇぉ"
    var i = text.length - 1
    var runChar = '\u0000'
    var runCount = 0
    while (i >= 0 && smallVowels.indexOf(text[i]) >= 0 && (runCount == 0 || text[i] == runChar)) {
        runChar = text[i]
        runCount++
        i--
    }
    val coreLen = i + 1
    return if (runCount >= 2 && coreLen >= 1) coreLen else null
}

private fun makeInterjection(text: String) = WordInfo().apply {
    this.text = text; dictionaryForm = text; normalizedForm = text; reading = text; partOfSpeech = PartOfSpeech.Interjection
}

private fun isVerbPast(forms: List<DeconjugationForm>): Boolean =
    forms.any { f -> f.tags.any { it.startsWith("v") } && f.process.any { it == "past" } }

private fun isRuVerb(forms: List<DeconjugationForm>, expectedDictionaryHiragana: String): Boolean =
    forms.any { f -> f.text == expectedDictionaryHiragana && f.tags.any { it == "v1" || it == "v5r" } }

internal fun MorphologicalAnalyser.repairVowelElongation(words: MutableList<WordInfo>): MutableList<WordInfo> {
    val wordInfos = words
    var changed = false

    for (i in wordInfos.indices) {
        var w = wordInfos[i]

        val deElongated = tryStripTrailingSmallVowel(w)
        if (deElongated != null) {
            wordInfos[i] = deElongated
            w = deElongated
            changed = true
        }

        if (w.text.length >= 2 && w.text.last() == 'ー' &&
            (w.partOfSpeech == PartOfSpeech.Particle || w.partOfSpeech == PartOfSpeech.Conjunction)) {
            val prtStripped = w.text.dropLast(1)
            if (prtStripped.all { it in '぀'..'ゟ' }) {
                wordInfos[i] = w.copy().apply { text = prtStripped }
                changed = true
                continue
            }
        }

        // ケドー otherwise matches the KEDO organisation instead of けど.
        if (w.text.length >= 2 && w.text.last() == 'ー' && isNounOrCommonNoun(w.partOfSpeech)) {
            val body = w.text.dropLast(1)
            val allKatakana = body.isNotEmpty() && body.all { it in '゠'..'ヿ' }
            if (allKatakana) {
                val hiragana = KanaConverter.toHiragana(body)
                if (hiragana in KnownParticlesAndConjunctions) {
                    wordInfos[i] = w.copy().apply {
                        text = hiragana
                        dictionaryForm = hiragana
                        normalizedForm = hiragana
                        reading = body
                        partOfSpeech = PartOfSpeech.Conjunction
                    }
                    changed = true
                    continue
                }
            }
        }

        if ('ー' !in w.text || w.text.last() == 'ー' || w.partOfSpeech == PartOfSpeech.Interjection) continue

        if (!w.text.all { it == 'ー' || it in '぀'..'ゟ' }) continue

        val stripped = w.text.replace("ー", "")
        if (stripped.isEmpty() || stripped == w.text) continue

        val normalizedHasKanji = w.normalizedForm.any { it in '一'..'鿿' }
        if (!normalizedHasKanji && stripped != w.normalizedForm) continue

        val src = w
        wordInfos[i] = src.copy().apply {
            text = stripped
            dictionaryForm = src.dictionaryForm.replace("ー", "")
            reading = src.reading.replace("ー", "")
        }
        changed = true
    }

    if (wordInfos.size < 2) return wordInfos

    val deconjugator = Deconjugator.instance
    val result = ArrayList<WordInfo>(wordInfos.size)

    for (i in wordInfos.indices) {
        val current = wordInfos[i]

        if (result.size == 0) {
            result.add(current)
            continue
        }

        val prev = result[result.size - 1]

        // A trailing ー makes Sudachi read んー as a filler (総|ちゃ|んー → 総|ちゃん); ー is discarded.
        if ((current.partOfSpeech == PartOfSpeech.Interjection || current.partOfSpeech == PartOfSpeech.Filler) &&
            current.text.length >= 2 && current.text[0] == 'ん' &&
            current.text.substring(1).all { it == 'ー' } &&
            isNounOrCommonNoun(prev.partOfSpeech) &&
            prev.text.length <= 2 &&
            !prev.text.endsWith('ん')) {
            result[result.size - 1] = prev.copy().apply {
                text = prev.text + "ん"; endOffset = current.endOffset; partOfSpeech = PartOfSpeech.Suffix
            }
            changed = true
            continue
        }

        // Sudachi shreds kana reciprocal 〜あう into interjections (微笑み|あ|う).
        if (current.partOfSpeech == PartOfSpeech.Interjection && current.text == "う" &&
            result.size >= 2 &&
            prev.partOfSpeech == PartOfSpeech.Interjection && prev.text == "あ" &&
            result[result.size - 2].partOfSpeech == PartOfSpeech.Verb) {
            result[result.size - 1] = prev.copy().apply {
                text = "あう"; dictionaryForm = "あう"; normalizedForm = "合う"; reading = "アウ"
                partOfSpeech = PartOfSpeech.Verb
                partOfSpeechSection1 = PartOfSpeechSection.PossibleDependant
                endOffset = current.endOffset
            }
            changed = true
            continue
        }

        // Intensifying っ-prefix verb (すっ|とぼける → すっとぼける).
        if (current.partOfSpeech == PartOfSpeech.Verb &&
            (prev.partOfSpeech == PartOfSpeech.Adverb || prev.partOfSpeech == PartOfSpeech.Prefix) &&
            prev.text.length == 2 && prev.text.last() == 'っ' &&
            prev.text[0] in '぀'..'ゟ' &&
            current.dictionaryForm.isNotEmpty() &&
            (hasKanaAppropriateCompoundLookup ?: hasCompoundLookup)?.invoke(prev.text + current.dictionaryForm) == true) {
            result[result.size - 1] = current.copy().apply {
                text = prev.text + current.text
                dictionaryForm = prev.text + current.dictionaryForm
                normalizedForm = prev.text + current.dictionaryForm
                reading = prev.reading + current.reading
                startOffset = prev.startOffset
            }
            changed = true
            continue
        }

        // An elongating ー splits る-verbs (来るー → 来|る|ー, おいしすぎるー → おいし|すぎ|る|ー).
        if (current.partOfSpeech == PartOfSpeech.SupplementarySymbol && current.text == "ー" &&
            result.size >= 2 &&
            prev.text == "る" && prev.partOfSpeech == PartOfSpeech.Noun &&
            (result[result.size - 2].partOfSpeech == PartOfSpeech.Prefix || result[result.size - 2].partOfSpeech == PartOfSpeech.Suffix)) {
            val preceding = result[result.size - 2]
            val verbText = preceding.text + "る"
            result.removeAt(result.size - 1)
            result[result.size - 1] = preceding.copy().apply {
                text = verbText; endOffset = current.endOffset
                dictionaryForm = verbText; normalizedForm = verbText
                partOfSpeech = PartOfSpeech.Verb
                partOfSpeechSection1 = if (preceding.partOfSpeech == PartOfSpeech.Suffix)
                    PartOfSpeechSection.PossibleDependant else PartOfSpeechSection.None
            }
            changed = true
            continue
        }

        // Godan volitional with う lengthened to ー (泳|ご|ー → 泳ごう).
        if (current.partOfSpeech == PartOfSpeech.SupplementarySymbol && current.text == "ー" &&
            result.size >= 2 &&
            prev.text.length == 1 &&
            prev.text[0] in GodanVolitionalOKana &&
            isNounOrCommonNoun(prev.partOfSpeech) &&
            result[result.size - 2].text.isNotEmpty() &&
            result[result.size - 2].text.all { it in '一'..'鿿' }) {
            val stem = result[result.size - 2]
            val volitionalCandidate = stem.text + prev.text + "う"
            val volitionalHiragana = normalizeToHiragana(stem.reading + prev.text + "う")
            val forms = deconjugator.deconjugate(volitionalHiragana)
            val isValidVolitional = forms.any { f ->
                f.tags.any { it.startsWith("v") } && f.process.any { "volitional" in it }
            }

            if (isValidVolitional) {
                result.removeAt(result.size - 1)
                result[result.size - 1] = stem.copy().apply {
                    text = stem.text + prev.text + "う"
                    dictionaryForm = volitionalCandidate
                    normalizedForm = volitionalCandidate
                    reading = KanaConverter.toHiragana(stem.reading + prev.reading + "う")
                    partOfSpeech = PartOfSpeech.Verb
                    endOffset = current.endOffset
                }
                changed = true
                continue
            }
        }

        // Same volitional with ー fused into the kana token (遊|ぼー → 遊ぼう).
        if (current.text.length == 2 && current.text.last() == 'ー' &&
            current.text[0] in GodanVolitionalOKana &&
            prev.text.isNotEmpty() && prev.text.all { it in '一'..'鿿' }) {
            val oKana = current.text[0].toString()
            val volitionalCandidate = prev.text + oKana + "う"
            val volitionalHiragana = normalizeToHiragana(prev.reading + oKana + "う")
            val forms = deconjugator.deconjugate(volitionalHiragana)
            val isValidVolitional = forms.any { f ->
                f.tags.any { it.startsWith("v") } && f.process.any { "volitional" in it }
            }

            if (isValidVolitional) {
                result[result.size - 1] = prev.copy().apply {
                    text = prev.text + oKana + "う"
                    dictionaryForm = volitionalCandidate
                    normalizedForm = volitionalCandidate
                    reading = KanaConverter.toHiragana(prev.reading + oKana + "う")
                    partOfSpeech = PartOfSpeech.Verb
                    endOffset = current.endOffset
                }
                changed = true
                continue
            }
        }

        // An elongating ー splits i-adjective forms (早くー → 早|くー).
        if (current.partOfSpeech == PartOfSpeech.Interjection &&
            current.text.length >= 2 && current.text.last() == 'ー' &&
            current.text.dropLast(1).all { it in '぀'..'ゟ' } &&
            (prev.partOfSpeech == PartOfSpeech.Prefix || prev.partOfSpeech == PartOfSpeech.IAdjective) &&
            prev.dictionaryForm.endsWith('い')) {
            val adverbText = prev.text + current.text.dropLast(1)
            result[result.size - 1] = prev.copy().apply {
                text = adverbText; endOffset = current.endOffset
                dictionaryForm = prev.dictionaryForm
                partOfSpeech = PartOfSpeech.IAdjective
            }
            changed = true
            continue
        }

        // る-verb plus elongating う (ぶつ|かるう → ぶつかる|う).
        if (current.text.endsWith("るう") && current.text.length >= 2) {
            val verbCandidate = prev.text + current.text.dropLast(1)
            val verbHiragana = normalizeToHiragana(verbCandidate)

            // Probes both negatives (食べない for v1, ぶつからない for v5r) and requires the exact candidate back.
            val isValidRuVerb = verbHiragana.endsWith("る") &&
                (isRuVerb(deconjugator.deconjugate(verbHiragana.dropLast(1) + "ない"), verbHiragana) ||
                    isRuVerb(deconjugator.deconjugate(verbHiragana.dropLast(1) + "らない"), verbHiragana))

            if (isValidRuVerb) {
                val cutAt = if (current.endOffset >= 0) current.endOffset - 1 else -1
                result[result.size - 1] = prev.copy().apply {
                    text = verbCandidate; dictionaryForm = verbCandidate; normalizedForm = verbCandidate
                    reading = KanaConverter.toHiragana(prev.reading + current.text.dropLast(1)); partOfSpeech = PartOfSpeech.Verb
                    endOffset = cutAt
                }
                val interjection = makeInterjection("う")
                interjection.startOffset = cutAt
                interjection.endOffset = current.endOffset
                result.add(interjection)
                changed = true
                continue
            }
        }

        // たあ is misread as the particle と (おき|たあ → おきた|あ).
        if (current.text == "たあ") {
            val pastCandidate = prev.text + "た"
            val pastHiragana = normalizeToHiragana(pastCandidate)

            if (isVerbPast(deconjugator.deconjugate(pastHiragana))) {
                val cutAt = if (current.startOffset >= 0) current.startOffset + 1 else -1
                result[result.size - 1] = prev.copy().apply {
                    text = pastCandidate; reading = KanaConverter.toHiragana(prev.reading + "た"); partOfSpeech = PartOfSpeech.Verb
                    endOffset = cutAt
                }
                val interjection = makeInterjection("あ")
                interjection.startOffset = cutAt
                interjection.endOffset = current.endOffset
                result.add(interjection)
                changed = true
                continue
            }
        }

        // A past tense before ああ gets misread as a na-adjective (いきた|ああ).
        if (current.text == "ああ") {
            val prevHiragana = normalizeToHiragana(prev.text)

            if (prevHiragana.endsWith("た") || prevHiragana.endsWith("だ")) {
                if (isVerbPast(deconjugator.deconjugate(prevHiragana)) && prev.partOfSpeech != PartOfSpeech.Verb) {
                    result[result.size - 1] = prev.copy().apply { partOfSpeech = PartOfSpeech.Verb }
                    changed = true
                }
            }
        }

        // Small-vowel run fused onto a conjugation ending (来|やが|れぇぇぇ → やがれ); a noun stem (移|れぇぇぇ) is left.
        // (As in C#, this reads prev, not result's last element, which the ああ branch may have replaced.)
        if ((prev.partOfSpeech == PartOfSpeech.Verb || prev.partOfSpeech == PartOfSpeech.Auxiliary) &&
            (isNounOrCommonNoun(current.partOfSpeech) || current.partOfSpeech == PartOfSpeech.Interjection)) {
            val coreLen = isTrailingSmallVowelRun_p1(current.text)
            if (coreLen != null) {
                val core = current.text.substring(0, coreLen)
                val candidate = normalizeToHiragana(prev.text + core)
                val prevDictHiragana = normalizeToHiragana(prev.dictionaryForm)
                val valid = candidate != prevDictHiragana && deconjugator.deconjugate(candidate).any { f ->
                    f.text == prevDictHiragana && f.tags.any { it.startsWith("v") }
                }

                if (valid) {
                    result[result.size - 1] = prev.copy().apply {
                        text = prev.text + core
                        reading = prev.reading + WanaKana.toKatakana(core)
                        endOffset = if (current.startOffset >= 0) current.startOffset + coreLen else prev.endOffset
                    }
                    changed = true
                    continue
                }
            }
        }

        result.add(current)
    }

    return if (changed) result else wordInfos
}

internal fun MorphologicalAnalyser.repairNTokenisation(words: MutableList<WordInfo>): MutableList<WordInfo> {
    val wordInfos = words
    if (wordInfos.size < 2) return wordInfos

    var split: MutableList<WordInfo>? = null
    for (idx in wordInfos.indices) {
        val word = wordInfos[idx]
        val plusOne = if (word.startOffset >= 0) word.startOffset + 1 else -1

        if (word.text.length > 1 && word.text[0] == 'ん') {
            val remainder = word.text.substring(1)
            val startsWithSuffix = NCompoundSuffixes.any { remainder.startsWith(it) }

            if (startsWithSuffix) {
                val s = split ?: copyAccumulatorUpTo(wordInfos, idx).also { split = it }
                val nToken = createNToken()
                nToken.startOffset = word.startOffset
                nToken.endOffset = plusOne
                if (word.partOfSpeech == PartOfSpeech.Interjection) nToken.dictionaryForm = "の"
                s.add(nToken)
                s.add(word.copy().apply {
                    text = remainder; dictionaryForm = remainder
                    normalizedForm = remainder; reading = remainder
                    startOffset = plusOne
                })
                continue
            }
        }

        if (word.text.length > 1 && word.text[0] == 'だ') {
            val sp = split
            val prevEmitted = if (sp != null) sp.lastOrNull() else if (idx > 0) wordInfos[idx - 1] else null
            if (prevEmitted != null && (prevEmitted.text == "ん" || prevEmitted.text.endsWith('ん'))) {
                val remainder = word.text.substring(1)
                if (remainder in DaCompoundSuffixes) {
                    val s = split ?: copyAccumulatorUpTo(wordInfos, idx).also { split = it }
                    val daToken = createDaToken()
                    daToken.startOffset = word.startOffset
                    daToken.endOffset = plusOne
                    s.add(daToken)
                    s.add(word.copy().apply {
                        text = remainder; dictionaryForm = remainder
                        normalizedForm = remainder; reading = remainder
                        startOffset = plusOne
                    })
                    continue
                }
            }
        }

        // Appearance/hearsay そうだ must be split for the combine stages.
        if (word.text == "そうだ" && word.partOfSpeech == PartOfSpeech.Adverb) {
            val s = split ?: copyAccumulatorUpTo(wordInfos, idx).also { split = it }
            val plusTwo = if (word.startOffset >= 0) word.startOffset + 2 else -1
            s.add(word.copy().apply {
                text = "そう"; dictionaryForm = "そう"; normalizedForm = "そう"; reading = "そう"
                partOfSpeech = PartOfSpeech.Auxiliary; partOfSpeechSection1 = PartOfSpeechSection.AuxiliaryVerbStem
                endOffset = plusTwo
            })
            val daToken = createDaToken()
            daToken.startOffset = plusTwo
            daToken.endOffset = word.endOffset
            s.add(daToken)
            continue
        }

        split?.add(word)
    }

    val source = split ?: wordInfos
    var result: MutableList<WordInfo>? = null
    var changed = false
    val deconj = Deconjugator.instance

    var i = 0
    while (i < source.size) {
        val current = source[i]

        // 飲ん|だ is past, but たくさん|で and さん|だ take the copula.
        if (current.text.endsWith('ん') && current.text.length > 1 && current.text != "ん" &&
            !isNaAdjectiveToken(current) &&
            current.partOfSpeech != PartOfSpeech.Suffix &&
            !normalizeToHiragana(current.dictionaryForm).endsWith('ん') &&
            i + 1 < source.size && (source[i + 1].text == "だ" || source[i + 1].text == "で")) {
            val next = source[i + 1]
            val candidateText = current.text + next.text
            if (isNdaVerbForm(deconj.deconjugate(normalizeToHiragana(candidateText)))) {
                val candidateReading = KanaConverter.toHiragana(current.reading + next.reading)
                val r = result ?: copyAccumulatorUpTo(source, i).also { result = it }
                r.add(current.copy().apply {
                    text = candidateText; partOfSpeech = PartOfSpeech.Verb
                    normalizedForm = candidateText; reading = candidateReading
                    endOffset = next.endOffset
                })
                changed = true
                i += 2
                continue
            }
        }

        if (current.text == "ん" && (result?.let { it.size > 0 } ?: (i > 0))) {
            val r = result ?: copyAccumulatorUpTo(source, i).also { result = it }
            var combined = false

            // Explanatory のだ needs the 連体形 (するんだ), so kana し+んだ is 死んだ (なかまもすべてしんだ).
            val prevShi = r.lastOrNull()
            if (i + 1 < source.size && (source[i + 1].text == "だ" || source[i + 1].text == "で") &&
                prevShi != null && prevShi.text == "し" && prevShi.partOfSpeech == PartOfSpeech.Verb &&
                (prevShi.dictionaryForm == "する" || prevShi.dictionaryForm == "為る")) {
                val next = source[i + 1]
                r[r.size - 1] = prevShi.copy().apply {
                    text = "しん" + next.text
                    dictionaryForm = "死ぬ"; normalizedForm = "死ぬ"
                    reading = "シン" + next.reading
                    endOffset = next.endOffset
                }
                changed = true
                i += 2
                continue
            }

            if (i + 1 < source.size && (source[i + 1].text == "だ" || source[i + 1].text == "で") &&
                current.dictionaryForm != "ぬ" && current.dictionaryForm != "の" && current.dictionaryForm != "ん") {
                val suffix = "ん" + source[i + 1].text
                val suffixReading = "ん" + source[i + 1].reading
                val combinedWord = tryCombineWithLookback(r, suffix, suffixReading, deconj, ::isNdaVerbForm)
                if (combinedWord != null) {
                    combinedWord.endOffset = source[i + 1].endOffset
                    r.add(combinedWord)
                    combined = true
                    i++
                }
            }

            // Sudachi can tag the verb stem a noun and ん explanatory (喜|んだだろうね).
            val lookup = hasCompoundLookup
            if (!combined && i + 1 < source.size && (source[i + 1].text == "だ" || source[i + 1].text == "で") &&
                (current.dictionaryForm == "の" || current.dictionaryForm == "ん") &&
                r.size > 0 && isNounOrCommonNoun(r[r.size - 1].partOfSpeech) &&
                lookup != null) {
                val prev = r[r.size - 1]
                for (ending in arrayOf("ぶ", "む", "ぬ", "ぐ")) {
                    if (lookup(prev.text + ending) || lookup(normalizeToHiragana(prev.text) + ending)) {
                        val next = source[i + 1]
                        val candidateText = prev.text + "ん" + next.text
                        val candidateReading = KanaConverter.toHiragana(prev.reading + "ん" + next.reading)
                        r.removeAt(r.size - 1)
                        r.add(prev.copy().apply {
                            text = candidateText; partOfSpeech = PartOfSpeech.Verb
                            normalizedForm = candidateText; reading = candidateReading
                            endOffset = next.endOffset
                            partOfSpeechSection1 = PartOfSpeechSection.None
                            partOfSpeechSection2 = PartOfSpeechSection.None
                            partOfSpeechSection3 = PartOfSpeechSection.None
                        })
                        combined = true
                        i++
                        break
                    }
                }
            }

            // Sudachi tags an ichidan-stem negative (足り+ん) explanatory, impossible since that ん needs the 連体形.
            val last = r.lastOrNull()
            val misreadStemNegative = (current.dictionaryForm == "の" || current.dictionaryForm == "ん") &&
                last != null &&
                last.partOfSpeech == PartOfSpeech.Verb &&
                last.text.isNotEmpty() &&
                last.text != last.dictionaryForm &&
                // After a past/te-form (死んだ+ん, 読んで+ん) ん is explanatory or a slurred ている.
                last.text.last() !in "ただてで" &&
                // する's negative is せん; しん is 死ぬ material and すんだ is するんだ.
                !((last.text == "し" || last.text == "す") && (last.dictionaryForm == "する" || last.dictionaryForm == "為る")) &&
                // ある's negative is never あん; あんだ is あるんだ.
                !(last.text == "あ" && (last.dictionaryForm == "ある" || last.dictionaryForm == "有る" || last.dictionaryForm == "在る"))

            val negativeWord = if (!combined && (current.dictionaryForm == "ぬ" || misreadStemNegative))
                tryCombineWithLookback(r, "ん", "ん", deconj, ::isAnyVerbForm) else null
            if (negativeWord != null) {
                negativeWord.endOffset = current.endOffset
                // The deconjugator alone prefers the shorter slurred-る path (してん), so chain selection needs this.
                negativeWord.isSlurredNegative = true

                // [し, ませ] + ん → しません.
                if (negativeWord.text.endsWith("ません") && r.size > 0) {
                    val verbStem = r[r.size - 1]
                    val candidateText = verbStem.text + negativeWord.text
                    val candidateHiragana = normalizeToHiragana(candidateText)
                    val forms = deconj.deconjugate(candidateHiragana)
                    if (isMasenVerbForm(forms)) {
                        r.removeAt(r.size - 1)
                        negativeWord.text = candidateText
                        negativeWord.startOffset = verbStem.startOffset
                        negativeWord.dictionaryForm = verbStem.dictionaryForm
                        negativeWord.normalizedForm = candidateText
                        negativeWord.reading = KanaConverter.toHiragana(verbStem.reading + negativeWord.reading)
                        if (verbStem.hasPartOfSpeechSection(PartOfSpeechSection.PossibleDependant))
                            negativeWord.partOfSpeechSection1 = PartOfSpeechSection.PossibleDependant
                    }
                }

                // Lookback stops at られん and strands the stem (認め|られん); loops for chains (食べ+させられん).
                while (negativeWord.dictionaryForm in VerbIndicatingAuxiliaries && r.size > 0 &&
                    (r[r.size - 1].partOfSpeech == PartOfSpeech.Verb ||
                        (r[r.size - 1].partOfSpeech == PartOfSpeech.Auxiliary &&
                            r[r.size - 1].dictionaryForm in VerbIndicatingAuxiliaries))) {
                    val stem = r[r.size - 1]
                    val candidateText = stem.text + negativeWord.text
                    val forms = deconj.deconjugate(normalizeToHiragana(candidateText))
                    val stemTarget = normalizeToHiragana(stem.dictionaryForm)
                    if (!containsText(forms, stemTarget)) break

                    r.removeAt(r.size - 1)
                    negativeWord.text = candidateText
                    negativeWord.startOffset = stem.startOffset
                    negativeWord.dictionaryForm = stem.dictionaryForm
                    negativeWord.normalizedForm = candidateText
                    negativeWord.reading = KanaConverter.toHiragana(stem.reading + negativeWord.reading)
                    if (stem.hasPartOfSpeechSection(PartOfSpeechSection.PossibleDependant))
                        negativeWord.partOfSpeechSection1 = PartOfSpeechSection.PossibleDependant
                }

                r.add(negativeWord)
                combined = true
            }

            if (combined) changed = true
            else r.add(current)
            i++
            continue
        }

        result?.add(current)
        i++
    }

    return if (changed) result!! else source
}

// DeconjugatesToVerbInLookup: only ProcessSpecialCases (C# line 2247, the other half) calls it.
@Suppress("unused")
private fun MorphologicalAnalyser.deconjugatesToVerbInLookup(surface: String): Boolean {
    val lookup = hasVerbOrAdjectiveLookup ?: return false
    for (f in Deconjugator.instance.deconjugate(normalizeToHiragana(surface)))
        if (f.tags.any { it.startsWith("v") } && lookup(f.text)) return true
    return false
}

// Stricter than DeconjugatesToVerbInLookup so plain ichidan negatives (聞こえない, 信用ない) stay split.
// Only ProcessSpecialCases (C# line 2262, the other half) calls it.
@Suppress("unused")
private fun MorphologicalAnalyser.deconjugatesToCausativeOrPassiveVerb(surface: String): Boolean {
    val lookup = hasVerbOrAdjectiveLookup ?: return false
    for (f in Deconjugator.instance.deconjugate(normalizeToHiragana(surface)))
        if (f.tags.any { it.startsWith("v") } && lookup(f.text) && f.process.any { "causative" in it || "passive" in it })
            return true
    return false
}

/** さっき loses its き to a following きみ (さっきみごと → さっ|きみ|ごと); み rejoins the next token when that attests. */
internal fun MorphologicalAnalyser.repairSakkiMoraTheft(words: MutableList<WordInfo>): MutableList<WordInfo> =
    if (hasCompoundLookup == null) words
    else scanRewrite(words) { tokens, i, _, output -> tryRepairSakkiMoraTheft(tokens, i, output) }

private fun MorphologicalAnalyser.tryRepairSakkiMoraTheft(
    tokens: MutableList<WordInfo>, i: Int, output: () -> MutableList<WordInfo>,
): Int {
    val word = tokens[i]
    if (word.text != "さっ" || i + 1 >= tokens.size || tokens[i + 1].text != "きみ") return 0

    val stolen = tokens[i + 1]
    val result = output()
    result.add(word.copy().apply {
        text = "さっき"; dictionaryForm = "さっき"; normalizedForm = "さっき"
        reading = "サッキ"; partOfSpeech = PartOfSpeech.Noun
        endOffset = if (word.startOffset >= 0) word.startOffset + 3 else -1
    })

    val stolenStart = if (stolen.startOffset >= 0) stolen.startOffset + 1 else -1
    val following = if (i + 2 < tokens.size) tokens[i + 2] else null
    if (following != null && hasCompoundLookup!!("み" + following.text)) {
        result.add(following.copy().apply {
            text = "み" + following.text
            dictionaryForm = "み" + following.text
            normalizedForm = "み" + following.text
            // Drops inherited match state; a partial reading is worse than none.
            reading = if (following.reading.isNotEmpty()) "ミ" + following.reading else ""
            partOfSpeech = PartOfSpeech.Noun
            preMatchedWordId = null
            preMatchedConjugations = null
            startOffset = stolenStart
        })
        return 3
    }

    result.add(stolen.copy().apply {
        text = "み"; dictionaryForm = "み"; normalizedForm = "み"
        reading = "ミ"; partOfSpeech = PartOfSpeech.Noun
        preMatchedWordId = null
        preMatchedConjugations = null
        startOffset = stolenStart
    })
    return 2
}

/** Pins a 3×+ mimetic run (ごろごろごろ) to its 2× entry; DictionaryForm holds the 2× key for reading index. */
internal fun MorphologicalAnalyser.collapseReduplicatedMimetic(words: MutableList<WordInfo>): MutableList<WordInfo> =
    if (getNonNameCompoundWordId == null) words
    else scanRewrite(words) { tokens, i, _, output -> tryCollapseReduplicatedMimetic(tokens, i, output) }

private fun MorphologicalAnalyser.tryCollapseReduplicatedMimetic(
    tokens: MutableList<WordInfo>, i: Int, output: () -> MutableList<WordInfo>,
): Int {
    val first = tokens[i]
    val unit = kanaUnitRepetition(first.text) ?: return 0

    var j = i + 1
    var unitCount = first.text.length / 2
    var allInterjections = first.partOfSpeech == PartOfSpeech.Interjection
    while (j < tokens.size && isRepetitionOf(tokens[j].text, unit)) {
        unitCount += tokens[j].text.length / 2
        allInterjections = allInterjections and (tokens[j].partOfSpeech == PartOfSpeech.Interjection)
        j++
    }

    // Repeated interjections (はい|はい|はい) are emphasis, and their 2× can be an unrelated word (はいはい → 這い這い).
    if (unitCount < 3 || allInterjections) return 0
    val twoXId = getNonNameCompoundWordId!!(unit + unit) ?: return 0

    val text = StringBuilder()
    val reading = StringBuilder()
    for (k in i until j) { text.append(tokens[k].text); reading.append(tokens[k].reading) }
    val end = tokens[j - 1].endOffset
    output().add(first.copy().apply {
        this.text = text.toString(); this.reading = reading.toString()
        dictionaryForm = unit + unit; normalizedForm = unit + unit
        partOfSpeech = PartOfSpeech.Adverb
        endOffset = end
        preMatchedWordId = twoXId
    })
    return j - i
}

/** C#'s IsKanaUnitRepetition(s, out unit): the unit, or null for false. */
private fun kanaUnitRepetition(s: String): String? {
    if (s.length < 2 || s.length % 2 != 0 || !JapaneseTextHelper.isAllKana(s)) return null
    val unit = s.substring(0, 2)
    return if (isRepetitionOf(s, unit)) unit else null
}

private fun isRepetitionOf(s: String, unit: String): Boolean {
    if (s.isEmpty() || s.length % 2 != 0 || !JapaneseTextHelper.isAllKana(s)) return false
    var k = 0
    while (k < s.length) {
        if (s[k] != unit[0] || s[k + 1] != unit[1]) return false
        k += 2
    }
    return true
}

// Lattice pressure blobs hiragana runs into one OOV noun (ってもんじゃねえのかよ) that segments cleanly in isolation.
internal fun MorphologicalAnalyser.retokeniseOovBlobs(words: MutableList<WordInfo>): MutableList<WordInfo> {
    val wordInfos = words
    // C#'s config/dic paths and SudachiInterop.StreamingAvailable: the analyser's Sudachi tokenizer.
    if (tokenizer == null || hasNonNameCompoundLookup == null || retokeniseOovDisabled) return wordInfos

    var blobs: MutableList<String>? = null
    var seen: HashSet<String>? = null
    for (w in wordInfos) {
        if (!isOovBlobCandidate(w)) continue
        val s = seen ?: HashSet<String>().also { seen = it }
        if (s.add(w.text)) (blobs ?: ArrayList<String>().also { blobs = it }).add(w.text)
    }
    val blobList = blobs ?: return wordInfos

    val memo: Map<String, List<WordInfo>?>
    try {
        memo = retokeniseBlobs(blobList)
    } catch (ex: Exception) {
        // A broken native context will not recover for the next blob.
        println("[Warning] RetokeniseOovBlobs: Sudachi failed, disabling retokenisation for this parse: ${ex.message}")
        retokeniseOovDisabled = true
        return wordInfos
    }

    var result: MutableList<WordInfo>? = null
    for (i in wordInfos.indices) {
        val w = wordInfos[i]
        val retok = memo[w.text]
        if (retok != null && retok.size > 1 && isOovBlobCandidate(w)) {
            val r = result ?: copyAccumulatorUpTo(wordInfos, i).also { result = it }
            var off = w.startOffset
            for (rt in retok) {
                // Memoized pieces are spliced at every occurrence and later stages mutate tokens in place.
                val piece = rt.copy()
                piece.startOffset = if (off >= 0) off else -1
                off = if (off >= 0) off + piece.text.length else -1
                piece.endOffset = if (off >= 0) off else -1
                r.add(piece)
            }
            continue
        }
        result?.add(w)
    }
    return result ?: wordInfos
}

private fun MorphologicalAnalyser.isOovBlobCandidate(w: WordInfo): Boolean =
    w.text.length >= 5 &&
        isNounOrCommonNoun(w.partOfSpeech) &&
        w.text.all { it in 'ぁ'..'ゟ' || it == 'ー' } &&
        !hasNonNameCompoundLookup!!(w.text) &&
        // A resolvable, different dictionary form marks an earlier repair's merge (わがままな → わがまま), not an OOV blob.
        (w.dictionaryForm == w.text || w.dictionaryForm.isEmpty() || !hasNonNameCompoundLookup!!(w.dictionaryForm))

/**
 * C# sends all blobs in one FFI call, one per line, and the native side analyses each line in isolation (its per-blob
 * fallback gives the same pieces); Sudachi Java tokenises each blob on its own, built as runSudachi builds tokens.
 * The per-request user dictionary CSV has no Sudachi Java equivalent.
 */
private fun MorphologicalAnalyser.retokeniseBlobs(blobs: List<String>): Map<String, List<WordInfo>?> {
    val tok = tokenizer!!
    val memo = HashMap<String, List<WordInfo>?>(blobs.size)
    for (blob in blobs) {
        val pieces = ArrayList<WordInfo>()
        for (sentence in tok.tokenizeSentences(sudachiMode, blob))
            for (m in sentence) pieces.add(WordInfo.fromSudachi(m, -1, -1))
        memo[blob] = pieces
    }
    return memo
}

/** なく belongs to a bound auxiliary verb stem after a て-form (出て+こ+なく), not to a following なった. */
// Only ProcessSpecialCases (C# line 2672, the other half) calls it.
@Suppress("unused")
private fun isBoundAuxiliaryNegative(w1: WordInfo, emitted: List<WordInfo>): Boolean {
    if (w1.text != "なく" || emitted.size < 2) return false

    val stem = emitted[emitted.size - 1]
    if (stem.partOfSpeech != PartOfSpeech.Verb || !stem.hasPartOfSpeechSection(PartOfSpeechSection.PossibleDependant))
        return false

    val teForm = emitted[emitted.size - 2]
    return teForm.text.endsWith('て') || teForm.text.endsWith('で')
}
