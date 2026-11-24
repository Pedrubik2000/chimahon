// Ported from Jiten (github.com/Sirush/Jiten, Apache-2.0, commit 44b48a4): Jiten.Parser/Stages/MorphologicalAnalyser.SplitStages.cs
package chimahon.lang.jiten

// C#-private helpers are file-private here; helpers other files use are marked "shared" below.

private fun hasKanjiChar(s: String) = s.any { it in '一'..'鿿' }

/** Splits Sudachi aux-verb compounds absent from JMDict into their parts (し終わっ → し + 終わっ). */
internal fun MorphologicalAnalyser.splitCompoundAuxiliaryVerbs(words: MutableList<WordInfo>): MutableList<WordInfo> {
    var result: MutableList<WordInfo>? = null

    for (i in words.indices) {
        val word = words[i]
        if (word.partOfSpeech != PartOfSpeech.Verb || word.dictionaryForm.isEmpty() || word.dictionaryForm.length < 3) {
            result?.add(word)
            continue
        }

        var matchedAux: String? = null
        for (aux in CompoundVerbSplitSuffixes) {
            if (word.dictionaryForm.endsWith(aux) && word.dictionaryForm.length > aux.length) {
                matchedAux = aux
                break
            }
        }

        if (matchedAux == null) {
            result?.add(word)
            continue
        }

        // Attested compounds stay whole so form scoring can use Sudachi's reading (滲み出す: にじみだす vs しみだす).
        val lookup = hasCompoundLookup
        if (lookup != null && lookup(word.dictionaryForm)) {
            result?.add(word)
            continue
        }

        val mainVerbDictLen = word.dictionaryForm.length - matchedAux.length
        val mainVerbDict = word.dictionaryForm.substring(0, mainVerbDictLen)

        // Main verb surface is assumed to share the dict-form prefix length (し終わっ → し + 終わっ).
        if (word.text.length <= mainVerbDictLen) {
            result?.add(word)
            continue
        }

        val mainVerbSurface = word.text.substring(0, mainVerbDictLen)
        val auxVerbSurface = word.text.substring(mainVerbDictLen)

        val auxStem = AuxiliaryVerbStems[matchedAux]
        if (auxStem == null || !auxVerbSurface.startsWith(auxStem)) {
            result?.add(word)
            continue
        }

        val mainVerb = WordInfo().apply {
            text = mainVerbSurface; dictionaryForm = mainVerbDict; normalizedForm = mainVerbDict
            partOfSpeech = PartOfSpeech.Verb; reading = KanaConverter.toHiragana(mainVerbSurface)
            startOffset = word.startOffset
            endOffset = if (word.startOffset >= 0) word.startOffset + mainVerbDictLen else -1
        }

        val auxVerb = WordInfo().apply {
            text = auxVerbSurface; dictionaryForm = matchedAux; normalizedForm = matchedAux
            partOfSpeech = PartOfSpeech.Verb; partOfSpeechSection1 = PartOfSpeechSection.PossibleDependant
            reading = KanaConverter.toHiragana(auxVerbSurface)
            startOffset = if (word.startOffset >= 0) word.startOffset + mainVerbDictLen else -1
            endOffset = word.endOffset
        }

        val list = result ?: copyAccumulatorUpTo(words, i).also { result = it }
        list.add(mainVerb)
        list.add(auxVerb)
    }

    return result ?: words
}

private fun MorphologicalAnalyser.deconjugatesToForm(surface: String, targetForm: String): Boolean {
    val target = KanaNormalizer.normalize(KanaConverter.toHiragana(targetForm))
    val hira = KanaNormalizer.normalize(KanaConverter.toHiragana(surface))
    for (f in pipelineCachedDeconjugate(hira))
        if (f.text == target) return true
    return false
}

private fun MorphologicalAnalyser.hasAttestedDeconjugatedBase(text: String): Boolean {
    for (form in pipelineCachedDeconjugate(KanaConverter.toHiragana(text)))
        if (form.text.length >= 2 && form.text != text && hasNonNameCompoundLookup!!(form.text)) return true
    return false
}

/** Splits OOV compound verbs (驚き戸惑う) into stem + verb so they aren't dropped; both parts must resolve. */
internal fun MorphologicalAnalyser.splitUnresolvableCompoundVerbs(words: MutableList<WordInfo>): MutableList<WordInfo> {
    val compoundLookup = hasCompoundLookup ?: return words
    val nonNameLookup = hasNonNameCompoundLookup ?: return words

    var result: MutableList<WordInfo>? = null

    for (idx in words.indices) {
        val word = words[idx]
        val dictForm = word.dictionaryForm

        if (word.partOfSpeech != PartOfSpeech.Verb || dictForm.isEmpty() || dictForm.length < 4 ||
            word.text.length < 2 || !hasKanjiChar(word.text)) {
            result?.add(word)
            continue
        }

        // The surface check keeps renyokei compounds that exist as nouns (買い支え).
        if (compoundLookup(dictForm) ||
            (word.text != dictForm && compoundLookup(word.text)) ||
            (word.normalizedForm.isNotEmpty() && word.normalizedForm != dictForm && compoundLookup(word.normalizedForm))) {
            result?.add(word)
            continue
        }

        // Keep whole when the lemma deconjugates to an attested verb: OOV surface lemmas (手に取って), Sudachi passive lemmas (揺られる → 揺る).
        if ((dictForm != word.text || dictForm.last() !in DictionaryVerbEndings) && hasAttestedDeconjugatedBase(dictForm)) {
            result?.add(word)
            continue
        }

        var splitBase: String? = null
        var splitAt = 0
        var stemIsVerb = false

        // Longest stem first (縫い+止める beats 縫+い止める); the tail is never empty.
        var p = minOf(dictForm.length - 2, word.text.length - 1)
        while (p >= 1 && splitBase == null) {
            val prefix = dictForm.substring(0, p)
            val suffixDict = dictForm.substring(p)
            if (!word.text.startsWith(prefix) || !nonNameLookup(suffixDict)) { p--; continue }

            val ichidan = prefix + 'る'
            if (nonNameLookup(ichidan)) {
                splitBase = ichidan; splitAt = p; stemIsVerb = true
                break
            }

            val baseEnd = RenyokeiToGodanBase[prefix.last()]
            if (baseEnd != null) {
                val godan = prefix.dropLast(1) + baseEnd
                if (nonNameLookup(godan)) {
                    splitBase = godan; splitAt = p; stemIsVerb = true
                    break
                }
            }

            // Non-verb lead (薄ら+笑う, もの+悲しむ) only when the tail is a verb/adjective, so the conjugation lands on it.
            if (prefix.length >= 2 && nonNameLookup(prefix) && hasVerbOrAdjectiveLookup?.invoke(suffixDict) == true) {
                splitBase = prefix; splitAt = p; stemIsVerb = false
            }
            p--
        }

        val stemBase = splitBase
        if (stemBase == null) {
            result?.add(word)
            continue
        }

        val list = result ?: copyAccumulatorUpTo(words, idx).also { result = it }

        val at = splitAt
        val stemSurface = word.text.substring(0, at)
        val tailSurface = word.text.substring(at)

        list.add(WordInfo().apply {
            text = stemSurface; dictionaryForm = stemBase; normalizedForm = stemBase
            partOfSpeech = if (stemIsVerb) PartOfSpeech.Verb else PartOfSpeech.Unknown
            reading = KanaConverter.toHiragana(stemSurface)
            startOffset = word.startOffset
            endOffset = if (word.startOffset >= 0) word.startOffset + at else -1
        })
        list.add(WordInfo().apply {
            text = tailSurface; dictionaryForm = word.dictionaryForm.substring(at); normalizedForm = word.dictionaryForm.substring(at)
            partOfSpeech = PartOfSpeech.Verb; reading = KanaConverter.toHiragana(tailSurface)
            startOffset = if (word.startOffset >= 0) word.startOffset + at else -1
            endOffset = word.endOffset
        })
    }

    return result ?: words
}

