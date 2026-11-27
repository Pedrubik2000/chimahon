package eu.kanade.tachiyomi.ui.glass.library

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class GlassCategoryEditorTest {

    @Test
    fun `a drag settles the item at its new index`() {
        moveItem(listOf("a", "b", "c", "d"), 0, 2) shouldBe listOf("b", "c", "a", "d")
        moveItem(listOf("a", "b", "c", "d"), 3, 1) shouldBe listOf("a", "d", "b", "c")
    }

    @Test
    fun `no move or a bad index leaves the list`() {
        moveItem(listOf("a", "b"), 1, 1) shouldBe listOf("a", "b")
        moveItem(listOf("a", "b"), 0, 5) shouldBe listOf("a", "b")
        moveItem(emptyList<String>(), 0, 0) shouldBe emptyList()
    }
}
