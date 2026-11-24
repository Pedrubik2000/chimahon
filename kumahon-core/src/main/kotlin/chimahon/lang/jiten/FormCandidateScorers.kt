// Ported from Jiten (github.com/Sirush/Jiten, Apache-2.0, commit 44b48a4): Jiten.Parser/Scoring/FormCandidateScorers.cs
package chimahon.lang.jiten

import kotlin.math.max
import kotlin.math.min

internal object FormCandidateScorer {
    fun score(candidate: FormCandidate, context: FormScoringContext, archaicPosTypes: Set<String>): FormScoreTrace {
        val wordScore = WordPriorityScorer.score(candidate, context.isNameContext, context.isArchaicSentence, archaicPosTypes,
            context.isSentenceInitial, context.isSentenceFinal)
        val entryPriorityScore = EntryPriorityScorer.score(candidate)
        val formPriorityScore = FormPriorityScorer.score(candidate, context.isKanaSurface)
        val formFlagScore = FormFlagScorer.score(candidate, context)

        var surfaceMatchScore = SurfaceScorer.score(candidate, context)
        surfaceMatchScore += LemmaScorer.score(candidate, context, surfaceMatchScore)

        // C#'s ref int: the penalties return (applied, new surface score)
        val (conjugatedIdentityPenaltyApplied, s1) = PenaltyScorer.applyConjugatedIdentityPenalty(candidate, context, surfaceMatchScore)
        surfaceMatchScore = s1
        val (expressionConflictPenaltyApplied, s2) = PenaltyScorer.applyExpressionConflictPenalty(candidate, context, surfaceMatchScore)
        surfaceMatchScore = s2

        val scriptScore = ScriptScorer.score(candidate, context)
        val readingMatchScore = ReadingScorer.score(candidate, context,
            conjugatedIdentityPenaltyApplied || expressionConflictPenaltyApplied, archaicPosTypes)

        if (readingMatchScore < 0 && surfaceMatchScore >= 300) surfaceMatchScore = (surfaceMatchScore * 0.3).toInt()

        val posAffinityScore = PosAffinityScorer.score(candidate, context)
        val rubyPriorsScore = RubyPriorsScorer.score(candidate, context)

        return FormScoreTrace(wordScore, entryPriorityScore, formPriorityScore, formFlagScore, surfaceMatchScore, scriptScore,
            readingMatchScore, posAffinityScore, conjugatedIdentityPenaltyApplied || expressionConflictPenaltyApplied,
            rubyPriorsScore)
    }
}

/** C#'s Math.Round(x / 10f): float division, rounded half to even. */
private fun roundTenth(rank: Int): Int = Math.rint((rank / 10f).toDouble()).toInt()

internal object WordPriorityScorer {
    private val SentenceFinalParticleSurfaces = hashSetOf("ね", "よ", "ぞ", "わ", "な", "さ", "か", "の", "かな", "かしら",
        "よね", "わよ", "わね", "のよ", "のね", "もんか")

    private val ModernPrimaryPos = hashSetOf("n", "n-adv", "n-t", "n-pref", "n-suf", "v1", "v1-s", "v5a", "v5b", "v5g",
        "v5k", "v5k-s", "v5m", "v5n", "v5r", "v5r-i", "v5s", "v5t", "v5u", "v5u-s", "v5uru", "vs", "vs-c", "vs-i", "vs-s",
        "vk", "vz", "adj-i", "adj-ix", "adj-na", "adj-no", "adj-pn", "adv", "adv-to", "cop", "aux", "aux-v", "aux-adj",
        "prt", "int", "exp", "pref", "ctr", "on-mim", "pn", "conj")

    fun score(
        candidate: FormCandidate, isNameContext: Boolean, isArchaicSentence: Boolean, archaicPosTypes: Set<String>,
        isSentenceInitial: Boolean = false, isSentenceFinal: Boolean = false,
    ): Int {
        val word = candidate.word
        var wordScore = 0

        if (word.priorities?.contains("jiten") == true) wordScore += 100

        if (!isNameContext && PartOfSpeech.Name in word.cachedPOS) wordScore -= 50

        if (word.isFullyArchaic) {
            val hasFrequencyMarker = KanaScoringHelpers.hasFrequencyMarker(word.priorities)
            if (!hasFrequencyMarker) {
                // Archaic pronouns (汝 なんじ) are common in modern literary/fantasy prose, so they get a softer penalty.
                val isPronoun = PartOfSpeech.Pronoun in word.cachedPOS
                wordScore -= if (isArchaicSentence) 50 else if (isPronoun) 100 else 350
            }
        }

        // Any modern primary POS exempts (無し adj-ku also has n); "suf" doesn't, since a v2a-s word can also be a suffix.
        val posToCheck = candidate.effectivePos
        if (posToCheck.any { it in archaicPosTypes } && posToCheck.none { it in ModernPrimaryPos })
            wordScore -= if (isArchaicSentence) 15 else 75

        if (word.partsOfSpeech.any { it == "on-mim" }) wordScore += 10

        if (isSentenceInitial && word.partsOfSpeech.any { it == "adv" || it == "adv-to" }) wordScore += 10

        // Separates the final particle な from homographs like the na-adjective ending.
        if (isSentenceFinal && word.partsOfSpeech.any { it == "prt" } &&
            candidate.formTextHiragana in SentenceFinalParticleSurfaces)
            wordScore += 25

        // Unclass entries are uncategorised JMnedict names, a last resort outside name context.
        if (!isNameContext && word.partsOfSpeech.all { it == "unclass" }) wordScore -= 40

        // Pure counters almost always follow a number, so homophones win elsewhere (色 いろ over counter ショク).
        if (word.partsOfSpeech.all { it == "ctr" }) wordScore -= 10

        val chainCount = candidate.deconjForm?.process?.size ?: 0
        if (chainCount <= 2) wordScore += 8 else wordScore -= 8 * (chainCount - 2)

        return wordScore
    }
}

