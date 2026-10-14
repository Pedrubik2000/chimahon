package eu.kanade.tachiyomi.ui.browse.source

import tachiyomi.core.common.storage.nameWithoutExtension

object MangaImportUtil {
    // Regex to find volume/chapter markers to strip them from the base title (supports English and Japanese)
    private val unwanted = Regex("""(?:\b(?:v|ver|vol|version|volume|season|s|ch|chapter)[^a-z]?[0-9]+)|(?:\s+[0-9]+$)|(?:第?\s*[0-9]+\s*(?:巻|話|冊))""", RegexOption.IGNORE_CASE)

    // Illegal characters for filenames on most systems (Android is Linux based but SD cards might be FAT32/exFAT)
    private val illegalCharacters = Regex("""[\\/:*?"<>|]""")

    /**
     * Extracts the base series title by removing volume/chapter markers.
     * Example: "One Piece Vol. 1" -> "One Piece"
     */
    fun getBaseTitle(fileName: String): String {
        val nameWithoutExt = fileName.substringBeforeLast(".")
        return unwanted.replace(nameWithoutExt, "")
            .trim()
            .removeSuffix("-")
            .removeSuffix("_")
            .trim()
    }

    private val archiveExt = Regex("""\.(cbz|cbr|cb7|zip|rar|7z|epub|pdf)$""", RegexOption.IGNORE_CASE)
    private val tags = Regex("""[\[({【][^\])}】]*[\])}】]""")
    private val volumeMarker = Regex("""(?:^|[\s\-])(?:v|vol\.?|volume|tome|ch\.?|chapter|c)\s*\d.*$""", RegexOption.IGNORE_CASE)
    private val japaneseVolume = Regex("""\s*第?\s*\d+\s*[巻話冊].*$""")
    // "2", "2w", "01-03", "01-09w6"
    private val trailingNumbers = Regex("""[\s\-]+\d+[a-z]*\d*(?:\s*-\s*\d+[a-z]*\d*)?$""", RegexOption.IGNORE_CASE)

    /**
     * Series name of a download, without volume ranges, version letters, groups or tags, so separate downloads
     * of one manga share a folder: "[Grp] Yotsuba&! v01-03 (Digital).rar" and "Yotsuba&! 2w.cbz" -> "Yotsuba&!".
     */
    // ponytail: with no "v01" marker a title ending in a number ("Kaiji 2") loses it; downloads still agree
    fun seriesTitle(name: String): String {
        val bare = archiveExt.replace(name, "")
        var s = tags.replace(bare, " ").replace('_', ' ').replace(Regex("""\s+"""), " ")
        val marked = volumeMarker.replace(s, "").let { japaneseVolume.replace(it, "") }
        if (marked != s) {
            s = marked
        } else {
            while (trailingNumbers.containsMatchIn(s)) s = trailingNumbers.replace(s, "")
        }
        return s.trim(' ', '-', '_', ',').ifBlank { bare.trim() }
    }

    private val jpVolume = Regex("""第?\s*(\d+)\s*巻\s*([a-z])?(?![a-z])""", RegexOption.IGNORE_CASE)
    private val volume = Regex("""(?:^|[\s\-_])(?:v|vol\.?|volume|tome)\s*(\d+)([a-z])?(?![a-z0-9])""", RegexOption.IGNORE_CASE)
    private val lastNumber = Regex("""(?:^|[\s\-_])(\d+)([a-z])?$""", RegexOption.IGNORE_CASE)

    /**
     * Chapter name of a volume file or folder: "[十五夜] Title 第02巻s.zip" -> "第02巻 s", "Title v03.cbz" -> "Vol. 03",
     * "Title 2w" -> "Vol. 02 w" (the version letter stays, so both versions of a volume are kept).
     * Names without a volume number keep their name, minus extension and [group]/(tag) parts.
     */
    fun volumeName(name: String): String {
        val bare = tags.replace(archiveExt.replace(name, ""), " ").replace(Regex("""\s+"""), " ").trim()
        jpVolume.find(bare)?.let { m -> return label("第%02d巻", m) }
        (volume.find(bare) ?: lastNumber.find(bare))?.let { m -> return label("Vol. %02d", m) }
        return bare.ifBlank { archiveExt.replace(name, "") }
    }

    private fun label(format: String, m: MatchResult): String {
        val letter = m.groupValues[2].lowercase()
        return format.format(m.groupValues[1].toInt()) + if (letter.isEmpty()) "" else " $letter"
    }

    /** Compares series names ignoring case, spaces and punctuation. */
    fun seriesKey(title: String) = title.lowercase().replace(Regex("""[^\p{L}\p{N}]"""), "")

    /**
     * Replaces illegal characters with underscores and trims the result.
     */
    fun getSafeFolderName(title: String): String {
        return illegalCharacters.replace(title, "_").trim()
    }
}
