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

/**
 * This list with [current] put back in [sort] order when a filter or [removeDuplicates] left it out.
 * [deduped] = the list keeps one chapter per number (a merged series, skip duplicates): [current] then takes the
 * place of the copy that was kept, instead of the volume showing twice.
 */
fun List<Chapter>.withCurrent(
    current: Chapter?,
    sort: (Chapter, Chapter) -> Int,
    deduped: Boolean = false,
): List<Chapter> = when {
    current == null || any { it.id == current.id } -> this
    // a number below 0 = not recognised: those are not copies of each other
    deduped && current.chapterNumber >= 0 ->
        (filter { it.chapterNumber != current.chapterNumber } + current).sortedWith(sort)
    else -> (this + current).sortedWith(sort)
}