internal object EntryPriorityScorer {
    fun score(candidate: FormCandidate): Int {
        val wordPri = candidate.word.priorities ?: emptyList()
        var entryPriorityScore = 0

        if ("ichi1" in wordPri) entryPriorityScore += 20
        if ("ichi2" in wordPri) entryPriorityScore += 10
        if ("news1" in wordPri) entryPriorityScore += 15
        if ("news2" in wordPri) entryPriorityScore += 10
        if ("gai1" in wordPri) entryPriorityScore += 15
        if ("gai2" in wordPri) entryPriorityScore += 10

        val wnf = wordPri.firstOrNull { it.startsWith("nf") }
        val wnfRank = if (wnf != null && wnf.length > 2) wnf.substring(2).toIntOrNull() else null
        if (wnfRank != null) {
            entryPriorityScore += max(0, 5 - roundTenth(wnfRank))
            if (wnfRank <= 5) entryPriorityScore += 6 - wnfRank
        }

        if (entryPriorityScore == 0) {
            if ("spec1" in wordPri) entryPriorityScore += 15
            if ("spec2" in wordPri) entryPriorityScore += 5
        } else {
            if ("spec1" in wordPri) entryPriorityScore += 4
            if ("spec2" in wordPri) entryPriorityScore += 2
        }

        // Copulas (である, だ) lack the ichi1/news1 tags that would keep frequent verbs from crowding them out.
        if ("cop" in candidate.word.partsOfSpeech) entryPriorityScore += 20

        return entryPriorityScore
    }
}

internal object FormPriorityScorer {
    fun score(candidate: FormCandidate, isKanaSurface: Boolean): Int {
        val word = candidate.word
        val priorities = candidate.form.priorities ?: emptyList()
        val wordPri = word.priorities ?: emptyList()
        var formPriorityScore = 0

        if ("ichi1" in priorities) formPriorityScore += 10
        if ("ichi2" in priorities) formPriorityScore += 5
        if ("news1" in priorities) formPriorityScore += 8
        if ("news2" in priorities) formPriorityScore += 5
        if ("gai1" in priorities) formPriorityScore += 8
        if ("gai2" in priorities) formPriorityScore += 5

        val nf = priorities.firstOrNull { it.startsWith("nf") }
        val nfRank = if (nf != null && nf.length > 2) nf.substring(2).toIntOrNull() else null
        if (nfRank != null) formPriorityScore += max(0, 3 - roundTenth(nfRank))

        if (formPriorityScore == 0) {
            if ("spec1" in priorities) formPriorityScore += 8
            if ("spec2" in priorities) formPriorityScore += 3
        }

        if ("jiten" in priorities) formPriorityScore += 25

        if ("uk" in word.partsOfSpeech) {
            // jiten is excluded: it's an internal priority, not public frequency evidence.
            val hasFreqMarker = KanaScoringHelpers.hasFrequencyMarker(wordPri, includeJiten = false)
            val ukBonus = if (hasFreqMarker) 10 else 3
            if (candidate.form.formType == JmDictFormType.KanaForm || isKanaSurface) formPriorityScore += ukBonus
            else formPriorityScore -= ukBonus
        }

        return formPriorityScore
    }
}

