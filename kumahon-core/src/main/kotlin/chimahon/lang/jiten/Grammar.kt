// Ported from Jiten (github.com/Sirush/Jiten, Apache-2.0, commit 44b48a4): Jiten.Parser/Grammar/TransitionRule.cs,
// TransitionRuleEngine.cs, TransitionRuleSets.cs
package chimahon.lang.jiten

import chimahon.lang.jiten.MatchCondition as M
import chimahon.lang.jiten.PartOfSpeech as P
import chimahon.lang.jiten.ScoringCondition as S

// TransitionRule.cs

internal enum class ViolationAction { None, RemoveCurrent, MergeWithPrevious, ReclassifyCurrentAsNoun, RequestResegmentation }

internal enum class MatchCondition {
    IsVerbOnlyAux,       // Auxiliary with DictionaryForm in VerbOnlyAuxDictForms
    IsVerbOrAdjAux,      // Auxiliary with DictionaryForm in VerbOrAdjAuxDictForms
    IsVerbAttachingAux,  // IsVerbOnlyAux || IsVerbOrAdjAux (for leading-strip rule)
    IsCounter,           // PartOfSpeech == Counter || (Suffix with Counter section)
    IsSentenceInitial,   // Index == 0
    PrevIsVerbOrAux,             // Prev.PartOfSpeech is Verb or Auxiliary
    PrevIsVerbAuxIAdjOrSfp,      // Prev.PartOfSpeech is Verb, Auxiliary, IAdjective, or sentence-ending particle
    IsNotEmphaticSfp,            // Current.Text is not the emphatic ぞ/ぜ (they never merge)
    PrevIsNumericOrNoun, // Prev.PartOfSpeech is Numeral, Noun, CommonNoun, Pronoun, or Name
    PrevExists,          // Prev != null
    IsSentenceEndingParticle, // PartOfSpeech == Particle && Section == SentenceEndingParticle
    NextIsContentWord,   // Next.PartOfSpeech is a content-bearing POS (noun/verb/adj/adverb/etc.)
    IsPrefix,            // PartOfSpeech == Prefix
    IsSentenceFinal,     // Index == Count - 1
    NextIsParticle,      // Next.PartOfSpeech == Particle
    IsSuffix,            // PartOfSpeech == Suffix
    IsStrictCaseMarkingParticle, // Particle with DictionaryForm in StrictCaseMarkingParticles (が/を/へ)
    NextIsNotQuotative, // Next token text does NOT start with quotative と (excludes embedded question patterns like かというと)
    PrevIsSfpValidHost, // Prev is Auxiliary/Particle, or a plain-form Verb/IAdjective (prohibitive えぐるな, exclamatory 欲しいな)
}

internal data class TransitionRule(
    val id: String,
    val whenToken: Array<M>,
    val validIf: Array<M>,
    val onViolation: ViolationAction,
)

internal data class TokenWindow(val prev: WordInfo?, val current: WordInfo, val next: WordInfo?, val index: Int, val count: Int)

internal enum class ScoringCondition {
    CandidateIsNounLike,
    CandidateIsNaAdj,
    CandidateIsAdverb,
    CandidateIsAuxiliary,
    CandidateIsParticle,
    CandidateIsSingleKanaNonParticle,

    NextIsCommonParticle,
    NextIsCopula,
    NextIsNaConnector,
    NextIsVerbOrIAdj,
    PrevIsVerbOrIAdj,
    PrevIsParticle,
    NextIsParticle,
    PrevIsSingleKanaNonParticle,
    NextIsSingleKanaNonParticle,
    CandidateIsPredicateHost,
    CandidateIsNoParticle,
    NextIsExplanatoryN,
    PrevIsVerbAuxOrIAdj,
    CandidateIsCounter,
    PrevIsNumeral,
    PrevIsNotNumericLike,
    CandidateIsSingleKanji,
    PrevIsSingleKanji,
    NextIsSingleKanji,
    NextIsConditionalParticle,
    CandidateIsAdvTo,
    NextIsToParticle,
    CandidateIsVerb,
    NextIsTeFormAux,
    PrevIsNoParticle,
    NextIsNotNaAdjConnector,
    NextIsBaParticle,
    CandidateIsPrenounAdjectival,
    NextIsNounLike,
    CandidateIsConjunction,
    IsSentenceInitial,
    CandidateIsInterjection,
    CandidateIsSuruNoun,
    NextIsSuru,
    PrevIsCaseParticle,
    CandidateIsName,
    NextIsHonorific,
    IsSentenceFinal,
    CandidateIsNounSuffix,
    PrevIsNounLike,
    CandidateIsNotNounLike,
    CandidateIsNotAdverb,
    NextIsNotNounLike,
    CandidateIsHonorific,
    CandidateIsNotHonorific,
    PrevIsName,
    PrevIsAuxiliary,
    NextIsNaAdj,
    PrevIsToParticle,
    CandidateIsNotName,
    CandidateHasVolitionalChain,
    NextIsVolitionalToVerb,
    CandidateIsInfinitiveOrImperative,
    NextIsNiParticle,
}

