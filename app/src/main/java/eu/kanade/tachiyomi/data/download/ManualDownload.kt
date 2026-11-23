package eu.kanade.tachiyomi.data.download

import eu.kanade.tachiyomi.data.coverage.Coverage

/** What to download by hand (no Real-Debrid): which links of which source cover the wanted volumes. */
object ManualDownload {

    /**
     * One file to get in the browser: [link] and the volumes the source says it holds; [mirrors] = the same file on
     * other hosters, to fall back on when [link] is gone ([alive]). [scans]: see [Coverage.File.scans].
     */
    data class File(
        val link: String,
        val volumes: List<Int>,
        val mirrors: List<String> = emptyList(),
        val scans: Boolean = false,
    )

    /**
     * One way to get the wanted volumes from one source (a source gives up to two: its smallest files, and its
     * biggest packs). [adds] = wanted volumes its files hold; [alreadyHere] = other
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
     * Best first: options adding all [wanted] before partial ones, partial ones adding more first, then options with
     * no file marked [File.scans] (the import refuses those; a source uses one only where it has nothing else),
     * then options with no file on a [slow] hoster, then fewest files (a big pack = fewest captchas),
     * then least [Option.extra] + [Option.alreadyHere], then the order of [coverages]. No sizes are known, none guessed.
     * Each file is on its first hoster that is not [slow].
     */
    fun options(wanted: Collection<Int>, here: Set<Int>, coverages: List<Coverage>): List<Option> {
        val want = wanted.toSet()
        return coverages.filter { it.state == Coverage.State.FOUND }.flatMap { c ->
            // a cache from before Coverage.files has only the one link per volume
            val known = c.files.ifEmpty {
                c.volumes.values.distinct().map { l -> Coverage.File(c.volumes.filterValues { it == l }.keys.sorted(), listOf(l)) }
            }
            val files = known.filter { f -> f.volumes.any { it in want } }.mapNotNull { f ->
                // magnets wait for torrent support
                val links = f.links.filterNot { it.startsWith("magnet:") }.sortedBy { slow(it) }
                links.firstOrNull()?.let { File(it, f.volumes, links.drop(1), f.scans) }
            }.distinctBy { it.link }
            listOf(smallest(files, want), biggest(files, want)).filter { it.isNotEmpty() }.distinct().map { chosen ->
                val all = chosen.flatMap { it.volumes }.toSet()
                Option(
                    sourceId = c.sourceId,
                    sourceName = c.sourceName,
                    files = chosen,
                    adds = all.filter { it in want }.sorted(),
                    alreadyHere = all.filter { it !in want && it in here }.sorted(),
                    extra = all.filter { it !in want && it !in here }.sorted(),
                )
            }
        }.sortedWith(order)
    }

    // sortedWith is stable: ties keep the order of coverages. adds is part of wanted, so "most adds" puts the
    // options adding everything first.
    private val order = compareByDescending<Option> { it.adds.size }
        .thenBy { o -> o.files.any { it.scans } }
        .thenBy { o -> o.files.any { slow(it.link) } }.thenBy { it.files.size }
        .thenBy { it.extra.size + it.alreadyHere.size }

    /** Per wanted volume the file holding the fewest volumes (the first listed on a tie), one not [File.scans] first. */
    private fun smallest(files: List<File>, want: Set<Int>): List<File> = want.sorted().mapNotNull { v ->
        files.filter { v in it.volumes }.minWithOrNull(compareBy<File> { it.scans }.thenBy { it.volumes.size })
    }.distinct()

    /** Fewest files: again and again the file holding the most still-missing wanted volumes (one not
     * [File.scans], then the smaller, on a tie). */
    private fun biggest(files: List<File>, want: Set<Int>): List<File> {
        val left = want.toMutableSet()
        val chosen = mutableListOf<File>()
        while (left.isNotEmpty()) {
            val best = files.maxWithOrNull(
                compareBy<File> { f -> f.volumes.count { it in left } }.thenBy { !it.scans }
                    .thenByDescending { it.volumes.size },
            )?.takeIf { f -> f.volumes.any { it in left } } ?: break
            chosen += best
            left -= best.volumes.toSet()
        }
        return chosen
    }

    /** Every link of [options], mirrors too: what the dead-link check asks. */
    fun links(options: List<Option>): List<String> =
        options.flatMap { o -> o.files.flatMap { listOf(it.link) + it.mirrors } }.distinct()

    /**
     * [options] with each file moved to its first link that is not in [gone] (a file with none left keeps its
     * link: the option is dead), best first again.
     */
    fun alive(options: List<Option>, gone: Set<String>): List<Option> = options.map { o ->
        o.copy(
            files = o.files.map { f ->
                val live = (listOf(f.link) + f.mirrors).filter { it !in gone }
                if (live.isEmpty()) f else f.copy(link = live.first(), mirrors = live.drop(1))
            },
        )
    }.sortedWith(order)

    /** Is [link] on a hoster too slow to use without paying? */
    // ponytail: only Rapidgator (2026-11-22: ~50-100 KB/s, no resume, a browser download died after 30 min).
    // Add a host here when another one proves as bad; measure speeds if a real ranking is ever needed.
    fun slow(link: String): Boolean = host(link) in setOf("rapidgator.net", "rg.to")

    /**
     * Does a hoster's answer to a plain request for a file's page say the file is gone? [landed] = the address
     * after redirects.
     */
    // ponytail: only what was seen on 2026-11-22: 404 / 410 (Uploady), and Rapidgator sending a missing file to
    // its premium page. Katfile and Hexload answer 200 either way: not detected; add their marker text if needed.
    fun gone(code: Int, landed: String): Boolean =
        code == 404 || code == 410 || (host(landed) == "rapidgator.net" && "/article/premium" in landed)

    /** "rapidgator.net" from a link, without "www."; "" when it has no host. */
    fun host(link: String): String {
        val rest = link.substringAfter("://", "").takeIf { it.isNotEmpty() } ?: return ""
        return rest.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
            .substringBefore(':').lowercase().removePrefix("www.")
    }
}