internal object FormFlagScorer {
    fun score(candidate: FormCandidate, context: FormScoringContext): Int {
        val word = candidate.word
        val form = candidate.form
        var formFlagScore = 0

        val formMatchesSurface = context.surface == form.text ||
            (candidate.deconjForm != null && candidate.deconjForm.text == form.text)
        if (form.isSearchOnly) formFlagScore += if (formMatchesSurface) 0 else -300
        if (form.isObsolete) formFlagScore += if (formMatchesSurface) 0 else -150
        if (!form.isActiveInLatestSource) formFlagScore -= 30

        val isPureKanaWord = word.forms.all { it.formType != JmDictFormType.KanjiForm }
        if (isPureKanaWord && form.formType == JmDictFormType.KanaForm && context.isKanaSurface) formFlagScore += 20

        // JMDict orders readings by prevalence, so a secondary one (草花 そうか) must not beat そうか "I see"; uk is exempt.
        if (!isPureKanaWord && form.formType == JmDictFormType.KanaForm && context.isKanaSurface &&
            "uk" !in word.partsOfSpeech && !isFirstKanaReading(word, candidate.formTextHiragana))
            formFlagScore -= 15

        // Exact-surface [exp, col] entries (こった = ことだ) beat homographs like こった "elaborate" (1238990) in speech.
        if (formMatchesSurface && "col" in word.partsOfSpeech && "exp" in word.partsOfSpeech) formFlagScore += 15

        // Single-kana tokens are almost always grammatical, so a jiten kanji word (打 だ) must not win through its reading.
        if (!isPureKanaWord && form.formType == JmDictFormType.KanaForm && context.isKanaSurface &&
            context.surface.length == 1 && word.priorities?.contains("jiten") == true)
            formFlagScore -= 100

        return formFlagScore
    }

    /** Phonetic compare, so a katakana spelling of the primary reading (懐炉 カイロ) counts as primary. */
    private fun isFirstKanaReading(word: JmDictWord, formTextHiragana: String): Boolean {
        for (f in word.forms) {
            if (f.formType != JmDictFormType.KanaForm) continue
            return KanaScoringHelpers.toNormalizedHiragana(f.text, convertLongVowelMark = false) == formTextHiragana
        }
        return false
    }
}

internal object SurfaceScorer {
    fun score(candidate: FormCandidate, context: FormScoringContext): Int {
        val surface = context.surface
        val formText = candidate.form.text
        var score = 0

        if (surface == formText) {
            score += 300
            // A katakana-exact match is usually intended gairaigo (タンゴ over 単語's たんご).
            val isPureKatakana = surface.isNotEmpty() && surface.all { it in '゠'..'ヿ' }
            if (isPureKatakana) score += 10
        } else if (context.surfaceHiragana == candidate.formTextHiragana) {
            // Hiragana → katakana folds hit different words (まま vs ママ), as do mixed → hiragana (ショートして → しょうとして).
            // Katakana surface → hiragana form keeps the full bonus (gairaigo written in kana).
            val pureScriptDifference = KanaScoringHelpers.isPureKanaScriptDifference(surface, formText)
            if (if (JapaneseTextHelper.isAllHiragana(surface)) formText.any(JapaneseTextHelper::isKatakana)
                else !pureScriptDifference && JapaneseTextHelper.isAllHiragana(formText) &&
                    surface.any(JapaneseTextHelper::isKatakana) && surface.any(JapaneseTextHelper::isHiragana))
                score += 40
            else
                score += if (pureScriptDifference) 280 else 120
        } else {
            val formLoose = KanaScoringHelpers.toNormalizedHiragana(formText, convertLongVowelMark = true)
            if (context.surfaceHiraganaLoose == formLoose) {
                score += 60
            } else if ('ー' in surface) {
                val surfaceStripped = surface.replace("ー", "")
                val formStripped = formText.replace("ー", "")
                if (surfaceStripped.isNotEmpty() && (surfaceStripped == formStripped ||
                        KanaScoringHelpers.isPureKanaScriptDifference(surfaceStripped, formStripped)))
                    score += 40
            }
        }
        return score
    }
}