/** Splits OOV 連用形 V+V nouns (撃ち漏らし → 撃つ + 漏らす); both halves must be verbs so plain nouns stay whole. */
internal fun MorphologicalAnalyser.splitUnresolvableRenyokeiNounCompounds(words: MutableList<WordInfo>): MutableList<WordInfo> {
    val compoundLookup = hasCompoundLookup ?: return words
    if (hasNonNameCompoundLookup == null) return words

    var result: MutableList<WordInfo>? = null

    for (idx in words.indices) {
        val word = words[idx]

        if ((word.partOfSpeech != PartOfSpeech.Noun && word.partOfSpeech != PartOfSpeech.CommonNoun) ||
            word.text.length < 3 ||
            !hasKanjiChar(word.text) ||
            compoundLookup(word.text) ||
            (word.normalizedForm.isNotEmpty() && word.normalizedForm != word.text && compoundLookup(word.normalizedForm))) {
            result?.add(word)
            continue
        }

        var at = -1
        var lBase = ""
        var rBase = ""
        // Longest left stem first so 撃ち|漏らし beats a shorter coincidental cut.
        var p = word.text.length - 1
        while (p >= 2 && at < 0) {
            val leftBase = renyokeiSurfaceToVerb(word.text.substring(0, p))
            val rightBase = leftBase?.let { renyokeiSurfaceToVerb(word.text.substring(p)) }
            if (leftBase != null && rightBase != null) { at = p; lBase = leftBase; rBase = rightBase }
            p--
        }

        if (at < 0) {
            result?.add(word)
            continue
        }

        val list = result ?: copyAccumulatorUpTo(words, idx).also { result = it }
        val leftSurface = word.text.substring(0, at)
        val rightSurface = word.text.substring(at)
        // Readings stay empty: the scorer skips empty ones, while a copied kanji surface would mismatch every kana form.
        list.add(WordInfo().apply {
            text = leftSurface; dictionaryForm = lBase; normalizedForm = lBase
            partOfSpeech = PartOfSpeech.Verb; reading = ""
            startOffset = word.startOffset
            endOffset = if (word.startOffset >= 0) word.startOffset + at else -1
        })
        list.add(WordInfo().apply {
            text = rightSurface; dictionaryForm = rBase; normalizedForm = rBase
            partOfSpeech = PartOfSpeech.Verb; reading = ""
            startOffset = if (word.startOffset >= 0) word.startOffset + at else -1
            endOffset = word.endOffset
        })
    }

    return result ?: words
}

// Each prefix must have its own JMDict entry.
private val AdjectivePrefixes = arrayOf("真っ", "薄", "真", "ほの", "ど", "超")

/** Splits OOV prefixed i-adjectives (薄赤い → 薄+赤い); resegmentation skips IAdjective, so they'd be dropped. */
internal fun MorphologicalAnalyser.splitUnresolvablePrefixedAdjectives(words: MutableList<WordInfo>): MutableList<WordInfo> {
    val compoundLookup = hasCompoundLookup ?: return words
    val nonNameLookup = hasNonNameCompoundLookup ?: return words

    var result: MutableList<WordInfo>? = null

    for (idx in words.indices) {
        val word = words[idx]
        val dictForm = word.dictionaryForm

        var matched: String? = null
        if (word.partOfSpeech == PartOfSpeech.IAdjective &&
            dictForm.isNotEmpty() && dictForm.length >= 3 &&
            !compoundLookup(dictForm) &&
            (dictForm == word.text || !compoundLookup(word.text)) &&
            // Slang whose NormalizedForm is attested (どでけえ → どでかい) resolves via deconjugation.
            (word.normalizedForm.isEmpty() || word.normalizedForm == dictForm || !compoundLookup(word.normalizedForm))) {
            for (p in AdjectivePrefixes) {
                if (dictForm.startsWith(p) && word.text.startsWith(p) && dictForm.length - p.length >= 2 &&
                    nonNameLookup(dictForm.substring(p.length))) {
                    matched = p
                    break
                }
            }
        }

        val prefix = matched
        if (prefix == null) {
            result?.add(word)
            continue
        }

        val list = result ?: copyAccumulatorUpTo(words, idx).also { result = it }

        list.add(WordInfo().apply {
            text = prefix; dictionaryForm = prefix; normalizedForm = prefix
            partOfSpeech = PartOfSpeech.Prefix; reading = KanaConverter.toHiragana(prefix)
            startOffset = word.startOffset
            endOffset = if (word.startOffset >= 0) word.startOffset + prefix.length else -1
        })
        list.add(WordInfo().apply {
            text = word.text.substring(prefix.length)
            dictionaryForm = word.dictionaryForm.substring(prefix.length)
            normalizedForm = word.dictionaryForm.substring(prefix.length)
            partOfSpeech = PartOfSpeech.IAdjective
            reading = KanaConverter.toHiragana(word.text.substring(prefix.length))
            startOffset = if (word.startOffset >= 0) word.startOffset + prefix.length else -1
            endOffset = word.endOffset
        })
    }

    return result ?: words
}

