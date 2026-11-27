package eu.kanade.tachiyomi.util.chapter

import tachiyomi.domain.chapter.model.Chapter

/**
 * Returns a copy of the list with duplicate chapters removed
 */
fun List<Chapter>.removeDuplicates(currentChapter: Chapter): List<Chapter> {
    return groupBy { it.chapterNumber }
        .map { (_, chapters) ->
            chapters.find { it.id == currentChapter.id }
                ?: chapters.find { it.scanlator == currentChapter.scanlator }
                ?: chapters.first()
        }
}

/** This list with [current] put back in [sort] order when a filter or [removeDuplicates] left it out. */
fun List<Chapter>.withCurrent(current: Chapter?, sort: (Chapter, Chapter) -> Int): List<Chapter> =
    if (current == null || any { it.id == current.id }) this else (this + current).sortedWith(sort)
