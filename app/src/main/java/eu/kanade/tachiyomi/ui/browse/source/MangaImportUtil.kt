package eu.kanade.tachiyomi.ui.browse.source

import tachiyomi.core.common.storage.nameWithoutExtension
import tachiyomi.domain.chapter.service.ChapterRecognition
import kotlin.math.floor

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
            .trim(' ', '-', ',') // "Ao_no_Hako_11-19_.rar"
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

    // chimahon --> one folder per title, one copy and one naming style per volume

    /**
     * Volume number of a chapter file or folder name in a series folder, read as the local source reads chapter
     * numbers ([ChapterRecognition]); a version letter ("第04巻 w", "Vol. 02 b") is the same volume. Null without one.
     */
    // ponytail: real half volumes (2.5) count as 2; none in the folders so far
    fun volumeNumber(mangaTitle: String, name: String): Int? {
        val n = ChapterRecognition.parseChapterNumber(mangaTitle, archiveExt.replace(name, ""))
        return if (n < 0) null else floor(n).toInt()
    }

    fun volumeNumbers(mangaTitle: String, names: Collection<String>): Set<Int> =
        names.mapNotNullTo(mutableSetOf()) { volumeNumber(mangaTitle, it) }

    private val jpStyle = Regex("""^第\d+巻""")
    private val enStyle = Regex("""^Vol\. \d+""")
    private val styled = Regex("""^(?:第(\d+)巻|Vol\. (\d+))( [a-z])?$""")

    /** True = "第NN巻", false = "Vol. NN": the style most of a folder's [names] use; "第NN巻" on a tie or none. */
    fun japaneseStyle(names: Collection<String>): Boolean =
        names.count { jpStyle.containsMatchIn(it) } >= names.count { enStyle.containsMatchIn(it) }

    /** A [volumeName] result in the folder's style: "Vol. 10" -> "第10巻", "第03巻 r" -> "Vol. 03 r"; others as they are. */
    fun restyle(volumeName: String, japanese: Boolean): String {
        val m = styled.matchEntire(volumeName) ?: return volumeName
        val n = m.groupValues[1].ifEmpty { m.groupValues[2] }.toInt()
        return (if (japanese) "第%02d巻" else "Vol. %02d").format(n) + m.groupValues[3]
    }

    /**
     * Which folder a title's volumes go to: the first of [linked] (folders of local series with this title's AniList
     * track, best first) that exists, else an existing folder whose series name matches one of [names] (native,
     * romaji, english, the link's name), else null = make a new one.
     */
    fun chooseFolder(linked: List<String>, folders: List<String>, names: List<String>): String? {
        linked.firstOrNull { it in folders }?.let { return it }
        val keys = names.flatMap { listOf(it, seriesTitle(it)) }.map(::seriesKey).filter { it.isNotEmpty() }.toSet()
        return folders.firstOrNull { seriesKey(seriesTitle(it)) in keys || seriesKey(it) in keys }
    }

    /** Archives under this size with no volume number, from a pack that has numbered volumes, are not kept. */
    const val JUNK_MAX_BYTES = 20L * 1024 * 1024

    /**
     * A volume-type file just unpacked from a pack that is no volume (a group's ad or info archive): it has no volume
     * number, the same pack held numbered volumes, and it is small. A one-shot (no numbered sibling) is kept.
     */
    fun isJunk(hasNumber: Boolean, packHasNumbered: Boolean, bytes: Long): Boolean =
        !hasNumber && packHasNumbered && bytes in 0 until JUNK_MAX_BYTES
    // chimahon <--

    /** Compares series names ignoring case, spaces and punctuation. */
    fun seriesKey(title: String) = title.lowercase().replace(Regex("""[^\p{L}\p{N}]"""), "")

    /**
     * Replaces illegal characters with underscores and trims the result.
     */
    fun getSafeFolderName(title: String): String {
        return illegalCharacters.replace(title, "_").trim()
    }
}
