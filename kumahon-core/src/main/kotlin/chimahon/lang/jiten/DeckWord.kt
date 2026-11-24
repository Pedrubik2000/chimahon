// Ported from Jiten (github.com/Sirush/Jiten, Apache-2.0, commit 44b48a4): Jiten.Core/Data/DeckWord.cs
package chimahon.lang.jiten

/**
 * One resolved word of a parse. The database fields (DeckWordId, DeckId, Deck) are left out; C#'s byte-indexed
 * conjugation cache is a plain list ([conjugations] is copied on set, like C#'s round trip through the cache).
 */
class DeckWord(
    var wordId: Int = 0,
    /** Original text before any deconjugation. */
    var originalText: String = "",
    /** Index of the reading in the word's forms (C# byte; Int here). */
    var readingIndex: Int = 0,
    var occurrences: Int = 0,
    var partsOfSpeech: MutableList<PartOfSpeech> = ArrayList(),
    var origin: WordOrigin = WordOrigin.Unknown,
    var sudachiReading: String = "",
    var sudachiPartOfSpeech: PartOfSpeech = PartOfSpeech.Unknown,
    var cachedMargin: Int? = null,
    conjugations: List<String> = emptyList(),
) {
    private var conjugationList: MutableList<String> = ArrayList(conjugations)

    var conjugations: List<String>
        get() = ArrayList(conjugationList)
        set(value) { conjugationList = ArrayList(value) }

    /** Last conjugation step, or null when there is none. */
    val lastConjugation: String? get() = conjugationList.lastOrNull()

    val conjugationCount: Int get() = conjugationList.size

    fun copyConjugationsFrom(source: DeckWord) {
        conjugationList = ArrayList(source.conjugationList)
    }

    /** Field-for-field copy that shares no mutable state with the source. */
    fun clone(): DeckWord = DeckWord(wordId, originalText, readingIndex, occurrences, ArrayList(partsOfSpeech), origin,
        sudachiReading, sudachiPartOfSpeech, cachedMargin, conjugationList)

    override fun toString() = "DeckWord($wordId/$readingIndex '$originalText')"
}