/** Splits unresolvable noun+する merges (大怪我して, no vs tag); vs nouns (密着した) stay merged. */
internal fun MorphologicalAnalyser.splitUnresolvableSuruCompounds(words: MutableList<WordInfo>): MutableList<WordInfo> {
    val compoundLookup = hasCompoundLookup ?: return words
    val nonNameLookup = hasNonNameCompoundLookup ?: return words
    val suruLookup = hasSuruVerbCompoundLookup ?: return words

    var result: MutableList<WordInfo>? = null
    val deconj = Deconjugator.instance

    for (idx in words.indices) {
        val word = words[idx]
        val txt = word.text

        if ((word.partOfSpeech != PartOfSpeech.Verb && word.partOfSpeech != PartOfSpeech.Noun) ||
            txt.length < 3 || !hasKanjiChar(txt)) {
            result?.add(word)
            continue
        }

        if (compoundLookup(txt)) {
            result?.add(word)
            continue
        }

        var splitAt = -1
        var p = txt.length - 1
        while (p >= 2 && splitAt < 0) {
            if (txt[p] != 'し' && txt[p] != 'さ' && txt[p] != 'す' && txt[p] != 'せ') { p--; continue }

            val prefix = txt.substring(0, p)
            if (!nonNameLookup(prefix)) { p--; continue }
            if (suruLookup(prefix)) break
            // Resolvable dictForm (思い出して → 思い出す) means deconjugation handles it; keep merged.
            if (word.dictionaryForm != prefix && word.dictionaryForm != txt && compoundLookup(word.dictionaryForm)) break
            // A NormalizedForm base (思い出せる → 思い出す) counts only if reachable; 話ししません claims 話す but can't.
            if (word.normalizedForm.isNotEmpty() && word.normalizedForm != prefix && word.normalizedForm != txt &&
                compoundLookup(word.normalizedForm) && deconjugatesToForm(txt, word.normalizedForm)) break

            val tail = txt.substring(p)
            for (f in deconj.deconjugate(tail)) {
                if (f.text == "する") { splitAt = p; break }
            }
            p--
        }

        if (splitAt < 0) {
            result?.add(word)
            continue
        }

        val list = result ?: copyAccumulatorUpTo(words, idx).also { result = it }

        val nounSurface = txt.substring(0, splitAt)
        val suruSurface = txt.substring(splitAt)

        list.add(WordInfo().apply {
            text = nounSurface; dictionaryForm = nounSurface; normalizedForm = nounSurface
            partOfSpeech = PartOfSpeech.Noun; reading = KanaConverter.toHiragana(nounSurface)
            startOffset = word.startOffset
            endOffset = if (word.startOffset >= 0) word.startOffset + splitAt else -1
        })
        list.add(WordInfo().apply {
            text = suruSurface; dictionaryForm = "する"; normalizedForm = "する"
            partOfSpeech = PartOfSpeech.Verb; reading = suruSurface
            startOffset = if (word.startOffset >= 0) word.startOffset + splitAt else -1
            endOffset = word.endOffset
        })
    }

    return result ?: words
}

/** Re-cuts Sudachi's イッ + たん(suffix) + だ as イッた + ん + だ; ProcessSpecialCases then merges ん + だ. */
internal fun MorphologicalAnalyser.splitTanSuffix(words: MutableList<WordInfo>): MutableList<WordInfo> {
    if (words.size < 2) return words

    val deconj = Deconjugator.instance
    val result = ArrayList<WordInfo>(words.size + 2)

    for (i in words.indices) {
        val word = words[i]

        if (word.text != "たん" || word.partOfSpeech != PartOfSpeech.Suffix ||
            i + 1 >= words.size ||
            words[i + 1].partOfSpeech != PartOfSpeech.Auxiliary ||
            (words[i + 1].dictionaryForm != "だ" && words[i + 1].dictionaryForm != "です") ||
            result.isEmpty()) {
            result.add(word)
            continue
        }

        val prev = result.last()
        var shouldSplit = false

        if (prev.text.last() == 'て' || prev.text.last() == 'で') {
            shouldSplit = true
        } else {
            val candidateText = normalizeToHiragana(prev.text + "た")
            val forms = deconj.deconjugate(candidateText)
            if (forms.any { f -> f.tags.any { it.startsWith('v') } && f.process.any { it == "past" } })
                shouldSplit = true
        }

        if (!shouldSplit) {
            result.add(word)
            continue
        }

        result[result.size - 1] = prev.copy().apply {
            text = prev.text + "た"
            endOffset = if (word.startOffset >= 0) word.startOffset + 1 else -1
        }
        result.add(WordInfo().apply {
            text = "ん"; dictionaryForm = "の"; normalizedForm = "ん"; reading = "ん"
            partOfSpeech = PartOfSpeech.Particle; partOfSpeechSection1 = PartOfSpeechSection.Juntaijoushi
            startOffset = if (word.startOffset >= 0) word.startOffset + 1 else -1
            endOffset = word.endOffset
        })
    }

    return result
}

