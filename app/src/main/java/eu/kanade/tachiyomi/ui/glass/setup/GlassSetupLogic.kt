package eu.kanade.tachiyomi.ui.glass.setup

/** The pure part of the first-run setup (canvas `Setup-2`): when it shows, where it starts, what it says is missing. */
object GlassSetupLogic {

    /**
     * What the setup knows without the network. Open Home needs [aniList] and [storage]; [realDebrid] is for the
     * summary only since 1.2.1: volumes also come by hand and by torrent, so a key can wait.
     */
    data class Facts(val aniList: Boolean, val realDebrid: Boolean, val storage: Boolean)

    /** Steps 0-6 (AniList, Real-Debrid, Storage, Notifications, Dictionary, Word colouring, Known words), then the summary. */
    const val STEPS = 7
    const val SUMMARY = 7

    /** The step names as the bars, the summary rows and [missingLine] say them. */
    val NAMES = listOf("AniList", "Real-Debrid", "Storage", "Notifications", "Dictionary", "Word colouring", "Known words")

    /** AniList (0) and Storage (2) are needed; Real-Debrid (1), Notifications (3), Dictionary (4), Word colouring (5) and Known words (6) can be skipped. */
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

    /** Known words has a source once a Jiten list was imported, kumapie is paired, or the kuma3 / AnkiDroid layer is on. */
    fun knownSourcesSet(jitenImported: Boolean, kumapiePaired: Boolean, ankiOn: Boolean): Boolean = jitenImported || kumapiePaired || ankiOn

    /** The sample line of the Word colouring step (a manga line with a new, a mined and a due word in the mockup). */
    const val SAMPLE = "「この魔法学校に入学するには、試験に合格する必要がある」"

    /**
     * The sample's characters coloured as the mockup shows them, when kumahon's dictionary is not ready: 入学 blue, 試験 red,
     * 合格 green (one colour per character, 0 = normal), the same shape as `KumapieColours.of`.
     */
    fun staticSample(): IntArray = IntArray(SAMPLE.length).also { out ->
        for ((word, colour) in listOf("入学" to 0xFF1565C0.toInt(), "試験" to 0xFFD32F2F.toInt(), "合格" to 0xFF2E7D32.toInt())) {
            val at = SAMPLE.indexOf(word)
            for (i in at until at + word.length) out[i] = colour
        }
    }

    /** The coloured tag after "STEP n OF 6 · " or "SUMMARY · ". */
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
