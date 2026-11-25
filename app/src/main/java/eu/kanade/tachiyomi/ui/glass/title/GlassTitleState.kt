package eu.kanade.tachiyomi.ui.glass.title

import eu.kanade.tachiyomi.data.coverage.Coverage
import eu.kanade.tachiyomi.data.coverage.CoverageMatch
import eu.kanade.tachiyomi.data.download.RealDebridQueue
import eu.kanade.tachiyomi.data.track.anilist.Anilist
import eu.kanade.tachiyomi.ui.browse.source.MangaImportUtil
import tachiyomi.domain.chapter.model.Chapter
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** One local-source chapter of the title (= one volume). [number] is the volume, from "Vol. NN". */
data class LocalVolume(val chapterId: Long, val number: Int, val read: Boolean, val lastPageRead: Long)

/** A queued or running Real-Debrid job's hold on one volume. [fraction] = download progress, null when unknown. */
data class Active(val sourceId: Long?, val running: Boolean, val fraction: Float?)

/** One box of a source card's coverage bar. */
enum class BoxState { NONE, HAVE, HERE, QUEUED }

enum class VolState { READ, HERE, DOWNLOADING, QUEUED, FAILED, NONE }

data class VolumeCell(val number: Int, val state: VolState, val local: LocalVolume?, val fraction: Float?)

/** What one queued job (one link) brings: a single volume or a pack. */
data class Pack(val sourceId: Long, val volumes: List<Int>)

sealed interface Primary {
    data class Resume(val volume: LocalVolume) : Primary
    data class Fetch(val volume: Int) : Primary
    data object None : Primary
}

/** Pure parts of the title page, for [GlassTitleStateTest]. */
object GlassTitleState {

    /** A chapter number as a volume box: integers ≥ 1 only ("Vol. 3.5" or an unnumbered file is not a box). */
    fun volumeNumber(chapterNumber: Double): Int? =
        chapterNumber.takeIf { it >= 1 && it % 1.0 == 0.0 }?.toInt()

    /**
     * A local chapter's volume, read from its file name as imports read it (url = "folder/file"), not the row's number:
     * a ComicInfo <Number> inside the archive overrides that one, so a cell could open another volume's file.
     */
    fun volumeNumber(chapter: Chapter): Int? =
        MangaImportUtil.volumeNumber(chapter.url.substringBeforeLast('/'), chapter.url.substringAfterLast('/'))
            ?.takeIf { it >= 1 }

    /**
     * Home's next volume = the title page's Resume: the same volumes (file-name number [volumeNumber], not in [empty],
     * deduped) and [primary]'s rule (a volume being read, else the first unread after the highest read here or on
     * AniList, [progressVolumes]). Volumes above [total] (0 = unknown, no cap) are dropped: a chapter-range file
     * ("ch146-153") is no volume. Null when the page would Fetch or has nothing.
     */
    fun nextUnread(chapters: List<Chapter>, empty: Set<String>, total: Int, progressVolumes: Int): Chapter? {
        val volumes = dedupe(
            chapters.filter { it.url !in empty }.mapNotNull { c ->
                volumeNumber(c)?.let { LocalVolume(c.id, it, c.read, c.lastPageRead) }
            },
        )
        val resume = primary(inRange(volumes, total), progressVolumes, total, emptySet()) as? Primary.Resume ?: return null
        return chapters.firstOrNull { it.id == resume.volume.chapterId }
    }

    /** [volumes] numbered 1..[total] (0 = unknown: all): a chapter-range file read as "146" is no volume of an 18-volume series. */
    fun inRange(volumes: List<LocalVolume>, total: Int): List<LocalVolume> =
        if (total > 0) volumes.filter { it.number <= total } else volumes

    /** A local file counts as on the tablet unless it is an empty (0-byte) archive or epub; a directory always counts. */
    fun counts(isDirectory: Boolean, length: Long): Boolean = isDirectory || length > 0

    /**
     * The urls ("folder/file") of [chapters] whose file is empty ([counts] false) or missing from the series folder:
     * the folder is listed once. An empty listing, or any error, keeps everything counting.
     */
    fun emptyUrls(chapters: List<Chapter>): Set<String> = runCatching {
        val dir = chapters.firstOrNull()?.url?.substringBefore('/') ?: return emptySet()
        val files = Injekt.get<tachiyomi.source.local.io.LocalSourceFileSystem>().getFilesInMangaDirectory(dir)
            .associateBy { it.name }
        if (files.isEmpty()) return emptySet()
        chapters.filter { c -> files[c.url.substringAfter('/')]?.let { !counts(it.isDirectory, it.length()) } ?: true }
            .mapTo(mutableSetOf()) { it.url }
    }.getOrDefault(emptySet())

