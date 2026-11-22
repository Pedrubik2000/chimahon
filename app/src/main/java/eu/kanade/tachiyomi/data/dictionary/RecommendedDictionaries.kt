package eu.kanade.tachiyomi.data.dictionary

import chimahon.dictionary.readDictionaryIndex
import java.io.File
import java.util.Locale

/**
 * One usual dictionary offered for download (build plan phase 12). URLs, sizes and attributions from Yomitan's
 * recommended-dictionaries.json (`ja.terms`, `ja.kanji`) and each zip's index.json, 2026-11-22. To offer one more,
 * add an entry to [RecommendedDictionaries.all]: the setup step, Settings and the licences screen list it.
 */
data class RecommendedDictionary(
    val id: String,
    val name: String,
    /** what it is, one short line ("Words · Japanese → English") */
    val what: String,
    /** the dictionaries/<type> folder it lands in: "term" or "kanji" */
    val type: String,
    /** a stable "latest" link; the same as the downloadUrl in its index.json */
    val url: String,
    /** the zip's size when checked; the real one changes with each release (shown as "about") */
    val bytes: Long,
    /** shown with a MAIN tag */
    val main: Boolean = false,
    val onByDefault: Boolean = true,
    val licence: String,
    /** the full acknowledgement for the licences screen (the EDRDG licence wants it on its own screen) */
    val attribution: String,
)

object RecommendedDictionaries {

    private const val EDRDG =
        "This publication has included material from the JMdict (EDICT, etc.) dictionary files in accordance " +
            "with the licence provisions of the Electronic Dictionaries Research Group. See http://www.edrdg.org/"

    val all: List<RecommendedDictionary> = listOf(
        RecommendedDictionary(
            id = "jitendex",
            name = "Jitendex",
            what = "Words · Japanese → English",
            type = "term",
            url = "https://github.com/stephenmk/stephenmk.github.io/releases/latest/download/jitendex-yomitan.zip",
            bytes = 38_831_822,
            main = true,
            licence = "CC BY-SA 4.0 · jitendex.org",
            attribution = "© CC BY-SA 4.0 Stephen Kraus 2023-2026\n\n" +
                "You are free to use, modify, and redistribute Jitendex files under the terms of the Creative " +
                "Commons Attribution-ShareAlike License (V4.0)\n\n" +
                "Jitendex includes material from several copyrighted sources in compliance with the terms and " +
                "conditions of those projects.\n\n" +
                "• JMdict (EDICT, etc.) dictionary data is provided by the Electronic Dictionaries Research Group. " +
                "Visit edrdg.org for more information.\n" +
                "• Example sentences (Japanese and English) are provided by Tatoeba (https://tatoeba.org/). " +
                "This data is licensed CC BY 2.0 FR.",
        ),
        RecommendedDictionary(
            id = "jmnedict",
            name = "JMnedict",
            what = "Names of people and places",
            type = "term",
            url = "https://github.com/yomidevs/jmdict-yomitan/releases/latest/download/JMnedict.zip",
            bytes = 11_433_266,
            licence = "CC BY-SA 4.0 · EDRDG licence (edrdg.org/edrdg/licence.html)",
            attribution = "This package uses the JMnedict dictionary files. These files are the property of the " +
                "Electronic Dictionary Research and Development Group, and are used in conformance with the " +
                "Group's licence.\n\n$EDRDG\n\nConverted for Yomitan by yomidevs/jmdict-yomitan.",
        ),
        RecommendedDictionary(
            id = "kanjidic",
            name = "KANJIDIC",
            what = "Kanji · readings, meanings, grade, JLPT level",
            type = "kanji",
            url = "https://github.com/yomidevs/jmdict-yomitan/releases/latest/download/KANJIDIC_english.zip",
            bytes = 721_032,
            licence = "CC BY-SA 4.0 · EDRDG licence (edrdg.org/edrdg/licence.html)",
            attribution = "This package uses the KANJIDIC2 dictionary file. This file is the property of the " +
                "Electronic Dictionary Research and Development Group, and is used in conformance with the " +
                "Group's licence.\n\n$EDRDG\n\nConverted for Yomitan by yomidevs/jmdict-yomitan.",
        ),
    )

    fun byId(id: String): RecommendedDictionary? = all.firstOrNull { it.id == id }

    /** MB as the design shows it: whole MB, one decimal under 1 MB ("37", "0.7"). */
    fun mb(bytes: Long): String {
        val mb = bytes / 1_048_576.0
        return if (mb < 1) String.format(Locale.ROOT, "%.1f", mb) else Math.round(mb).toString()
    }

    /** The Download pill's size for a selection ("48" for Jitendex + JMnedict). */
    fun totalMb(picked: List<RecommendedDictionary>): String = mb(picked.sumOf { it.bytes })

    /** "A", "A and B", "A, B and C". */
    fun names(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> names[0]
        else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
    }

    /** One installed dictionary folder: dictionaries/<type>/<folder>, with its index.json's downloadUrl. */
    data class Found(val type: String, val folder: String, val downloadUrl: String?)

    /** Installed = its downloadUrl matches, or (an older or hand-imported copy) its folder starts with the name. */
    fun matches(entry: RecommendedDictionary, found: Found): Boolean =
        found.downloadUrl == entry.url || (found.type == entry.type && found.folder.startsWith(entry.name))

    fun installedIds(found: List<Found>): Set<String> =
        all.filter { e -> found.any { matches(e, it) } }.map { it.id }.toSet()

    /** The person's own dictionaries (folder names), not one of [all], in the profile's [order] (unknown ones last). */
    fun others(found: List<Found>, order: List<String> = emptyList()): List<String> =
        found.filter { f -> all.none { matches(it, f) } }.map { it.folder }.distinct()
            .sortedBy { order.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }

    /** The own-dictionaries row's line: "A", "A and B", "A, B and 9 more"; null for none (no row). */
    fun ownSummary(own: List<String>): String? = when (own.size) {
        0 -> null
        1, 2 -> names(own)
        else -> "${own[0]}, ${own[1]} and ${own.size - 2} more"
    }

    fun scan(dictionariesDir: File): List<Found> =
        dictionariesDir.listFiles().orEmpty().filter { it.isDirectory }.flatMap { type ->
            type.listFiles().orEmpty().filter { it.isDirectory }.map { dir ->
                Found(type.name, dir.name, readDictionaryIndex(dir)?.downloadUrl)
            }
        }
}