internal class ScoringRule(
    val id: String,
    val candidateMatch: Array<S>,
    val contextMatch: Array<S>,
    val delta: Int,
) {
    val requiredCandidateMask: Int = computeRequiredCandidateMask(candidateMatch)

    private companion object {
        fun computeRequiredCandidateMask(conditions: Array<S>): Int {
            var mask = 0
            for (c in conditions) {
                mask = mask or when (c) {
                    S.CandidateIsNounLike -> PosMask.NounLike
                    S.CandidateIsNaAdj -> PosMask.NaAdjective
                    S.CandidateIsAdverb -> PosMask.AdverbGroup
                    S.CandidateIsAuxiliary -> PosMask.Auxiliary
                    S.CandidateIsParticle -> PosMask.Particle
                    S.CandidateIsPredicateHost -> PosMask.PredicateHost
                    S.CandidateIsCounter -> PosMask.Counter
                    S.CandidateIsAdvTo -> PosMask.AdverbTo
                    S.CandidateIsVerb -> PosMask.Verb
                    S.CandidateIsPrenounAdjectival -> PosMask.PrenounAdjectival
                    S.CandidateIsConjunction -> PosMask.Conjunction
                    S.CandidateIsInterjection -> PosMask.Interjection
                    S.CandidateIsName -> PosMask.NameBit
                    S.CandidateIsNoParticle -> PosMask.Particle
                    S.CandidateIsNounSuffix -> PosMask.SuffixGroup
                    S.CandidateIsHonorific -> PosMask.SuffixGroup
                    else -> 0
                }
            }
            return mask
        }
    }
}

/**
 * C#'s ScoringWindow holds a FormCandidate; the rules only read its word, form and deconjugation form, so those are the
 * fields (the secondary constructor takes the FormCandidate, as C# does).
 */
internal data class ScoringWindow(
    val candidateWord: JmDictWord,
    val candidateForm: JmDictWordForm,
    val candidateDeconjForm: DeconjugationForm?,
    val prevMask: Int,
    val hasPrev: Boolean,
    val prevText: String?,
    val nextMask: Int,
    val hasNext: Boolean,
    val nextText: String?,
) {
    constructor(
        candidate: FormCandidate, prevMask: Int, hasPrev: Boolean, prevText: String?, nextMask: Int, hasNext: Boolean,
        nextText: String?,
    ) : this(candidate.word, candidate.form, candidate.deconjForm, prevMask, hasPrev, prevText, nextMask, hasNext, nextText)
}

// TransitionRuleEngine.cs

internal object TransitionRuleEngine {
    private val LeadingStripRules: List<TransitionRule> =
        TransitionRuleSets.HardRules.filter { it.id == "leading-aux-strip" || it.id == "particle-at-sentence-start" }

    // Pass-2 rules keep their declaration order: break-on-first-match makes order load-bearing.
    private val ContextHardRules: List<TransitionRule> =
        TransitionRuleSets.HardRules.filter { it.id != "leading-aux-strip" && it.id != "particle-at-sentence-start" }

    /** Diagnostics are left out (C#'s optional ParserDiagnostics argument). */
    fun applyHardRules(words: MutableList<SentenceWord>, hasLookup: (String) -> Boolean) {
        // Loops because removing index 0 exposes a new index 0.
        var leadingRemoved: Boolean
        do {
            leadingRemoved = false
            if (words.isEmpty()) break
            val window = buildWindow(words, 0)
            for (rule in LeadingStripRules) {
                if (!matchesAll(window, rule.whenToken)) continue
                if (isValidState(window, rule.validIf)) continue
                words.removeAt(0)
                leadingRemoved = true
                break
            }
        } while (leadingRemoved)

        // Backwards so merges and removals never shift unvisited indices.
        for (i in words.indices.reversed()) {
            val window = buildWindow(words, i)
            for (rule in ContextHardRules) {
                if (!matchesAll(window, rule.whenToken)) continue
                if (isValidState(window, rule.validIf)) continue
                applyViolation(rule, words, i, hasLookup)
                break
            }
        }
    }

    // Empty ValidIf means never valid.
    private fun isValidState(w: TokenWindow, validIf: Array<M>): Boolean {
        if (validIf.isEmpty()) return false
        return matchesAll(w, validIf)
    }

    private val ContentWordPos = setOf(
        P.Noun, P.CommonNoun, P.Name, P.Pronoun, P.Verb, P.IAdjective, P.NaAdjective, P.NominalAdjective, P.Adverb,
        P.AdverbTo, P.Numeral, P.PrenounAdjectival, P.Counter, P.Prefix, P.Expression,
    )