/** Splits Sudachi's 接続助詞 たって/だって after a 連用形 into た/だ + って for deconjugation (出たって → 出 + た + って). */
internal fun MorphologicalAnalyser.splitTatteParticle(words: MutableList<WordInfo>): MutableList<WordInfo> {
    if (words.size < 2) return words

    var result: MutableList<WordInfo>? = null

    for (i in words.indices) {
        val word = words[i]

        // だな misparsed as 棚 → だ + な.
        if (word.text == "だな" && word.partOfSpeech == PartOfSpeech.Noun && word.normalizedForm == "棚") {
            val list = result ?: copyAccumulatorUpTo(words, i).also { result = it }
            list.add(WordInfo().apply {
                text = "だ"; dictionaryForm = "だ"; normalizedForm = "だ"; partOfSpeech = PartOfSpeech.Auxiliary; reading = "だ"
                startOffset = word.startOffset; endOffset = if (word.startOffset >= 0) word.startOffset + 1 else -1
            })
            list.add(WordInfo().apply {
                text = "な"; dictionaryForm = "な"; normalizedForm = "な"; partOfSpeech = PartOfSpeech.Particle
                partOfSpeechSection1 = PartOfSpeechSection.SentenceEndingParticle; reading = "な"
                startOffset = if (word.startOffset >= 0) word.startOffset + 1 else -1; endOffset = word.endOffset
            })
            continue
        }

        // かって after a predicate is か + って (飲むかってこと), not かつて; after a noun or clause-initially it stays かつて.
        if (i > 0 &&
            word.text == "かって" && word.partOfSpeech == PartOfSpeech.Adverb && word.reading == "カツテ" &&
            words[i - 1].partOfSpeech.let {
                it == PartOfSpeech.Verb || it == PartOfSpeech.IAdjective || it == PartOfSpeech.Auxiliary || it == PartOfSpeech.Expression
            }) {
            val list = result ?: copyAccumulatorUpTo(words, i).also { result = it }
            list.add(WordInfo().apply {
                text = "か"; dictionaryForm = "か"; normalizedForm = "か"
                partOfSpeech = PartOfSpeech.Particle
                partOfSpeechSection1 = PartOfSpeechSection.SentenceEndingParticle
                reading = "カ"
                startOffset = word.startOffset
                endOffset = if (word.startOffset >= 0) word.startOffset + 1 else -1
            })
            list.add(WordInfo().apply {
                text = "って"; dictionaryForm = "って"; normalizedForm = "って"
                partOfSpeech = PartOfSpeech.Particle
                partOfSpeechSection1 = PartOfSpeechSection.ConjunctionParticle
                reading = "ッテ"
                startOffset = if (word.startOffset >= 0) word.startOffset + 1 else -1
                endOffset = word.endOffset
            })
            continue
        }

        // いたって after a て/で 接続助詞 is い + た + って (見ていたって); elsewhere (clause-initial, after は) it stays 至って.
        if (i > 0 &&
            word.text == "いたって" && word.partOfSpeech == PartOfSpeech.Adverb && word.reading == "イタッテ" &&
            words[i - 1].let { prevTe ->
                prevTe.partOfSpeech == PartOfSpeech.Particle && (prevTe.text == "て" || prevTe.text == "で") &&
                    prevTe.hasPartOfSpeechSection(PartOfSpeechSection.ConjunctionParticle)
            }) {
            val list = result ?: copyAccumulatorUpTo(words, i).also { result = it }
            list.add(WordInfo().apply {
                text = "い"; dictionaryForm = "いる"; normalizedForm = "居る"
                partOfSpeech = PartOfSpeech.Verb; reading = "イ"
                startOffset = word.startOffset
                endOffset = if (word.startOffset >= 0) word.startOffset + 1 else -1
            })
            list.add(WordInfo().apply {
                text = "た"; dictionaryForm = "た"; normalizedForm = "た"
                partOfSpeech = PartOfSpeech.Auxiliary; reading = "タ"
                startOffset = if (word.startOffset >= 0) word.startOffset + 1 else -1
                endOffset = if (word.startOffset >= 0) word.startOffset + 2 else -1
            })
            list.add(WordInfo().apply {
                text = "って"; dictionaryForm = "って"; normalizedForm = "って"
                partOfSpeech = PartOfSpeech.Particle
                partOfSpeechSection1 = PartOfSpeechSection.ConjunctionParticle
                reading = "ッテ"
                startOffset = if (word.startOffset >= 0) word.startOffset + 2 else -1
                endOffset = word.endOffset
            })
            continue
        }

        if (i > 0 &&
            word.partOfSpeech == PartOfSpeech.Particle &&
            word.hasPartOfSpeechSection(PartOfSpeechSection.ConjunctionParticle) &&
            (word.text == "たって" || word.text == "だって")) {
            val prev = words[i - 1]

            if (prev.partOfSpeech == PartOfSpeech.Verb || prev.partOfSpeech == PartOfSpeech.IAdjective ||
                prev.partOfSpeech == PartOfSpeech.Auxiliary) {
                val pastMarker = if (word.text == "たって") "た" else "だ"

                val list = result ?: copyAccumulatorUpTo(words, i).also { result = it }
                list.add(WordInfo().apply {
                    text = pastMarker
                    dictionaryForm = pastMarker
                    normalizedForm = pastMarker
                    partOfSpeech = PartOfSpeech.Auxiliary
                    reading = pastMarker
                    startOffset = word.startOffset
                    endOffset = if (word.startOffset >= 0) word.startOffset + 1 else -1
                })
                list.add(WordInfo().apply {
                    text = "って"
                    dictionaryForm = "って"
                    normalizedForm = "って"
                    partOfSpeech = PartOfSpeech.Particle
                    partOfSpeechSection1 = PartOfSpeechSection.ConjunctionParticle
                    reading = "って"
                    startOffset = if (word.startOffset >= 0) word.startOffset + 1 else -1
                    endOffset = word.endOffset
                })
                continue
            }
        }

        result?.add(word)
    }

    return result ?: words
}

/** Splits OOV adverb Xと into X + と (JMDict lists 凛 bare); hiragana Xと is a deformed mimetic (ひっしと), kept. */
internal fun MorphologicalAnalyser.splitUnattestedToAdverbs(words: MutableList<WordInfo>): MutableList<WordInfo> {
    var result: MutableList<WordInfo>? = null

    for (i in words.indices) {
        val word = words[i]
        val lookup = hasNonNameCompoundLookup

        if (word.partOfSpeech == PartOfSpeech.Adverb && word.preMatchedWordId == null &&
            word.text.length >= 2 && word.text.last() == 'と' &&
            word.text[0] !in 'ぁ'..'ゟ' &&
            word.reading.length >= 2 && (word.reading.last() == 'ト' || word.reading.last() == 'と') &&
            lookup != null) {
            val wholeAttested = lookup(word.text) || lookup(normalizeToHiragana(word.text))
            val lead = word.text.dropLast(1)
            if (!wholeAttested && (lookup(lead) || lookup(normalizeToHiragana(lead)))) {
                val list = result ?: copyAccumulatorUpTo(words, i).also { result = it }
                val mid = if (word.endOffset >= 0) word.endOffset - 1 else -1
                list.add(word.copy().apply {
                    text = lead; dictionaryForm = lead; normalizedForm = lead
                    reading = word.reading.dropLast(1); endOffset = mid
                })
                list.add(WordInfo().apply {
                    text = "と"; dictionaryForm = "と"; normalizedForm = "と"; reading = "ト"
                    partOfSpeech = PartOfSpeech.Particle; partOfSpeechSection1 = PartOfSpeechSection.CaseMarkingParticle
                    startOffset = mid; endOffset = word.endOffset
                })
                continue
            }
        }

        result?.add(word)
    }

    return result ?: words
}

