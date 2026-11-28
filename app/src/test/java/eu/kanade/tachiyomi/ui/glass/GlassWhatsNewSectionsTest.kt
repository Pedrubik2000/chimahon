package eu.kanade.tachiyomi.ui.glass

import eu.kanade.tachiyomi.ui.glass.settings.noteSections
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class GlassWhatsNewSectionsTest {

    @Test
    fun `kumahon notes are cut at their bold lines`() {
        val notes = "Intro sentence.\r\n\r\n**Install**\r\n\r\n- `a.apk`: tablets\r\n- `b.apk`: TVs\r\n\r\nKeeps everything.\r\n\r\n" +
            "**New**\r\n\r\n- **Rate a word.** A blue word.\r\n\r\n## Fixed\n* one\n* two\n* three"
        val s = noteSections(notes)
        s.map { it.title } shouldBe listOf(null, "Install", "New", "Fixed")
        s.map { it.items } shouldBe listOf(0, 2, 1, 3)
        s[0].body shouldBe "Intro sentence."
        s[2].body shouldBe "- **Rate a word.** A blue word."
    }

    @Test
    fun `notes without headings stay one part`() {
        noteSections("- one\n- **two** more").map { it.title to it.items } shouldBe listOf(null to 2)
        noteSections("") shouldBe emptyList()
    }
}
