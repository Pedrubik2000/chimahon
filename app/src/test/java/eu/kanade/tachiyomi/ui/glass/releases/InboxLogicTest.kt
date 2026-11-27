package eu.kanade.tachiyomi.ui.glass.releases

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class InboxLogicTest {

    // (id, dateFetch, read, downloaded, started, bookmarked)
    private data class U(val id: Int, val date: Long, val read: Boolean = false, val tablet: Boolean = false, val started: Boolean = false, val mark: Boolean = false)

    private val all = listOf(U(1, 100), U(2, 200, tablet = true), U(3, 300, read = true), U(4, 400, started = true, mark = true))

    private fun band(seen: Long, showSeen: Boolean = false) = InboxLogic.band(all, seen, showSeen, { it.date }, { it.read })

    @Test
    fun `the band holds unread items newer than seen`() {
        band(0).map { it.id } shouldBe listOf(1, 2, 4)
        band(150).map { it.id } shouldBe listOf(2, 4)
        band(500).map { it.id } shouldBe emptyList()
    }

    @Test
    fun `the number on a cover is the first number of the chapter name`() {
        numberOf("Vol. 4") shouldBe "04"
        numberOf("Chapter 182") shouldBe "182"
        numberOf("Ch.182.5") shouldBe "182.5"
        numberOf("Prologue") shouldBe null
        numberOf("Mob Psycho 100 - Vol. 3") shouldBe "03"
        numberOf("Zach 5 Vol 3") shouldBe "03"
        numberOf("Spy x Family 2023 Vol 5") shouldBe "05"
        numberOf("Vol 1-2") shouldBe "01"
        numberOf("\u7b2c04\u5dfb") shouldBe "04"
    }

    @Test
    fun `show seen lists everything`() {
        band(500, showSeen = true).map { it.id } shouldBe listOf(1, 2, 3, 4)
    }

    @Test
    fun `chips filter the band like Classic Updates`() {
        fun ids(f: InboxFilter) = InboxLogic.filter(all, f, { it.read }, { it.tablet }, { it.started }, { it.mark }).map { it.id }
        ids(InboxFilter.Unread) shouldBe listOf(1, 2, 4)
        ids(InboxFilter.All) shouldBe listOf(1, 2, 3, 4)
        ids(InboxFilter.OnTablet) shouldBe listOf(2)
        ids(InboxFilter.Started) shouldBe listOf(4)
        ids(InboxFilter.Bookmarked) shouldBe listOf(4)
    }
}
