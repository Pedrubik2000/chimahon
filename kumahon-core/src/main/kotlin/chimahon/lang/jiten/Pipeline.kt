// Ported from Jiten (github.com/Sirush/Jiten, Apache-2.0, commit 44b48a4): Jiten.Parser/Stages/MorphologicalAnalyser.Pipeline.cs
// (+ TrackStage from Jiten.Parser/Helpers/MorphologicalAnalyser.Diagnostics.cs, without the diagnostics)
package chimahon.lang.jiten

private val StageDebug: Boolean = !System.getenv("JITEN_STAGE_DEBUG").isNullOrEmpty()

private fun stage(
    group: TokenStageGroup,
    process: kotlin.reflect.KFunction1<MutableList<WordInfo>, MutableList<WordInfo>>,
    requires: Int = TokenFeatures.None,
) = TokenStage(process.name, group, process, requires)

private fun candidateStage(
    group: TokenStageGroup,
    process: kotlin.reflect.KFunction2<MutableList<WordInfo>, List<Int>, MutableList<WordInfo>>,
    candidateFeature: Int,
) = TokenStage(process.name, group, { it }, candidateFeature, process)

/** The stage list in Jiten's order (C# caches it per instance in _tokenStages; so does [MorphologicalAnalyser.tokenStages]). */
internal fun MorphologicalAnalyser.buildTokenStages(): List<TokenStage> = listOf(
    stage(TokenStageGroup.Split, this::splitOovGarbageTokens, TokenFeatures.OovGarbage),
    stage(TokenStageGroup.Split, this::splitCompoundAuxiliaryVerbs),
    stage(TokenStageGroup.Split, this::splitUnresolvableCompoundVerbs),
    stage(TokenStageGroup.Split, this::splitUnresolvableRenyokeiNounCompounds),
    stage(TokenStageGroup.Split, this::splitUnresolvablePrefixedAdjectives),
    stage(TokenStageGroup.Split, this::splitTatteParticle, TokenFeatures.TextTatte),
    stage(TokenStageGroup.Split, this::splitUnattestedToAdverbs, TokenFeatures.AdverbEndsTo),
    stage(TokenStageGroup.Split, this::splitTanSuffix, TokenFeatures.TextTanSuffix),
    stage(TokenStageGroup.Split, this::splitTawakeNoun, TokenFeatures.TextTawake),
    stage(TokenStageGroup.Split, this::splitLexicalisedKaratte, TokenFeatures.TextKaratte),
    stage(TokenStageGroup.Split, this::splitDoushiteContraction),
    stage(TokenStageGroup.Split, this::splitEmphaticMoSuru),

    stage(TokenStageGroup.Repair, this::repairHasaNoun, TokenFeatures.TextHasa),
    stage(TokenStageGroup.Repair, this::repairNTokenisation),
    stage(TokenStageGroup.Repair, this::repairVowelElongation),
    stage(TokenStageGroup.Repair, this::applyTokenRewriteRulesEarly),
    stage(TokenStageGroup.Repair, this::processSpecialCases),
    stage(TokenStageGroup.Repair, this::retokeniseOovBlobs, TokenFeatures.HiraganaOovBlob),
    stage(TokenStageGroup.Repair, this::repairColloquialNegativeNee, TokenFeatures.Interjection),
    stage(TokenStageGroup.Repair, this::repairColloquialRanNai, TokenFeatures.TextRan),
    stage(TokenStageGroup.Repair, this::repairIntensifierKaeru, TokenFeatures.VerbKaeru),
    stage(TokenStageGroup.Repair, this::repairQuotativeTte, TokenFeatures.EndsWithTsu),
    candidateStage(TokenStageGroup.Repair, this::repairGeminateSuffixTheft, TokenFeatures.GeminateSuffixShape),
    candidateStage(TokenStageGroup.Repair, this::repairKatakanaShreds, TokenFeatures.KatakanaRun),
    candidateStage(TokenStageGroup.Repair, this::repairCompoundBoundaryTheft, TokenFeatures.CompoundBoundaryShape),
    candidateStage(TokenStageGroup.Repair, this::repairKanjiVerbShred, TokenFeatures.SingleKanjiNoun),

    stage(TokenStageGroup.Repair, this::recombineHiraganaTokens),
    stage(TokenStageGroup.Repair, this::applyTokenRewriteRulesLate),
    stage(TokenStageGroup.Repair, this::repairSakkiMoraTheft, TokenFeatures.TextSakki),
    stage(TokenStageGroup.Repair, this::collapseReduplicatedMimetic, TokenFeatures.KanaRepetition),
    stage(TokenStageGroup.Repair, this::repairClippedAdjective, TokenFeatures.EndsWithTsu),
    stage(TokenStageGroup.Repair, this::repairClassicalKiAdjective),

    stage(TokenStageGroup.Combine, this::combinePrefixes, TokenFeatures.Prefix),
    stage(TokenStageGroup.Combine, this::combineInflections, TokenFeatures.InflectableBase),
    stage(TokenStageGroup.Combine, this::combineCompletionAuxVerb, TokenFeatures.DictKiru),
    stage(TokenStageGroup.Combine, this::combineAmounts, TokenFeatures.NumericAmount),
    stage(TokenStageGroup.Combine, this::combineTte, TokenFeatures.EndsWithTsu),
    stage(TokenStageGroup.Combine, this::combineAuxiliaryVerbStem, TokenFeatures.AuxVerbStem),
    stage(TokenStageGroup.Combine, this::combineSuffix, TokenFeatures.Suffix),
    stage(TokenStageGroup.Cleanup, this::reclassifyOrphanedSuffixes, TokenFeatures.Suffix),
    stage(TokenStageGroup.Combine, this::combineConjunctiveParticle, TokenFeatures.ConjParticle),
    stage(TokenStageGroup.Combine, this::combineAuxiliary),
    stage(TokenStageGroup.Combine, this::combineToNaru),
    stage(TokenStageGroup.Repair, this::repairFusedInterjectionParticle, TokenFeatures.Interjection),
    stage(TokenStageGroup.Repair, this::repairOrphanedAuxiliary),
    stage(TokenStageGroup.Combine, this::combineAdverbialParticle, TokenFeatures.AdvParticle),
    stage(TokenStageGroup.Combine, this::combineVerbDependant),
    stage(TokenStageGroup.Combine, this::combineParticles),
    stage(TokenStageGroup.Combine, this::combineQuotativeToIu),
    stage(TokenStageGroup.Combine, this::combineFinal),
    stage(TokenStageGroup.Split, this::splitUnresolvableSuruCompounds),
    stage(TokenStageGroup.Repair, this::repairTankaToTaNKa, TokenFeatures.TextTanka),
    stage(TokenStageGroup.Repair, this::repairTteNani),

    stage(TokenStageGroup.Cleanup, this::applyTokenRewriteRulesCleanup),
    stage(TokenStageGroup.Cleanup, this::repairDanTobashi, TokenFeatures.TextTobashi),
    stage(TokenStageGroup.Cleanup, this::applyContextPins),
    stage(TokenStageGroup.Disambiguation, this::applyTokenRewriteRulesReading),
    stage(TokenStageGroup.Disambiguation, this::fixReadingAmbiguity),
)

