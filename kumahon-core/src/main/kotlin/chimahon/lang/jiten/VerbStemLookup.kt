// Ported from Jiten (github.com/Sirush/Jiten, Apache-2.0, commit 44b48a4): Jiten.Parser/VerbStemLookup.cs
package chimahon.lang.jiten

/** C#'s (int WordId, string DictionaryForm) tuple. */
internal data class VerbStem(val wordId: Int, val dictionaryForm: String)

/** `lookups`, `wordMeta` and `frequencyRanks` are all [JitenLexicon]. */
internal object VerbStemLookup {
    /** Most frequent verb whose bare 連用形 is [surface] (回し → 回す, 答え → 答える); kana-only surfaces never match. */
    fun find(surface: String, reading: String, lexicon: JitenLexicon): VerbStem? {
        if (surface.length < 2 || !JapaneseTextHelper.isHiragana(surface[surface.length - 1]) ||
            surface.none(JapaneseTextHelper::isKanji)) return null

        val readingHira = if (reading.isEmpty()) null else KanaConverter.toHiragana(reading, convertLongVowelMark = false)

        var best: VerbStem? = null
        var bestRank = Int.MAX_VALUE

        fun consider(dictForm: String?, readingDictForm: String?) {
            if (dictForm == null) return
            val ids = lexicon.lookup(dictForm) ?: return

            var readingIds: IntArray? = null
            // A kanji verb read differently from Sudachi's reading of the stem is a different word.
            if (readingHira != null) {
                readingIds = readingDictForm?.let(lexicon::lookup) ?: return
            }

            for (id in ids) {
                if (readingIds != null && id !in readingIds) continue
                if (!lexicon.hasPos(id, PartOfSpeech.Verb)) continue
                val rank = lexicon.wordFrequencyRank(id) ?: (Int.MAX_VALUE - 1)
                if (rank < bestRank) {
                    bestRank = rank
                    best = VerbStem(id, dictForm)
                }
            }
        }

        consider(tryGodanDictForm(surface), readingHira?.let(::tryGodanDictForm))
        consider(surface + "る", readingHira?.let { it + "る" })
        return best
    }

    fun pin(word: WordInfo, verb: VerbStem) {
        word.partOfSpeech = PartOfSpeech.Verb
        word.dictionaryForm = verb.dictionaryForm
        word.normalizedForm = verb.dictionaryForm
        word.preMatchedWordId = verb.wordId
        word.preMatchedCandidateWordIds = null
        word.preMatchedConjugations = mutableListOf("(infinitive)")
    }
}