/** Splits たわけ after a verb-like token into た + わけ (してたわけ); 戯け "fool" after a noun stays whole. */
@Suppress("UnusedReceiverParameter")
internal fun MorphologicalAnalyser.splitTawakeNoun(words: MutableList<WordInfo>): MutableList<WordInfo> {
    val result = ArrayList<WordInfo>(words.size + 2)

    for (i in words.indices) {
        val word = words[i]

        if (word.text == "たわけ" && (word.dictionaryForm == "たわけ" || word.dictionaryForm == "たわける") && i > 0) {
            val prev = words[i - 1]
            val afterVerbContext = prev.partOfSpeech == PartOfSpeech.Verb || prev.partOfSpeech == PartOfSpeech.Auxiliary ||
                prev.partOfSpeech == PartOfSpeech.IAdjective || prev.partOfSpeech == PartOfSpeech.Particle ||
                (prev.partOfSpeech == PartOfSpeech.SupplementarySymbol && prev.text == "っ") ||
                prev.partOfSpeech == PartOfSpeech.Adverb

            if (afterVerbContext) {
                result.add(WordInfo().apply {
                    text = "た"; dictionaryForm = "た"; normalizedForm = "た"
                    partOfSpeech = PartOfSpeech.Auxiliary; reading = "た"
                    startOffset = word.startOffset
                    endOffset = if (word.startOffset >= 0) word.startOffset + 1 else -1
                })
                result.add(WordInfo().apply {
                    text = "わけ"; dictionaryForm = "わけ"; normalizedForm = "わけ"
                    partOfSpeech = PartOfSpeech.Noun; reading = "わけ"
                    startOffset = if (word.startOffset >= 0) word.startOffset + 1 else -1
                    endOffset = word.endOffset
                })
                continue
            }
        }

        result.add(word)
    }

    return result
}

/** Re-cuts どうして directly before た as どう + し + て (どうしてた contraction); どうして来たの stays whole. */
@Suppress("UnusedReceiverParameter")
internal fun MorphologicalAnalyser.splitDoushiteContraction(words: MutableList<WordInfo>): MutableList<WordInfo> {
    var result: MutableList<WordInfo>? = null

    for (i in words.indices) {
        val word = words[i]

        if (word.text == "どうして" && word.partOfSpeech == PartOfSpeech.Adverb &&
            i + 1 < words.size &&
            words[i + 1].text == "た" && words[i + 1].partOfSpeech == PartOfSpeech.Auxiliary &&
            word.startOffset >= 0) {
            val s = word.startOffset
            val list = result ?: copyAccumulatorUpTo(words, i).also { result = it }
            list.add(WordInfo().apply {
                text = "どう"; dictionaryForm = "どう"; normalizedForm = "どう"
                partOfSpeech = PartOfSpeech.Adverb; reading = "ドウ"
                startOffset = s; endOffset = s + 2
            })
            list.add(WordInfo().apply {
                text = "し"; dictionaryForm = "する"; normalizedForm = "為る"
                partOfSpeech = PartOfSpeech.Verb; reading = "シ"
                startOffset = s + 2; endOffset = s + 3
            })
            list.add(WordInfo().apply {
                text = "て"; dictionaryForm = "てる"; normalizedForm = "てる"
                partOfSpeech = PartOfSpeech.Auxiliary; reading = "テ"
                startOffset = s + 3; endOffset = s + 4
            })
            continue
        }

        result?.add(word)
    }

    return result ?: words
}

/** Re-cuts adverb もし after a 連用形 and before a negation/polite tail as も + し (かすりもしない). */
@Suppress("UnusedReceiverParameter")
internal fun MorphologicalAnalyser.splitEmphaticMoSuru(words: MutableList<WordInfo>): MutableList<WordInfo> {
    var result: MutableList<WordInfo>? = null

    for (i in words.indices) {
        val word = words[i]

        if (word.text == "もし" && word.partOfSpeech == PartOfSpeech.Adverb &&
            i > 0 &&
            words[i - 1].let { prev -> prev.partOfSpeech == PartOfSpeech.Verb && prev.text != prev.dictionaryForm } &&
            i + 1 < words.size &&
            isSuruNegationOrPoliteContinuation(words[i + 1].text) &&
            word.startOffset >= 0) {
            val s = word.startOffset
            val list = result ?: copyAccumulatorUpTo(words, i).also { result = it }
            list.add(WordInfo().apply {
                text = "も"; dictionaryForm = "も"; normalizedForm = "も"
                partOfSpeech = PartOfSpeech.Particle
                partOfSpeechSection1 = PartOfSpeechSection.BindingParticle
                reading = "モ"; startOffset = s; endOffset = s + 1
            })
            // Pinned to する: free scoring picks the homograph 四 (し).
            list.add(WordInfo().apply {
                text = "し"; dictionaryForm = "する"; normalizedForm = "為る"
                partOfSpeech = PartOfSpeech.Verb; reading = "シ"
                preMatchedWordId = 1157170
                startOffset = s + 1; endOffset = s + 2
            })
            continue
        }

        result?.add(word)
    }

    return result ?: words
}

private fun isSuruNegationOrPoliteContinuation(text: String): Boolean =
    text.startsWith("ませ") || text.startsWith("まし") || text.startsWith("ます") || text.startsWith("ない") ||
        text.startsWith("なかっ") || text.startsWith("なく") || text.startsWith("ねえ") || text.startsWith("ねぇ")

// No real noun contains りゃ; it is always a shredded conditional (て|りゃもろ…) or ありゃ/こりゃ.
private val OovGrammarMarkers = arrayOf("って", "った", "のは", "のが", "のに", "ので", "んだ", "んで", "わけ", "ない", "りゃ")

private fun isLikelyOovGarbage(w: WordInfo): Boolean {
    // Length 3 admits the minimal mora-theft blob (る+って → なる|って); downstream gating keeps it safe.
    if (w.text.length < 3) return false
    if (w.partOfSpeech != PartOfSpeech.Noun && w.partOfSpeech != PartOfSpeech.CommonNoun &&
        w.partOfSpeech != PartOfSpeech.Interjection && w.partOfSpeech != PartOfSpeech.Filler) return false
    if (!JapaneseTextHelper.isAllHiragana(w.text)) return false
    if (w.normalizedForm != w.text) return false

    for (marker in OovGrammarMarkers)
        if (w.text.contains(marker)) return true

    return false
}

