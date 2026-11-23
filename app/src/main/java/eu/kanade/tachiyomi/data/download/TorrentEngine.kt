package eu.kanade.tachiyomi.data.download

import android.content.Context
import eu.kanade.tachiyomi.data.torrentServer.service.TorrentServerService
import eu.kanade.tachiyomi.torrentServer.TorrentServerApi
import eu.kanade.tachiyomi.torrentServer.TorrentServerUtils
import eu.kanade.tachiyomi.torrentServer.model.Torrent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The torrent engine the anime side ships (TorrServer), as the manga downloads use it: started in the caller's
 * own process when needed (not through [TorrentServerService]: a background start of that service can be
 * refused), and stopped when it holds no torrent. Every call blocks or suspends on the network: off the main thread.
 */
object TorrentEngine {

    private const val START_WAIT_SECONDS = 20
    private const val PEEK_WAIT_MS = 45_000L

    // ponytail: fixed public trackers beside the engine's own list and DHT (a Nyaa magnet here is a bare
    // info hash); a setting if they go away
    private const val TRACKERS = "&tr=http%3A%2F%2Fnyaa.tracker.wf%3A7777%2Fannounce" +
        "&tr=udp%3A%2F%2Fopen.stealth.si%3A80%2Fannounce" +
        "&tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337%2Fannounce" +
        "&tr=udp%3A%2F%2Fexodus.desync.com%3A6969%2Fannounce"

    /** The engine is running when this returns. */
    fun ensure(context: Context) {
        if (TorrentServerApi.echo() != "") return
        torrServer.TorrServer.startTorrentServer(context.applicationContext.filesDir.absolutePath)
        if (!TorrentServerService.wait(START_WAIT_SECONDS)) error("The torrent engine did not start")
        TorrentServerUtils.setTrackersList()
    }

    /** Adds [magnet]; returns its info hash. */
    fun add(magnet: String, title: String): String =
        TorrentServerApi.addTorrent(magnet + TRACKERS, title).hash ?: error("The torrent engine did not take the magnet")

    /** The engine forgets [hash], and stops when that leaves it with nothing to do. */
    fun remove(hash: String) {
        runCatching { TorrentServerApi.remTorrent(hash) }
        // a list that cannot be read is not an empty list: the engine stays
        if (runCatching { TorrentServerApi.listTorrent() }.getOrNull()?.isEmpty() == true) {
            runCatching { torrServer.TorrServer.stopTorrentServer() }
            runCatching { TorrentServerApi.shutdown() }
        }
    }

    /** "magnet:?xt=urn:btih:<hash>&…" -> the hash, lower case. */
    fun hashOf(magnet: String): String = magnet.substringAfter("btih:", "").substringBefore('&').lowercase()

    /**
     * What [magnet] holds, without downloading any of it: (path, bytes) per file, or null when nobody gave the
     * file list in time. A torrent the engine already holds (a download in progress) is read and left alone.
     */
    suspend fun peek(context: Context, magnet: String): List<Pair<String, Long>>? = withContext(Dispatchers.IO) {
        ensure(context)
        val known = TorrentServerApi.listTorrent().firstOrNull { it.hash?.lowercase() == hashOf(magnet) }
        val hash = known?.hash ?: add(magnet, "")
        try {
            val deadline = System.currentTimeMillis() + PEEK_WAIT_MS
            var torrent: Torrent = TorrentServerApi.getTorrent(hash)
            while (torrent.file_stats.isNullOrEmpty() && System.currentTimeMillis() < deadline) {
                delay(1_500)
                torrent = TorrentServerApi.getTorrent(hash)
            }
            torrent.file_stats?.takeIf { it.isNotEmpty() }?.map { it.path to it.length }
        } finally {
            if (known == null) remove(hash)
        }
    }
}
