package eu.kanade.presentation.reader.glass

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class GlassPageBarTest {

    @Test
    fun `a double spread counts pages left from its higher page`() {
        spreadLastPage("195-196", 195) shouldBe 196
        spreadLastPage("196-195", 195) shouldBe 196
        spreadLastPage("84", 84) shouldBe 84
        spreadLastPage("", 84) shouldBe 84
    }

    @Test
    fun `pages left never goes below zero`() {
        pagesLeft(84, 196) shouldBe 112
        pagesLeft(196, 196) shouldBe 0
        pagesLeft(5, 3) shouldBe 0
    }

    @Test
    fun `an R2L track fills from the right`() {
        trackPosition(1, 101, rtl = false) shouldBe 0f
        trackPosition(101, 101, rtl = false) shouldBe 1f
        trackPosition(1, 101, rtl = true) shouldBe 1f
        trackPosition(26, 101, rtl = true) shouldBe 0.75f
        trackPosition(1, 1, rtl = true) shouldBe 1f
    }

    @Test
    fun `a finger maps back to the page, both directions`() {
        pageAt(0f, 101, rtl = false) shouldBe 1
        pageAt(1f, 101, rtl = false) shouldBe 101
        pageAt(1f, 101, rtl = true) shouldBe 1
        pageAt(0.75f, 101, rtl = true) shouldBe 26
        pageAt(-3f, 101, rtl = false) shouldBe 1
        (1..101).forEach { pageAt(trackPosition(it, 101, rtl = true), 101, rtl = true) shouldBe it }
    }

    @Test
    fun `ticks only for several chapters`() {
        tickPositions(emptyList(), 100, false) shouldBe emptyList()
        tickPositions(listOf(1), 100, false) shouldBe emptyList()
        tickPositions(listOf(1, 51), 101, false) shouldBe listOf(0.5f)
        tickPositions(listOf(1, 51), 101, true) shouldBe listOf(0.5f)
        tickPositions(listOf(1, 26), 101, true) shouldBe listOf(0.75f)
    }

    @Test
    fun `back pill shows after a jump and goes after reading on`() {
        showBackPill(null, 120, 120) shouldBe false
        showBackPill(84, 120, 120) shouldBe true
        showBackPill(84, 120, 122) shouldBe true
        showBackPill(84, 120, 123) shouldBe false
        showBackPill(84, 120, 84) shouldBe false
        // on its way to the target the viewer shows other pages: the pill stays until it arrived
        showBackPill(84, 120, 100, arrived = false) shouldBe true
        showBackPill(84, 120, 100, arrived = true) shouldBe false
    }
}