    private fun matchesAll(w: TokenWindow, conditions: Array<M>): Boolean {
        for (c in conditions) {
            val prevPos = w.prev?.partOfSpeech
            val ok = when (c) {
                M.IsVerbOnlyAux ->
                    w.current.partOfSpeech == P.Auxiliary &&
                        w.current.dictionaryForm in TransitionRuleSets.VerbOnlyAuxDictForms
                M.IsVerbOrAdjAux ->
                    w.current.partOfSpeech == P.Auxiliary &&
                        w.current.dictionaryForm in TransitionRuleSets.VerbOrAdjAuxDictForms
                M.IsVerbAttachingAux ->
                    w.current.partOfSpeech == P.Auxiliary &&
                        (w.current.dictionaryForm in TransitionRuleSets.VerbOnlyAuxDictForms ||
                            w.current.dictionaryForm in TransitionRuleSets.VerbOrAdjAuxDictForms)
                M.IsCounter ->
                    w.current.partOfSpeech == P.Counter ||
                        (w.current.partOfSpeech == P.Suffix && w.current.hasPartOfSpeechSection(PartOfSpeechSection.Counter))
                M.IsSentenceInitial -> w.index == 0
                M.PrevIsVerbOrAux -> prevPos == P.Verb || prevPos == P.Auxiliary
                M.PrevIsVerbAuxIAdjOrSfp ->
                    prevPos == P.Verb || prevPos == P.Auxiliary || prevPos == P.IAdjective ||
                        (prevPos == P.Particle &&
                            w.prev?.hasPartOfSpeechSection(PartOfSpeechSection.SentenceEndingParticle) == true)
                M.PrevIsNumericOrNoun ->
                    prevPos == P.Numeral || prevPos == P.Noun || prevPos == P.CommonNoun || prevPos == P.Pronoun ||
                        prevPos == P.Name
                M.IsNotEmphaticSfp -> w.current.text != "ぞ" && w.current.text != "ぜ"
                M.PrevExists -> w.prev != null
                M.IsSentenceEndingParticle ->
                    w.current.partOfSpeech == P.Particle &&
                        w.current.hasPartOfSpeechSection(PartOfSpeechSection.SentenceEndingParticle)
                M.NextIsContentWord -> w.next?.partOfSpeech in ContentWordPos
                M.IsPrefix -> w.current.partOfSpeech == P.Prefix
                M.IsSentenceFinal -> w.index == w.count - 1
                M.NextIsParticle -> w.next?.partOfSpeech == P.Particle
                M.IsSuffix -> w.current.partOfSpeech == P.Suffix
                M.IsStrictCaseMarkingParticle ->
                    w.current.partOfSpeech == P.Particle &&
                        w.current.dictionaryForm in TransitionRuleSets.StrictCaseMarkingParticles
                M.NextIsNotQuotative -> w.next == null || !w.next.text.startsWith("という")
                // Plain predicate + よ/ね is valid only before a vocative (やべえよ母ちゃん), not run-on text (食べるよ明日).
                M.PrevIsSfpValidHost -> {
                    val prev = w.prev
                    val prevPlain = prev != null && prev.text == prev.dictionaryForm
                    prevPos == P.Auxiliary || prevPos == P.Particle ||
                        (w.current.text == "な" && (prevPos == P.Verb || prevPos == P.IAdjective) && prevPlain) ||
                        (w.current.text == "か" && prevPos == P.Verb && prevPlain) ||
                        ((w.current.text == "よ" || w.current.text == "ね") &&
                            (prevPos == P.Verb || prevPos == P.IAdjective) && prevPlain && isVocativeFollower(w.next)) ||
                        // Clause-initial noun + よ is the vocative (主よ, 神よ); merging it fabricates a verb (主る).
                        (w.current.text == "よ" && w.index == 1 &&
                            (prevPos == P.Noun || prevPos == P.CommonNoun || prevPos == P.Pronoun || prevPos == P.Name))
                }
            }
            if (!ok) return false
        }
        return true
    }

    private fun isVocativeFollower(next: WordInfo?): Boolean {
        if (next?.partOfSpeech == P.Name || next?.partOfSpeech == P.Pronoun) return true
        if (next == null || (next.partOfSpeech != P.Noun && next.partOfSpeech != P.CommonNoun)) return false
        return TransitionRuleSets.HonorificSuffixes.any { next.text.endsWith(it) }
    }

    private fun applyViolation(rule: TransitionRule, words: MutableList<SentenceWord>, i: Int, hasLookup: (String) -> Boolean) {
        when (rule.onViolation) {
            ViolationAction.RemoveCurrent -> words.removeAt(i)
            ViolationAction.MergeWithPrevious -> {
                if (i == 0) {
                    words.removeAt(i)
                    return
                }
                val (prevWord, prevPos, prevLen) = words[i - 1]
                val merged = prevWord.text + words[i].word.text
                if (hasLookup(merged)) {
                    val auxLen = words[i].length
                    words[i - 1] = SentenceWord(prevWord.copy().apply {
                        text = merged
                        dictionaryForm = merged
                        normalizedForm = merged
                        partOfSpeech = prevWord.partOfSpeech
                    }, prevPos, prevLen + auxLen)
                    words.removeAt(i)
                } else if (prevWord.partOfSpeech == P.Noun && hasLookup(prevWord.text + "る")) {
                    val auxLen = words[i].length
                    val verbDictForm = prevWord.text + "る"
                    words[i - 1] = SentenceWord(prevWord.copy().apply {
                        text = merged
                        dictionaryForm = verbDictForm
                        normalizedForm = verbDictForm
                        partOfSpeech = P.Verb
                    }, prevPos, prevLen + auxLen)
                    words.removeAt(i)
                } else {
                    words.removeAt(i)
                }
            }
            ViolationAction.ReclassifyCurrentAsNoun -> words[i].word.partOfSpeech = P.Noun
            else -> {}
        }
    }

    private fun isSingleKanji(text: String?) = text != null && text.length == 1 && JapaneseTextHelper.isKanji(text[0])

    private fun buildWindow(words: List<SentenceWord>, i: Int): TokenWindow {
        val prev = if (i > 0) words[i - 1].word else null
        val next = if (i + 1 < words.size) words[i + 1].word else null
        return TokenWindow(prev, words[i].word, next, i, words.size)
    }