internal object LemmaScorer {
    fun score(candidate: FormCandidate, context: FormScoringContext, existingSurfaceScore: Int): Int {
        var score = 0
        val word = candidate.word
        val formText = candidate.form.text

        val surface = context.surface
        val dictionaryForm = context.dictionaryForm
        val normalizedForm = context.normalizedForm

        var lemmaScale = 1.0
        val deconjProcess = candidate.deconjForm?.process
        if (deconjProcess != null && deconjProcess.isNotEmpty()) lemmaScale = max(0.0, 1.0 - (deconjProcess.size - 1) * 0.35)

        if (!dictionaryForm.isNullOrEmpty() && dictionaryForm != surface) {
            if (dictionaryForm == formText) {
                // Sudachi and the deconjugator agreeing earns a higher floor so deep chains (来てない→来る) aren't crushed.
                val deconjConfirmsDictForm = candidate.deconjForm != null && candidate.deconjForm.process.isNotEmpty() &&
                    candidate.deconjForm.text == context.dictionaryFormHiragana
                val floor = if (deconjConfirmsDictForm) 0.8 else 0.3
                val effectiveScale = max(floor, lemmaScale)
                score += (100 * effectiveScale).toInt()
            } else if (context.dictionaryFormHiragana == candidate.formTextHiragana) {
                score += (40 * lemmaScale).toInt()
            }
        }

        if (!normalizedForm.isNullOrEmpty() && normalizedForm != surface && normalizedForm != dictionaryForm) {
            if (normalizedForm == formText) {
                score += (50 * lemmaScale).toInt()
            } else {
                if (context.normalizedFormHiragana == candidate.formTextHiragana) score += (20 * lemmaScale).toInt()

                if (word.forms.any { it.text == normalizedForm }) {
                    var normBonus = 50
                    // A standalone kana token is not a bound suffix: ねえ must not become 姉 (n-suf) via its NormalizedForm.
                    if (context.isKanaSurface && KanaScoringHelpers.containsKanji(normalizedForm) &&
                        "n-suf" in word.partsOfSpeech && word.partsOfSpeech.none { it == "n" || it == "n-adv" || it == "n-t" })
                        normBonus = 10
                    score += (normBonus * lemmaScale).toInt()
                }
            }
        }

        if (candidate.deconjForm?.text != null && existingSurfaceScore + score == 0) {
            val deconjHira = KanaScoringHelpers.toNormalizedHiragana(candidate.deconjForm.text, convertLongVowelMark = false)
            if (deconjHira == candidate.formTextHiragana) {
                // Against a conflicting DictionaryForm, an unprioritized deconj match is spurious (背負っていた → 背負ってる exp).
                val dictFormConflicts = !context.dictionaryForm.isNullOrEmpty() && context.dictionaryForm != context.surface &&
                    !KanaScoringHelpers.wordHasFormEquivalentTo(word, context.dictionaryForm)
                if (!dictFormConflicts || KanaScoringHelpers.hasFrequencyMarker(word.priorities))
                    score += (100 * lemmaScale).toInt()
            }
        }
        return score
    }
}

/** C#'s `ref int surfaceMatchScore` becomes a returned (applied, surfaceMatchScore) pair. */
internal object PenaltyScorer {
    fun applyConjugatedIdentityPenalty(
        candidate: FormCandidate, context: FormScoringContext, surfaceMatchScore: Int,
    ): Pair<Boolean, Int> {
        var s = surfaceMatchScore
        val surfaceMatchesFormDirectly = KanaScoringHelpers.surfaceEquivalentTo(context.surface, candidate.form.text)

        if (!context.dictionaryForm.isNullOrEmpty() && context.dictionaryForm != context.surface && surfaceMatchesFormDirectly &&
            (candidate.deconjForm == null || candidate.deconjForm.process.isEmpty())) {
            val posToCheck = candidate.effectivePos
            val isInflectable = KanaScoringHelpers.isInflectableVerbOrAdj(posToCheck)

            if (!isInflectable) {
                // Expressions are handled by ApplyExpressionConflictPenalty.
                if (posToCheck.any { it == "exp" || it == "on-mim" }) return false to s

                // Sudachi gives numerals (一つ) DictionaryForm=つ, the counter, not a conjugation base.
                if (posToCheck.any { it == "num" }) return false to s

                // Frequent adverbs (悪しからず ichi1) are fixed forms; their DictionaryForm comes from a Sudachi sub-token.
                val isAdverb = posToCheck.any { it == "adv" || it == "adv-to" }
                if (isAdverb && KanaScoringHelpers.hasFrequencyMarker(candidate.word.priorities)) return false to s

                // Particle で gets DictionaryForm=だ but isn't a conjugation; kanji particle forms (許し for ばかし) stay penalised.
                val isParticle = posToCheck.any { it == "prt" }
                if (isParticle && context.isKanaSurface) return false to s

                // adj-pn/adj-t (亡き, 堂々たる) are exempt only when DictionaryForm is their own form, not another word's (させる→する).
                val isAdnominal = posToCheck.any { it == "adj-pn" || it == "adj-t" }
                if (isAdnominal && KanaScoringHelpers.wordHasFormEquivalentTo(candidate.word, context.dictionaryForm))
                    return false to s

                // Sudachi reads suffix たまえ (食べたまえ) as 給う's imperative; its 非自立可能 flag marks the auxiliary use.
                val isBoundSuffix = posToCheck.any { it == "suf" } && posToCheck.none { it == "n" || it == "n-adv" || it == "n-t" }
                if (isBoundSuffix && context.isSudachiPossibleDependant) return false to s

                // Classical き-forms (悪しき) get the modern い-adjective as DictionaryForm, but the き entry is the right word.
                if ((isAdnominal || "adj-f" in posToCheck) && candidate.form.text.endsWith('き')) {
                    val attributiveStem = candidate.form.text.dropLast(1)
                    if (context.dictionaryForm == attributiveStem || context.dictionaryForm == attributiveStem + "い" ||
                        context.normalizedForm == attributiveStem + "い")
                        return false to s
                }

                // A DictionaryForm outside this word's forms names another word: noun 答え must not beat verb 答える.
                val nounHasDictForm = KanaScoringHelpers.wordHasFormEquivalentTo(candidate.word, context.dictionaryForm)
                if (!nounHasDictForm) {
                    // An interjection (やった) is never a verb form, so -300 fully cancels its surface bonus.
                    if (posToCheck.any { it == "int" }) return true to (s - 300)

                    // Ichidan stems often stand alone as nouns (目覚め) or prefixes (生き); godan masu-stems (立ち) are verbal.
                    if (!context.sudachiReading.isNullOrEmpty() && !context.isKanaSurface &&
                        context.dictionaryForm == context.surface + "る") {
                        val sudachiHira = KanaScoringHelpers.toNormalizedHiragana(context.sudachiReading, convertLongVowelMark = false)
                        val hasExactReadingMatch = candidate.word.forms.filter { it.formType == JmDictFormType.KanaForm }
                            .any { KanaScoringHelpers.toNormalizedHiragana(it.text, convertLongVowelMark = false) == sudachiHira }
                        if (hasExactReadingMatch) {
                            val isPrefixLike = "pref" in candidate.word.partsOfSpeech
                            val isNounLike = candidate.word.partsOfSpeech.any { it == "n" || it == "n-adv" || it == "n-t" }
                            if (isPrefixLike) return false to (s - 100)
                            if (isNounLike) return false to (s - if (context.sudachiPOS == PartOfSpeech.Verb) 200 else 110)
                        }
                    }

                    // adj-pn/adj-t/aux-v (無き, 如く) get archaic-base DictionaryForms; plain nouns (支店 as してん) get the full -300.
                    val isNonNounWithLegitimateForm = posToCheck.any { it == "adj-pn" || it == "adj-t" || it == "aux-v" }
                    s -= if (isNonNounWithLegitimateForm) 200 else 300
                    return true to s
                }
                return false to s
            }

            // adj-ix base forms aren't other words' conjugations, even when Sudachi reads いい as いう.
            if (posToCheck.any { it == "adj-ix" }) return false to s

            // Frequent fixed expressions (いけない ichi1) keep their exact match though Sudachi reads いける+ない.
            val isInflectableExpression = posToCheck.any { it == "exp" || it == "on-mim" }
            if (isInflectableExpression && KanaScoringHelpers.hasFrequencyMarker(candidate.word.priorities)) return false to s

            // Words owning the DictionaryForm (食べ from 食べる) get -200; unrelated ones (一転 for いってん ← いう) get -300.
            val inflectableHasDictForm = KanaScoringHelpers.wordHasFormEquivalentTo(candidate.word, context.dictionaryForm)
            s -= if (inflectableHasDictForm) 200 else 300
            return true to s
        }
        return false to s
    }

