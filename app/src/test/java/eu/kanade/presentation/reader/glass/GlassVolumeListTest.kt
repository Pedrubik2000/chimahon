package eu.kanade.presentation.reader.glass

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class GlassVolumeListTest {

    private fun ch(id: Long, read: Boolean = false, bookmark: Boolean = false) =
        Chapter.create().copy(id = id, read = read, bookmark = bookmark)

    private val all = listOf(ch(1, read = true), ch(2, bookmark = true), ch(3), ch(4, read = true, bookmark = true))

    @Test
    fun `filters keep the right chapters`() {
        fun ids(f: VolumeFilter) = all.filterVolumes(f, { it }) { it.id % 2 == 1L }.map { it.id }
        ids(VolumeFilter.All) shouldBe listOf(1L, 2L, 3L, 4L)
        ids(VolumeFilter.Unread) shouldBe listOf(2L, 3L)
        ids(VolumeFilter.Bookmarks) shouldBe listOf(2L, 4L)
        ids(VolumeFilter.OnTablet) shouldBe listOf(1L, 3L)
    }

    @Test
    fun `on-tablet is only asked for the on-tablet filter`() {
        all.filterVolumes(VolumeFilter.Unread, { it }) { error("disk hit") }
    }
}