    // ContextMatch must read only prev/next/position fields, so its mask is per token; rules past 64 evaluate live.
    private const val MaskCapacity = 64

    /** C#'s ulong mask as Long (bit r = soft rule r). */
    fun computeContextApplicableMask(
        prevMask: Int, hasPrev: Boolean, prevText: String?,
        nextMask: Int, hasNext: Boolean, nextText: String?,
    ): Long {
        val ctx = ConditionContext(0, "", prevMask, hasPrev, prevText, nextMask, hasNext, nextText)
        val rules = TransitionRuleSets.SoftRules
        var mask = 0L
        val n = minOf(MaskCapacity, rules.size)
        for (r in 0 until n)
            if (matchesAll(ctx, rules[r].contextMatch)) mask = mask or (1L shl r)
        return mask
    }

    fun evaluateSoftRules(window: ScoringWindow, contextApplicableMask: Long, rulesMatched: MutableList<String>? = null): Int {
        var bonus = 0
        val ctx = ConditionContext.fromScoringWindow(window)
        val rules = TransitionRuleSets.SoftRules
        for (r in rules.indices) {
            val contextApplicable = if (r < MaskCapacity) (contextApplicableMask and (1L shl r)) != 0L
            else matchesAll(ctx, rules[r].contextMatch)
            if (!contextApplicable) continue

            val rule = rules[r]
            if (rule.requiredCandidateMask != 0 && !PosMask.has(ctx.candidateMask, rule.requiredCandidateMask)) continue
            if (!matchesAll(ctx, rule.candidateMatch)) continue

            bonus += rule.delta
            rulesMatched?.add(rule.id)
        }
        return bonus
    }

    fun couldAnySoftRuleApply(
        currentPOS: List<P>, currentText: String,
        prevPOS: List<P>?, prevText: String?,
        nextPOS: List<P>?, nextText: String?,
    ): Boolean {
        if (currentPOS.isEmpty()) return false
        return anySoftRuleMatches(ConditionContext(
            PosMask.fromList(currentPOS), currentText,
            if (prevPOS != null) PosMask.fromList(prevPOS) else 0, prevPOS != null, prevText,
            if (nextPOS != null) PosMask.fromList(nextPOS) else 0, nextPOS != null, nextText))
    }

    private fun anySoftRuleMatches(ctx: ConditionContext): Boolean {
        for (rule in TransitionRuleSets.SoftRules) {
            if (rule.requiredCandidateMask != 0 && !PosMask.has(ctx.candidateMask, rule.requiredCandidateMask)) continue
            if (!matchesAll(ctx, rule.candidateMatch)) continue
            if (matchesAll(ctx, rule.contextMatch)) return true
        }
        return false
    }

    private data class ConditionContext(
        val candidateMask: Int,
        val candidateText: String,
        val prevMask: Int,
        val hasPrev: Boolean,
        val prevText: String?,
        val nextMask: Int,
        val hasNext: Boolean,
        val nextText: String?,
        val candidateIsSuruNounVal: Boolean = false,
        val candidateHasHonorificRegister: Boolean = false,
        // True by default: the prefilter has no candidate, so deconjugation-dependent rules must stay applicable.
        val candidateHasVolitionalChainVal: Boolean = true,
        val candidateIsInfinitiveVal: Boolean = true,
    ) {
        companion object {
            fun fromScoringWindow(w: ScoringWindow): ConditionContext {
                val process = w.candidateDeconjForm?.process
                return ConditionContext(
                    w.candidateWord.cachedPOSMask,
                    w.candidateForm.text,
                    w.prevMask, w.hasPrev, w.prevText,
                    w.nextMask, w.hasNext, w.nextText,
                    w.candidateWord.isSuruVerb,
                    w.candidateForm.text in TransitionRuleSets.HonorificSuffixes &&
                        PosMask.has(w.candidateWord.cachedPOSMask, PosMask.SuffixGroup) &&
                        w.candidateWord.partsOfSpeech.any { it in TransitionRuleSets.HonorificRegisterTags },
                    process?.contains("volitional") == true,
                    process != null && process.isNotEmpty() &&
                        process.last().let { it == "(infinitive)" || it == "(unstressed infinitive)" || it == "imperative" },
                )
            }
        }
    }

