package eu.kanade.tachiyomi.data.download

import eu.kanade.tachiyomi.data.coverage.Coverage

/** What to download by hand (no Real-Debrid): which links of which source cover the wanted volumes. */
object ManualDownload {

    /** One file to get in the browser: [link] and the volumes the source says it holds. */
    data class File(val link: String, val volumes: List<Int>)

    /**
     * One way to get the wanted volumes from one source. [adds] = wanted volumes its files hold; [alreadyHere] = other
     * volumes in those files that are on the device; [extra] = volumes in those files neither wanted nor here.
     */
    data class Option(
        val sourceId: Long,
        val sourceName: String,
        val files: List<File>,
        val adds: List<Int>,
        val alreadyHere: List<Int>,
        val extra: List<Int>,
    )

    /**
     * Best first: options adding all [wanted] before partial ones, partial ones adding more first, then fewest files
     * (a big pack = fewest captchas),
     * then least [Option.extra] + [Option.alreadyHere], then the order of [coverages]. No sizes are known, none guessed.
     */
    fun options(wanted: Collection<Int>, here: Set<Int>, coverages: List<Coverage>): List<Option> {
        val want = wanted.toSet()
        return coverages.filter { it.state == Coverage.State.FOUND }.mapNotNull { c ->
            // magnets wait for torrent support
            val links = c.volumes.filter { (v, l) -> v in want && !l.startsWith("magnet:") }.values.distinct()
            if (links.isEmpty()) return@mapNotNull null
            val files = links.map { l -> File(l, c.volumes.filterValues { it == l }.keys.sorted()) }
            val all = files.flatMap { it.volumes }.toSet()
            Option(
                sourceId = c.sourceId,
                sourceName = c.sourceName,
                files = files,
                adds = all.filter { it in want }.sorted(),
                alreadyHere = all.filter { it !in want && it in here }.sorted(),
                extra = all.filter { it !in want && it !in here }.sorted(),
            )
        }.sortedWith(
            // sortedWith is stable: ties keep the order of coverages
            compareBy<Option> { it.adds.size != want.size }.thenByDescending { it.adds.size }.thenBy { it.files.size }
                .thenBy { it.extra.size + it.alreadyHere.size },
        )
    }

    /** "rapidgator.net" from a link, without "www."; "" when it has no host. */
    fun host(link: String): String {
        val rest = link.substringAfter("://", "").takeIf { it.isNotEmpty() } ?: return ""
        return rest.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
            .substringBefore(':').lowercase().removePrefix("www.")
    }
}
