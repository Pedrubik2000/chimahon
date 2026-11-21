package eu.kanade.tachiyomi.ui.glass

import eu.kanade.tachiyomi.data.anilist.AniListLibrary
import eu.kanade.tachiyomi.ui.glass.discover.BrowseFilters
import eu.kanade.tachiyomi.ui.glass.discover.Length

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

/**
 * The Library panel past search and sort. [published] = AniList MediaStatus (FINISHED / RELEASING). Genres and
 * included tags are "has every one", like Browse's AniList `genre_in` / `tag_in`.
 */
data class TitleFilters(
    val status: ListFilter = ListFilter.ALL,
    val here: Here? = null,
    val genres: Set<String> = emptySet(),
    val published: String? = null,
    val length: Length? = null,
    val tagsIn: Set<String> = emptySet(),
    val tagsOut: Set<String> = emptySet(),
) {
    /** Tag chip cycle: off → include → exclude → off (as [BrowseFilters.cycleTag]). */
    fun cycleTag(tag: String): TitleFilters = when (tag) {
        in tagsIn -> copy(tagsIn = tagsIn - tag, tagsOut = tagsOut + tag)
        in tagsOut -> copy(tagsOut = tagsOut - tag)
        else -> copy(tagsIn = tagsIn + tag)
    }

    fun toggleGenre(g: String) = copy(genres = if (g in genres) genres - g else genres + g)
}

/** A Library filter group; a group's counts apply every group but its own. */
enum class Facet { STATUS, HERE, GENRES, PUBLISHED, LENGTH, TAGS }

/** Everything the Library page shows; chip lists are in display order, name to count. */
data class LibraryView(
    val shown: List<GlassTitle> = emptyList(),
    val statusCounts: Map<ListFilter, Int> = emptyMap(),
    val hereCounts: Map<Here, Int> = emptyMap(),
    val genres: List<Pair<String, Int>> = emptyList(),
    val publishedCounts: Map<String?, Int> = emptyMap(),
    val lengthCounts: Map<Length, Int> = emptyMap(),
    val tags: List<Pair<String, Int>> = emptyList(),
)

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
        filter(list, query, TitleFilters(status = status, here = here))

    fun filter(list: List<GlassTitle>, query: String, f: TitleFilters): List<GlassTitle> =
        list.filter { matchesQuery(it, query) && matches(it, f) }

    /** The tags chips offer and filters use: rank 60+, not a spoiler (title page / Discover rule). */
    fun tagNames(t: GlassTitle): Set<String> =
        t.entry.tags.filter { it.rank >= 60 && !it.spoiler }.mapTo(HashSet()) { it.name }

    /** Browse's LENGTH ranges over AniList volumes (exclusive bounds); unknown volumes match no bucket. */
    fun inLength(volumes: Int?, l: Length): Boolean =
        volumes != null && volumes > 0 && (l.gt == null || volumes > l.gt) && (l.lt == null || volumes < l.lt)

    /** [skip] = leave that group out (its own counts). */
    fun matches(t: GlassTitle, f: TitleFilters, skip: Facet? = null): Boolean {
        val e = t.entry
        return (skip == Facet.STATUS || f.status.matches(t)) &&
            (skip == Facet.HERE || f.here == null || here(t) == f.here) &&
            (skip == Facet.GENRES || e.genres.containsAll(f.genres)) &&
            (skip == Facet.PUBLISHED || f.published == null || e.mediaStatus == f.published) &&
            (skip == Facet.LENGTH || f.length == null || inLength(e.volumes, f.length)) &&
            (skip == Facet.TAGS || (f.tagsIn.isEmpty() && f.tagsOut.isEmpty()) ||
                tagNames(t).let { n -> n.containsAll(f.tagsIn) && f.tagsOut.none { it in n } })
    }

    /**
     * The Library page: [LibraryView.shown] = search + every group, sorted; each group's counts = search + every other
     * group. Genre chips = the list's genres (Browse's order first, the rest A–Z); tag chips = selected ones, then the
     * [topTags] most frequent of the rest.
     */
    fun view(all: List<GlassTitle>, query: String, f: TitleFilters, sort: GlassSort, topTags: Int = 12): LibraryView {
        val searched = all.filter { matchesQuery(it, query) }
        fun without(x: Facet) = searched.filter { matches(it, f, skip = x) }
        val genreCounts = without(Facet.GENRES).flatMap { it.entry.genres }.groupingBy { it }.eachCount()
        val genres = (all.flatMapTo(HashSet()) { it.entry.genres } + f.genres).sortedWith(
            compareBy<String> { BrowseFilters.GENRES.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }.thenBy { it },
        )
        val tagCounts = without(Facet.TAGS).flatMap { tagNames(it) }.groupingBy { it }.eachCount()
        val chosen = f.tagsIn + f.tagsOut
        val top = tagCounts.entries.filter { it.key !in chosen }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(topTags)
        val byStatus = without(Facet.STATUS)
        val byLength = without(Facet.LENGTH)
        return LibraryView(
            shown = sort(searched.filter { matches(it, f) }, sort),
            statusCounts = ListFilter.entries.associateWith { s -> byStatus.count { s.matches(it) } },
            hereCounts = without(Facet.HERE).groupingBy { here(it) }.eachCount(),
            genres = genres.map { it to (genreCounts[it] ?: 0) },
            publishedCounts = without(Facet.PUBLISHED).groupingBy { it.entry.mediaStatus }.eachCount(),
            lengthCounts = Length.entries.associateWith { l -> byLength.count { inLength(it.entry.volumes, l) } },
            tags = chosen.map { it to (tagCounts[it] ?: 0) } + top.map { it.key to it.value },
        )
    }

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