    private fun matchesAll(ctx: ConditionContext, conditions: Array<S>): Boolean {
        for (c in conditions) {
            val ok = when (c) {
                S.CandidateIsNounLike -> PosMask.has(ctx.candidateMask, PosMask.NounLike)
                S.CandidateIsNaAdj -> PosMask.has(ctx.candidateMask, PosMask.NaAdjective)
                S.CandidateIsAdverb -> PosMask.has(ctx.candidateMask, PosMask.AdverbGroup)
                S.CandidateIsAuxiliary -> PosMask.has(ctx.candidateMask, PosMask.Auxiliary)
                S.CandidateIsParticle -> PosMask.has(ctx.candidateMask, PosMask.Particle)
                S.CandidateIsSingleKanaNonParticle ->
                    ctx.candidateText.length <= 1 && !PosMask.has(ctx.candidateMask, PosMask.Particle)
                S.NextIsCommonParticle ->
                    PosMask.has(ctx.nextMask, PosMask.Particle) &&
                        ctx.nextText != null && ctx.nextText in TransitionRuleSets.CommonParticles
                S.NextIsCopula -> ctx.nextText != null && ctx.nextText in TransitionRuleSets.CopulaForms
                S.NextIsNaConnector -> ctx.nextText == "な"
                S.NextIsNiParticle -> ctx.nextText == "に" && PosMask.has(ctx.nextMask, PosMask.Particle)
                S.NextIsVerbOrIAdj -> ctx.hasNext && PosMask.has(ctx.nextMask, PosMask.VerbOrIAdj)
                S.PrevIsVerbOrIAdj -> ctx.hasPrev && PosMask.has(ctx.prevMask, PosMask.VerbOrIAdj)
                S.PrevIsParticle -> ctx.hasPrev && PosMask.has(ctx.prevMask, PosMask.Particle)
                S.NextIsParticle -> ctx.hasNext && PosMask.has(ctx.nextMask, PosMask.Particle)
                S.PrevIsSingleKanaNonParticle -> ctx.prevText?.length == 1 && !PosMask.has(ctx.prevMask, PosMask.Particle)
                S.NextIsSingleKanaNonParticle -> ctx.nextText?.length == 1 && !PosMask.has(ctx.nextMask, PosMask.Particle)
                S.CandidateIsPredicateHost -> PosMask.has(ctx.candidateMask, PosMask.PredicateHost)
                S.CandidateIsNoParticle -> ctx.candidateText == "の" && PosMask.has(ctx.candidateMask, PosMask.Particle)
                S.NextIsExplanatoryN -> ctx.nextText != null && ctx.nextText in TransitionRuleSets.ExplanatoryNForms
                S.PrevIsVerbAuxOrIAdj -> ctx.hasPrev && PosMask.has(ctx.prevMask, PosMask.VerbAuxOrIAdj)
                S.CandidateIsCounter -> PosMask.has(ctx.candidateMask, PosMask.Counter)
                S.PrevIsNumeral -> ctx.hasPrev && PosMask.has(ctx.prevMask, PosMask.Numeral)
                S.PrevIsNotNumericLike -> !ctx.hasPrev || !PosMask.has(ctx.prevMask, PosMask.NumericLike)
                S.CandidateIsSingleKanji -> isSingleKanji(ctx.candidateText)
                S.PrevIsSingleKanji -> isSingleKanji(ctx.prevText)
                S.NextIsSingleKanji -> isSingleKanji(ctx.nextText)
                S.NextIsConditionalParticle ->
                    PosMask.has(ctx.nextMask, PosMask.Particle) &&
                        ctx.nextText != null && ctx.nextText in TransitionRuleSets.ConditionalParticles
                S.CandidateIsAdvTo -> PosMask.has(ctx.candidateMask, PosMask.AdverbTo)
                S.NextIsToParticle ->
                    (ctx.nextText == "と" && PosMask.has(ctx.nextMask, PosMask.Particle)) || ctx.nextText == "という"
                S.CandidateIsVerb -> PosMask.has(ctx.candidateMask, PosMask.Verb)
                S.NextIsTeFormAux -> ctx.nextText != null && ctx.nextText in TransitionRuleSets.TeFormAuxiliaries
                S.PrevIsNoParticle -> ctx.prevText == "の" && PosMask.has(ctx.prevMask, PosMask.Particle)
                S.NextIsNotNaAdjConnector ->
                    ctx.nextText != null &&
                        ctx.nextText != "な" && ctx.nextText != "に" && ctx.nextText != "で" && ctx.nextText != "の" &&
                        ctx.nextText !in TransitionRuleSets.CopulaForms &&
                        !PosMask.has(ctx.nextMask, PosMask.Particle) &&
                        !PosMask.has(ctx.nextMask, PosMask.SuffixGroup)
                S.NextIsBaParticle -> ctx.nextText == "ば" && PosMask.has(ctx.nextMask, PosMask.Particle)
                S.CandidateIsPrenounAdjectival -> PosMask.has(ctx.candidateMask, PosMask.PrenounAdjectival)
                S.NextIsNounLike -> ctx.hasNext && PosMask.has(ctx.nextMask, PosMask.NounLike)
                S.NextIsNotNounLike -> !ctx.hasNext || !PosMask.has(ctx.nextMask, PosMask.NounLike)
                S.CandidateIsConjunction -> PosMask.has(ctx.candidateMask, PosMask.Conjunction)
                S.IsSentenceInitial -> !ctx.hasPrev && ctx.prevText == null
                S.CandidateIsInterjection ->
                    PosMask.has(ctx.candidateMask, PosMask.Interjection) &&
                        ctx.candidateText in TransitionRuleSets.Interjections
                S.CandidateIsSuruNoun -> ctx.candidateIsSuruNounVal
                S.NextIsSuru -> ctx.nextText != null && ctx.nextText in TransitionRuleSets.SuruForms
                S.PrevIsCaseParticle ->
                    PosMask.has(ctx.prevMask, PosMask.Particle) &&
                        ctx.prevText != null && ctx.prevText in TransitionRuleSets.CaseMarkingParticles
                S.CandidateIsName -> PosMask.has(ctx.candidateMask, PosMask.NameBit)
                S.NextIsHonorific -> ctx.nextText != null && ctx.nextText in TransitionRuleSets.HonorificSuffixes
                S.IsSentenceFinal -> !ctx.hasNext && ctx.nextText == null
                S.CandidateIsNounSuffix ->
                    PosMask.has(ctx.candidateMask, PosMask.SuffixGroup) &&
                        ctx.candidateText in TransitionRuleSets.NounSuffixes
                S.PrevIsNounLike -> ctx.hasPrev && PosMask.has(ctx.prevMask, PosMask.NounLike)
                S.CandidateIsNotNounLike -> !PosMask.has(ctx.candidateMask, PosMask.NounLike)
                S.CandidateIsNotAdverb -> !PosMask.has(ctx.candidateMask, PosMask.AdverbGroup)
                S.CandidateIsHonorific ->
                    ctx.candidateText in TransitionRuleSets.HonorificSuffixes &&
                        PosMask.has(ctx.candidateMask, PosMask.SuffixGroup) &&
                        ctx.candidateHasHonorificRegister
                S.CandidateIsNotHonorific -> ctx.candidateText !in TransitionRuleSets.HonorificSuffixes
                S.PrevIsName -> ctx.hasPrev && PosMask.has(ctx.prevMask, PosMask.NameBit)
                S.PrevIsAuxiliary -> ctx.hasPrev && PosMask.has(ctx.prevMask, PosMask.Auxiliary)
                S.NextIsNaAdj -> ctx.hasNext && PosMask.has(ctx.nextMask, PosMask.NaAdjective)
                S.PrevIsToParticle -> ctx.hasPrev && ctx.prevText == "と" && PosMask.has(ctx.prevMask, PosMask.Particle)
                S.CandidateIsNotName -> !PosMask.has(ctx.candidateMask, PosMask.NameBit)
                S.CandidateHasVolitionalChain -> ctx.candidateHasVolitionalChainVal
                S.NextIsVolitionalToVerb -> ctx.nextText != null && ctx.nextText in TransitionRuleSets.VolitionalToVerbForms
                S.CandidateIsInfinitiveOrImperative -> ctx.candidateIsInfinitiveVal
            }
            if (!ok) return false
        }
        return true
    }
}

