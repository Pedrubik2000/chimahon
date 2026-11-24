package chimahon.chardict

/** A Japanese name split in family and given part (family/given are null when it isn't split). */
data class JapaneseNameParts(
    val hasSpace: Boolean,
    val original: String,
    val combined: String,
    val family: String?,
    val given: String?,
)

/** Hiragana readings of a name. */
data class NameReadings(val full: String, val family: String, val given: String)

/**
 * Splitting Japanese names and generating their readings, the port of Bee's `name_parser.rs` (MIT, see NOTICE.md).
 * AniList gives `first` (given) and `last` (family) in romaji: they split names written without a space.
 */
object NameParser {
    private fun trimmedOrNull(s: String?): String? = s?.trim()?.takeIf { it.isNotEmpty() }

    private fun cps(s: String): IntArray = s.codePoints().toArray()
    private fun part(cps: IntArray, from: Int, to: Int): String = String(cps, from, to - from)
    private fun count(s: String): Int = s.codePointCount(0, s.length)

    /** Splits on the first space. */
    internal fun splitJapaneseName(name: String): JapaneseNameParts {
        val pos = name.indexOf(' ')
        if (name.isEmpty() || pos < 0) return JapaneseNameParts(false, name, name, null, null)
        val family = name.substring(0, pos)
        val given = name.substring(pos + 1)
        return JapaneseNameParts(true, name, family + given, family, given)
    }

    /**
     * Readings for names without hints: the romanized name is Western order (Given Family), the Japanese one
     * Family Given, so romanized part 0 maps to the Japanese family.
     */
    internal fun generateMixedNameReadings(nameOriginal: String, romanizedName: String): NameReadings {
        if (nameOriginal.isEmpty()) return NameReadings("", "", "")
        if (' ' !in nameOriginal) {
            val full = if (Kana.containsKanji(nameOriginal)) Kana.alphabetToKana(romanizedName) else Kana.kataToHira(nameOriginal)
            return NameReadings(full, full, full)
        }
        val jp = splitJapaneseName(nameOriginal)
        val familyJp = jp.family ?: ""
        val givenJp = jp.given ?: ""
        val rom = romanizedName.split(' ', limit = 2)
        val romFirst = rom.getOrElse(0) { "" }
        val romSecond = rom.getOrElse(1) { "" }
        val family = if (Kana.containsKanji(familyJp)) Kana.alphabetToKana(romFirst) else Kana.kataToHira(familyJp)
        val given = if (Kana.containsKanji(givenJp)) Kana.alphabetToKana(romSecond) else Kana.kataToHira(givenJp)
        return NameReadings(family + given, family, given)
    }

    /**
     * The primary split: a space, else (with both hints) the reading lengths find the point; katakana with a
     * middle dot (foreign names) is never split.
     */
    fun splitJapaneseNameWithHints(nameOriginal: String, firstNameHint: String?, lastNameHint: String?): JapaneseNameParts {
        if (' ' in nameOriginal) return splitJapaneseName(nameOriginal)
        val first = trimmedOrNull(firstNameHint)
        val last = trimmedOrNull(lastNameHint)
        if (first == null || last == null) return splitJapaneseName(nameOriginal)
        if ('・' in nameOriginal) return splitJapaneseName(nameOriginal)
        val split = findSplitPoint(nameOriginal, Kana.alphabetToKana(last), Kana.alphabetToKana(first))
            ?: return splitJapaneseName(nameOriginal)
        return JapaneseNameParts(false, nameOriginal, split.first + split.second, split.first, split.second)
    }

    /** Index of the first non-kanji after a kanji, as Bee scans it (null when there is none). */
    private fun kanjiKanaBoundary(chars: IntArray): Int? {
        var lastKanji: Int? = null
        var firstKanaAfter: Int? = null
        for ((i, c) in chars.withIndex()) {
            if (Kana.containsKanji(String(chars, i, 1))) lastKanji = i
            else if (lastKanji != null && firstKanaAfter == null) firstKanaAfter = i
        }
        return firstKanaAfter
    }

    /** (split position, score) for every plausible position of an all-kanji name, by kana-per-character ratios. */
    private fun scoredSplits(total: Int, familyKanaLen: Int, givenKanaLen: Int): List<Pair<Int, Double>> {
        val out = ArrayList<Pair<Int, Double>>()
        for (pos in 1 until total) {
            val familyChars = pos
            val givenChars = total - pos
            val familyRatio = familyKanaLen.toDouble() / familyChars
            val givenRatio = givenKanaLen.toDouble() / givenChars
            if (familyRatio !in 0.5..4.0 || givenRatio !in 0.5..4.0) continue
            // 1..2 kana per kanji is the sweet spot; a small balance term prefers even splits on ties
            fun penalty(r: Double) = if (r < 1.0) 1.0 - r else if (r > 2.0) r - 2.0 else 0.0
            val balance = Math.abs(familyChars - givenChars).toDouble()
            out.add(pos to penalty(familyRatio) + penalty(givenRatio) + balance * 0.01)
        }
        return out
    }