    /** One per number (an unread copy wins), by number. */
    fun dedupe(volumes: List<LocalVolume>): List<LocalVolume> =
        volumes.groupBy { it.number }
            .map { (_, same) -> same.firstOrNull { !it.read } ?: same.first() }
            .sortedBy { it.number }

    /**
     * Volumes 1..N: AniList's count; unknown → the highest any source or the tablet has. A file on the tablet numbered
     * above AniList's count (a chapter pack read as "153") does not stretch the range, and gets no cell ([cells]).
     */
    fun volumeCount(aniListVolumes: Int?, coverages: Collection<Coverage>, local: List<LocalVolume>): Int {
        aniListVolumes?.takeIf { it > 0 }?.let { return it }
        val sources = coverages.flatMap { it.volumes.keys }.maxOrNull() ?: 0
        return maxOf(sources, local.maxOfOrNull { it.number } ?: 0)
    }

    /** MangaDex covers are wanted unless every volume 1..N (AniList's count) is on the tablet; unknown N → wanted. */
    fun needsMangaDex(aniListVolumes: Int?, local: List<LocalVolume>): Boolean {
        val n = aniListVolumes?.takeIf { it > 0 } ?: return true
        val here = local.map { it.number }.toSet()
        return (1..n).any { it !in here }
    }

    /** Volume → the queued/running job holding it (the first such row). Finished and failed rows are not active. */
    fun active(rows: List<RealDebridQueue.Row>): Map<Int, Active> {
        val result = mutableMapOf<Int, Active>()
        rows.filter { it.state == RealDebridQueue.State.QUEUED || it.state == RealDebridQueue.State.RUNNING }
            .forEach { r ->
                val running = r.state == RealDebridQueue.State.RUNNING
                val fraction = if (running && r.total > 0) (r.bytes.toFloat() / r.total).coerceIn(0f, 1f) else null
                r.volumes.forEach { v -> result.putIfAbsent(v, Active(r.servedBy ?: r.sourceId, running, fraction)) }
            }
        return result
    }

    /**
     * A source card's boxes: ice = this source's job holds it; dim = the source has it and it is on the tablet;
     * bright = the source has it; empty = it doesn't.
     */
    fun boxes(coverage: Coverage?, count: Int, here: Set<Int>, active: Map<Int, Active>): List<BoxState> {
        val has = coverage?.takeIf { it.state == Coverage.State.FOUND }?.volumes?.keys.orEmpty()
        return (1..count).map { v ->
            when {
                coverage != null && active[v]?.sourceId == coverage.sourceId -> BoxState.QUEUED
                v !in has -> BoxState.NONE
                v in here -> BoxState.HERE
                else -> BoxState.HAVE
            }
        }
    }

    /**
     * [readUpTo] = AniList's volumes read: a volume up to it that is not on the tablet still shows as read.
     * [totalKnown]: [count] is AniList's total, so files numbered above it get no cell (Library's in-range rule,
     * [eu.kanade.tachiyomi.ui.glass.GlassTitles.volumesHere]: a stray "153" in an 18-volume series is no volume).
     * [failed] = the volumes still failed ([eu.kanade.tachiyomi.ui.glass.downloads.TitleGroup.failedVolumes]): failed
     * wins over read and empty; on the tablet and queued/running win over failed.
     */
    fun cells(
        count: Int,
        local: List<LocalVolume>,
        active: Map<Int, Active>,
        readUpTo: Int = 0,
        totalKnown: Boolean = false,
        failed: Set<Int> = emptySet(),
    ): List<VolumeCell> {
        val byNumber = local.associateBy { it.number }
        // extras beyond the count, one each, only when the count is not a known total
        val numbers = (1..count) + if (totalKnown) emptyList<Int>() else byNumber.keys.filter { it > count }.sorted()
        return numbers.map { v ->
            val l = byNumber[v]
            val a = active[v]
            when {
                l != null -> VolumeCell(v, if (l.read) VolState.READ else VolState.HERE, l, null)
                a != null -> VolumeCell(v, if (a.running) VolState.DOWNLOADING else VolState.QUEUED, null, a.fraction)
                v in failed -> VolumeCell(v, VolState.FAILED, null, null)
                v <= readUpTo -> VolumeCell(v, VolState.READ, null, null)
                else -> VolumeCell(v, VolState.NONE, null, null)
            }
        }
    }

