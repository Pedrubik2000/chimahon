package eu.kanade.tachiyomi.ui.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PageBookmarkLogicTest {

    @Test
    fun `text round trip, junk ignored`() {
        PageBookmarkLogic.parse("84, 3,x,17,0,-2") shouldBe setOf(3, 17, 84)
        PageBookmarkLogic.encode(setOf(84, 3, 17)) shouldBe "3,17,84"
        PageBookmarkLogic.parse(null) shouldBe emptySet()
    }

    @Test
    fun `a single page toggles on and off`() {
        val (on, added) = PageBookmarkLogic.toggle(emptySet(), listOf(10))
        on shouldBe setOf(10)
        added shouldBe 10
        val (off, gone) = PageBookmarkLogic.toggle(on, listOf(10))
        off shouldBe emptySet()
        gone shouldBe null
    }

    @Test
    fun `a spread adds its first page, and either page bookmarked removes`() {
        PageBookmarkLogic.toggle(emptySet(), listOf(11, 10)) shouldBe (setOf(10) to 10)
        // the second page is the bookmarked one: the button shows bookmarked and tapping removes it
        PageBookmarkLogic.toggle(setOf(11, 40), listOf(10, 11)) shouldBe (setOf(40) to null)
    }
}