    /** The kanji-to-kana boundary split when the kana part matches the given reading. */
    private fun boundarySplit(chars: IntArray, givenKana: String): Pair<String, String>? {
        val b = kanjiKanaBoundary(chars) ?: return null
        val candidateGiven = part(chars, b, chars.size)
        val hira = Kana.kataToHira(candidateGiven)
        return if (count(hira) == count(givenKana) || hira == Kana.kataToHira(givenKana)) part(chars, 0, b) to candidateGiven else null
    }

    /** Where to split a spaceless name: a kanji-to-kana boundary matching the given reading, else the best ratio score. */
    internal fun findSplitPoint(native: String, familyKana: String, givenKana: String): Pair<String, String>? {
        val chars = cps(native)
        if (chars.isEmpty()) return null
        boundarySplit(chars, givenKana)?.let { return it }
        if (chars.size < 2 || count(familyKana) + count(givenKana) == 0) return null
        // strict "<": the first of equally good positions wins
        var best: Pair<Int, Double>? = null
        for (s in scoredSplits(chars.size, count(familyKana), count(givenKana))) if (best == null || s.second < best.second) best = s
        return best?.let { part(chars, 0, it.first) to part(chars, it.first, chars.size) }
    }

    /** Every split within 1e-6 of the best score: names with symmetric kana lengths (石井守) have two. */
    internal fun findAllSplitPoints(native: String, familyKana: String, givenKana: String): List<Pair<String, String>> {
        val chars = cps(native)
        if (chars.isEmpty()) return emptyList()
        boundarySplit(chars, givenKana)?.let { return listOf(it) }
        if (chars.size < 2 || count(familyKana) + count(givenKana) == 0) return emptyList()
        val scored = scoredSplits(chars.size, count(familyKana), count(givenKana))
        if (scored.isEmpty()) return emptyList()
        val best = scored.minOf { it.second }
        return scored.filter { Math.abs(it.second - best) < 1e-6 }
            .map { part(chars, 0, it.first) to part(chars, it.first, chars.size) }
    }

    /** Like [splitJapaneseNameWithHints] but with every tied split, so lookups work whichever is right. */
    fun splitJapaneseNameAllCandidates(nameOriginal: String, firstNameHint: String?, lastNameHint: String?): List<JapaneseNameParts> {
        if (' ' in nameOriginal || '・' in nameOriginal) return listOf(splitJapaneseName(nameOriginal))
        val first = trimmedOrNull(firstNameHint)
        val last = trimmedOrNull(lastNameHint)
        if (first == null || last == null) return listOf(splitJapaneseName(nameOriginal))
        val splits = findAllSplitPoints(nameOriginal, Kana.alphabetToKana(last), Kana.alphabetToKana(first))
        if (splits.isEmpty()) return listOf(splitJapaneseName(nameOriginal))
        return splits.map { (f, g) -> JapaneseNameParts(false, nameOriginal, f + g, f, g) }
    }

    /** Hiragana readings of a character name, with AniList's romaji hints (first = given, last = family) when there are any. */
    fun generateNameReadings(nameOriginal: String, romanizedName: String, firstNameHint: String?, lastNameHint: String?): NameReadings {
        val r = generateInner(nameOriginal, romanizedName, firstNameHint, lastNameHint)
        // "Elaina no Haha" would give "えらいな の はは": readings carry no whitespace
        fun strip(s: String) = s.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString("")
        return NameReadings(strip(r.full), strip(r.family), strip(r.given))
    }

    private fun same(s: String) = NameReadings(s, s, s)

    private fun generateInner(nameOriginal: String, romanizedName: String, firstNameHint: String?, lastNameHint: String?): NameReadings {
        if (nameOriginal.isEmpty()) return NameReadings("", "", "")
        val first = trimmedOrNull(firstNameHint)
        val last = trimmedOrNull(lastNameHint)
        if (first == null && last == null) return generateMixedNameReadings(nameOriginal, romanizedName)
        if ('・' in nameOriginal) return same(Kana.kataToHira(nameOriginal))
        if (last == null) { // only a first hint: a single name
            return if (Kana.containsKanji(nameOriginal)) same(Kana.alphabetToKana(first ?: romanizedName))
            else same(Kana.kataToHira(nameOriginal))
        }
        val firstHint = first ?: ""
        if (' ' in nameOriginal) {
            val p = splitJapaneseName(nameOriginal)
            val familyJp = p.family ?: ""
            val givenJp = p.given ?: ""
            val family = if (Kana.containsKanji(familyJp)) Kana.alphabetToKana(last) else Kana.kataToHira(familyJp)
            val given = if (Kana.containsKanji(givenJp)) Kana.alphabetToKana(firstHint) else Kana.kataToHira(givenJp)
            return NameReadings(family + given, family, given)
        }
        val familyKana = Kana.alphabetToKana(last)
        val givenKana = if (firstHint.isNotEmpty()) Kana.alphabetToKana(firstHint) else ""
        val parts = splitJapaneseNameWithHints(nameOriginal, firstNameHint, lastNameHint)
        val familyJp = parts.family
        val givenJp = parts.given
        if (familyJp != null && givenJp != null) {
            val family = if (Kana.containsKanji(familyJp)) familyKana else Kana.kataToHira(familyJp)
            val given = if (Kana.containsKanji(givenJp)) givenKana else Kana.kataToHira(givenJp)
            return NameReadings(family + given, family, given)
        }
        return NameReadings(familyKana + givenKana, familyKana, givenKana)
    }
}
