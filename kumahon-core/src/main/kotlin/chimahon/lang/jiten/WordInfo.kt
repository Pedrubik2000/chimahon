// Ported from Jiten (github.com/Sirush/Jiten, Apache-2.0, commit 44b48a4): Jiten.Parser/Data/WordInfo.cs
package chimahon.lang.jiten

import com.worksap.nlp.sudachi.Morpheme

/** (WordId, ReadingIndex) of a form; C# byte reading indices are Int here (Kotlin's Byte is signed). */
data class WordForm(val wordId: Int, val readingIndex: Int)

/** One token. Jiten's byte-span Sudachi-line constructor is replaced by [fromSudachi]. */
class WordInfo() {
    var text: String = ""
    var startOffset: Int = -1
    var endOffset: Int = -1
    var partOfSpeech: PartOfSpeech = PartOfSpeech.Unknown
    var partOfSpeechSection1: PartOfSpeechSection = PartOfSpeechSection.None
    var partOfSpeechSection2: PartOfSpeechSection = PartOfSpeechSection.None
    var partOfSpeechSection3: PartOfSpeechSection = PartOfSpeechSection.None
    var normalizedForm: String = ""
    var dictionaryForm: String = ""
    var reading: String = ""
    var isInvalid: Boolean = false
    var isPersonNameContext: Boolean = false
    var preMatchedWordId: Int? = null
    var preMatchedReadingIndex: Int? = null
    var preMatchedConjugations: MutableList<String>? = null
    var preMatchedCandidateWordIds: MutableList<Int>? = null
    var isImperative: Boolean = false

    /** Merged ん was Sudachi's negative ぬ (認め+られ+ん); the deconjugator prefers the slurred-る path (してん). */
    var isSlurredNegative: Boolean = false

    var wasReclassifiedFromSuffix: Boolean = false
    var isMergedInflection: Boolean = false
    var resolvedWordId: Int? = null

    /** Null for tokens that are not vocabulary; set once the misparse gates ran. */
    var keptForm: WordForm? = null

    /** Sudachi's original interjection tag, kept across POS rewrites; blocks reading matches (イエーイ → 遺影). */
    var isKanaExclamation: Boolean = false

    /** Sudachi's original katakana-noun tag, kept across POS rewrites; blocks kanji deconjugation (ハガナ → 剥ぐ). */
    var isKatakanaNounSurface: Boolean = false

    /** Misparse gates must not overrule it (え|っつった); PreMatchedWordId is also set by compound matches. */
    var pinnedByRewriteRule: Boolean = false

    /** Compounds must not absorb it (ordinal 目); soft pins stay absorbable by attested spans (そうした). */
    var hardPinned: Boolean = false

    /** Extra cost of the cheapest rival lattice path; 99999 = no rival, null = not requested. */
    var sudachiBoundaryMargin: Int? = null

    /** C#'s copy constructor (WordInfo(WordInfo other)); lists are copied. */
    fun copy(): WordInfo {
        val o = this
        return WordInfo().apply {
            text = o.text
            startOffset = o.startOffset
            endOffset = o.endOffset
            partOfSpeech = o.partOfSpeech
            partOfSpeechSection1 = o.partOfSpeechSection1
            partOfSpeechSection2 = o.partOfSpeechSection2
            partOfSpeechSection3 = o.partOfSpeechSection3
            normalizedForm = o.normalizedForm
            dictionaryForm = o.dictionaryForm
            reading = o.reading
            isInvalid = o.isInvalid
            isPersonNameContext = o.isPersonNameContext
            preMatchedWordId = o.preMatchedWordId
            preMatchedReadingIndex = o.preMatchedReadingIndex
            preMatchedConjugations = o.preMatchedConjugations?.toMutableList()
            preMatchedCandidateWordIds = o.preMatchedCandidateWordIds?.toMutableList()
            isImperative = o.isImperative
            isSlurredNegative = o.isSlurredNegative
            wasReclassifiedFromSuffix = o.wasReclassifiedFromSuffix
            isMergedInflection = o.isMergedInflection
            resolvedWordId = o.resolvedWordId
            keptForm = o.keptForm
            sudachiBoundaryMargin = o.sudachiBoundaryMargin
            isKanaExclamation = o.isKanaExclamation
            isKatakanaNounSurface = o.isKatakanaNounSurface
            pinnedByRewriteRule = o.pinnedByRewriteRule
            hardPinned = o.hardPinned
        }
    }

    fun hasPartOfSpeechSection(section: PartOfSpeechSection) =
        partOfSpeechSection1 == section || partOfSpeechSection2 == section || partOfSpeechSection3 == section

    companion object {
        /**
         * A Sudachi morpheme as Jiten reads its Sudachi output line: POS from part 0 (Sudachi names first, then JMdict
         * tags), sections from parts 1-3, katakana reading as Sudachi gives it, imperative from the conjugation form
         * (part 5). Offsets default to Sudachi's; Jiten recomputes them against the cleaned text (ComputeTokenOffsets).
         */
        fun fromSudachi(m: Morpheme, startOffset: Int = m.begin(), endOffset: Int = m.end()): WordInfo {
            val pos = m.partOfSpeech()
            return WordInfo().apply {
                text = m.surface()
                this.startOffset = startOffset
                this.endOffset = endOffset
                partOfSpeech = PosMapper.fromAny(pos[0])
                partOfSpeechSection1 = pos.getOrNull(1)?.toPartOfSpeechSection() ?: PartOfSpeechSection.None
                partOfSpeechSection2 = pos.getOrNull(2)?.toPartOfSpeechSection() ?: PartOfSpeechSection.None
                partOfSpeechSection3 = pos.getOrNull(3)?.toPartOfSpeechSection() ?: PartOfSpeechSection.None
                normalizedForm = m.normalizedForm()
                dictionaryForm = m.dictionaryForm()
                reading = m.readingForm()
                isImperative = pos.getOrNull(5) == "命令形"
            }
        }
    }
}