private fun isVerbAdjOrAux(pos: PartOfSpeech) =
    pos == PartOfSpeech.Verb || pos == PartOfSpeech.IAdjective || pos == PartOfSpeech.Auxiliary

internal fun MorphologicalAnalyser.splitOovGarbageTokens(words: MutableList<WordInfo>): MutableList<WordInfo> {
    if (words.size < 2) return words

    val deconj = Deconjugator.instance
    val result = ArrayList<WordInfo>(words.size + 8)
    var changed = false

    for (i in words.indices) {
        val word = words[i]

        if (!isLikelyOovGarbage(word) || result.isEmpty()) {
            result.add(word)
            continue
        }

        val compoundLookup = hasCompoundLookup
        if (compoundLookup != null && compoundLookup(word.text)) {
            result.add(word)
            continue
        }

        val prev = result.last()
        var repaired = false

        // 見られ|て|るって → 見られ + てる + って so CombineInflections folds 見られてる instead of leaving 照る (1350860).
        if (word.text.startsWith("るって") &&
            prev.partOfSpeech == PartOfSpeech.Particle && (prev.text == "て" || prev.text == "で") &&
            result.size >= 2 &&
            isVerbAdjOrAux(result[result.size - 2].partOfSpeech)) {
            val boundary = if (word.startOffset >= 0) word.startOffset + 1 else -1
            result[result.size - 1] = prev.copy().apply {
                text = prev.text + "る"
                dictionaryForm = prev.text + "る"
                normalizedForm = prev.text + "る"
                reading = prev.reading + "ル"
                partOfSpeech = PartOfSpeech.Auxiliary
                endOffset = boundary
            }
            result.addAll(tokenizeGrammarRemainder(word.text.substring(1), boundary))
            changed = true
            continue
        }

        // て|りゃあ… → てりゃあ (ていれば, known to the deconjugator) so CombineInflections folds 相手にしてりゃあ.
        if (word.text.startsWith("りゃ") &&
            prev.partOfSpeech == PartOfSpeech.Particle && (prev.text == "て" || prev.text == "で") &&
            result.size >= 2 &&
            isVerbAdjOrAux(result[result.size - 2].partOfSpeech)) {
            val cut = if (word.text.length >= 3 && word.text[2] == 'あ') 3 else 2
            val remainder = word.text.substring(cut)
            val boundary = if (word.startOffset >= 0) word.startOffset + cut else -1
            // An attested remainder (もろ in 見て|りゃもろ) is one word; the grammar tokenizer would cut it も|ろ.
            val grammarTail: List<WordInfo> = when {
                remainder.isEmpty() -> emptyList()
                hasNonNameCompoundLookup?.invoke(remainder) == true -> listOf(WordInfo().apply {
                    text = remainder; dictionaryForm = remainder; normalizedForm = remainder
                    reading = WanaKana.toKatakana(normalizeToHiragana(remainder))
                    partOfSpeech = PartOfSpeech.Noun
                    startOffset = boundary
                    endOffset = word.endOffset
                })
                else -> tokenizeGrammarRemainder(remainder, boundary)
            }
            val badLeftover = remainder.isNotEmpty() &&
                (grammarTail.isEmpty() || grammarTail.any { t ->
                    t.partOfSpeech == PartOfSpeech.Noun &&
                        t.partOfSpeechSection1 == PartOfSpeechSection.CommonNoun &&
                        t.text != "わけ" && t.text != "こと" &&
                        hasNonNameCompoundLookup?.invoke(t.text) != true
                })
            if (!badLeftover) {
                val contraction = word.text.substring(0, cut)
                result[result.size - 1] = prev.copy().apply {
                    text = prev.text + contraction
                    dictionaryForm = prev.text + contraction
                    normalizedForm = prev.text + contraction
                    reading = prev.reading + WanaKana.toKatakana(contraction)
                    partOfSpeech = PartOfSpeech.Auxiliary
                    endOffset = boundary
                }
                result.addAll(grammarTail)
                changed = true
                continue
            }
        }

        // Suffix prev (頑|張): reattach the stolen mora across both tokens (頑張れ) so 頑 isn't orphaned; needs a real verb.
        if (result.size >= 2 && result[result.size - 1].partOfSpeech == PartOfSpeech.Suffix && word.text.length >= 2) {
            val mora = word.text.substring(0, 1)
            val runHira = normalizeToHiragana(result[result.size - 2].text + result[result.size - 1].text + mora)
            val runDict: String? = deconj.deconjugate(runHira)
                .firstOrNull { f -> f.tags.any { it.startsWith("v") } && hasCompoundLookup?.invoke(f.text) == true }?.text
            if (runDict != null) {
                val gt = tokenizeGrammarRemainder(word.text.substring(1), if (word.startOffset >= 0) word.startOffset + 1 else -1)
                val leftoverNoun = gt.any { t ->
                    t.partOfSpeech == PartOfSpeech.Noun && t.partOfSpeechSection1 == PartOfSpeechSection.CommonNoun &&
                        t.text != "わけ" && t.text != "こと"
                }
                if (gt.isNotEmpty() && !leftoverNoun) {
                    val head = result[result.size - 2]
                    val verbText = result[result.size - 2].text + result[result.size - 1].text + mora
                    result.subList(result.size - 2, result.size).clear()
                    result.add(head.copy().apply {
                        text = verbText; dictionaryForm = runDict; normalizedForm = runDict
                        reading = WanaKana.toKatakana(normalizeToHiragana(verbText))
                        partOfSpeech = PartOfSpeech.Verb; partOfSpeechSection1 = PartOfSpeechSection.Common
                        endOffset = if (word.startOffset >= 0) word.startOffset + 1 else -1
                    })
                    result.addAll(gt)
                    changed = true
                    continue
                }
            }
        }

        val maxPrefix = minOf(3, word.text.length - 2)
        for (prefixLen in 1..maxPrefix) {
            val prefix = word.text.substring(0, prefixLen)
            val candidate = prev.text + prefix
            val hiragana = normalizeToHiragana(candidate)
            val forms = deconj.deconjugate(hiragana)

            var dictForm: String? = null
            var repairedPos = PartOfSpeech.Verb
            var isValid = false
            var foundVerb = false

            for (f in forms) {
                for (t in f.tags) {
                    if (t.startsWith("v")) {
                        isValid = true
                        foundVerb = true
                        if (dictForm == null) dictForm = f.text
                        break
                    }
                    if (t.startsWith("adj")) {
                        isValid = true
                        if (dictForm == null) dictForm = f.text
                    }
                }
                if (foundVerb) break
            }

            if (isValid && !foundVerb) repairedPos = PartOfSpeech.IAdjective

            if (!isValid) continue

            val remainder = word.text.substring(prefixLen)
            val grammarTokens = tokenizeGrammarRemainder(remainder, if (word.startOffset >= 0) word.startOffset + prefixLen else -1)

            if (grammarTokens.isEmpty()) continue
            val hasLeftoverNoun = grammarTokens.any { t ->
                t.partOfSpeech == PartOfSpeech.Noun && t.partOfSpeechSection1 == PartOfSpeechSection.CommonNoun &&
                    t.text != "わけ" && t.text != "こと"
            }
            if (hasLeftoverNoun) continue

            val repairedDict = dictForm ?: hiragana
            result[result.size - 1] = WordInfo().apply {
                text = candidate
                dictionaryForm = repairedDict
                normalizedForm = repairedDict
                reading = WanaKana.toKatakana(hiragana)
                partOfSpeech = repairedPos
                partOfSpeechSection1 = PartOfSpeechSection.Common
                startOffset = prev.startOffset
                endOffset = if (word.startOffset >= 0) word.startOffset + prefixLen else -1
            }

            result.addAll(grammarTokens)
            repaired = true
            changed = true
            break
        }

        if (!repaired) result.add(word)
    }

    return if (changed) result else words
}

