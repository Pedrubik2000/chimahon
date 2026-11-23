package eu.kanade.tachiyomi.data.download

import eu.kanade.tachiyomi.data.coverage.Coverage
import eu.kanade.tachiyomi.source.online.all.VolumeLabels

/** What to download as a torrent on this device (no Real-Debrid): which magnets hold the wanted volumes. */
object TorrentDownload {

    /**
     * One torrent. [adds] = wanted volumes it holds; [alreadyHere] = its other volumes that are on the device;
     * [extra] = its volumes neither wanted nor here. [seeders] = people sharing it when the source was last read
     * (null = the source did not say); [size] as the source writes it ("1.2 GiB").
     */
    data class Option(
        val sourceId: Long,
        val sourceName: String,
        val magnet: String,
        val volumes: List<Int>,
        val adds: List<Int>,
        val alreadyHere: List<Int>,
        val extra: List<Int>,
        val seeders: Int?,
        val size: String?,
    )

    /**
     * Best first: a torrent someone is sharing before one nobody is (0 seeders; unknown counts as shared), then
     * the one adding more of [wanted], then the most seeders (one person "sharing" often sends nothing), then the
     * least [Option.extra] + [Option.alreadyHere], then the order of [coverages]. Size hardly counts: only the
     * files of the wanted volumes are fetched ([wantedFiles]).
     */
    fun options(wanted: Collection<Int>, here: Set<Int>, coverages: List<Coverage>): List<Option> {
        val want = wanted.toSet()
        return coverages.filter { it.state == Coverage.State.FOUND }.flatMap { c ->
            c.files.mapNotNull { f ->
                val magnet = f.links.firstOrNull { it.startsWith("magnet:") } ?: return@mapNotNull null
                val adds = f.volumes.filter { it in want }
                if (adds.isEmpty()) return@mapNotNull null
                Option(
                    sourceId = c.sourceId,
                    sourceName = c.sourceName,
                    magnet = magnet,
                    volumes = f.volumes.sorted(),
                    adds = adds.sorted(),
                    alreadyHere = f.volumes.filter { it !in want && it in here }.sorted(),
                    extra = f.volumes.filter { it !in want && it !in here }.sorted(),
                    seeders = f.seeders,
                    size = f.size,
                )
            }
        }.distinctBy { it.magnet }.sortedWith(
            // sortedWith is stable: ties keep the order of coverages
            compareBy<Option> { it.seeders == 0 }.thenByDescending { it.adds.size }
                .thenByDescending { it.seeders ?: 0 }.thenBy { it.extra.size + it.alreadyHere.size },
        )
    }

    /**
     * What Start would do with a torrent whose [files] (path, bytes) are known: the [fetched] files and their
     * [bytes] for the [wanted] volumes. [chapters]: the names are chapters, not volumes (nothing would be added).
     * [everything]: every file is fetched although the torrent has more volumes than wanted, because its names
     * do not say which file holds which volume (one archive: what is inside shows only after the download).
     * [scans]: a fetched file's name has the "s" mark ([VolumeLabels.scans]): probably chapters inside, which the
     * import refuses after the download.
     */
    data class Plan(
        val fetched: List<String>,
        val bytes: Long,
        val chapters: Boolean,
        val everything: Boolean,
        val scans: Boolean = false,
    )

    fun plan(files: List<Pair<String, Long>>, wanted: Set<Int>): Plan {
        val paths = files.map { it.first }
        val fetched = wantedFiles(paths, wanted).toSet()
        val names = paths.filter { it in fetched }.map { it.substringAfterLast('/') }
        return Plan(
            fetched = names,
            bytes = files.filter { it.first in fetched }.sumOf { it.second },
            chapters = VolumeLabels.areChapters(paths),
            everything = wanted.isNotEmpty() && fetched.size == paths.size &&
                paths.all { VolumeLabels.volumes(it.substringAfterLast('/')).isEmpty() },
            scans = names.any { VolumeLabels.scans(it) },
        )
    }

    /**
     * Of a torrent's file [paths], those to fetch for the [wanted] volumes: the files whose own name (not the
     * folder's) has a wanted volume number. Every file when nothing is wanted in particular or no file name has a
     * number (one archive, loose pages); none when the names have numbers but not the wanted ones.
     */
    fun wantedFiles(paths: List<String>, wanted: Set<Int>): List<String> {
        if (wanted.isEmpty()) return paths
        val numbered = paths.associateWith { VolumeLabels.volumes(it.substringAfterLast('/')) }
        if (numbered.values.all { it.isEmpty() }) return paths
        return paths.filter { p -> numbered.getValue(p).any { it in wanted } }
    }
}