internal fun MorphologicalAnalyser.runPipeline(input: MutableList<WordInfo>): MutableList<WordInfo> {
    var wordInfos = input
    pipelineDeconjCache = HashMap()
    var candidateScan: TokenFeatureScan? = null
    var features = TokenFeatureScanner.scan(wordInfos)
    var featuresStale = false
    var candidateScanDirty = true

    for (stage in tokenStages) {
        if (stage.usesCandidatePositions && candidateScanDirty) {
            candidateScan = TokenFeatureScanner.scanWithCandidates(wordInfos)
            features = candidateScan.features
            candidateScanDirty = false
            featuresStale = false
        }

        if (stage.requiredFeatures != TokenFeatures.None && (features and stage.requiredFeatures) == TokenFeatures.None) {
            // A stale set bit only costs a no-op run, but a skip must never trust a stale clear bit.
            if (featuresStale) {
                features = TokenFeatureScanner.scan(wordInfos)
                featuresStale = false
            }
            if ((features and stage.requiredFeatures) == TokenFeatures.None) continue
        }

        val prev = wordInfos
        wordInfos = stage.apply(wordInfos, candidateScan) // TrackStage without diagnostics

        if (StageDebug) println("[stage] ${stage.name}: ${wordInfos.joinToString("|") { it.text }}")

        if (prev !== wordInfos) {
            featuresStale = true
            candidateScanDirty = true
        } else if (!stage.usesCandidatePositions) {
            // In-place edits leave the list reference unchanged, so candidate positions must still be rescanned.
            candidateScanDirty = true
        }
    }

    pipelineDeconjCache = null
    return wordInfos
}

internal fun MorphologicalAnalyser.getPipelineStageNamesForTesting(): List<String> = tokenStages.map { it.name }

internal fun MorphologicalAnalyser.applyStageForTesting(stageName: String, input: MutableList<WordInfo>): MutableList<WordInfo> =
    tokenStages.first { it.name == stageName }.apply(input, TokenFeatureScanner.scanWithCandidates(input))

internal fun MorphologicalAnalyser.runPipelineForTesting(input: MutableList<WordInfo>): MutableList<WordInfo> = runPipeline(input)
