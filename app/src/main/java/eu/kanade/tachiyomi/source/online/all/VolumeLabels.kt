package eu.kanade.tachiyomi.source.online.all

/**
 * The volume numbers a Real-Debrid source's chapter label, file name or post title covers:
 * "04-06" -> 4..6, "01-02b" -> 1..2, "第01-27巻" -> 1..27, "全03巻" -> 1..3, "Draft_King_27.rar (138.86 MB)" -> 27,
 * "Vol. 12" / "v12" / "第12巻" -> 12. Dates, years and sizes give nothing ("excnn20-11-2026a07", "2024",
 * "Shonen_Jump_2026-04-05"). Pure, for tests.
 */
object VolumeLabels {

    /** A range wider than this is a misread (a date, an id), not volumes. */
    private const val MAX_SPAN = 200

    private val noise = Regex(
        """\([^)]*\)|（[^）]*）|【[^】]*】|\.(?:rar|zip|7z|cbz|cbr|cb7|pdf|epub)(?:\.html)?\b|[\d.]+\s*[KMGT]i?B\b""",
        RegexOption.IGNORE_CASE,
    )
    private const val DASH = """\s*[-~～〜]\s*"""
    private val complete = Regex("""全\s*(\d{1,3})\s*巻""")
    private val japanese = Regex("""(?<!\d)(\d{1,3})(?:$DASH(\d{1,3}))?\s*巻""")
    private val marked = Regex(
        """(?<![a-z])(?:v|vol\.?|volume)\s*(\d{1,3})(?:$DASH(?:v|vol\.?)?\s*(\d{1,3}))?(?!\d)""",
        RegexOption.IGNORE_CASE,
    )

    // a whole word of digits: "03", "04-06", "01-02b", "02w", "20-21s", "01-09w6", and with a scan mark on the
    // first number too: "01s-05", "06w-08w", "13s-16s+" (BsZip), "01b-08" (X3DL)
    private val bare = Regex(
        """^(\d{1,3})(?:[a-z]{1,2}\d?)?(?:-(\d{1,3}))?(?:[a-z]{1,2}\d?)?\+?$""",
        RegexOption.IGNORE_CASE,
    )
    private val separators = Regex("""[\s　_,]+""")

    fun volumes(label: String): Set<Int> {
        val s = noise.replace(halfWidthDigits(label), " ")
        complete.find(s)?.let { return span(1, it.groupValues[1]) }
        val jp = japanese.findAll(s).flatMap { range(it) }.toSet()
        if (jp.isNotEmpty()) return jp
        val vol = marked.findAll(s).flatMap { range(it) }.toSet()
        if (vol.isNotEmpty()) return vol
        return s.split(separators).mapNotNull { bare.matchEntire(it) }.flatMap { range(it) }.toSet()
    }

    private fun range(m: MatchResult): Set<Int> {
        val start = m.groupValues[1].toInt()
        val end = m.groupValues[2].ifEmpty { null } ?: return setOf(start)
        return span(start, end)
    }

    private fun span(start: Int, endText: String): Set<Int> {
        val end = endText.toInt()
        if (start == end) return setOf(start)
        if (start > end || end - start > MAX_SPAN) return emptySet()
        return (start..end).toSet()
    }

    // "全３巻" -> "全3巻"
    private fun halfWidthDigits(s: String) =
        String(CharArray(s.length) { i -> s[i].let { if (it in '０'..'９') '0' + (it - '０') else it } })
}
