package eu.kanade.tachiyomi.util.chapter

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class ChapterRemoveDuplicatesTest {

    private fun ch(id: Long, number: Double, scanlator: String? = null) =
        Chapter.create().copy(id = id, chapterNumber = number, scanlator = scanlator)

    private val byNumber: (Chapter, Chapter) -> Int = { a, b -> a.chapterNumber.compareTo(b.chapterNumber) }

    @Test
    fun `of two files with one number the one being read stays`() {
        val list = listOf(ch(1, 1.0, "a"), ch(2, 2.0, "a"), ch(3, 2.0, "b"))
        list.removeDuplicates(ch(3, 2.0, "b")).map { it.id } shouldBe listOf(1L, 3L)
    }

    @Test
    fun `the chapter being read is put back in order when a step dropped it`() {
        val list = listOf(ch(1, 1.0), ch(3, 3.0))
        list.withCurrent(ch(2, 2.0), byNumber).map { it.id } shouldBe listOf(1L, 2L, 3L)
        list.withCurrent(ch(3, 3.0), byNumber) shouldBe list
        list.withCurrent(null, byNumber) shouldBe list
    }
}
