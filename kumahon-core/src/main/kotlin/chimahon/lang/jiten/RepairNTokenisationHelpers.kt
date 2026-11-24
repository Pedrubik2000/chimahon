// Ported from Jiten (github.com/Sirush/Jiten, Apache-2.0, commit 44b48a4):
// Jiten.Parser/Helpers/MorphologicalAnalyser.RepairNTokenisationHelpers.cs
package chimahon.lang.jiten

internal fun normalizeToHiragana(text: String): String = KanaConverter.toNormalizedHiragana(text)

internal fun isNdaVerbForm(forms: List<DeconjugationForm>): Boolean = forms.any { f ->
    f.tags.isNotEmpty() &&
        (("v5m" in f.tags && f.text.endsWith('む')) ||
            ("v5n" in f.tags && f.text.endsWith('ぬ')) ||
            ("v5b" in f.tags && f.text.endsWith('ぶ')) ||
            ("v5g" in f.tags && f.text.endsWith('ぐ')))
}

/** True only for む/ぬ/ぶ/ぐ past tense (読んだ); a slurred negative ん + copula (知らん + だ) is false. */
internal fun MorphologicalAnalyser.isValidNdaPastTense(verbText: String): Boolean {
    if (!verbText.endsWith('ん')) return false
    val verbForms = pipelineCachedDeconjugate(normalizeToHiragana(verbText))
    if (verbForms.any { f -> f.process.any { "slurred negative" in it || "colloquial negative" in it } }) return false
    val forms = pipelineCachedDeconjugate(normalizeToHiragana(verbText + "だ"))
    return isNdaVerbForm(forms)
}

internal fun isAnyVerbForm(forms: List<DeconjugationForm>): Boolean =
    forms.any { f -> f.tags.isNotEmpty() && f.tags.any { it.startsWith('v') } }

internal fun isMasenVerbForm(forms: List<DeconjugationForm>): Boolean =
    forms.any { f -> f.tags.isNotEmpty() && f.tags.any { it.startsWith('v') } && !f.text.endsWith("ます") }

internal fun createNToken(): WordInfo = WordInfo().apply {
    text = "ん"; dictionaryForm = ""; normalizedForm = "ん"; reading = "ん"
    partOfSpeech = PartOfSpeech.Auxiliary; partOfSpeechSection1 = PartOfSpeechSection.None
}

internal fun createDaToken(): WordInfo = WordInfo().apply {
    text = "だ"; dictionaryForm = "だ"; normalizedForm = "だ"; reading = "だ"
    partOfSpeech = PartOfSpeech.Auxiliary; partOfSpeechSection1 = PartOfSpeechSection.None
}

private fun buildCandidateText(words: List<WordInfo>, lookback: Int, suffix: String): String {
    val sb = StringBuilder()
    for (j in words.size - lookback until words.size) sb.append(words[j].text)
    sb.append(suffix)
    return sb.toString()
}

private fun buildCandidateReading(words: List<WordInfo>, lookback: Int, suffixReading: String): String {
    val sb = StringBuilder()
    for (j in words.size - lookback until words.size) sb.append(words[j].reading)
    sb.append(suffixReading)
    return sb.toString()
}

private fun removeLastN(list: MutableList<WordInfo>, n: Int) {
    repeat(n) { list.removeAt(list.size - 1) }
}

/** C#'s bool TryCombineWithLookback(..., out WordInfo? combined): returns combined, or null for false. */
internal fun tryCombineWithLookback(
    result: MutableList<WordInfo>,
    suffix: String,
    suffixReading: String,
    deconj: Deconjugator,
    validator: (List<DeconjugationForm>) -> Boolean,
): WordInfo? {
    // な after a na-adjective belongs to it (好きな + んだ).
    if (result.size >= 2) {
        val lastToken = result[result.size - 1]
        if (lastToken.text == "な" && lastToken.dictionaryForm == "だ" && isNaAdjectiveToken(result[result.size - 2])) return null
    }

    // After an i-adjective んだ is explanatory (いい + んだ), not a verb conjugation.
    if (result.size >= 1 && suffix.startsWith('ん')) {
        if (result[result.size - 1].partOfSpeech == PartOfSpeech.IAdjective) return null
    }

    for (lookback in 1..minOf(3, result.size)) {
        // Blank spaces deconjugate to a false match.
        if ((result.size - lookback until result.size).any { result[it].partOfSpeech == PartOfSpeech.BlankSpace }) continue

        // ん after a te-form contracts いる (ている → てん), not past tense.
        if ((result.size - lookback until result.size).any {
                val w = result[it]
                (w.text == "て" || w.text == "で") && w.partOfSpeech == PartOfSpeech.Particle
            }) continue

        val candidateText = buildCandidateText(result, lookback, suffix)
        val forms = deconj.deconjugate(normalizeToHiragana(candidateText))

        if (validator(forms)) {
            val baseWord = result[result.size - lookback]
            val candidateReading = buildCandidateReading(result, lookback, suffixReading)
            removeLastN(result, lookback)
            return baseWord.copy().apply {
                text = candidateText
                partOfSpeech = PartOfSpeech.Verb
                normalizedForm = candidateText
                reading = KanaConverter.toHiragana(candidateReading)
                partOfSpeechSection1 = PartOfSpeechSection.None
                partOfSpeechSection2 = PartOfSpeechSection.None
                partOfSpeechSection3 = PartOfSpeechSection.None
            }
        }
    }

    return null
}

internal fun isNaAdjectiveToken(word: WordInfo): Boolean =
    word.partOfSpeech == PartOfSpeech.NaAdjective ||
        word.hasPartOfSpeechSection(PartOfSpeechSection.PossibleNaAdjective) ||
        word.hasPartOfSpeechSection(PartOfSpeechSection.NaAdjectiveLike)