// TransitionRuleSets.cs

internal object TransitionRuleSets {
    val VerbOnlyAuxDictForms: Set<String> = hashSetOf("られる", "れる", "せる", "させる", "たい", "たがる", "ます")

    val VerbOrAdjAuxDictForms: Set<String> = hashSetOf("た", "ぬ")

    val CommonParticles: Set<String> = hashSetOf(
        "が", "を", "に", "で", "へ", "は", "の", "も", "や",
        "から", "まで", "より", "だけ", "しか", "ばかり", "など", "さえ",
    )

    val CaseMarkingParticles: Set<String> = hashSetOf("が", "を", "に", "で", "へ")

    // Never sentence-initial; で is left out because conjunctive で can open a sentence.
    val StrictCaseMarkingParticles: Set<String> = hashSetOf("が", "を", "へ")

    val CopulaForms: Set<String> = hashSetOf("だ", "です", "である")

    val ExplanatoryNForms: Set<String> = hashSetOf("ん", "んだ", "んです", "んじゃ", "んで")

    val ConditionalParticles: Set<String> = hashSetOf("と", "なら")

    val TeFormAuxiliaries: Set<String> = hashSetOf(
        "いる", "ある", "しまう", "おく", "みる", "くる", "いく", "もらう", "あげる", "くれる",
    )

    val Interjections: Set<String> = hashSetOf("ああ", "ええ", "まあ", "ほら", "よう", "そうそう")

    val SuruForms: Set<String> = hashSetOf("する", "した", "して", "し", "される", "させる")

    // Volitional とする "try to" forces the volitional reading (走り出そうと = 走り出す, not 走り出る + そう).
    val VolitionalToVerbForms: Set<String> = hashSetOf(
        "とする", "として", "とした", "としている", "としていた", "とすれば", "としたら", "と思う", "と思った",
    )

    val HonorificSuffixes: Set<String> = hashSetOf("さん", "くん", "ちゃん", "様", "殿", "氏")

    val HonorificRegisterTags: Set<String> = hashSetOf("pol", "hon", "fam")

    val NounSuffixes: Set<String> = hashSetOf("的", "性", "化", "中", "用", "式", "風")

    // A nearby resolved contextWordId boosts the target verb over its homophones.
    val NounVerbCollocations: Map<Int, Pair<Int, Int>> = hashMapOf(
        1172400 to (1444150 to 30), // 嘘 → 吐く (to tell a lie), not 点く/付く
        1597190 to (1444150 to 30), // ため息 → 吐く (to sigh), not 付く
        1371260 to (1229610 to 30), // 水 → 汲む (to draw water), not 組む
    )

    // A next-token surface in NextAnchors boosts the target wordId over a higher-priority homograph.
    val ForwardAnchorBoosts: Map<Int, Pair<Array<String>, Int>> = linkedMapOf(
        // 一分前 = いっぷん "one minute", not the news-ranked いちぶ "one tenth" (1166270).
        1166290 to (arrayOf("前", "後", "ぐらい", "くらい", "ほど", "経つ", "経った", "経って") to 40),
    )

