// Ported from Jiten (github.com/Sirush/Jiten, Apache-2.0, commit 44b48a4): Jiten.Core/Data/JMDict/JmDictWord.cs,
// JmDictWordForm.cs, JmDictFormType.cs, JmDictDefinition.cs, Jiten.Core/Data/WordOrigin.cs,
// Jiten.Parser/Data/JmDictWordMeta.cs
package chimahon.lang.jiten

import kotlin.math.max

enum class WordOrigin { Unknown, Wago, Kango, Gairaigo }

enum class JmDictFormType { KanjiForm, KanaForm }

/** A written form; [readingIndex] = position in the word's forms (kanji forms first, then kana). */
class JmDictWordForm(
    val wordId: Int,
    val readingIndex: Int,
    val text: String,
    val formType: JmDictFormType,
    /** var: PriorityOverrides adds "jiten" when the parser caches the word. */
    var priorities: List<String>?,
    val infoTags: List<String>?,
    val isNoKanji: Boolean = false,
) {
    val isObsolete: Boolean get() = infoTags?.any { it == "ok" || it == "oK" } == true
    val isSearchOnly: Boolean get() = infoTags?.any { it == "sK" || it == "sk" } == true

    /** Always true: the lexicon is built from one JMdict release, so no form is retired. */
    val isActiveInLatestSource: Boolean get() = true
}

/** A sense without its glosses: what the parser reads (ReadingPosHelper). */
class JmDictDefinition(
    val partsOfSpeech: List<String>,
    /** Kanji and kana indices in one list (stagk/stagr); null = every form. */
    val restrictedToReadingIndices: List<Int>?,
)

/** A word as Jiten's JmDictCache serves it to the parser (no glosses, ruby, pitch or lookups). */
class JmDictWord(
    val wordId: Int,
    val partsOfSpeech: List<String>,
    /** var: PriorityOverrides adds "jiten" when the parser caches the word. */
    var priorities: List<String>?,
    val origin: WordOrigin,
    val forms: List<JmDictWordForm>,
    val definitions: List<JmDictDefinition>,
    val isFullyArchaic: Boolean,
) {
    val cachedPOS: List<PartOfSpeech> by lazy { partsOfSpeech.toPartOfSpeech() }
    val cachedPOSMask: Int by lazy { PosMask.fromList(cachedPOS) }
    val isSuruVerb: Boolean by lazy { partsOfSpeech.any { it == "vs" || it == "vs-i" || it == "vs-s" } }

    fun getPriorityScore(isKana: Boolean): Int {
        var score = 0
        val p = priorities
        if (!p.isNullOrEmpty()) {
            // jiten: common words that get the wrong reading by default (秋, 陽)
            if ("jiten" in p) score += 100
            if ("ichi1" in p) score += 20
            if ("ichi2" in p) score += 10
            if ("news1" in p) score += 15
            if ("news2" in p) score += 10
            if ("gai1" in p) score += 15
            if ("gai2" in p) score += 10
            p.firstOrNull { it.startsWith("nf") }?.let { score += nfScore(it.substring(2).toInt()) }
            if (score == 0) {
                if ("spec1" in p) score += 15
                if ("spec2" in p) score += 5
            }
        }
        if ("uk" in partsOfSpeech) score += if (isKana) 10 else -10
        // Deprioritise names
        if (PartOfSpeech.Name in cachedPOS) score -= 50
        return score
    }

    companion object {
        /** max(0, 5 - round(nf / 10)) with C#'s Math.Round, which rounds halves to even (nf25 -> 2, not 3). */
        fun nfScore(nfRank: Int): Int = max(0, 5 - Math.rint(nfRank / 10.0).toInt())
    }
}

/**
 * The per-word metadata Parser keeps in memory (Parser.WordMeta). [JitenLexicon] stores it in primitive arrays and
 * builds one of these only when asked ([JitenLexicon.wordMeta]).
 */
class JmDictWordMeta(
    val pos: Array<PartOfSpeech>,
    val priorityScoreKana: Short,
    val priorityScoreKanji: Short,
    val origin: WordOrigin,
    /** False for JMnedict "unclass" entries, which cover slang like ダサ despite mapping to Name. */
    val isTrueName: Boolean = false,
) {
    fun getPriorityScore(isKana: Boolean): Int = if (isKana) priorityScoreKana.toInt() else priorityScoreKanji.toInt()

    fun getPrimaryPos(): PartOfSpeech {
        for (p in pos) if (p != PartOfSpeech.Name && p != PartOfSpeech.Unknown) return p
        return PartOfSpeech.Noun
    }
}
