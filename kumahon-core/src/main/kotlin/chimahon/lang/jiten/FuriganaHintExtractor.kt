// Ported from Jiten (github.com/Sirush/Jiten, Apache-2.0, commit 44b48a4): Jiten.Parser/FuriganaHintExtractor.cs
package chimahon.lang.jiten

data class FuriganaHint(val offset: Int, val length: Int, val reading: String)

object FuriganaHintExtractor {
    const val AnnotationPattern = "\\{([^\\u0001-\\u007F]{1,32})'([^'{}\\n]{1,32})\\}"

    private val HintPattern = Regex(AnnotationPattern)

    /** Drops inline furigana markup ({base'reading}), keeping only the base text. */
    fun strip(annotatedText: String): String =
        if (annotatedText.isEmpty()) annotatedText else HintPattern.replace(annotatedText, "$1")

    fun extract(annotatedText: String): Pair<String, Array<FuriganaHint>> {
        val matches = HintPattern.findAll(annotatedText).toList()
        if (matches.isEmpty()) return annotatedText to emptyArray()

        val hints = ArrayList<FuriganaHint>(matches.size)
        val sb = StringBuilder(annotatedText.length)
        var lastEnd = 0
        for (match in matches) {
            sb.append(annotatedText, lastEnd, match.range.first)
            val baseText = match.groupValues[1]
            val reading = match.groupValues[2]
            if (baseText.isNotEmpty() && reading.isNotEmpty()) hints.add(FuriganaHint(sb.length, baseText.length, reading))
            sb.append(baseText)
            lastEnd = match.range.last + 1
        }
        sb.append(annotatedText, lastEnd, annotatedText.length)
        return sb.toString() to hints.toTypedArray()
    }

    fun annotate(cleanText: String, hints: List<FuriganaHint>): String {
        if (hints.isEmpty()) return cleanText
        val sb = StringBuilder(cleanText.length + hints.size * 6)
        var lastEnd = 0
        for (hint in hints) {
            sb.append(cleanText, lastEnd, hint.offset)
            sb.append('{').append(cleanText, hint.offset, hint.offset + hint.length).append('\'').append(hint.reading).append('}')
            lastEnd = hint.offset + hint.length
        }
        sb.append(cleanText, lastEnd, cleanText.length)
        return sb.toString()
    }

    internal fun relocateToCleanedOriginal(cleanedOriginal: String, hints: Array<FuriganaHint>, cleanText: String): Array<FuriganaHint> {
        if (hints.isEmpty()) return emptyArray()
        val relocated = ArrayList<FuriganaHint>(hints.size)
        var searchStart = 0
        for (hint in hints) {
            val span = cleanText.substring(hint.offset, hint.offset + hint.length)
            val found = cleanedOriginal.indexOf(span, searchStart)
            if (found >= 0) {
                relocated.add(FuriganaHint(found, hint.length, hint.reading))
                searchStart = found + hint.length
            }
        }
        return relocated.toTypedArray()
    }
}
