package eu.kanade.tachiyomi.ui.glass.popup

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The pure parts of the reader's lookup popup (option D, "Thumb bar"): pitch drawing data, furigana, the status line and the bottom bar mode. */
object PopupLogic {

    // ── pitch ────────────────────────────────────────────────────────────

    private const val SMALL = "ゃゅょぁぃぅぇぉゎャュョァィゥェォヮ"

    /** The reading split into morae: a small kana joins the kana before it; ッ, ー and ん are morae of their own. */
    fun morae(reading: String): List<String> {
        val out = ArrayList<String>()
        for (c in reading) {
            if (c in SMALL && out.isNotEmpty()) out[out.size - 1] = out.last() + c else out += c.toString()
        }
        return out
    }

    /** One drawn pitch: [high] per mora; [drop] = the line falls after that mora (-1: it never does, 平板). */
    data class Pitch(val morae: List<String>, val high: List<Boolean>, val downstep: Int, val name: String, val drop: Int)

    /** Is mora [i] (0-based) high for the downstep number [n]: 0 = heiban (low, then high), 1 = high then low, n = high up to the n-th mora. */
    fun isHigh(i: Int, n: Int): Boolean = when {
        n == 0 -> i >= 1
        n == 1 -> i == 0
        else -> i in 1 until n
    }

    /** heiban (0), atamadaka (1), odaka (n = the last mora), else nakadaka. */
    fun patternName(n: Int, moraCount: Int): String = when {
        n == 0 -> "heiban"
        n == 1 -> "atamadaka"
        n >= moraCount -> "odaka"
        else -> "nakadaka"
    }

    /** The pitch of [reading] with downstep number [n]; null without a reading. */
    fun pitch(reading: String, n: Int): Pitch? {
        val m = morae(reading)
        if (m.isEmpty() || n < 0) return null
        return Pitch(m, m.indices.map { isHigh(it, n) }, n, patternName(n, m.size), if (n > 0) minOf(n, m.size) - 1 else -1)
    }

    /** The distinct downstep numbers over the dictionaries' groups, first group first (the user's dictionary order). */
    fun positions(groups: List<List<Int>>): List<Int> = groups.flatten().distinct()

    // ── furigana ─────────────────────────────────────────────────────────

    private fun isKana(c: Char) = c in 'ぁ'..'ゖ' || c in 'ァ'..'ヺ'
    private fun hira(s: String) = s.map { if (it in 'ァ'..'ヺ') (it.code - 0x60).toChar() else it }.joinToString("")

    /**
     * (text, reading) pieces of [expression] for its [reading]: the reading goes over the kanji runs, the kana in between stay plain
     * (食べる/たべる = 食 た, べる). A reading that does not fit the kana is put over everything between the leading and trailing kana.
     */
    fun furigana(expression: String, reading: String): List<Pair<String, String>> {
        if (reading.isEmpty() || reading == expression) return listOf(expression to "")
        val groups = ArrayList<Pair<Boolean, String>>() // (isKana, text)
        for (c in expression) {
            val k = isKana(c)
            if (groups.isNotEmpty() && groups.last().first == k) groups[groups.size - 1] = k to (groups.last().second + c) else groups += k to c.toString()
        }
        if (groups.size == 1) return if (groups[0].first) listOf(expression to "") else listOf(expression to reading)
        val norm = hira(reading)
        fun match(g: Int, r: Int): List<Pair<String, String>>? {
            if (g >= groups.size) return if (r >= reading.length) emptyList() else null
            val (kana, text) = groups[g]
            if (kana) {
                if (!norm.startsWith(hira(text), r)) return null
                return match(g + 1, r + text.length)?.let { listOf(text to "") + it }
            }
            var found: List<Pair<String, String>>? = null
            for (i in reading.length downTo r + text.length) {
                val rest = match(g + 1, i) ?: continue
                if (found != null) return null // ambiguous
                found = listOf(text to reading.substring(r, i)) + rest
            }
            return found
        }
        match(0, 0)?.let { return it }
        // kana at both ends, one reading over the kanji between
        var front = 0
        var rp = 0
        while (front < groups.size && groups[front].first && norm.startsWith(hira(groups[front].second), rp)) { rp += groups[front].second.length; front++ }
        var back = groups.size - 1
        var rb = reading.length
        while (back >= front && groups[back].first && rb - groups[back].second.length >= rp && norm.substring(rb - groups[back].second.length, rb) == hira(groups[back].second)) {
            rb -= groups[back].second.length; back--
        }
        if (front > back) return listOf(expression to reading)
        val out = ArrayList<Pair<String, String>>()
        for (i in 0 until front) out += groups[i].second to ""
        out += groups.subList(front, back + 1).joinToString("") { it.second } to reading.substring(rp, rb)
        for (i in back + 1 until groups.size) out += groups[i].second to ""
        return out
    }

    // ── word state, status line, bar ─────────────────────────────────────

    /** The word's state as the page colours it: u new (red), d due (green), b mined and never rated (blue), k known (white). */
    fun stateName(state: String?): String = when (state) { "u", "d", "b", "k" -> state; else -> "u" }

    enum class Bar { MINE, RATE_DUE, RATE_EARLY }

    /** The bottom bar: no card of its own = Mark known + Mine; a card that is due (or never rated) = rate it; any other card = rate early. */
    fun bar(state: String, hasCard: Boolean): Bar = when {
        state == "d" || state == "b" -> Bar.RATE_DUE
        state == "k" && hasCard -> Bar.RATE_EARLY
        else -> Bar.MINE
    }

    private val dayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

    /**
     * The status line under the headword: "New", "Known", "Mined 12 Nov · due today", "Mined 12 Nov · not rated yet",
     * "Mined 12 Nov · next review in 5 d". [created] / [due] are the card's times (ms) when kumahon has it, else null.
     */
    fun statusLine(state: String, created: Long?, due: Long?, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val mined = created?.let { "Mined " + dayMonth.format(Instant.ofEpochMilli(it).atZone(zone)) } ?: "Mined"
        return when (state) {
            "d" -> "$mined · due today"
            "b" -> "$mined · not rated yet"
            "k" -> if (created == null) "Known" else {
                val days = due?.let { ((it - now) / 86_400_000L).toInt().coerceAtLeast(1) }
                if (days == null) mined else "$mined · next review in $days d"
            }
            else -> "New"
        }
    }

    /** The line beside the card icon above the rating buttons. */
    fun barLine(bar: Bar): String = when (bar) {
        Bar.RATE_DUE -> "Card due · rate it"
        Bar.RATE_EARLY -> "Not due · rate early"
        Bar.MINE -> ""
    }

    /** The button that is white (the likely answer) is Good. */
    val RATINGS = listOf("Again", "Hard", "Good", "Easy")
}
