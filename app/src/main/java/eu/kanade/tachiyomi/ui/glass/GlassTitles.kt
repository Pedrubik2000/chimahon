package eu.kanade.tachiyomi.ui.glass

import eu.kanade.tachiyomi.data.anilist.AniListLibrary

/** The next volume to read of a title on the tablet (a local-source chapter). */
data class NextVolume(val chapterId: Long, val name: String, val number: Double, val lastPageRead: Long)

/**
 * One AniList title as the glass screens show it: the list entry plus what is on the tablet (the local-source manga
 * bound to it by an AniList track row). [localMangaId] null = nothing local.
 */
data class GlassTitle(
    val entry: AniListLibrary.Entry,
    val localMangaId: Long? = null,
    val volumesHere: Int = 0,
    /** max(local volumes read, AniList progressVolumes) */
    val volumesRead: Int = 0,
    /** epoch millis of the last read on the tablet */
    val lastRead: Long? = null,
    /** filled only for the hero candidate (see [GlassTitlesModel]) */
    val next: NextVolume? = null,
) {
    val mediaId: Long get() = entry.mediaId
    val name: String get() = entry.native ?: entry.romaji ?: entry.english ?: ""

    /** volumes read ÷ AniList volumes; null when AniList doesn't know the volume count. */
    val readFraction: Float?
        get() = entry.volumes?.takeIf { it > 0 }?.let { (volumesRead.toFloat() / it).coerceIn(0f, 1f) }

    /** Last read on the tablet, else the AniList entry's last update (both millis). */
    val lastTouched: Long get() = lastRead ?: (entry.updatedAt * 1000)
}

/** Library ANILIST row. */
enum class ListFilter(val statuses: Set<String>) {
    ALL(emptySet()),
    READING(setOf("CURRENT", "REPEATING")),
    PLANNING(setOf("PLANNING")),
    COMPLETED(setOf("COMPLETED")),
    PAUSED(setOf("PAUSED")),
    DROPPED(setOf("DROPPED")),
    ;

    fun matches(t: GlassTitle) = this == ALL || t.entry.status in statuses

    companion object {
        val more = listOf(COMPLETED, PAUSED, DROPPED)
    }
}

/** Library ON THIS TABLET row. */
enum class Here { EVERY, SOME, NOTHING }

enum class GlassSort { LAST_READ, TITLE, VOLUMES_HERE }

/** The Home hero: [resume] = the reader can open [GlassTitle.next]; else only "Volumes". */
data class Hero(val title: GlassTitle, val resume: Boolean)

object GlassTitles {

    fun here(t: GlassTitle): Here {
        val total = t.entry.volumes
        return when {
            t.volumesHere == 0 -> Here.NOTHING
            total != null && total > 0 && t.volumesHere >= total -> Here.EVERY
            else -> Here.SOME
        }
    }

    fun matchesQuery(t: GlassTitle, query: String): Boolean {
        val q = query.trim()
        return q.isEmpty() || t.entry.titles.any { it.contains(q, ignoreCase = true) }
    }

    fun filter(list: List<GlassTitle>, query: String, status: ListFilter, here: Here?): List<GlassTitle> =
        list.filter { matchesQuery(it, query) && status.matches(it) && (here == null || here(it) == here) }

    fun sort(list: List<GlassTitle>, sort: GlassSort): List<GlassTitle> = when (sort) {
        GlassSort.LAST_READ -> list.sortedByDescending { it.lastTouched }
        GlassSort.TITLE -> list.sortedBy { it.name }
        GlassSort.VOLUMES_HERE -> list.sortedWith(
            compareByDescending<GlassTitle> { it.volumesHere }.thenByDescending { it.lastTouched },
        )
    }

    /** Home rows: most recently read/updated first. */
    fun reading(list: List<GlassTitle>) = sort(list.filter { ListFilter.READING.matches(it) }, GlassSort.LAST_READ)
    fun planning(list: List<GlassTitle>) = sort(list.filter { ListFilter.PLANNING.matches(it) }, GlassSort.LAST_READ)

    /**
     * The linked title read most recently that still has a next volume here; else the most recently updated
     * Reading title (no Resume).
     */
    fun hero(list: List<GlassTitle>): Hero? {
        list.filter { it.localMangaId != null && it.next != null && it.lastRead != null }
            .maxByOrNull { it.lastRead!! }
            ?.let { return Hero(it, resume = true) }
        return list.filter { ListFilter.READING.matches(it) }
            .maxByOrNull { it.entry.updatedAt }
            ?.let { Hero(it, resume = false) }
    }

    /** Hero candidates in order: titles here that were read and have unread volumes left, latest first. */
    fun heroCandidates(list: List<GlassTitle>, unread: (GlassTitle) -> Boolean): List<GlassTitle> =
        list.filter { it.localMangaId != null && it.lastRead != null && unread(it) }.sortedByDescending { it.lastRead }

    /**
     * Joins the AniList list with what is here: [linked] = mediaId → local manga (one per media, the one with the
     * most volumes). Local series without an AniList link are not shown.
     */
    fun build(entries: List<AniListLibrary.Entry>, linked: Map<Long, Local>): List<GlassTitle> =
        entries.map { e ->
            val l = linked[e.mediaId]
            if (l == null) {
                GlassTitle(e, volumesRead = e.progressVolumes)
            } else {
                GlassTitle(
                    entry = e,
                    localMangaId = l.mangaId,
                    volumesHere = l.volumes,
                    volumesRead = maxOf(l.read, e.progressVolumes),
                    lastRead = l.lastRead.takeIf { it > 0 },
                )
            }
        }

    /** A local-source manga as [build] needs it. */
    data class Local(val mangaId: Long, val volumes: Int, val read: Int, val lastRead: Long)

    /** Volume label for the hero line: the number when the chapter has one ("3", "3.5"), else its name. */
    fun volumeLabel(next: NextVolume): String = when {
        next.number < 0 -> next.name
        next.number % 1.0 == 0.0 -> next.number.toLong().toString()
        else -> next.number.toString()
    }
}
