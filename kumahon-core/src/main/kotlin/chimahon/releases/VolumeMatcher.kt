package chimahon.releases

import java.text.Normalizer
import java.time.LocalDate

/**
 * Which listings are the next volumes of one watched series: NFKC both sides, the title must be `<jpTitle> <number>` (`１５`, `（41）`,
 * `(041)`, `15巻`), anything else (spin-offs, chapters, novels, boxes, magazines, colouring books) is dropped. A 特装版/同梱版 row
 * is kept only as a date for that volume (a regular row of the same source wins), e-book rows only when no paper row has the same volume and date.
 */
object VolumeMatcher {
    /** One volume as one source lists it. [special] = the date comes from a 特装版/同梱版 row. */
    data class Candidate(val vol: Int, val date: LocalDate?, val special: Boolean, val source: String)

    private val SPECIAL = Regex("特装版|同梱版")

    fun norm(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKC).replace(Regex("\\s+"), " ").trim()

    /** Candidates with vol > [lastKnownVol], at most one per (vol, source), sorted by vol: the first one is the next volume. */
    fun match(jpTitle: String, lastKnownVol: Int, listings: List<Listing>, publisher: String? = null): List<Candidate> {
        val base = norm(jpTitle)
        if (base.isEmpty()) return emptyList()
        val re = Regex("^" + Regex.escape(base) + """ ?\(?(\d{1,4})\)? ?(?:巻)?(?:\s*(\D.*))?$""")
        val pin = publisher?.let(::norm)?.ifEmpty { null }
        val found = listings.mapNotNull { l ->
            val t = norm(l.title)
            val special: Boolean
            val vol: Int
            val m = re.find(t)
            if (m != null) {
                special = m.groupValues[2].isNotEmpty()
                if (special && !SPECIAL.containsMatchIn(m.groupValues[2])) return@mapNotNull null
                vol = m.groupValues[1].toInt()
            } else if (t == base && l.vol != null) { // NDL's own records: the title is the series, the number is separate
                special = false
                vol = l.vol
            } else return@mapNotNull null
            if (pin != null && l.publisher != null && !norm(l.publisher).contains(pin)) return@mapNotNull null
            if (vol <= lastKnownVol) return@mapNotNull null
            Triple(l, vol, special) // (listing, vol, special)
        }
        val paperKeys = found.filter { !it.first.ebook }.map { it.second to it.first.date }.toSet()
        return found
            .filter { (l, vol, _) -> !l.ebook || (vol to l.date) !in paperKeys }
            .groupBy { (l, vol, _) -> vol to l.source }
            .map { (key, rows) ->
                // regular before special, paper before e-book, dated before undated, earlier first
                val best = rows.minWith(compareBy({ it.third }, { it.first.ebook }, { it.first.date == null }, { it.first.date }))
                Candidate(key.first, best.first.date, best.third, key.second)
            }
            .sortedWith(compareBy({ it.vol }, { it.source }))
    }
}
