package eu.kanade.tachiyomi.data.download

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.ui.browse.source.ImportHandler
import eu.kanade.tachiyomi.ui.browse.source.MangaImportUtil
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import kotlinx.coroutines.delay
import logcat.LogPriority
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * A magnet or real-debrid.com link shared to the app -> Real-Debrid -> a folder in local manga, added to the library.
 * Archives (cbz/zip/rar/epub...) become chapters in the series folder; loose images become one chapter
 * folder per parent folder of the torrent.
 */
class RealDebridImportJob(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val client = Injekt.get<NetworkHelper>().client
    private val token = Injekt.get<DownloadPreferences>().realDebridToken().get().trim()

    override suspend fun getForegroundInfo() = foreground("Real-Debrid")

    private fun foreground(text: String) = ForegroundInfo(
        Notifications.ID_REAL_DEBRID_PROGRESS,
        applicationContext.notificationBuilder(Notifications.CHANNEL_DOWNLOADER_PROGRESS) {
            setContentTitle("Real-Debrid")
            setContentText(text)
            setSmallIcon(android.R.drawable.stat_sys_download)
            setOngoing(true)
        }.build(),
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
    )

    private suspend fun status(text: String) = runCatching { setForeground(foreground(text)) }

    private fun done(text: String) = applicationContext.notify(
        Notifications.ID_REAL_DEBRID_DONE,
        Notifications.CHANNEL_DOWNLOADER_ERROR,
    ) {
        setContentTitle("Real-Debrid")
        setContentText(text)
        setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(text))
        setSmallIcon(android.R.drawable.stat_sys_download_done)
    }

    override suspend fun doWork(): Result {
        val link = inputData.getString(KEY_LINK) ?: return Result.failure()
        if (token.isEmpty()) {
            done("Set the Real-Debrid API token in Settings > Downloads")
            return Result.failure()
        }
        return try {
            status("Adding link")
            val (name, files) = if (link.startsWith("magnet:")) torrentFiles(link) else linkFiles(link)
            val localDir = Injekt.get<StorageManager>().getLocalSourceDirectory()
                ?: error("No local source folder (Settings > Data and storage)")
            val folder = MangaImportUtil.getSafeFolderName(name.substringBeforeLast('.').ifBlank { name })
            val mangaDir = localDir.createDirectory(folder) ?: error("Can't create $folder")
            files.forEachIndexed { i, (path, url) ->
                status("${i + 1}/${files.size} ${path.substringAfterLast('/')}")
                download(url, target(mangaDir, path))
            }
            ImportHandler.addMangaToLibrary(folder)
            done("Added $folder (${files.size} files)")
            Result.success()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Real-Debrid import failed: $link" }
            done("Failed: ${e.message}")
            Result.failure()
        }
    }

    /** Torrent name + (path inside the torrent, direct download url) per file, once Real-Debrid has it. */
    private suspend fun torrentFiles(magnet: String): Pair<String, List<Pair<String, String>>> {
        val id = JSONObject(api("/torrents/addMagnet", "magnet" to magnet)).getString("id")
        api("/torrents/selectFiles/$id", "files" to "all")
        while (true) {
            val info = JSONObject(api("/torrents/info/$id"))
            when (val s = info.getString("status")) {
                "downloaded" -> {
                    val selected = info.getJSONArray("files").objects().filter { it.getInt("selected") == 1 }
                    val links = info.getJSONArray("links").let { a -> List(a.length()) { a.getString(it) } }
                    // ponytail: assumes one link per selected file (RD's usual); a torrent RD packs into one RAR lands as one archive
                    val paths = if (links.size == selected.size) selected.map { it.getString("path") } else links.map { "" }
                    val files = links.zip(paths).map { (l, p) ->
                        val u = unrestrict(l)
                        p.ifEmpty { u.getString("filename") } to u.getString("download")
                    }
                    return info.getString("filename") to files
                }
                "magnet_error", "error", "virus", "dead" -> error("Real-Debrid: $s")
                else -> status("Real-Debrid: $s ${info.optInt("progress")}%")
            }
            delay(5_000)
        }
    }

    private fun linkFiles(link: String): Pair<String, List<Pair<String, String>>> {
        if (".download.real-debrid.com/" in link) { // already a direct download link
            val name = java.net.URLDecoder.decode(link.substringAfterLast('/'), "UTF-8")
            return name to listOf(name to link)
        }
        val u = unrestrict(link)
        val name = u.getString("filename")
        return name to listOf(name to u.getString("download"))
    }

    private fun unrestrict(link: String) = JSONObject(api("/unrestrict/link", "link" to link))

    private fun api(path: String, vararg form: Pair<String, String>): String {
        val req = Request.Builder().url(API + path).header("Authorization", "Bearer $token")
        if (form.isNotEmpty()) req.post(FormBody.Builder().apply { form.forEach { add(it.first, it.second) } }.build())
        client.newCall(req.build()).execute().use { r ->
            val body = r.body.string()
            if (!r.isSuccessful) error("Real-Debrid $path: HTTP ${r.code} ${body.take(200)}")
            return body
        }
    }

    /** Series folder for archives, a chapter folder (named after the image's parent folder) for loose images. */
    private fun target(mangaDir: UniFile, path: String): Pair<UniFile, String> {
        val parts = path.trim('/').split('/')
        val name = MangaImportUtil.getSafeFolderName(parts.last())
        if (name.substringAfterLast('.').lowercase() !in IMAGES) return mangaDir to name
        val chapter = MangaImportUtil.getSafeFolderName(parts.getOrNull(parts.size - 2) ?: "Chapter 1")
        return (mangaDir.createDirectory(chapter) ?: error("Can't create $chapter")) to name
    }

    private fun download(url: String, target: Pair<UniFile, String>) {
        val (dir, name) = target
        if (dir.findFile(name) != null) return // already there from an earlier run
        val part = dir.createFile("$name.part") ?: error("Can't create $name")
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) error("Download $name: HTTP ${r.code}")
            part.openOutputStream().use { out -> r.body.byteStream().copyTo(out) }
        }
        if (!part.renameTo(name)) error("Can't rename $name")
    }

    private fun JSONArray.objects() = List(length()) { getJSONObject(it) }

    companion object {
        private const val API = "https://api.real-debrid.com/rest/1.0"
        private const val KEY_LINK = "link"
        private val IMAGES = setOf("jpg", "jpeg", "png", "webp", "avif", "gif", "jxl", "heif")

        fun isLink(text: String) = text.startsWith("magnet:") || text.contains("real-debrid.com/")

        fun start(context: Context, link: String) {
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<RealDebridImportJob>().setInputData(workDataOf(KEY_LINK to link)).build(),
            )
        }
    }
}