    fun applyExpressionConflictPenalty(
        candidate: FormCandidate, context: FormScoringContext, surfaceMatchScore: Int,
    ): Pair<Boolean, Int> {
        val expressionSurfaceMatchesForm = KanaScoringHelpers.surfaceEquivalentTo(context.surface, candidate.form.text)

        if (context.dictionaryForm.isNullOrEmpty() || context.dictionaryForm == context.surface || !expressionSurfaceMatchesForm)
            return false to surfaceMatchScore

        val posToCheck = candidate.effectivePos
        if (posToCheck.none { it == "exp" || it == "on-mim" }) return false to surfaceMatchScore

        if (KanaScoringHelpers.hasFrequencyMarker(candidate.word.priorities)) return false to surfaceMatchScore

        val dictFormMatchesWord = KanaScoringHelpers.wordHasFormEquivalentTo(candidate.word, context.dictionaryForm)
        if (!dictFormMatchesWord) {
            // A DictionaryForm prefixing the surface signals a dialectal or auxiliary base (Kansai や → やろ), so softer.
            val dfh = context.dictionaryFormHiragana
            val dictFormIsPrefixOfSurface = !dfh.isNullOrEmpty() && context.surfaceHiragana.length > dfh.length &&
                context.surfaceHiragana.startsWith(dfh)
            return true to (surfaceMatchScore - if (dictFormIsPrefixOfSurface) 100 else 250)
        }
        return false to surfaceMatchScore
    }
}

internal object ScriptScorer {
    // Superlinear by common-prefix length: 5*n*(n+1)/2 for kanji forms, 3*n*(n+1)/2 for kana, capped at n=5.
    private val KanjiScale = intArrayOf(0, 5, 15, 30, 50, 75)
    private val KanaScale = intArrayOf(0, 3, 9, 18, 30, 45)

