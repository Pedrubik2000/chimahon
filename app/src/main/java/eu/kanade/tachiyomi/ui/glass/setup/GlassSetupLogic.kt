package eu.kanade.tachiyomi.ui.glass.setup

/** The pure part of the first-run setup (canvas `Setup-2`): when it shows, where it starts, what it says is missing. */
object GlassSetupLogic {

    /**
     * What the setup knows without the network. Open Home needs [aniList] and [storage]; [realDebrid] is for the
     * summary only since 1.2.1: volumes also come by hand and by torrent, so a key can wait.
     */
    data class Facts(val aniList: Boolean, val realDebrid: Boolean, val storage: Boolean)

    /** Steps 0-4 (AniList, Real-Debrid, Storage, Notifications, Dictionary), then the summary. */
    const val STEPS = 5
    const val SUMMARY = 5

    /** The step names as the bars, the summary rows and [missingLine] say them. */
    val NAMES = listOf("AniList", "Real-Debrid", "Storage", "Notifications", "Dictionary")

    /** AniList (0) and Storage (2) are needed; Real-Debrid (1), Notifications (3) and Dictionary (4) can be skipped. */
    fun needed(step: Int): Boolean = step == 0 || step == 2

    /** Indices of the needed facts that are missing, in step order. */
    fun missing(facts: Facts): List<Int> = listOfNotNull(
        0.takeUnless { facts.aniList },
        2.takeUnless { facts.storage },
    )

    /** The setup is the root only in the new look, not done yet, and with something missing. */
    fun shows(glassRoot: Boolean, done: Boolean, facts: Facts): Boolean =
        glassRoot && !done && missing(facts).isNotEmpty()

    /** The first missing step, or the summary when nothing is missing. */
    fun firstStep(facts: Facts): Int = missing(facts).firstOrNull() ?: SUMMARY

    /** "Finish Storage first", "Finish AniList and Storage first" (three names read "A, B and C"). */
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

    /**
     * The storage folder as the canvas shows it: "/storage/emulated/0/Chimahon" → "/sdcard/Chimahon". Settings › Data
     * shows the raw path (UniFile.displayablePath); an SD card or a content URI stays as it is.
     */
    fun shortPath(path: String): String = path.replaceFirst(Regex("^/storage/emulated/0(?=/|$)"), "/sdcard")

    /** A pasted key shown as dots but its last 4 characters; same length, so the text field's cursor maps 1:1. */
    fun masked(key: String): String = if (key.length <= 4) "•".repeat(key.length) else "•".repeat(key.length - 4) + key.takeLast(4)
}
