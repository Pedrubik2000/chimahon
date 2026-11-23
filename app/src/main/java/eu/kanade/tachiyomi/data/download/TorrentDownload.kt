package eu.kanade.tachiyomi.data.download

import eu.kanade.tachiyomi.data.coverage.Coverage

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
     * the one adding more of [wanted], then the least [Option.extra] + [Option.alreadyHere] (less to download),
     * then the most seeders, then the order of [coverages]. The whole torrent is downloaded: no file is picked.
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
                .thenBy { it.extra.size + it.alreadyHere.size }.thenByDescending { it.seeders ?: 0 },
        )
    }
}
