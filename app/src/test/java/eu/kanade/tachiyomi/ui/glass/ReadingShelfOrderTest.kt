package eu.kanade.tachiyomi.ui.glass

import eu.kanade.tachiyomi.ui.glass.GlassTitles.caughtUpLast
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ReadingShelfOrderTest {

    // (name, lastRead, unreadCount)
    private data class S(val name: String, val lastRead: Long, val unread: Int)

    private fun order(vararg s: S) = s.toList().caughtUpLast({ it.lastRead }, { it.unread }).map { it.name }

    @Test
    fun `last read first when everything has something left`() {
        order(S("a", 1, 2), S("b", 3, 1), S("c", 2, 5)) shouldBe listOf("b", "c", "a")
    }

    @Test
    fun `caught up series go to the end, in last read order`() {
        order(S("a", 9, 0), S("b", 1, 3), S("c", 7, 0), S("d", 2, 1)) shouldBe listOf("d", "b", "a", "c")
    }

    @Test
    fun `caught up is decided per volume, not by counts`() {
        val six = (6..10).map { it.toDouble() }
        val none = List(5) { false }
        // AniList progress 5 (read on paper), tablet has volumes 6-10 unread: five left, not caught up
        GlassTitles.unreadLocal(six, none, progress = 5) shouldBe 5
        // progress 8, 9 read here: only 10 left
        GlassTitles.unreadLocal(six, listOf(false, false, false, true, false), progress = 8) shouldBe 1
        // everything read one way or the other
        GlassTitles.unreadLocal(six, listOf(false, false, false, true, true), progress = 8) shouldBe 0
    }

    @Test
    fun `caught up counts every chapter by itself`() {
        // files with no recognised number (-1) are never read by progress
        GlassTitles.unreadLocal(listOf(-1.0, -1.0), listOf(false, false), progress = 5) shouldBe 2
        // two editions of volume 3: reading one does not mark the other
        GlassTitles.unreadLocal(listOf(3.0, 3.0), listOf(true, false), progress = 0) shouldBe 1
        // volumes 1-10 and a 12 (count says 10): the 12 still counts
        GlassTitles.unreadLocal((1..10).map { it.toDouble() } + 12.0, List(11) { false }, progress = 10) shouldBe 1
    }

    @Test
    fun `a new volume brings a series back to its last read place`() {
        order(S("a", 9, 0), S("b", 1, 3)) shouldBe listOf("b", "a")
        order(S("a", 9, 1), S("b", 1, 3)) shouldBe listOf("a", "b")
    }
}