    // Pass-2 prefilter keeps a confident token rescorable when an anchor could flip its homograph.
    val ForwardAnchorSurfaces: Set<String> = ForwardAnchorBoosts.values.flatMap { it.first.asList() }.toHashSet()

    // A nominal previous token boosts the target wordId over a higher-priority homograph of the surface.
    val PrevNominalBoosts: Map<Int, Pair<String, Int>> = linkedMapOf(
        // NといいNといい "both N and N" follows a nominal; the wish 〜といい (2872982) follows a predicate.
        2844736 to ("といい" to 40),
    )

    val PrevNominalBoostSurfaces: Set<String> = PrevNominalBoosts.values.map { it.first }.toHashSet()

    private fun rule(id: String, candidate: Array<S>, context: Array<S>, delta: Int) =
        ScoringRule(id, candidate, context, delta)

    val SoftRules: Array<ScoringRule> = arrayOf(
        rule("noun-particle-synergy", arrayOf(S.CandidateIsNounLike), arrayOf(S.NextIsCommonParticle), 40),
        rule("noun-copula-synergy",
            arrayOf(S.CandidateIsNounLike, S.CandidateIsNotHonorific, S.CandidateIsNotName), arrayOf(S.NextIsCopula), 65),
        rule("na-adj-connector-synergy", arrayOf(S.CandidateIsNaAdj), arrayOf(S.NextIsNaConnector), 30),
        // Must outweigh noun-particle-synergy (+40) from the same に (露になった = あらわ, not dew).
        rule("na-adj-ni-adverbial-synergy", arrayOf(S.CandidateIsNaAdj), arrayOf(S.NextIsNiParticle), 65),
        rule("adverb-verb-synergy", arrayOf(S.CandidateIsAdverb), arrayOf(S.NextIsVerbOrIAdj), 20),
        rule("verb-aux-synergy", arrayOf(S.CandidateIsAuxiliary), arrayOf(S.PrevIsVerbOrIAdj), 20),
        rule("aux-copula-synergy", arrayOf(S.CandidateIsAuxiliary), arrayOf(S.NextIsCopula), 65),
        rule("single-kana-penalty-left", arrayOf(S.CandidateIsSingleKanaNonParticle), arrayOf(S.PrevIsSingleKanaNonParticle), -40),
        rule("single-kana-penalty-right", arrayOf(S.CandidateIsSingleKanaNonParticle), arrayOf(S.NextIsSingleKanaNonParticle), -40),
        rule("particle-particle-penalty-left",
            arrayOf(S.CandidateIsParticle, S.CandidateIsNotNounLike), arrayOf(S.PrevIsParticle), -20),
        rule("particle-particle-penalty-right",
            arrayOf(S.CandidateIsParticle, S.CandidateIsNotNounLike), arrayOf(S.NextIsParticle), -20),
        rule("no-da-synergy", arrayOf(S.CandidateIsNoParticle), arrayOf(S.PrevIsVerbAuxOrIAdj, S.NextIsCopula), 25),
        rule("predicate-explanatory-n-synergy", arrayOf(S.CandidateIsPredicateHost), arrayOf(S.NextIsExplanatoryN), 25),
        // Must outbid a top-priority noun homograph plus its ruby prior (第二話: counter わ beats はなし).
        rule("numeral-counter-cohesion", arrayOf(S.CandidateIsCounter), arrayOf(S.PrevIsNumeral), 60),
        rule("orphan-counter-penalty",
            arrayOf(S.CandidateIsCounter, S.CandidateIsNotNounLike), arrayOf(S.PrevIsNotNumericLike), -30),
        rule("kanji-compound-break-penalty-left", arrayOf(S.CandidateIsSingleKanji), arrayOf(S.PrevIsSingleKanji), -30),
        rule("kanji-compound-break-penalty-right", arrayOf(S.CandidateIsSingleKanji), arrayOf(S.NextIsSingleKanji), -30),
        rule("conjunctive-particle-verb-link", arrayOf(S.CandidateIsPredicateHost), arrayOf(S.NextIsConditionalParticle), 20),
        rule("adv-to-to-synergy", arrayOf(S.CandidateIsAdvTo), arrayOf(S.NextIsToParticle), 25),
        rule("verb-te-form-aux-synergy", arrayOf(S.CandidateIsVerb), arrayOf(S.NextIsTeFormAux), 25),
        rule("noun-no-noun-synergy", arrayOf(S.CandidateIsNounLike), arrayOf(S.PrevIsNoParticle), 20),
        // A 連用形 never follows genitive の (子供たちの群れ is the noun); real noun+verb compounds are fused upstream.
        rule("genitive-infinitive-penalty",
            arrayOf(S.CandidateIsVerb, S.CandidateIsInfinitiveOrImperative), arrayOf(S.PrevIsNoParticle), -60),
        rule("noun-adjacent-infinitive-penalty",
            arrayOf(S.CandidateIsVerb, S.CandidateIsInfinitiveOrImperative), arrayOf(S.PrevIsNounLike), -70),
        rule("na-adj-no-connector-penalty",
            arrayOf(S.CandidateIsNaAdj, S.CandidateIsNotAdverb), arrayOf(S.NextIsNotNaAdjConnector), -20),
        rule("verb-ba-form-conditional-synergy", arrayOf(S.CandidateIsPredicateHost), arrayOf(S.NextIsBaParticle), 20),
        rule("prenominal-adj-noun-synergy", arrayOf(S.CandidateIsPrenounAdjectival), arrayOf(S.NextIsNounLike), 30),
        rule("prenominal-adj-not-noun-penalty", arrayOf(S.CandidateIsPrenounAdjectival), arrayOf(S.NextIsNotNounLike), -200),
        rule("conjunction-at-boundary-synergy", arrayOf(S.CandidateIsConjunction), arrayOf(S.IsSentenceInitial), 15),
        rule("interjection-at-boundary-synergy", arrayOf(S.CandidateIsInterjection), arrayOf(S.IsSentenceInitial), 15),
        rule("interjection-after-predicate-penalty", arrayOf(S.CandidateIsInterjection), arrayOf(S.PrevIsVerbAuxOrIAdj), -120),
        rule("noun-suru-synergy", arrayOf(S.CandidateIsSuruNoun), arrayOf(S.NextIsSuru), 25),
        rule("verb-after-case-particle-synergy", arrayOf(S.CandidateIsVerb), arrayOf(S.PrevIsCaseParticle), 35),
        rule("noun-after-case-particle-demotion",
            arrayOf(S.CandidateIsNounLike, S.CandidateIsNotAdverb), arrayOf(S.PrevIsCaseParticle), -25),
        rule("name-honorific-synergy", arrayOf(S.CandidateIsName), arrayOf(S.NextIsHonorific), 20),
        rule("verb-sentence-final-synergy", arrayOf(S.CandidateIsPredicateHost), arrayOf(S.IsSentenceFinal), 10),
        rule("adverb-before-noun-penalty",
            arrayOf(S.CandidateIsAdverb, S.CandidateIsNotNounLike), arrayOf(S.NextIsNounLike), -15),
        rule("interjection-adverb-noun-exempt", arrayOf(S.CandidateIsInterjection), arrayOf(S.NextIsNounLike), 15),
        rule("suffix-after-noun-synergy", arrayOf(S.CandidateIsNounSuffix), arrayOf(S.PrevIsNounLike), 15),
        rule("honorific-after-name-synergy", arrayOf(S.CandidateIsHonorific), arrayOf(S.PrevIsName), 30),
        rule("noun-after-aux-penalty", arrayOf(S.CandidateIsNounLike), arrayOf(S.PrevIsAuxiliary), -45),
        rule("particle-after-noun-synergy",
            arrayOf(S.CandidateIsParticle, S.CandidateIsNotNounLike), arrayOf(S.PrevIsNounLike), 20),
        rule("adverb-na-adj-synergy", arrayOf(S.CandidateIsAdverb), arrayOf(S.NextIsNaAdj), 20),
        rule("verb-after-quotation-to-synergy", arrayOf(S.CandidateIsVerb), arrayOf(S.PrevIsToParticle), 20),
        rule("volitional-to-suru-synergy",
            arrayOf(S.CandidateIsVerb, S.CandidateHasVolitionalChain), arrayOf(S.NextIsVolitionalToVerb), 40),
    )

