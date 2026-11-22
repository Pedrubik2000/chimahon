package eu.kanade.tachiyomi.ui.glass.setup

/** The pure part of the first-run setup (canvas `Setup-2`): when it shows, where it starts, what it says is missing. */
object GlassSetupLogic {

    /** The three facts Open Home needs. None of them needs the network. */
    data class Facts(val aniList: Boolean, val realDebrid: Boolean, val storage: Boolean)

    /** Steps 0-4 (AniList, Real-Debrid, Storage, Notifications, Dictionary), then the summary. */
    const val STEPS = 5
    const val SUMMARY = 5

    /** The step names as the bars, the summary rows and [missingLine] say them. */
    val NAMES = listOf("AniList", "Real-Debrid", "Storage", "Notifications", "Dictionary")

    /** Steps 0-2 are needed, 3 and 4 can be skipped. */
    fun needed(step: Int): Boolean = step in 0..2

    /** Indices (0-2) of the facts that are missing, in step order. */
    fun missing(facts: Facts): List<Int> = listOfNotNull(
        0.takeUnless { facts.aniList },
        1.takeUnless { facts.realDebrid },
        2.takeUnless { facts.storage },
    )

    /** The setup is the root only in the new look, not done yet, and with something missing. */
    fun shows(glassRoot: Boolean, done: Boolean, facts: Facts): Boolean =
        glassRoot && !done && missing(facts).isNotEmpty()

    /** The first missing step, or the summary when nothing is missing. */
    fun firstStep(facts: Facts): Int = missing(facts).firstOrNull() ?: SUMMARY

    /** "Finish Storage first", "Finish Real-Debrid and Storage first", "Finish AniList, Real-Debrid and Storage first". */
    fun missingLine(names: List<String>): String {
        val list = if (names.size > 1) names.dropLast(1).joinToString(", ") + " and " + names.last() else names.firstOrNull().orEmpty()
        return "Finish $list first"
    }

    /** The coloured tag after "STEP n OF 5 · " or "SUMMARY · ". */
    fun tag(step: Int, missingCount: Int): String = when {
        step < SUMMARY -> if (needed(step)) "NEEDED" else "CAN SKIP"
        missingCount == 0 -> "READY"
        else -> "$missingCount NEEDED ${if (missingCount > 1) "STEPS" else "STEP"} LEFT"
    }

    /** A pasted key shown as dots but its last 4 characters; same length, so the text field's cursor maps 1:1. */
    fun masked(key: String): String = if (key.length <= 4) "•".repeat(key.length) else "•".repeat(key.length - 4) + key.takeLast(4)
}