    /** The coverages to fetch from: the chosen source first, then (fill gaps) the others in order. */
    fun fetchOrder(inOrder: List<Coverage>, chosen: Long?, fillGaps: Boolean): List<Coverage> {
        val first = inOrder.firstOrNull { it.sourceId == chosen } ?: return if (fillGaps) inOrder else inOrder.take(1)
        return if (fillGaps) listOf(first) + inOrder.filter { it.sourceId != chosen } else listOf(first)
    }

    /** Default radio: the first source (in order) that has any volume. */
    fun defaultSource(inOrder: List<Coverage>): Long? =
        inOrder.firstOrNull { it.state == Coverage.State.FOUND && it.volumes.isNotEmpty() }?.sourceId

    /** The jobs [RealDebridQueue.enqueueAll] would queue for [wanted], same walk: a pack once, its volumes skipped. */
    fun plan(wanted: Collection<Int>, order: List<Coverage>): List<Pack> {
        val covered = mutableSetOf<Int>()
        val packs = mutableListOf<Pack>()
        wanted.sorted().forEach { v ->
            if (v in covered) return@forEach
            val (sourceId, link) = CoverageMatch.links(v, order).firstOrNull() ?: return@forEach
            val holds = order.first { it.sourceId == sourceId }.volumes.filterValues { it == link }.keys.sorted()
            covered += holds
            packs += Pack(sourceId, holds)
        }
        return packs
    }

    /** Volumes not on the tablet and not queued. */
    fun missing(count: Int, local: List<LocalVolume>, active: Map<Int, Active>): List<Int> {
        val here = local.map { it.number }.toSet()
        return (1..count).filter { it !in here && it !in active }
    }

    /** "Fetch K": the missing volumes some job of the plan brings. */
    fun fetchable(missing: List<Int>, order: List<Coverage>): List<Int> {
        val brought = plan(missing, order).flatMap { it.volumes }.toSet()
        return missing.filter { it in brought }
    }

    /**
     * The primary pill: a volume being read (unread, page > 0), else the first unread volume here after the last one
     * read (here or on AniList); else fetch the first volume after the last read that is neither here nor queued.
     */
    fun primary(local: List<LocalVolume>, progressVolumes: Int, count: Int, active: Set<Int>): Primary {
        local.firstOrNull { !it.read && it.lastPageRead > 0 }?.let { return Primary.Resume(it) }
        val lastRead = maxOf(progressVolumes, local.filter { it.read }.maxOfOrNull { it.number } ?: 0)
        local.firstOrNull { !it.read && it.number > lastRead }?.let { return Primary.Resume(it) }
        val here = local.map { it.number }.toSet()
        val next = (lastRead + 1..maxOf(count, lastRead + 1)).firstOrNull { it !in here && it !in active }
            ?: return Primary.None
        return if (count > 0 && next > count) Primary.None else Primary.Fetch(next)
    }

    /** AniList raw score (0-100) → 0..5 stars, as the tracker's 5-star display rounds it. */
    fun stars(raw: Int): Int = if (raw <= 0) 0 else ((raw + 10) / 20).coerceIn(1, 5)

    /** AniList list status ↔ the tracker's status ids ([eu.kanade.tachiyomi.data.track.anilist.Anilist]). */
    val statuses = listOf("CURRENT", "PLANNING", "COMPLETED", "PAUSED", "DROPPED", "REPEATING")

    fun trackerStatus(status: String): Long = when (status) {
        "CURRENT" -> Anilist.READING
        "COMPLETED" -> Anilist.COMPLETED
        "PAUSED" -> Anilist.ON_HOLD
        "DROPPED" -> Anilist.DROPPED
        "PLANNING" -> Anilist.PLAN_TO_READ
        "REPEATING" -> Anilist.REREADING
        else -> error("Unknown AniList status $status")
    }

    fun listStatus(trackerStatus: Long): String? = statuses.firstOrNull { trackerStatus(it) == trackerStatus }

    /** "On Home" = on the Reading or Plan to read list. */
    fun onHome(status: String?): Boolean = status == "CURRENT" || status == "PLANNING" || status == "REPEATING"
}
