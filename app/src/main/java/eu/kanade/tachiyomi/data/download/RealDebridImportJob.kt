package eu.kanade.tachiyomi.data.download

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.anilist.AniListLibrary
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.online.all.ExcnnParser
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
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.source.local.LocalSource
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

    /** "<source id>|<reason>" per dead link tried, for the output data. */
    private val failures = mutableListOf<String>()
    private var servedBy = NO_SOURCE

    override suspend fun getForegroundInfo() = foreground("Real-Debrid")

    private fun foreground(text: String) = ForegroundInfo(
        // torrents run in their own queue next to hoster links: their own notification
        if (inputData.getString(KEY_LINK)?.startsWith("magnet:") == true) ID_TORRENT_PROGRESS else Notifications.ID_REAL_DEBRID_PROGRESS,
        applicationContext.notificationBuilder(Notifications.CHANNEL_DOWNLOADER_PROGRESS) {
            setContentTitle("Real-Debrid")
            setContentText(text)
            setSmallIcon(android.R.drawable.stat_sys_download)
            setOngoing(true)
        }.build(),
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
    )

    private suspend fun status(text: String) = runCatching { setForeground(foreground(text)) }

    /** WorkInfo progress for RealDebridQueue: step, bytes of the current file, its size, bytes/s. */
    private fun progress(step: String, bytes: Long = 0, total: Long = 0, speed: Long = 0) = runCatching {
        setProgressAsync(workDataOf(KEY_STEP to step, KEY_BYTES to bytes, KEY_TOTAL to total, KEY_SPEED to speed))
    }

    private fun output(error: String? = null) = workDataOf(
        KEY_SERVED_BY to servedBy,
        KEY_FAILURES to failures.toTypedArray(),
        KEY_ERROR to error,
    )

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
            return Result.failure(output("No Real-Debrid token"))
        }
        return try {
            if (ExcnnParser.isPost(link)) { // an excnn.com post: queue each of its volumes under the post's title
                val html = client.newCall(Request.Builder().url(link).build()).execute().use { it.body.string() }
                val post = ExcnnParser.parsePost(html)
                post.volumes.forEach { start(applicationContext, it.link, post.title) }
                return Result.success()
            }
            progress(STEP_WAITING)
            status("Adding link")
            val (name, files) = resolve(link)
            val localDir = Injekt.get<StorageManager>().getLocalSourceDirectory()
                ?: error("No local source folder (Settings > Data and storage)")
            // Same series = same folder, whatever volumes or version letter this download has (v01-03, v06-09, 2w);
            // named after AniList's romaji title when AniList knows it
            val guess = MangaImportUtil.seriesTitle(inputData.getString(KEY_NAME) ?: name)
            status("AniList: $guess")
            val media = runCatching { aniList(guess, inputData.getLong(KEY_MEDIA_ID, 0)) }
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
                progress(STEP_DOWNLOADING)
                val (dir, fileName) = target(mangaDir, path, title) ?: return@forEachIndexed
                val file = download(url, dir, fileName, report = true) ?: return@forEachIndexed
                if (fileName.substringAfterLast('.').lowercase() in BUNDLES) {
                    status("$label: unpacking")
                    progress(STEP_UNPACKING)
                    unpack(file, mangaDir)
                }
            }
            progress(STEP_ADDING)
            // The link's name can be a romanization AniList doesn't know; the volumes inside usually carry the
            // original title ("[檜原フキ] タダでは抱かれません 第01巻.rar")
            val found = media ?: originals.map { MangaImportUtil.seriesTitle(it) }.distinct().take(2)
                .firstNotNullOfOrNull { runCatching { aniList(it) }.getOrNull() }
            found?.let { runCatching { writeMetadata(mangaDir, it) } }
            ImportHandler.addMangaToLibrary(folder)
            found?.let { bindAniList(folder, it) }
            done("Added $folder (${files.size} files)")
            Result.success(output())
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Real-Debrid import failed: $link" }
            done("Failed: ${e.message}")
            Result.failure(output(e.message ?: e.javaClass.simpleName))
        }
    }

    /**
     * Files of [link], or of the first fallback link (input [KEY_FALLBACKS], other sources' links for the same
     * volume) Real-Debrid can serve. A dead link (file gone, hoster unsupported or down) is noted and the next one
     * tried; anything else, or the last dead link, fails as before.
     */
    private suspend fun resolve(link: String): Pair<String, List<Pair<String, String>>> {
        val fallbacks = (inputData.getLongArray(KEY_FALLBACK_SOURCES) ?: LongArray(0))
            .zip(inputData.getStringArray(KEY_FALLBACKS) ?: emptyArray())
        val candidates = listOf(inputData.getLong(KEY_SOURCE, NO_SOURCE) to link) + fallbacks
        candidates.forEachIndexed { i, (source, url) ->
            try {
                val files = if (url.startsWith("magnet:")) torrentFiles(url) else linkFiles(url)
                servedBy = source
                return files
            } catch (e: DeadLinkException) {
                failures += "$source|${e.message}"
                if (i == candidates.lastIndex) throw e
                logcat(LogPriority.WARN) { "Real-Debrid: dead link from source $source, trying the next" }
                status("Dead link, trying the next source")
            }
        }
        error("No link") // not reached: the last candidate returns or throws
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
                "magnet_error", "error", "virus", "dead" -> throw DeadLinkException("Real-Debrid: $s")
                else -> {
                    status("Real-Debrid: $s ${info.optInt("progress")}%")
                    progress(STEP_WAITING, info.optLong("progress"), 100)
                }
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
            if (!r.isSuccessful) {
                val message = "Real-Debrid $path: HTTP ${r.code} ${body.take(200)}"
                if (isDeadLink(body)) throw DeadLinkException(message)
                error(message)
            }
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

    /** The new file, or null when it's already there from an earlier run. [report]: bytes go to the job's progress. */
    private fun download(url: String, dir: UniFile, name: String, report: Boolean = false): UniFile? {
        if (dir.findFile(name) != null) return null
        val part = dir.createFile("$name.part") ?: error("Can't create $name")
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) error("Download $name: HTTP ${r.code}")
            part.openOutputStream().use { out ->
                if (report) copyReporting(r.body.byteStream(), out, r.body.contentLength()) else r.body.byteStream().copyTo(out)
            }
        }
        if (!part.renameTo(name)) error("Can't rename $name")
        return part
    }

    /** copyTo, posting bytes done / total / speed to the progress at most twice a second. */
    private fun copyReporting(input: java.io.InputStream, out: java.io.OutputStream, total: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        val start = System.currentTimeMillis()
        var last = 0L
        var done = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            done += n
            val now = System.currentTimeMillis()
            if (now - last >= 500) {
                last = now
                progress(STEP_DOWNLOADING, done, total, done * 1000 / (now - start).coerceAtLeast(1))
            }
        }
        progress(STEP_DOWNLOADING, done, total)
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

    /** AniList track row for the series (also puts it on Pedro's AniList list), unless it has one or he is logged out. */
    private suspend fun bindAniList(folder: String, media: JSONObject) {
        val mediaId = media.optLong("id").takeIf { it > 0 } ?: return
        runCatching {
            val manga = Injekt.get<GetManga>().await(folder, LocalSource.ID) ?: return
            val title = media.getJSONObject("title").let { it.str("romaji") ?: it.str("native") } ?: folder
            Injekt.get<AniListLibrary>().link(manga.id, mediaId, title, media.optLong("chapters"))
        }.onFailure { logcat(LogPriority.WARN, it) { "AniList track row failed: $folder" } }
    }

    /** AniList's best match for a manga title, or null; the media itself when [id] (input [KEY_MEDIA_ID]) is known. */
    private fun aniList(title: String, id: Long = 0): JSONObject? {
        val media = if (id > 0) {
            "query(${'$'}q: Int) { Media(id: ${'$'}q, type: MANGA) {"
        } else {
            "query(${'$'}q: String) { Media(search: ${'$'}q, type: MANGA) {"
        }
        val query = media + """
            id chapters title { romaji native english } description(asHtml: false) status genres
            staff(perPage: 6) { edges { role node { name { full } } } } coverImage { extraLarge } } }"""
        val q: Any = if (id > 0) id else title
        val body = JSONObject().put("query", query).put("variables", JSONObject().put("q", q)).toString()
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

    /** Real-Debrid can't serve this link (file gone, hoster unsupported or down): the next source's may work. */
    private class DeadLinkException(message: String) : IllegalStateException(message)

    /** Real-Debrid account state for Settings: premium and days left, or a token it refused. */
    data class Account(val premium: Boolean, val daysLeft: Int, val tokenRejected: Boolean = false)

    companion object {
        private const val API = "https://api.real-debrid.com/rest/1.0"
        private const val KEY_LINK = "link"
        private const val KEY_NAME = "name"
        private const val KEY_MEDIA_ID = "media_id"
        private const val KEY_VOLUMES = "volumes"
        private const val KEY_SOURCE = "source"
        private const val KEY_FALLBACKS = "fallbacks"
        private const val KEY_FALLBACK_SOURCES = "fallback_sources"
        private const val ID_TORRENT_PROGRESS = -2205
        const val NO_SOURCE = -1L

        /** Tags on every request; the others: "al:<AniList media id>", "vol:<n>", "src:<source id>". */
        const val TAG = "rd"
        const val TAG_MEDIA = "al:"
        const val TAG_VOLUME = "vol:"
        const val TAG_SOURCE = "src:"
        const val WORK_LINKS = "RealDebridImport"
        const val WORK_TORRENTS = "RealDebridTorrent"

        // progress
        const val KEY_STEP = "step"
        const val KEY_BYTES = "bytes"
        const val KEY_TOTAL = "total"
        const val KEY_SPEED = "speed"
        const val STEP_WAITING = "waiting"
        const val STEP_DOWNLOADING = "downloading"
        const val STEP_UNPACKING = "unpacking"
        const val STEP_ADDING = "adding"

        // output
        const val KEY_SERVED_BY = "served_by"
        const val KEY_FAILURES = "failures"
        const val KEY_ERROR = "error"

        /** RD error bodies that mean "this link, not the account": file gone, hoster unsupported / down / not free. */
        private fun isDeadLink(body: String): Boolean {
            val json = runCatching { JSONObject(body) }.getOrNull() ?: return false
            val name = json.optString("error")
            return name == "unavailable_file" || name == "file_unavailable" || name.startsWith("hoster_") ||
                json.optInt("error_code") in DEAD_CODES
        }

        // 16 unsupported hoster, 17 in maintenance, 18 limit reached, 19 temporarily unavailable, 20 not free, 24 file unavailable
        private val DEAD_CODES = setOf(16, 17, 18, 19, 20, 24)

        /** Premium flag and days left of the stored token's account; null with no token. Throws on network errors. */
        suspend fun account(): Account? = withContext(Dispatchers.IO) {
            val token = Injekt.get<DownloadPreferences>().realDebridToken().get().trim()
            if (token.isEmpty()) return@withContext null
            val req = Request.Builder().url("$API/user").header("Authorization", "Bearer $token").build()
            Injekt.get<NetworkHelper>().client.newCall(req).execute().use { r ->
                if (r.code == 401 || r.code == 403) return@withContext Account(false, 0, tokenRejected = true)
                if (!r.isSuccessful) error("Real-Debrid /user: HTTP ${r.code}")
                val user = JSONObject(r.body.string())
                // "premium" = seconds of premium left
                Account(premium = user.optString("type") == "premium", daysLeft = (user.optLong("premium") / 86_400).toInt())
            }
        }
        private val IMAGES = setOf("jpg", "jpeg", "png", "webp", "avif", "gif", "jxl", "heif")
        private val CHAPTERS = setOf("cbz", "cbr", "cb7", "epub", "pdf")
        private val BUNDLES = setOf("zip", "rar", "7z")

        private var hosts: Set<String>? = null

        /** Magnets, real-debrid.com links, and links to hosters Real-Debrid supports (once a token is set). */
        suspend fun isLink(text: String): Boolean {
            if (text.startsWith("magnet:") || text.contains("real-debrid.com/") || ExcnnParser.isPost(text)) return true
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

        /**
         * [name]: the series when the hoster's filename says nothing about it (Excnn's "excnn20-11-2026a07.rar").
         * From RealDebridQueue also: the AniList [mediaId], the [volumes] the link holds, its [sourceId], and
         * [fallbacks] (source id, link) of other sources for the same volume, tried in order when [link] is dead.
         */
        fun start(
            context: Context,
            link: String,
            name: String? = null,
            mediaId: Long? = null,
            volumes: List<Int> = emptyList(),
            sourceId: Long? = null,
            fallbacks: List<Pair<Long, String>> = emptyList(),
        ) {
            val input = Data.Builder().putString(KEY_LINK, link).putString(KEY_NAME, name)
            mediaId?.let { input.putLong(KEY_MEDIA_ID, it) }
            if (volumes.isNotEmpty()) input.putIntArray(KEY_VOLUMES, volumes.toIntArray())
            sourceId?.let { input.putLong(KEY_SOURCE, it) }
            if (fallbacks.isNotEmpty()) {
                input.putLongArray(KEY_FALLBACK_SOURCES, fallbacks.map { it.first }.toLongArray())
                input.putStringArray(KEY_FALLBACKS, fallbacks.map { it.second }.toTypedArray())
            }
            val request = OneTimeWorkRequestBuilder<RealDebridImportJob>().setInputData(input.build()).addTag(TAG)
            mediaId?.let { request.addTag(TAG_MEDIA + it) }
            volumes.forEach { request.addTag(TAG_VOLUME + it) }
            sourceId?.let { request.addTag(TAG_SOURCE + it) }
            // One at a time: links shared together often hold the same series (or the same file from two hosters).
            // Magnets (slow torrents) queue apart so they don't hold up hoster links.
            WorkManager.getInstance(context).enqueueUniqueWork(
                if (link.startsWith("magnet:")) WORK_TORRENTS else WORK_LINKS,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request.build(),
            )
        }
    }
}