    val HardRules: Array<TransitionRule> = arrayOf(
        TransitionRule("leading-aux-strip",
            arrayOf(M.IsSentenceInitial, M.IsVerbAttachingAux), emptyArray(), ViolationAction.RemoveCurrent),
        TransitionRule("aux-must-follow-verb",
            arrayOf(M.IsVerbOnlyAux), arrayOf(M.PrevIsVerbOrAux), ViolationAction.MergeWithPrevious),
        TransitionRule("verb-or-adj-aux-must-follow-content",
            arrayOf(M.IsVerbOrAdjAux), arrayOf(M.PrevIsVerbAuxIAdjOrSfp), ViolationAction.MergeWithPrevious),
        TransitionRule("counter-must-follow-numberlike",
            arrayOf(M.IsCounter), arrayOf(M.PrevExists, M.PrevIsNumericOrNoun), ViolationAction.ReclassifyCurrentAsNoun),
        // Plain predicates host an SFP in unpunctuated speech (欲しいな); ぞ/ぜ never merge (出るぞ無名！).
        TransitionRule("sfp-must-be-near-clause-end",
            arrayOf(M.IsSentenceEndingParticle, M.IsNotEmphaticSfp, M.NextIsContentWord, M.NextIsNotQuotative),
            arrayOf(M.PrevIsSfpValidHost), ViolationAction.MergeWithPrevious),
        TransitionRule("prefix-at-sentence-end",
            arrayOf(M.IsPrefix, M.IsSentenceFinal), emptyArray(), ViolationAction.ReclassifyCurrentAsNoun),
        TransitionRule("prefix-before-particle",
            arrayOf(M.IsPrefix, M.NextIsParticle), emptyArray(), ViolationAction.ReclassifyCurrentAsNoun),
        TransitionRule("suffix-must-follow-content",
            arrayOf(M.IsSuffix, M.IsSentenceInitial), emptyArray(), ViolationAction.ReclassifyCurrentAsNoun),
        // A following content word makes it a valid fragment start (がいないと).
        TransitionRule("particle-at-sentence-start",
            arrayOf(M.IsSentenceInitial, M.IsStrictCaseMarkingParticle), arrayOf(M.NextIsContentWord),
            ViolationAction.RemoveCurrent),
    )
}