/** Splits からって when から ends an expression entry (病は気から|って); any-entry lookups hit homographs (た+から = 宝). */
internal fun MorphologicalAnalyser.splitLexicalisedKaratte(words: MutableList<WordInfo>): MutableList<WordInfo> {
    val expressionLookup = hasExpressionLookup ?: return words

    var result: MutableList<WordInfo>? = null

    for (i in words.indices) {
        val word = words[i]
        if (word.text != "からって" || word.preMatchedWordId != null || i == 0) {
            result?.add(word)
            continue
        }

        // Widest span first; three tokens covers the target expressions (病+は+気).
        var lexicalised = false
        var span = minOf(3, i)
        while (span >= 1 && !lexicalised) {
            val left = when (span) {
                1 -> words[i - 1].text
                2 -> words[i - 2].text + words[i - 1].text
                else -> words[i - 3].text + words[i - 2].text + words[i - 1].text
            }
            lexicalised = left.isNotEmpty() && expressionLookup(left + "から")
            span--
        }

        if (!lexicalised) {
            result?.add(word)
            continue
        }

        val list = result ?: copyAccumulatorUpTo(words, i).also { result = it }
        val splitOffset = if (word.startOffset >= 0) word.startOffset + 2 else -1
        list.add(word.copy().apply {
            text = "から"; dictionaryForm = "から"; normalizedForm = "から"
            partOfSpeech = PartOfSpeech.Particle; reading = "カラ"; endOffset = splitOffset
        })
        list.add(word.copy().apply {
            text = "って"; dictionaryForm = "って"; normalizedForm = "って"
            partOfSpeech = PartOfSpeech.Particle; reading = "ッテ"; startOffset = splitOffset
        })
    }

    return result ?: words
}

// Shared (RepairStages and Disambiguation use them): ported with the stubs.

internal val RenyokeiToGodanBase: Map<Char, Char> = hashMapOf(
    'き' to 'く', 'ぎ' to 'ぐ', 'し' to 'す', 'ち' to 'つ', 'に' to 'ぬ',
    'び' to 'ぶ', 'み' to 'む', 'り' to 'る', 'い' to 'う',
)

internal fun MorphologicalAnalyser.renyokeiSurfaceToVerb(s: String): String? {
    val lookup = hasNonNameCompoundLookup
    if (s.length < 2 || lookup == null) return null
    val ichidan = s + 'る'
    if (lookup(ichidan)) return ichidan
    RenyokeiToGodanBase[s.last()]?.let { baseEnd ->
        val godan = s.dropLast(1) + baseEnd
        if (lookup(godan)) return godan
    }
    return null
}

internal data class GrammarToken(val text: String, val reading: String, val pos: PartOfSpeech, val sec: PartOfSpeechSection)