    fun score(candidate: FormCandidate, context: FormScoringContext): Int {
        val word = candidate.word
        val form = candidate.form
        val surface = context.surface

        val prefixLen = KanaScoringHelpers.getCommonPrefixLen(surface, form.text)
        val hasKanji = KanaScoringHelpers.containsKanji(form.text)
        val scale = if (hasKanji) KanjiScale else KanaScale
        var scriptScore = scale[min(prefixLen, scale.size - 1)]

        if (form.text.length > 2 && form.text[form.text.length - 1] == 'る' &&
            candidate.effectivePos.any { it == "v1" || it == "v1-s" } &&
            surface.startsWith(form.text.dropLast(1))) {
            if (!KanaScoringHelpers.dictFormPointsToDifferentWord(context, word)) {
                val stemLen = form.text.length - 1
                scriptScore += min(15, stemLen * 5)
            }
        }

        // Prefix overlap is coincidental when DictionaryForm names another word (noun 気づかれ vs passive of 気づく).
        if (scriptScore > 15 && KanaScoringHelpers.dictFormPointsToDifferentWord(context, word)) scriptScore = min(scriptScore, 15)

        return scriptScore
    }
}

internal object ReadingScorer {
    fun score(
        candidate: FormCandidate, context: FormScoringContext, identityPenaltyApplied: Boolean,
        archaicPosTypes: Set<String>? = null,
    ): Int {
        var readingMatchScore = 0
        if (!context.sudachiReading.isNullOrEmpty() && !context.isKanaSurface) {
            val word = candidate.word
            val sudachiHira = KanaScoringHelpers.toNormalizedHiragana(context.sudachiReading, convertLongVowelMark = false)
            val kanaForms = word.forms.filter { it.formType == JmDictFormType.KanaForm }
            fun hira(f: JmDictWordForm) = KanaScoringHelpers.toNormalizedHiragana(f.text, convertLongVowelMark = false)

            val kanaFormCount = kanaForms.size
            val hasMatchingReading = kanaForms.any { hira(it) == sudachiHira }

            if (hasMatchingReading) {
                readingMatchScore += 70
            } else if (sudachiHira.length > 1) {
                var found = kanaForms.any { val h = hira(it); h.length > sudachiHira.length && h.startsWith(sudachiHira) }

                if (found) {
                    readingMatchScore += 70
                } else if (sudachiHira.length > 2) {
                    found = kanaForms.any { val h = hira(it); h.length >= 3 && h.endsWith('る') && sudachiHira.startsWith(h.dropLast(1)) }
                    if (found) readingMatchScore += 70
                }

                if (!found) {
                    val sudachiStem = sudachiHira.dropLast(1)
                    var hasStemMatch = kanaForms.any { val h = hira(it); h.length > 1 && h.dropLast(1) == sudachiStem }

                    if (!hasStemMatch && sudachiStem.length > 1 && sudachiStem[sudachiStem.length - 1] == 'い') {
                        val rootStem = sudachiStem.dropLast(1)
                        hasStemMatch = kanaForms.any { val h = hira(it); h.length > 1 && h.dropLast(1) == rootStem }
                    }

                    if (!hasStemMatch && sudachiStem.length > 1 && sudachiStem[sudachiStem.length - 1] == 'っ') {
                        val rootStem = sudachiStem.dropLast(1)
                        hasStemMatch = kanaForms.any { val h = hira(it); h.length > 1 && h.dropLast(1) == rootStem }
                    }

                    if (hasStemMatch) readingMatchScore += 25
                }
            }

            if (readingMatchScore == 0 && sudachiHira.length > 2) {
                val hasSuruStemMatch = kanaForms.any { f ->
                    val h = hira(f)
                    h.length >= 2 && h.length < sudachiHira.length && sudachiHira.startsWith(h) && sudachiHira[h.length] in "しすさせ"
                }
                if (hasSuruStemMatch) readingMatchScore += 70
            }

            if (readingMatchScore == 0 && sudachiHira.length >= 2 && kanaFormCount > 0) {
                val firstChar = sudachiHira[0]
                val anyPrefixMatch = kanaForms.any { val h = hira(it); h.isNotEmpty() && h[0] == firstChar }

                if (anyPrefixMatch) {
                    readingMatchScore += 25
                } else {
                    val formMatchesDictForm = !context.dictionaryForm.isNullOrEmpty() && word.forms.any { it.text == context.dictionaryForm }
                    if (formMatchesDictForm && candidate.deconjForm?.process.isNullOrEmpty()) readingMatchScore -= 70
                }
            }

            if (readingMatchScore > 0 && !archaicPosTypes.isNullOrEmpty() && candidate.effectivePos.any { it in archaicPosTypes })
                readingMatchScore /= 2
        }

        if (identityPenaltyApplied && readingMatchScore > 0) readingMatchScore = 0

        // Multi-reading kanji (得る える/うる) aren't crushed by Sudachi's pick, which also skips the 0.3 surface slash.
        // A bare single kanji qualifies without frequency data: Sudachi must pick some reading (智→サトシ).
        if (readingMatchScore < 0 && candidate.form.formType == JmDictFormType.KanjiForm && context.surface == candidate.form.text &&
            (KanaScoringHelpers.hasFrequencyMarker(candidate.word.priorities) || context.surface.length == 1))
            readingMatchScore = 0

        // A name agreeing with Sudachi's name lemma on a bare kanji is circular (仁も義も礼も智も is the noun, not サトシ).
        if (readingMatchScore > 0 && !context.isKanaSurface && context.surface.length == 1 && !context.isNameContext &&
            PartOfSpeech.Name in candidate.word.cachedPOS)
            readingMatchScore = 0

        // A Sudachi 人名 guess on a bare kanji is a name reading; it must not vouch for a common noun (忍 → fern しのぶ).
        if (readingMatchScore > 0 && !context.isKanaSurface && context.surface.length == 1 && context.isSudachiNameGuess &&
            PartOfSpeech.Name !in candidate.word.cachedPOS)
            readingMatchScore = 0

        // Sudachi normalizes bare kanji to an okurigana lemma (改 → 改め); that reading must not beat 改 かい.
        if (readingMatchScore != 0 && sudachiNormalizedBareKanjiToOkurigana(context)) readingMatchScore = 0

        return readingMatchScore
    }

