// Ported from Jiten (github.com/Sirush/Jiten, Apache-2.0, commit 44b48a4): Jiten.Parser/Data/SentenceInfo.cs
package chimahon.lang.jiten

/** C#'s (WordInfo word, int position, int length) tuple: position and span length inside the sentence text. */
data class SentenceWord(val word: WordInfo, val position: Int, val length: Int)

class SentenceInfo(var text: String) {
    val words: MutableList<SentenceWord> = ArrayList()
}
