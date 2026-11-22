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
import eu.kanade.tachiyomi.data.coverage.SourceCoverage
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.online.all.ExcnnParser
import eu.kanade.tachiyomi.ui.browse.source.ImportHandler
import eu.kanade.tachiyomi.ui.browse.source.MangaImportUtil
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
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
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.track.repository.TrackRepository
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

    // chimahon --> the series folder as this job found it (see doWork)
    /** The series folder's name: the title [MangaImportUtil.volumeNumber] removes, as the local source does. */
    private var seriesTitle = ""

    /** New volume names: "第NN巻" (true) or "Vol. NN", the folder's majority style. */
    private var japanese = true

    /** Volume numbers the folder held before this job (empty on a rerun). */
    private val present = mutableSetOf<Int>()

    /** Volume number -> the chapter name this job wrote it under. */
    private val written = mutableMapOf<Int, String>()

    /** Numbered volumes met so far (stored or skipped): did a pack hold any ([unpack]'s junk rule). */
    private var numberedSeen = 0

    /** Volumes not stored because they were already here, for the output note. */
    private val skipped = sortedSetOf<Int>()

    /** (folder, "<name>.part") of every download this job starts: deleted when it fails or is cancelled. */
    private val partFiles = mutableListOf<Pair<UniFile, String>>()

    /** Files this run created (volumes, images, the bundle, metadata): deleted when it is cancelled. */
    private val created = mutableListOf<UniFile>()

    /** Chapter folders this run created: deleted on cancel when empty. */
    private val createdDirs = mutableListOf<UniFile>()

    /** The series folder, when this run created it: removed when it ends up empty. */
    private var createdSeries: UniFile? = null

    /** Numbered volumes the link being fetched held (stored or skipped): what a pack really holds. */
    private val held = mutableSetOf<Int>()

    /** The source whose link is being fetched now, for the row while it runs (progress [KEY_SERVED_BY]). */
    private var fetching = NO_SOURCE

    /** Ids of the torrents this run added on Real-Debrid: deleted there when it is cancelled. */
    private val torrents = mutableListOf<String>()

    /** Started again by WorkManager after a killed run. */
    private val isRerun get() = runAttemptCount > 0
    // chimahon <--

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
        setProgressAsync(
            workDataOf(KEY_STEP to step, KEY_BYTES to bytes, KEY_TOTAL to total, KEY_SPEED to speed, KEY_SERVED_BY to fetching),
        )
    }

    private fun output(
        error: String? = null,
        note: String? = null,
        delivered: Collection<Int> = emptyList(),
        missing: Collection<Int> = emptyList(),
    ) = workDataOf(
        KEY_SERVED_BY to servedBy,
        KEY_FAILURES to failures.toTypedArray(),
        KEY_ERROR to error,
        KEY_NOTE to note,
        KEY_DELIVERED to delivered.sorted().toIntArray(),
        KEY_MISSING to missing.sorted().toIntArray(),
    )

    /** The job's summary: successes on the downloader channel, failures on the error one. */
    private fun done(text: String, error: Boolean = false) = applicationContext.notify(
        Notifications.ID_REAL_DEBRID_DONE,
        if (error) Notifications.CHANNEL_DOWNLOADER_ERROR else Notifications.CHANNEL_DOWNLOADER_PROGRESS,
    ) {
        setContentTitle("Real-Debrid")
        setContentText(text)
        setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(text))
        setSmallIcon(android.R.drawable.stat_sys_download_done)
    }

    /** Cancelled from RealDebridQueue (or stopped by WorkManager): ends the job between two steps. */
    private fun checkCancelled() {
        if (isStopped || Injekt.get<RealDebridQueue>().isCancelled(id)) throw CancelledException()
    }

    // Never Result.failure(): a failed work fails every work chained behind it (RealDebridQueue reads the error).
    override suspend fun doWork(): Result {
        val link = inputData.getString(KEY_LINK) ?: return Result.success(output("No link"))
        if (token.isEmpty()) {
            done("Set the Real-Debrid API token in Settings > Downloads", error = true)
            return Result.success(output("No Real-Debrid token"))
        }
        var titleDir: UniFile? = null // chimahon: outside the try: the dead-link catch reads the volumes already here
        return try {
            checkCancelled()
            if (ExcnnParser.isPost(link)) { // an excnn.com post: queue each of its volumes under the post's title
                val html = client.newCall(Request.Builder().url(link).build()).execute().use { it.body.string() }
                val post = ExcnnParser.parsePost(html)
                post.volumes.forEach { start(applicationContext, it.link, post.title) }
                return Result.success()
            }
            progress(STEP_WAITING)
            val localDir = Injekt.get<StorageManager>().getLocalSourceDirectory()
                ?: error("No local source folder (Settings > Data and storage)")
            val mediaId = inputData.getLong(KEY_MEDIA_ID, 0)
            // chimahon --> a title page job (AniList media id): its folder before anything is downloaded
            val hint = inputData.getString(KEY_NAME)
            var media: JSONObject? = null
            if (mediaId > 0) {
                status("AniList: ${hint ?: mediaId}")
                media = runCatching { aniList(hint.orEmpty(), mediaId) }
                    .onFailure { logcat(LogPriority.WARN, it) { "AniList lookup failed: $mediaId" } }.getOrNull()
                titleDir = titleFolder(localDir, mediaId, titleNames(media, mediaId) + listOfNotNull(hint))
                val wanted = inputData.getIntArray(KEY_VOLUMES)?.toList().orEmpty()
                // never store a volume twice: every volume this link holds is in the folder already
                if (titleDir != null && wanted.isNotEmpty() && !isRerun &&
                    volumesIn(titleDir).containsAll(wanted)
                ) {
                    titleDir.name?.let { syncSeries(it) }
                    done("${titleDir.name}: already on the tablet")
                    return Result.success(output(note = NOTE_ALREADY_HERE, delivered = wanted))
                }
            }
            // chimahon <--
            status("Adding link")
            val candidates = candidates(link)
            val wanted = inputData.getIntArray(KEY_VOLUMES)?.toSet().orEmpty()
            val (first, resolved) = resolve(candidates, mediaId, wanted)
            val (name, files) = resolved
            // Same series = same folder, whatever volumes or version letter this download has (v01-03, v06-09, 2w);
            // named after AniList's romaji title when AniList knows it
            val guess = MangaImportUtil.seriesTitle(hint ?: name)
            val mangaDir: UniFile
            val title: String
            if (mediaId > 0) {
                // chimahon: linked series' folder > a folder named like any of the title's names > a new one (romaji)
                title = MangaImportUtil.getSafeFolderName(
                    media?.getJSONObject("title")?.str("romaji") ?: Injekt.get<AniListLibrary>().get(mediaId)?.romaji ?: guess,
                )
                mangaDir = titleDir
                    ?: titleFolder(localDir, mediaId, titleNames(media, mediaId) + listOfNotNull(hint, guess, title))
                    ?: newSeriesFolder(localDir, title)
            } else {
                status("AniList: $guess")
                media = runCatching { aniList(guess) }
                    .onFailure { logcat(LogPriority.WARN, it) { "AniList lookup failed: $guess" } }.getOrNull()
                title = MangaImportUtil.getSafeFolderName(media?.getJSONObject("title")?.str("romaji") ?: guess)
                val keys = setOf(MangaImportUtil.seriesKey(title), MangaImportUtil.seriesKey(guess))
                val existing = localDir.listFiles()?.firstOrNull {
                    it.isDirectory && MangaImportUtil.seriesKey(MangaImportUtil.seriesTitle(it.name.orEmpty())) in keys
                }
                mangaDir = existing ?: newSeriesFolder(localDir, title)
            }
            val folder = mangaDir.name ?: title
            // chimahon --> what the folder holds before this job: volumes already here are skipped, new names follow
            // the folder's style. A rerun (killed run) skips nothing: what it finds may be its own half-written volume.
            seriesTitle = folder
            val names = chapterNames(mangaDir)
            japanese = MangaImportUtil.japaneseStyle(names)
            if (!isRerun) present += MangaImportUtil.volumeNumbers(folder, names)
            // chimahon <--
            fetch(files, mangaDir, title)
            // chimahon --> a job for given volumes knows what it delivered: a pack can hold less than its label
            var missing = emptySet<Int>()
            val tried = mutableListOf(candidates[first].source)
            if (wanted.isNotEmpty()) {
                learn(mediaId, candidates[first], candidates[first].volumes ?: wanted)
                missing = missingVolumes(wanted, written.keys, skipped, volumesIn(mangaDir))
                // other sources' links that add nothing but missing volumes (a bigger pack or a torrent waits for the
                // dialog: the corrected coverage offers it on the next tap); "already here" stops doubles
                for (c in candidates.drop(first + 1)) {
                    if (missing.isEmpty()) break
                    if (!autoFallback(c.url, c.volumes, missing)) {
                        if (canHold(c.volumes, missing)) {
                            logcat(LogPriority.INFO) { "Real-Debrid: source ${c.source} holds more than vol. ${missing.joinToString()}, not fetched without asking" }
                        }
                        continue
                    }
                    checkCancelled()
                    logcat(LogPriority.WARN) { "Real-Debrid: vol. ${missing.joinToString()} not in the link, trying source ${c.source}" }
                    status("Vol. ${missing.joinToString()} missing, trying the next source")
                    fetching = c.source
                    progress(STEP_WAITING)
                    val more = try {
                        filesOf(c.url)
                    } catch (e: DeadLinkException) {
                        dead(mediaId, c, e)
                        continue
                    }
                    tried += c.source
                    held.clear()
                    fetch(more.second, mangaDir, title)
                    learn(mediaId, c, c.volumes ?: missing)
                    missing = missingVolumes(wanted, written.keys, skipped, volumesIn(mangaDir))
                }
            }
            val reason = if (missing.isEmpty()) null else missingReason(missing, tried.distinct().map(::sourceName), deadSources())
            // a series folder this job made and nothing landed in: gone, and not added to the library
            if (wanted.isNotEmpty() && createdSeries != null && mangaDir.listFiles().isNullOrEmpty()) {
                mangaDir.delete()
                done("$folder: ${reason ?: "nothing added"}", error = true)
                return Result.success(output(reason ?: "Nothing added", delivered = wanted - missing, missing = missing))
            }
            checkCancelled()
            // chimahon <--
            progress(STEP_ADDING)
            // The link's name can be a romanization AniList doesn't know; the volumes inside usually carry the
            // original title ("[檜原フキ] タダでは抱かれません 第01巻.rar")
            val found = media ?: originals.map { MangaImportUtil.seriesTitle(it) }.distinct().take(2)
                .firstNotNullOfOrNull { runCatching { aniList(it) }.getOrNull() }
            found?.let { runCatching { writeMetadata(mangaDir, it) } }
            checkCancelled()
            // chimahon: the last cancel check. From here the job is done: a cancel now no longer stops or undoes it
            ImportHandler.addMangaToLibrary(folder)
            found?.let { bindAniList(folder, it) }
            syncSeries(folder) // chimahon: the title page shows the new volumes without a classic refresh
            // chimahon: volumes skipped because they were already here are named in the notification
            val note = if (skipped.isEmpty()) null else "$NOTE_ALREADY_HERE: vol. ${skipped.joinToString()}"
            if (reason != null) {
                // ends as failed for those volumes (the queue reads the error); what came is in the library
                done("$folder: $reason" + note?.let { "; $it" }.orEmpty(), error = true)
                return Result.success(output(reason, note, delivered = wanted - missing, missing = missing))
            }
            done("Added $folder (${files.size} files)" + note?.let { "; $it" }.orEmpty())
            Result.success(output(note = note, delivered = wanted))
        } catch (e: CancelledException) {
            logcat(LogPriority.INFO) { "Real-Debrid import cancelled: ${inputData.getString(KEY_NAME) ?: id}" }
            // chimahon: cancelled from the queue = leave nothing; stopped by WorkManager alone (system) = keep the volumes
            if (Injekt.get<RealDebridQueue>().isCancelled(id)) undo() else deleteParts()
            Result.success(output(RealDebridQueue.CANCELLED))
        } catch (e: CancellationException) {
            // WorkManager stopped the worker ("Cancel all", system): no failed notification
            if (Injekt.get<RealDebridQueue>().isCancelled(id)) undo() // chimahon: "Cancel all" flags its jobs first
            throw e
        } catch (e: DeadLinkException) {
            // chimahon: every link dead (only resolve lets one out): one summary, the volumes and the sources tried
            logcat(LogPriority.WARN, e) { "Real-Debrid: every link dead: $link" }
            deleteParts()
            // chimahon: the pack's volumes less those already in the title's folder (a pack of 1-4 for 1-2: "Vol. 1, 2")
            val pack = inputData.getIntArray(KEY_VOLUMES)?.toSet().orEmpty()
            val here = titleDir?.let { runCatching { volumesIn(it) }.getOrNull() }.orEmpty()
            val vols = missingVolumes(pack, emptySet(), emptySet(), here)
            val text = when {
                pack.isEmpty() -> "Dead link: ${e.message}"
                vols.isEmpty() -> "Dead link: ${deadSources().joinToString()}"
                else -> missingReason(vols, emptyList(), deadSources())
            }
            done(listOfNotNull(inputData.getString(KEY_NAME), text).joinToString(": "), error = true)
            Result.success(output(text, delivered = pack - vols, missing = vols))
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Real-Debrid import failed: $link" }
            deleteParts() // chimahon: a failed job leaves no .part, as a cancelled one
            done("Failed: ${e.message}", error = true)
            Result.success(output(e.message ?: e.javaClass.simpleName))
        }
    }

    /** A link to try: the job's own, then other sources' (input [KEY_FALLBACKS]); [volumes] null = not known. */
    private class Candidate(val source: Long, val url: String, val volumes: Set<Int>?)

    private fun candidates(link: String): List<Candidate> {
        val urls = inputData.getStringArray(KEY_FALLBACKS) ?: emptyArray()
        val sources = inputData.getLongArray(KEY_FALLBACK_SOURCES) ?: LongArray(0)
        // per fallback "1,2,3"; jobs queued before it existed have none (volumes not known)
        val vols = inputData.getStringArray(KEY_FALLBACK_VOLUMES)?.takeIf { it.size == urls.size }
        val own = inputData.getIntArray(KEY_VOLUMES)?.toSet()?.ifEmpty { null }
        return listOf(Candidate(inputData.getLong(KEY_SOURCE, NO_SOURCE), link, own)) +
            sources.zip(urls).mapIndexed { i, (s, u) -> Candidate(s, u, vols?.get(i)?.let { decodeVolumes(it) }) }
    }

    /**
     * Index and files of the first of [candidates] Real-Debrid can serve. A dead link (file gone, hoster unsupported
     * or down) is noted and the next one tried; anything else, or the last dead link, fails as before. For a job of
     * given volumes ([wanted]) only fallbacks that add nothing else are tried ([autoFallback]).
     */
    private suspend fun resolve(
        candidates: List<Candidate>,
        mediaId: Long,
        wanted: Set<Int>,
    ): Pair<Int, Pair<String, List<Pair<String, String>>>> {
        val usable = candidates.indices.filter { i ->
            i == 0 || wanted.isEmpty() || candidates[i].let { autoFallback(it.url, it.volumes, wanted) }
        }
        usable.forEach { i ->
            val c = candidates[i]
            try {
                fetching = c.source
                val files = filesOf(c.url)
                servedBy = c.source
                return i to files
            } catch (e: DeadLinkException) {
                dead(mediaId, c, e)
                if (i == usable.last()) throw e
                logcat(LogPriority.WARN) { "Real-Debrid: dead link from source ${c.source}, trying the next" }
                status("Dead link, trying the next source")
            }
        }
        error("No link") // not reached: the last candidate returns or throws
    }

    private suspend fun filesOf(url: String) = if (url.startsWith("magnet:")) torrentFiles(url) else linkFiles(url)

    /** Downloads [files] of one link into [mangaDir]; bundles are unpacked. */
    private suspend fun fetch(files: List<Pair<String, String>>, mangaDir: UniFile, title: String) {
        files.forEachIndexed { i, (path, url) ->
            checkCancelled()
            val label = "${i + 1}/${files.size} ${path.substringAfterLast('/')}"
            status(label)
            progress(STEP_DOWNLOADING)
            val (dir, fileName) = target(mangaDir, path, title) ?: return@forEachIndexed
            val bundle = fileName.substringAfterLast('.').lowercase() in BUNDLES
            partFiles += dir to "$fileName.part"
            val file = download(url, dir, fileName, report = true)
                // a bundle still there: a run killed while unpacking it (unpacking deletes it when done)
                ?: dir.findFile(fileName)?.takeIf { bundle }
                ?: return@forEachIndexed
            if (bundle) {
                status("$label: unpacking")
                progress(STEP_UNPACKING)
                unpack(file, mangaDir)
                checkCancelled()
            }
        }
    }

    // chimahon --> what a link really held; leave nothing when cancelled
    /**
     * After fetching [c]: when it held numbered volumes but not all of [promised], its source's coverage learns that
     * it holds only those ([SourceCoverage.recordHeld]), so the pages stop offering the link for the others.
     */
    private suspend fun learn(mediaId: Long, c: Candidate, promised: Set<Int>) {
        if (mediaId <= 0 || c.source == NO_SOURCE || held.isEmpty() || held.containsAll(promised)) return
        logcat(LogPriority.WARN) { "Real-Debrid: link of source ${c.source} held vol. ${held.sorted().joinToString()} only" }
        Injekt.get<SourceCoverage>().recordHeld(mediaId, c.source, c.url, held.toSet())
    }

    /** A dead link [c]: noted in the output; a file gone for good ([DeadLinkException.gone]) holds nothing from now on. */
    private suspend fun dead(mediaId: Long, c: Candidate, e: DeadLinkException) {
        failures += "${c.source}|${e.message}"
        if (!e.gone || mediaId <= 0 || c.source == NO_SOURCE) return
        logcat(LogPriority.WARN) { "Real-Debrid: link of source ${c.source} is gone" }
        Injekt.get<SourceCoverage>().recordHeld(mediaId, c.source, c.url, emptySet())
    }

    /** chimahon: names of the sources whose link was dead in this run ([failures]), for the summary. */
    private fun deadSources(): List<String> =
        failures.mapNotNull { it.substringBefore('|').toLongOrNull() }.filter { it != NO_SOURCE }.distinct().map(::sourceName)

    private fun sourceName(id: Long): String =
        runCatching { Injekt.get<SourceManager>().get(id)?.name }.getOrNull() ?: "source $id"

    /** A series folder for this job ([createdSeries] when it did not exist). */
    private fun newSeriesFolder(localDir: UniFile, title: String): UniFile {
        val existed = localDir.findFile(title) != null
        val dir = localDir.createDirectory(title) ?: error("Can't create $title")
        if (!existed) createdSeries = dir
        return dir
    }

    /**
     * A cancelled job: its .part files, the files it wrote (still there: an unpacked bundle is gone already), its
     * chapter folders and series folder when empty, and the torrents it added on Real-Debrid.
     */
    private fun undo() {
        deleteParts()
        created.asReversed().forEach { runCatching { if (it.exists()) it.delete() } }
        (createdDirs.asReversed() + listOfNotNull(createdSeries)).forEach { dir ->
            runCatching { if (dir.exists() && dir.listFiles().isNullOrEmpty()) dir.delete() }
        }
        torrents.forEach { id ->
            runCatching {
                val req = Request.Builder().url("$API/torrents/delete/$id").header("Authorization", "Bearer $token").delete()
                client.newCall(req.build()).execute().use { r -> check(r.isSuccessful) { "HTTP ${r.code}" } }
            }.onFailure { logcat(LogPriority.WARN, it) { "Real-Debrid: torrent $id not deleted" } }
        }
    }
    // chimahon <--

    /** Torrent name + (path inside the torrent, direct download url) per file, once Real-Debrid has it. */
    private suspend fun torrentFiles(magnet: String): Pair<String, List<Pair<String, String>>> {
        val id = JSONObject(api("/torrents/addMagnet", "magnet" to magnet)).getString("id")
        torrents += id // chimahon: deleted on Real-Debrid when this run is cancelled
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
                    checkCancelled()
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
                if (isDeadLink(body)) throw DeadLinkException(message, gone = isGone(body))
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
                val volume = MangaImportUtil.restyle(MangaImportUtil.volumeName(name), japanese)
                if (alreadyHere(volume)) null else mangaDir to "$volume.$ext"
            }
            ext in BUNDLES -> mangaDir to name
            ext in IMAGES -> {
                val original = parts.getOrNull(parts.size - 2) ?: defaultChapter
                if (originals.lastOrNull() != original) originals += original
                val chapter = MangaImportUtil.getSafeFolderName(
                    MangaImportUtil.restyle(MangaImportUtil.volumeName(original), japanese),
                )
                if (alreadyHere(chapter)) return null // before createDirectory: no empty chapter folder
                val existed = mangaDir.findFile(chapter) != null
                val dir = mangaDir.createDirectory(chapter) ?: error("Can't create $chapter")
                if (!existed) createdDirs += dir
                dir to name
            }
            else -> null
        }
    }

    // chimahon --> never store a volume twice
    /**
     * True when [chapter] (a volume file name without extension, or a chapter folder name) is a volume the folder
     * held before this job, or one this job already wrote under another name ("第04巻" then "第04巻 w" in one pack).
     */
    private fun alreadyHere(chapter: String): Boolean {
        val n = MangaImportUtil.volumeNumber(seriesTitle, chapter) ?: return false
        numberedSeen++
        held += n
        val skip = n in present || written[n].let { it != null && it != chapter }
        if (skip) {
            logcat(LogPriority.INFO) { "Real-Debrid: vol. $n already in $seriesTitle, $chapter skipped" }
            skipped += n
        } else {
            written[n] = chapter
        }
        return skip
    }

    /** Names of what the local source reads as chapters in [dir]: folders and archives, no .part or cover. */
    private fun chapterNames(dir: UniFile): List<String> = dir.listFiles().orEmpty()
        .filter { f ->
            val n = f.name.orEmpty()
            !n.startsWith('.') && (f.isDirectory || n.substringAfterLast('.').lowercase() in CHAPTERS + BUNDLES)
        }
        .mapNotNull { it.name }

    private fun volumesIn(dir: UniFile) = MangaImportUtil.volumeNumbers(dir.name.orEmpty(), chapterNames(dir))

    /** native, romaji, english of [media] (this job's AniList answer) and of the cached list entry. */
    private fun titleNames(media: JSONObject?, mediaId: Long): List<String> {
        val t = media?.optJSONObject("title")
        return listOfNotNull(t?.str("native"), t?.str("romaji"), t?.str("english")) +
            Injekt.get<AniListLibrary>().get(mediaId)?.titles.orEmpty()
    }

    /**
     * The folder of AniList title [mediaId]: the one of a local series with its AniList track (the one with most
     * chapters first), else an existing folder named like one of [names]; null = none yet.
     */
    private suspend fun titleFolder(localDir: UniFile, mediaId: Long, names: List<String>): UniFile? {
        val folders = localDir.listFiles().orEmpty().filter { it.isDirectory }
        val linked = runCatching {
            val getManga = Injekt.get<GetManga>()
            val chapters = Injekt.get<ChapterRepository>()
            Injekt.get<TrackRepository>().getTracks()
                .filter { it.trackerId == TrackerManager.ANILIST && it.remoteId == mediaId }
                .mapNotNull { t -> getManga.await(t.mangaId)?.takeIf { it.source == LocalSource.ID } }
                .map { it.url to chapters.getChapterByMangaId(it.id).size }
                .sortedByDescending { it.second }
                .map { it.first }
        }.onFailure { logcat(LogPriority.WARN, it) { "Linked series lookup failed: $mediaId" } }.getOrDefault(emptyList())
        val name = MangaImportUtil.chooseFolder(linked, folders.mapNotNull { it.name }, names) ?: return null
        return folders.firstOrNull { it.name == name }
    }

    /** Chapter rows of the local series in [folder] = its files (the new screens read rows, not the folder). */
    private suspend fun syncSeries(folder: String) {
        runCatching { Injekt.get<GetManga>().await(folder, LocalSource.ID) }
            .onFailure { logcat(LogPriority.WARN, it) { "Local series lookup failed: $folder" } }
            .getOrNull()?.let { LocalSeriesSync.sync(it.id) }
    }

    /** A failed or cancelled job's own .part files (also one a killed earlier run left under the same name). */
    private fun deleteParts() {
        partFiles.forEach { (dir, name) -> runCatching { dir.findFile(name)?.delete() } }
    }
    // chimahon <--

    /** The new file, or null when it's already there from an earlier run. [report]: bytes go to the job's progress. */
    private fun download(url: String, dir: UniFile, name: String, report: Boolean = false): UniFile? {
        if (dir.findFile(name) != null) return null
        dir.findFile("$name.part")?.delete() // left by a killed run
        val part = dir.createFile("$name.part") ?: error("Can't create $name")
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) error("Download $name: HTTP ${r.code}")
                part.openOutputStream().use { out ->
                    if (report) copyReporting(r.body.byteStream(), out, r.body.contentLength()) else r.body.byteStream().copyTo(out)
                }
            }
        } catch (e: Exception) {
            part.delete()
            throw e
        }
        if (!part.renameTo(name)) error("Can't rename $name")
        created += part
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
                checkCancelled()
                progress(STEP_DOWNLOADING, done, total, done * 1000 / (now - start).coerceAtLeast(1))
            }
        }
        progress(STEP_DOWNLOADING, done, total)
    }

    /** copyTo that checks for a cancel every MiB. */
    private fun copyCancellable(input: java.io.InputStream, out: java.io.OutputStream) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var sinceCheck = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            sinceCheck += n
            if (sinceCheck >= 1L shl 20) {
                sinceCheck = 0
                checkCancelled()
            }
        }
    }

    /** A rar/7z/zip of volumes (or of one volume's images) -> its volumes / chapter folders in the series folder. */
    // ponytail: multi-part RARs (.part1.rar...) aren't joined; libarchive reads one file
    private fun unpack(bundle: UniFile, mangaDir: UniFile) {
        val stem = bundle.name.orEmpty().substringBeforeLast('.')
        val seenBefore = numberedSeen // chimahon: numbered volumes in this pack = seen since here (kept or skipped)
        val unnumbered = mutableListOf<String>() // chimahon: volume files without one this unpack wrote
        bundle.archiveReader(applicationContext).use { reader ->
            ArchiveInputStream(reader.address, reader.size, reader.encrypted).use { stream ->
                generateSequence { stream.getNextEntry() }.filter { it.isFile }.forEach { entry ->
                    checkCancelled() // chimahon: between entries, and every MiB inside one (copyCancellable)
                    // target skips volumes already in the folder (chimahon)
                    val (dir, name) = target(mangaDir, entry.name, stem, inBundle = true) ?: return@forEach
                    val volumeFile = dir === mangaDir
                    val hasNumber = MangaImportUtil.volumeNumber(seriesTitle, name) != null
                    if (dir.findFile(name) != null) return@forEach
                    // via .part: a run killed mid-entry must not leave a half volume that looks done
                    dir.findFile("$name.part")?.delete()
                    partFiles += dir to "$name.part"
                    val file = dir.createFile("$name.part") ?: error("Can't create $name")
                    file.openOutputStream().use { copyCancellable(stream, it) }
                    if (!file.renameTo(name)) error("Can't rename $name")
                    created += file
                    if (volumeFile && !hasNumber) unnumbered += name
                }
            }
        }
        // chimahon --> a small archive with no volume number next to numbered volumes is no volume (a group's ad)
        val numbered = numberedSeen > seenBefore
        unnumbered.forEach { name ->
            val file = mangaDir.findFile(name) ?: return@forEach
            if (MangaImportUtil.isJunk(hasNumber = false, packHasNumbered = numbered, bytes = file.length())) {
                logcat(LogPriority.INFO) { "Real-Debrid: $name (${file.length()} B) is no volume, not kept" }
                file.delete()
            }
        }
        // chimahon <--
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
            mangaDir.createFile(COMIC_INFO_FILE)?.also { created += it }?.openOutputStream()?.use { it.write(xml.toByteArray()) }
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
    private class DeadLinkException(message: String, val gone: Boolean = false) : IllegalStateException(message)

    /** The job was cancelled from RealDebridQueue: it ends as success with the error "Cancelled". */
    private class CancelledException : Exception("Cancelled")

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

        /** chimahon: each fallback link's volumes, "1,2,3" (parallel to [KEY_FALLBACKS]). */
        private const val KEY_FALLBACK_VOLUMES = "fallback_volumes"
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

        /** chimahon: done, but some or all of the link's volumes were not stored again ([NOTE_ALREADY_HERE]). */
        const val KEY_NOTE = "note"
        const val NOTE_ALREADY_HERE = "already on the tablet"

        /** chimahon: of the job's volumes ([KEY_VOLUMES]), those on the tablet now and those no link held. */
        const val KEY_DELIVERED = "delivered"
        const val KEY_MISSING = "missing"

        // chimahon --> pure parts, for RealDebridImportJobTest
        /** [promised] volumes neither [written] nor [skipped] by this job, nor in the folder now ([present]). */
        internal fun missingVolumes(promised: Set<Int>, written: Set<Int>, skipped: Set<Int>, present: Set<Int>): Set<Int> =
            promised - written - skipped - present

        /** Can a link with [volumes] (null = not known) hold one of [missing]? */
        internal fun canHold(volumes: Set<Int>?, missing: Set<Int>): Boolean =
            volumes == null || volumes.any { it in missing }

        /**
         * May the job fetch a fallback link with [volumes] for [missing] without asking? Only a hoster link known to
         * hold nothing but missing volumes: a bigger pack or a torrent is offered by the title page's dialog instead.
         */
        internal fun autoFallback(url: String, volumes: Set<Int>?, missing: Set<Int>): Boolean =
            !url.startsWith("magnet:") && volumes != null && canHold(volumes, missing) && missing.containsAll(volumes)

        /** "1,2,3" -> {1, 2, 3}; null when it names none. */
        internal fun decodeVolumes(text: String): Set<Int>? =
            text.split(',').mapNotNull { it.trim().toIntOrNull() }.toSet().ifEmpty { null }

        /**
         * "Vol. 1, 2 were not in the pack (X3DL); dead link: Rawxt"; no link served at all ([sources] empty, [dead]
         * not): "Vol. 1, 2 were not found; dead link: X3DL, Rawxt".
         */
        internal fun missingReason(missing: Set<Int>, sources: List<String>, dead: List<String> = emptyList()): String {
            val verb = if (missing.size == 1) "was" else "were"
            val what = if (sources.isEmpty() && dead.isNotEmpty()) "not found" else "not in the pack"
            return "Vol. ${missing.sorted().joinToString()} $verb $what" +
                (if (sources.isEmpty()) "" else " (${sources.joinToString()})") +
                if (dead.isEmpty()) "" else "; dead link: ${dead.joinToString()}"
        }
        // chimahon <--

        /** RD error bodies that mean "this link, not the account": file gone, hoster unsupported / down / not free. */
        private fun isDeadLink(body: String): Boolean {
            val json = runCatching { JSONObject(body) }.getOrNull() ?: return false
            val name = json.optString("error")
            return name == "unavailable_file" || name == "file_unavailable" || name.startsWith("hoster_") ||
                json.optInt("error_code") in DEAD_CODES
        }

        /** chimahon: of those, the definite "the file is gone" answer (not a hoster down or a limit). */
        private fun isGone(body: String): Boolean {
            val json = runCatching { JSONObject(body) }.getOrNull() ?: return false
            val name = json.optString("error")
            return name == "unavailable_file" || name == "file_unavailable" || json.optInt("error_code") == 24
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
         * [fallbacks] (source id, link) of other sources for the same volume, tried in order when [link] is dead,
         * and again for the volumes [link] turns out not to hold; [fallbackVolumes] = each fallback's volumes.
         */
        fun start(
            context: Context,
            link: String,
            name: String? = null,
            mediaId: Long? = null,
            volumes: List<Int> = emptyList(),
            sourceId: Long? = null,
            fallbacks: List<Pair<Long, String>> = emptyList(),
            fallbackVolumes: List<Collection<Int>> = emptyList(),
        ) {
            val input = Data.Builder().putString(KEY_LINK, link).putString(KEY_NAME, name)
            mediaId?.let { input.putLong(KEY_MEDIA_ID, it) }
            if (volumes.isNotEmpty()) input.putIntArray(KEY_VOLUMES, volumes.toIntArray())
            sourceId?.let { input.putLong(KEY_SOURCE, it) }
            if (fallbacks.isNotEmpty()) {
                input.putLongArray(KEY_FALLBACK_SOURCES, fallbacks.map { it.first }.toLongArray())
                input.putStringArray(KEY_FALLBACKS, fallbacks.map { it.second }.toTypedArray())
                if (fallbackVolumes.size == fallbacks.size) {
                    input.putStringArray(KEY_FALLBACK_VOLUMES, fallbackVolumes.map { it.sorted().joinToString(",") }.toTypedArray())
                }
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