    private fun sudachiNormalizedBareKanjiToOkurigana(context: FormScoringContext): Boolean {
        if (context.isKanaSurface) return false
        val surface = context.surface
        val normalized = context.normalizedForm
        if (surface.isEmpty() || normalized.isNullOrEmpty()) return false
        if (normalized.length <= surface.length) return false
        if (!normalized.startsWith(surface)) return false
        if (!surface.all(JapaneseTextHelper::isKanji)) return false
        for (i in surface.length until normalized.length) if (!JapaneseTextHelper.isHiragana(normalized[i])) return false
        return true
    }
}

internal object ReadingPosHelper {
    fun getPosForReading(word: JmDictWord, readingIndex: Int): Set<String> {
        if (word.definitions.isEmpty()) return emptySet()
        val result = LinkedHashSet<String>()
        for (d in word.definitions) if (appliesToReading(word, d, readingIndex)) result.addAll(d.partsOfSpeech)
        return result
    }

    // stagk and stagr share one flat index list, so a restriction only excludes forms of its own type (kanji vs kana).
    private fun appliesToReading(word: JmDictWord, definition: JmDictDefinition, readingIndex: Int): Boolean {
        val restrictions = definition.restrictedToReadingIndices
        if (restrictions.isNullOrEmpty()) return true

        val currentForm = word.forms.firstOrNull { it.readingIndex == readingIndex } ?: return readingIndex in restrictions

        var sawSameAxis = false
        for (index in restrictions) {
            val form = word.forms.firstOrNull { it.readingIndex == index }
            if (form == null || form.formType != currentForm.formType) continue
            if (index == readingIndex) return true
            sawSameAxis = true
        }
        return !sawSameAxis
    }
}

internal object KanaScoringHelpers {
    fun toNormalizedHiragana(text: String, convertLongVowelMark: Boolean): String =
        KanaNormalizer.normalize(KanaConverter.toHiragana(text, convertLongVowelMark))

    fun isPureKanaScriptDifference(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        for (i in a.indices) {
            if (a[i] == b[i]) continue
            val diff = a[i] - b[i]
            if (diff != 0x60 && diff != -0x60) return false
        }
        return true
    }

    fun wordHasFormEquivalentTo(word: JmDictWord, text: String?): Boolean {
        if (text.isNullOrEmpty()) return false
        return word.forms.any { it.text == text || isPureKanaScriptDifference(it.text, text) }
    }

    fun dictFormPointsToDifferentWord(context: FormScoringContext, word: JmDictWord): Boolean =
        context.dictionaryForm != null && context.dictionaryForm != context.surface &&
            !wordHasFormEquivalentTo(word, context.dictionaryForm)

    fun surfaceEquivalentTo(surface: String, formText: String): Boolean =
        surface == formText || isPureKanaScriptDifference(surface, formText)

    fun isInflectableVerbOrAdj(pos: Iterable<String>): Boolean {
        for (p in pos) {
            if (p == "adj-i" || p == "adj-ix") return true
            if (p.isNotEmpty() && p[0] == 'v' && p != "vulg" && p != "vet" && p != "vidg") return true
        }
        return false
    }

    fun getCommonPrefixLen(s1: String, s2: String): Int {
        val len = min(s1.length, s2.length)
        var match = 0
        for (i in 0 until len) if (s1[i] == s2[i]) match++ else break
        return match
    }

