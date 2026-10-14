package eu.kanade.tachiyomi.data.download

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
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
import eu.kanade.tachiyomi.source.model.SManga
import nl.adaptivity.xmlutil.serialization.XML
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.metadata.comicinfo.COMIC_INFO_FILE
import tachiyomi.core.metadata.comicinfo.ComicInfo
import tachiyomi.core.metadata.comicinfo.getComicInfo
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

    /** Original names of the volumes saved, for the AniList fallback. */
    private val originals = mutableListOf<String>()

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
            // Same series = same folder, whatever volumes or version letter this download has (v01-03, v06-09, 2w);
            // named after AniList's romaji title when AniList knows it
            val guess = MangaImportUtil.seriesTitle(name)
            status("AniList: $guess")
            val media = runCatching { aniList(guess) }
                .onFailure { logcat(LogPriority.WARN, it) { "AniList lookup failed: $guess" } }.getOrNull()
            val title = MangaImportUtil.getSafeFolderName(media?.getJSONObject("title")?.str("romaji") ?: guess)
            val keys = setOf(MangaImportUtil.seriesKey(title), MangaImportUtil.seriesKey(guess))
            val existing = localDir.listFiles()?.firstOrNull {
                it.isDirectory && MangaImportUtil.seriesKey(MangaImportUtil.seriesTitle(it.name.orEmpty())) in keys
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
            // The link's name can be a romanization AniList doesn't know; the volumes inside usually carry the
            // original title ("[檜原フキ] タダでは抱かれません 第01巻.rar")
            val found = media ?: originals.map { MangaImportUtil.seriesTitle(it) }.distinct().take(2)
                .firstNotNullOfOrNull { runCatching { aniList(it) }.getOrNull() }
            found?.let { runCatching { writeMetadata(mangaDir, it) } }
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
    private fun target(
        mangaDir: UniFile,
        path: String,
        defaultChapter: String,
        inBundle: Boolean = false,
    ): Pair<UniFile, String>? {
        val parts = path.trim('/').split('/')
        val name = MangaImportUtil.getSafeFolderName(parts.last())
        val ext = name.substringAfterLast('.').lowercase()
        // a rar/zip inside a bundle is one volume, not another bundle
        val isVolume = ext in CHAPTERS || (inBundle && ext in BUNDLES)
        return when {
            isVolume -> {
                originals += name
                mangaDir to "${MangaImportUtil.volumeName(name)}.$ext"
            }
            ext in BUNDLES -> mangaDir to name
            ext in IMAGES -> {
                val original = parts.getOrNull(parts.size - 2) ?: defaultChapter
                if (originals.lastOrNull() != original) originals += original
                val chapter = MangaImportUtil.getSafeFolderName(MangaImportUtil.volumeName(original))
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
                    val (dir, name) = target(mangaDir, entry.name, stem, inBundle = true) ?: return@forEach
                    if (dir.findFile(name) != null) return@forEach
                    val file = dir.createFile(name) ?: error("Can't create $name")
                    file.openOutputStream().use { stream.copyTo(it) }
                }
            }
        }
        bundle.delete()
    }

    /** AniList's best match for a manga title, or null. */
    private fun aniList(title: String): JSONObject? {
        val query = """query(${'$'}q: String) { Media(search: ${'$'}q, type: MANGA) {
            title { romaji native english } description(asHtml: false) status genres
            staff(perPage: 6) { edges { role node { name { full } } } } coverImage { extraLarge } } }"""
        val body = JSONObject().put("query", query).put("variables", JSONObject().put("q", title)).toString()
        val req = Request.Builder().url("https://graphql.anilist.co")
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json")
            .build()
        client.newCall(req).execute().use { r ->
            if (r.code == 404) return null // no match
            if (!r.isSuccessful) error("AniList: HTTP ${r.code}")
            return JSONObject(r.body.string()).getJSONObject("data").optJSONObject("Media")
        }
    }

    /** ComicInfo.xml (title, authors, description, genres, status) and cover.jpg, unless the folder has them. */
    private fun writeMetadata(mangaDir: UniFile, media: JSONObject) {
        if (mangaDir.findFile(COMIC_INFO_FILE) == null) {
            val titles = media.getJSONObject("title")
            val staff = media.getJSONObject("staff").getJSONArray("edges").objects()
            fun people(role: String) = staff.filter { it.getString("role").contains(role, ignoreCase = true) }
                .joinToString { it.getJSONObject("node").getJSONObject("name").getString("full") }.ifBlank { null }
            val manga = SManga.create().apply {
                title = titles.str("romaji").orEmpty()
                author = people("Story")
                artist = people("Art")
                description = listOfNotNull(
                    titles.str("native"),
                    titles.str("english"),
                    media.str("description")?.replace(Regex("<[^>]+>"), "")?.trim(),
                ).joinToString("\n\n")
                genre = media.getJSONArray("genres").let { a -> List(a.length()) { a.getString(it) } }.joinToString()
                status = when (media.str("status")) {
                    "RELEASING" -> SManga.ONGOING
                    "FINISHED" -> SManga.COMPLETED
                    "CANCELLED" -> SManga.CANCELLED
                    "HIATUS" -> SManga.ON_HIATUS
                    else -> SManga.UNKNOWN
                }
            }
            val xml = Injekt.get<XML>().encodeToString(ComicInfo.serializer(), manga.getComicInfo())
            mangaDir.createFile(COMIC_INFO_FILE)?.openOutputStream()?.use { it.write(xml.toByteArray()) }
        }
        val cover = media.optJSONObject("coverImage")?.str("extraLarge").orEmpty()
        if (cover.isNotBlank() && mangaDir.findFile("cover.jpg") == null) {
            download(cover, mangaDir, "cover.jpg")
        }
    }

    private fun JSONArray.objects() = List(length()) { getJSONObject(it) }

    /** null for missing, JSON null or blank (optString gives "null" for JSON null). */
    private fun JSONObject.str(key: String) = if (isNull(key)) null else optString(key).ifBlank { null }

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
            // One at a time: links shared together often hold the same series (or the same file from two hosters)
            WorkManager.getInstance(context).enqueueUniqueWork(
                "RealDebridImport",
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<RealDebridImportJob>().setInputData(workDataOf(KEY_LINK to link)).build(),
            )
        }
    }
}
