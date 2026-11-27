package eu.kanade.tachiyomi.ui.reader

import android.app.Application
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** The pure part of page bookmarks: the stored text and the toggle rule. */
object PageBookmarkLogic {

    /** "3,17,84" -> {3, 17, 84}; junk is ignored. */
    fun parse(text: String?): Set<Int> =
        text.orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it > 0 }.toSortedSet()

    fun encode(pages: Set<Int>): String = pages.sorted().joinToString(",")

    /**
     * Toggles the bookmark of what is on screen: [shown] are the pages of the spread (one page when single). If any of
     * them is bookmarked the button shows as bookmarked and tapping removes them all; else the first one (lowest number =
     * first in reading order) is added. Returns the new set and the page added (null = removed).
     */
    fun toggle(pages: Set<Int>, shown: List<Int>): Pair<Set<Int>, Int?> {
        val hit = shown.filter { it in pages }
        return if (hit.isNotEmpty()) {
            (pages - hit.toSet()) to null
        } else {
            val first = shown.min()
            (pages + first) to first
        }
    }
}

/**
 * Page bookmarks per chapter id (1-based page numbers), kept in the app's own SharedPreferences (key = chapter id, value =
 * "3,17,84"): no database change, survives restarts. [all] is what the Glass UI observes.
 * Stored by chapter id only: not in backups, and lost when a chapter is re-created or migrated.
 */
object PageBookmarks {

    private val prefs by lazy {
        Injekt.get<Application>().getSharedPreferences("kumahon_page_bookmarks", Context.MODE_PRIVATE)
    }

    private val state: MutableStateFlow<Map<Long, Set<Int>>> by lazy {
        MutableStateFlow(
            prefs.all.mapNotNull { (key, value) ->
                val id = key.toLongOrNull() ?: return@mapNotNull null
                val pages = PageBookmarkLogic.parse(value as? String)
                if (pages.isEmpty()) null else id to pages
            }.toMap(),
        )
    }

    val all: StateFlow<Map<Long, Set<Int>>> get() = state

    /** Reads the file once; call off the main thread so the first composition does not. */
    fun warm() {
        state.value
    }

    fun pages(chapterId: Long): Set<Int> = state.value[chapterId].orEmpty()

    /** Toggles the bookmark of [shown] in the chapter; returns the page added, or null when removed. */
    fun toggle(chapterId: Long, shown: List<Int>): Int? {
        val (pages, added) = PageBookmarkLogic.toggle(pages(chapterId), shown)
        val e = prefs.edit()
        if (pages.isEmpty()) e.remove(chapterId.toString()) else e.putString(chapterId.toString(), PageBookmarkLogic.encode(pages))
        e.apply()
        state.update { if (pages.isEmpty()) it - chapterId else it + (chapterId to pages) }
        return added
    }
}
