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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.core.archive.ArchiveInputStream
import mihon.core.archive.archiveReader
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * A magnet, real-debrid.com or hoster link shared to the app -> Real-Debrid -> the series' folder in local manga
 * (an existing one when the series matches), added to the library. cbz/cbr/epub/pdf become chapters; rar/7z/zip
 * bundles are unpacked into their volumes; loose images become one chapter folder per parent folder.
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
            // Same series = same folder, whatever volumes or version letter this download has (v01-03, v06-09, 2w)
            val title = MangaImportUtil.getSafeFolderName(MangaImportUtil.seriesTitle(name))
            val key = MangaImportUtil.seriesKey(title)
            val existing = localDir.listFiles()?.firstOrNull {
                it.isDirectory && MangaImportUtil.seriesKey(MangaImportUtil.seriesTitle(it.name.orEmpty())) == key
            }
            val mangaDir = existing ?: localDir.createDirectory(title) ?: error("Can't create $title")
            val folder = mangaDir.name ?: title
            files.forEachIndexed { i, (path, url) ->
                val label = "${i + 1}/${files.size} ${path.substringAfterLast('/')}"
                status(label)
                val (dir, fileName) = target(mangaDir, path, title) ?: return@forEachIndexed
                val file = download(url, dir, fileName) ?: return@forEachIndexed
                if (fileName.substringAfterLast('.').lowercase() in BUNDLES) {
                    status("$label: unpacking")
                    unpack(file, mangaDir)
                }
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

    /**
     * Series folder for chapter files (cbz, rar, epub...), a chapter folder (the image's parent folder, else
     * [defaultChapter]) for loose images, null for anything else (nfo, txt...).
     */
    private fun target(mangaDir: UniFile, path: String, defaultChapter: String): Pair<UniFile, String>? {
        val parts = path.trim('/').split('/')
        val name = MangaImportUtil.getSafeFolderName(parts.last())
        return when (name.substringAfterLast('.').lowercase()) {
            in CHAPTERS, in BUNDLES -> mangaDir to name
            in IMAGES -> {
                val chapter = MangaImportUtil.getSafeFolderName(parts.getOrNull(parts.size - 2) ?: defaultChapter)
                (mangaDir.createDirectory(chapter) ?: error("Can't create $chapter")) to name
            }
            else -> null
        }
    }

    /** The new file, or null when it's already there from an earlier run. */
    private fun download(url: String, dir: UniFile, name: String): UniFile? {
        if (dir.findFile(name) != null) return null
        val part = dir.createFile("$name.part") ?: error("Can't create $name")
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) error("Download $name: HTTP ${r.code}")
            part.openOutputStream().use { out -> r.body.byteStream().copyTo(out) }
        }
        if (!part.renameTo(name)) error("Can't rename $name")
        return part
    }

    /** A rar/7z/zip of volumes (or of one volume's images) -> its volumes / chapter folders in the series folder. */
    // ponytail: multi-part RARs (.part1.rar...) aren't joined; libarchive reads one file
    private fun unpack(bundle: UniFile, mangaDir: UniFile) {
        val stem = bundle.name.orEmpty().substringBeforeLast('.')
        bundle.archiveReader(applicationContext).use { reader ->
            ArchiveInputStream(reader.address, reader.size, reader.encrypted).use { stream ->
                generateSequence { stream.getNextEntry() }.filter { it.isFile }.forEach { entry ->
                    val (dir, name) = target(mangaDir, entry.name, stem) ?: return@forEach
                    if (dir.findFile(name) != null) return@forEach
                    val file = dir.createFile(name) ?: error("Can't create $name")
                    file.openOutputStream().use { stream.copyTo(it) }
                }
            }
        }
        bundle.delete()
    }

    private fun JSONArray.objects() = List(length()) { getJSONObject(it) }

    companion object {
        private const val API = "https://api.real-debrid.com/rest/1.0"
        private const val KEY_LINK = "link"
        private val IMAGES = setOf("jpg", "jpeg", "png", "webp", "avif", "gif", "jxl", "heif")
        private val CHAPTERS = setOf("cbz", "cbr", "cb7", "epub", "pdf")
        private val BUNDLES = setOf("zip", "rar", "7z")

        private var hosts: Set<String>? = null

        /** Magnets, real-debrid.com links, and links to hosters Real-Debrid supports (once a token is set). */
        suspend fun isLink(text: String): Boolean {
            if (text.startsWith("magnet:") || text.contains("real-debrid.com/")) return true
            if (Injekt.get<DownloadPreferences>().realDebridToken().get().isBlank()) return false
            val host = text.toHttpUrlOrNull()?.host ?: return false
            val domains = hosts ?: withContext(Dispatchers.IO) {
                runCatching {
                    val req = Request.Builder().url("$API/hosts/domains").build()
                    Injekt.get<NetworkHelper>().client.newCall(req).execute().use { r ->
                        JSONArray(r.body.string()).let { a -> List(a.length()) { a.getString(it) }.toSet() }
                    }
                }.getOrNull()
            }?.also { hosts = it } ?: return false
            return domains.any { host == it || host.endsWith(".$it") }
        }

        fun start(context: Context, link: String) {
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<RealDebridImportJob>().setInputData(workDataOf(KEY_LINK to link)).build(),
            )
        }
    }
}