    fun isKanaSurfaceWithNoMatchingReading(context: FormScoringContext, word: JmDictWord, formText: String): Boolean {
        if (!context.isKanaSurface || !containsKanji(formText)) return false
        if (word.forms.none { !containsKanji(it.text) }) return false

        val surfaceFirstChar = if (context.surfaceHiragana.isNotEmpty()) context.surfaceHiragana[0] else '\u0000'
        for (f in word.forms) {
            if (containsKanji(f.text)) continue
            val fHiragana = toNormalizedHiragana(f.text, convertLongVowelMark = false)
            if (fHiragana.isNotEmpty() && fHiragana[0] == surfaceFirstChar) return false
        }
        return true
    }

    fun containsKanji(text: String): Boolean = text.any(JapaneseTextHelper::isKanji)

    fun containsKatakana(text: String): Boolean = text.any { it in 'ァ'..'ヺ' }

    fun isPureKatakanaToken(text: String): Boolean {
        var hasKatakana = false
        for (c in text) {
            if (c in 'ァ'..'ヺ') { hasKatakana = true; continue }
            if (c == 'ー' || c == 'ヽ' || c == 'ヾ' || c == '・') continue
            return false
        }
        return hasKatakana
    }

    fun hasFrequencyMarker(priorities: List<String>?, includeJiten: Boolean = true): Boolean {
        if (priorities == null) return false
        for (p in priorities) {
            if (p == "ichi1" || p == "ichi2" || p == "news1" || p == "news2") return true
            if (includeJiten && p == "jiten") return true
            if (p.startsWith("nf")) return true
        }
        return false
    }
}

internal object PosAffinityScorer {
    private val NonSuruVerbTags = hashSetOf("v1", "v1-s", "vs-c", "vs-i", "vs-s", "v5a", "v5b", "v5g", "v5k", "v5k-s",
        "v5m", "v5n", "v5r", "v5r-i", "v5s", "v5t", "v5u", "v5u-s", "v5uru", "vk", "vz", "aux-v")

    fun score(candidate: FormCandidate, context: FormScoringContext): Int {
        if (context.sudachiPOS == PartOfSpeech.Unknown) return 0

        val isHighConfidencePOS = context.sudachiPOS == PartOfSpeech.Verb || context.sudachiPOS == PartOfSpeech.IAdjective ||
            context.sudachiPOS == PartOfSpeech.Suffix || context.sudachiPOS == PartOfSpeech.Interjection
        if (!isHighConfidencePOS) return 0

        val compatible = PosMapper.isJmDictCompatibleWithSudachi(candidate.word.cachedPOS, context.sudachiPOS)
        if (!compatible) return if (context.sudachiPOS == PartOfSpeech.Interjection) -250 else -20

        var score = 25

        // A noun+suffix hybrid (ゲ [n-suf, n]) must not beat a pure suffix (げ) via noun-particle synergy.
        if (context.sudachiPOS == PartOfSpeech.Suffix && PosMask.has(candidate.word.cachedPOSMask, PosMask.NounLike)) score -= 50

        // A DictionaryForm's godan row rules out other rows (こく v5k vs こる v5r).
        if (context.sudachiPOS == PartOfSpeech.Verb && !context.dictionaryForm.isNullOrEmpty() &&
            context.dictionaryForm != context.surface) {
            val expectedTag = inferGodanTag(context.dictionaryForm)
            if (expectedTag != null) {
                val hasMatchingTag = expectedTag in candidate.word.partsOfSpeech || (expectedTag + "-s") in candidate.word.partsOfSpeech
                if (!hasMatchingTag) score -= 45
            }
        }

        // Suru nouns conjugate only via する: 遺棄 いき must not match いきました (行く).
        val dfh = context.dictionaryFormHiragana
        if (context.sudachiPOS == PartOfSpeech.Verb && candidate.form.formType == JmDictFormType.KanaForm &&
            "vs" in candidate.word.partsOfSpeech && candidate.word.partsOfSpeech.none { it in NonSuruVerbTags } &&
            // Exempts DictionaryForm = form + する, or キスさせてくれ resolves to 記す.
            !(dfh != null && dfh.length > 2 && dfh.endsWith("する") && candidate.formTextHiragana + "する" == dfh))
            score -= 60

        // Sudachi 非自立可能 marks auxiliary use (te-form + くれる → 呉れる, not 暮れる).
        if (context.isSudachiPossibleDependant && context.sudachiPOS == PartOfSpeech.Verb)
            score += if ("aux-v" in candidate.word.partsOfSpeech) 30 else -30

        return score
    }

    private fun inferGodanTag(dictionaryForm: String): String? {
        if (dictionaryForm.isEmpty()) return null
        return when (dictionaryForm[dictionaryForm.length - 1]) {
            'く' -> "v5k"
            'ぐ' -> "v5g"
            'す' -> "v5s"
            'つ' -> "v5t"
            'ぬ' -> "v5n"
            'ぶ' -> "v5b"
            'む' -> "v5m"
            'う' -> "v5u"
            else -> null // る is ambiguous between v5r and v1
        }
    }
}