internal val GrammarTokenTable: List<GrammarToken> = listOf(
    GrammarToken("って", "ッテ", PartOfSpeech.Particle, PartOfSpeechSection.AdverbialParticle),
    GrammarToken("った", "ッタ", PartOfSpeech.Auxiliary, PartOfSpeechSection.None),
    GrammarToken("わけ", "ワケ", PartOfSpeech.Noun, PartOfSpeechSection.CommonNoun),
    GrammarToken("こと", "コト", PartOfSpeech.Noun, PartOfSpeechSection.CommonNoun),
    // Pronoun, not CommonNoun, so これ after って doesn't trip the hasLeftoverNoun abort (るってこれ).
    GrammarToken("これ", "コレ", PartOfSpeech.Pronoun, PartOfSpeechSection.Pronoun),
    GrammarToken("それ", "ソレ", PartOfSpeech.Pronoun, PartOfSpeechSection.Pronoun),
    GrammarToken("あれ", "アレ", PartOfSpeech.Pronoun, PartOfSpeechSection.Pronoun),
    GrammarToken("どれ", "ドレ", PartOfSpeech.Pronoun, PartOfSpeechSection.Pronoun),
    GrammarToken("ここ", "ココ", PartOfSpeech.Pronoun, PartOfSpeechSection.Pronoun),
    GrammarToken("そこ", "ソコ", PartOfSpeech.Pronoun, PartOfSpeechSection.Pronoun),
    GrammarToken("どこ", "ドコ", PartOfSpeech.Pronoun, PartOfSpeechSection.Pronoun),
    GrammarToken("ない", "ナイ", PartOfSpeech.IAdjective, PartOfSpeechSection.PossibleDependant),
    GrammarToken("から", "カラ", PartOfSpeech.Particle, PartOfSpeechSection.ConjunctionParticle),
    GrammarToken("けど", "ケド", PartOfSpeech.Particle, PartOfSpeechSection.ConjunctionParticle),
    GrammarToken("だけ", "ダケ", PartOfSpeech.Particle, PartOfSpeechSection.AdverbialParticle),
    GrammarToken("でも", "デモ", PartOfSpeech.Particle, PartOfSpeechSection.AdverbialParticle),
    GrammarToken("の", "ノ", PartOfSpeech.Particle, PartOfSpeechSection.CaseMarkingParticle),
    GrammarToken("は", "ハ", PartOfSpeech.Particle, PartOfSpeechSection.BindingParticle),
    GrammarToken("が", "ガ", PartOfSpeech.Particle, PartOfSpeechSection.CaseMarkingParticle),
    GrammarToken("も", "モ", PartOfSpeech.Particle, PartOfSpeechSection.BindingParticle),
    GrammarToken("で", "デ", PartOfSpeech.Particle, PartOfSpeechSection.CaseMarkingParticle),
    GrammarToken("に", "ニ", PartOfSpeech.Particle, PartOfSpeechSection.CaseMarkingParticle),
    GrammarToken("を", "ヲ", PartOfSpeech.Particle, PartOfSpeechSection.CaseMarkingParticle),
    GrammarToken("と", "ト", PartOfSpeech.Particle, PartOfSpeechSection.CaseMarkingParticle),
    GrammarToken("か", "カ", PartOfSpeech.Particle, PartOfSpeechSection.AdverbialParticle),
    // Longest match keeps だろ whole so ろ isn't stranded as a leftover noun (…ってことだろ).
    GrammarToken("だろう", "ダロウ", PartOfSpeech.Auxiliary, PartOfSpeechSection.None),
    GrammarToken("だろ", "ダロ", PartOfSpeech.Auxiliary, PartOfSpeechSection.None),
    GrammarToken("だ", "ダ", PartOfSpeech.Auxiliary, PartOfSpeechSection.None),
    // Keeps …るって|なれば from leaving a ば-final leftover noun that aborts the split.
    GrammarToken("なれば", "ナレバ", PartOfSpeech.Conjunction, PartOfSpeechSection.None),
    GrammarToken("ならば", "ナラバ", PartOfSpeech.Conjunction, PartOfSpeechSection.None),
    GrammarToken("な", "ナ", PartOfSpeech.Particle, PartOfSpeechSection.SentenceEndingParticle),
    GrammarToken("ね", "ネ", PartOfSpeech.Particle, PartOfSpeechSection.SentenceEndingParticle),
    GrammarToken("よ", "ヨ", PartOfSpeech.Particle, PartOfSpeechSection.SentenceEndingParticle),
    GrammarToken("さ", "サ", PartOfSpeech.Particle, PartOfSpeechSection.SentenceEndingParticle),
    GrammarToken("わ", "ワ", PartOfSpeech.Particle, PartOfSpeechSection.SentenceEndingParticle),
    GrammarToken("ん", "ン", PartOfSpeech.Particle, PartOfSpeechSection.Juntaijoushi),
    GrammarToken("た", "タ", PartOfSpeech.Auxiliary, PartOfSpeechSection.None),
)

internal fun deconjugatesToVerb(hiragana: String): Boolean =
    Deconjugator.instance.deconjugate(hiragana).any { f -> f.tags.any { it.startsWith("v") } }

internal fun MorphologicalAnalyser.tokenizeGrammarRemainder(text: String, startOffset: Int): MutableList<WordInfo> {
    val tokens = ArrayList<WordInfo>()
    var i = 0
    while (i < text.length) {
        var markerLen = 0
        var marker: GrammarToken? = null
        for (g in GrammarTokenTable)
            if (g.text.length > markerLen && text.startsWith(g.text, i)) { markerLen = g.text.length; marker = g }

        // う-row dictionary-form verbs only (ってのは+わかる); looser tails mis-cut clusters (のはだな → の|肌|な, んだ).
        var verbLen = 0
        var tailPos = PartOfSpeech.Verb
        val lookup = hasNonNameCompoundLookup
        if (lookup != null) {
            var len = minOf(text.length - i, 6)
            while (len > markerLen && len >= 2) {
                val cand = text.substring(i, i + len)
                if (cand.last() in "うくぐすつぬぶむる" && lookup(cand) && deconjugatesToVerb(cand)) { verbLen = len; break }
                len--
            }
        }

        // Keeps a conjugated i-adjective (よかった) from shredding into よ|か|った; window 8 fits ありがたかった.
        if (verbLen == 0 && lookup != null) {
            var len = minOf(text.length - i, 8)
            while (len > markerLen && len >= 3 && verbLen == 0) {
                val cand = text.substring(i, i + len)
                if (cand.last() == 'た' || cand.last() == 'ば') {
                    for (f in Deconjugator.instance.deconjugate(cand)) {
                        if (f.text == cand || !lookup(f.text)) continue
                        if (f.tags.none { it.startsWith("adj") }) continue
                        verbLen = len
                        tailPos = PartOfSpeech.IAdjective
                        break
                    }
                }
                len--
            }
        }

        if (verbLen > 0) {
            val w = text.substring(i, i + verbLen)
            tokens.add(WordInfo().apply {
                this.text = w; dictionaryForm = w; normalizedForm = w
                reading = WanaKana.toKatakana(normalizeToHiragana(w))
                partOfSpeech = tailPos
                this.startOffset = if (startOffset >= 0) startOffset + i else -1
                endOffset = if (startOffset >= 0) startOffset + i + verbLen else -1
            })
            i += verbLen
            continue
        }

        val m = marker ?: break

        val gramText = text.substring(i, i + markerLen)
        tokens.add(WordInfo().apply {
            this.text = gramText; dictionaryForm = gramText; normalizedForm = gramText; reading = m.reading
            partOfSpeech = m.pos; partOfSpeechSection1 = m.sec
            this.startOffset = if (startOffset >= 0) startOffset + i else -1
            endOffset = if (startOffset >= 0) startOffset + i + markerLen else -1
        })
        i += markerLen
    }

    if (i < text.length) {
        val leftover = text.substring(i)
        tokens.add(WordInfo().apply {
            this.text = leftover; dictionaryForm = leftover; normalizedForm = leftover; reading = leftover
            partOfSpeech = PartOfSpeech.Noun; partOfSpeechSection1 = PartOfSpeechSection.CommonNoun
            this.startOffset = if (startOffset >= 0) startOffset + i else -1
            endOffset = if (startOffset >= 0) startOffset + text.length else -1
        })
    }

    return tokens
}
